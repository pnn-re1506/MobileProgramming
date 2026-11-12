package com.example.jetpacknavigatepeopleroomcrud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val seedPeople = listOf(
    Person(name = "Ada", age = 36),
    Person(name = "Bob", age = 24),
    Person(name = "Cleo", age = 41),
    Person(name = "Dan", age = 19)
)

@Serializable
object PeopleList

@Serializable
data class PersonDetails(val personId: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = PeopleDatabase.getInstance(applicationContext)
        val dao = db.personDao()

        setContent {
            PeopleNavHost(dao = dao)
        }
    }
}

@Composable
fun PeopleNavHost(dao: PersonDao) {
    val navController: NavHostController = rememberNavController()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        if (dao.count() == 0) {
            dao.insertAll(seedPeople)
        }
    }

    val people by dao.getAll().collectAsState(initial = emptyList())

    NavHost(navController = navController, startDestination = PeopleList) {
        composable<PeopleList> {
            PeopleListScreen(
                people = people,
                onPersonClick = { person ->
                    navController.navigate(PersonDetails(personId = person.id))
                },
                onAddPerson = { name, age ->
                    scope.launch { dao.insert(Person(name = name, age = age)) }
                },
                onDeletePerson = { person ->
                    scope.launch { dao.delete(person) }
                }
            )
        }
        composable<PersonDetails> { backStackEntry ->
            val details: PersonDetails = backStackEntry.toRoute()
            val person = people.first { it.id == details.personId }
            PersonDetailsScreen(
                person = person,
                onBackClick = { navController.popBackStack() },
                onUpdatePerson = { updatedName, updatedAge ->
                    scope.launch {
                        dao.update(person.copy(name = updatedName, age = updatedAge))
                    }
                }
            )
        }
    }
}

@Composable
fun PeopleListScreen(
    people: List<Person>,
    onPersonClick: (Person) -> Unit,
    onAddPerson: (name: String, age: Int) -> Unit,
    onDeletePerson: (Person) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AddPersonForm(onAddPerson = onAddPerson)
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = people, key = { person -> person.id }) { person ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPersonClick(person) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${person.name} (${person.age})",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onDeletePerson(person) }) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete ${person.name}"
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AddPersonForm(onAddPerson: (name: String, age: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }
    val age = ageText.toIntOrNull()
    val canSubmit = name.isNotBlank() && age != null

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it.filter(Char::isDigit) },
                label = { Text("Age") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        Button(
            onClick = {
                if (canSubmit && age != null) {
                    onAddPerson(name.trim(), age)
                    name = ""
                    ageText = ""
                }
            },
            enabled = canSubmit,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Add")
        }
    }
}

@Composable
fun PersonDetailsScreen(
    person: Person,
    onBackClick: () -> Unit,
    onUpdatePerson: (name: String, age: Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Name: ${person.name}", fontSize = 24.sp)
        Text(
            text = "Age: ${person.age}",
            fontSize = 24.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        EditPersonForm(person = person, onUpdatePerson = onUpdatePerson)
        Button(onClick = onBackClick, modifier = Modifier.padding(top = 24.dp)) {
            Text("Back")
        }
    }
}

@Composable
fun EditPersonForm(person: Person, onUpdatePerson: (name: String, age: Int) -> Unit) {
    var name by remember(person.id) { mutableStateOf(person.name) }
    var ageText by remember(person.id) { mutableStateOf(person.age.toString()) }
    val age = ageText.toIntOrNull()
    val canSubmit = name.isNotBlank() && age != null

    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = ageText,
                onValueChange = { ageText = it.filter(Char::isDigit) },
                label = { Text("Age") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        Button(
            onClick = {
                if (canSubmit && age != null) {
                    onUpdatePerson(name.trim(), age)
                }
            },
            enabled = canSubmit,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Save")
        }
    }
}
