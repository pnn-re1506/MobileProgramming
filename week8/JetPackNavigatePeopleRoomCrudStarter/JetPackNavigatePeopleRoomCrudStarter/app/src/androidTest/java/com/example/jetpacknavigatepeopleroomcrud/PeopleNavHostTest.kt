package com.example.jetpacknavigatepeopleroomcrud

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleNavHostTest {

    // Property initializers run before composeTestRule launches MainActivity.
    @Suppress("unused")
    private val useTestDatabase = run {
        PeopleDatabase.databaseName = "people_test.db"
        reseed()
    }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetDatabase() {
        reseed()
        composeTestRule.waitUntilDisplayed("Ada (36)")
        composeTestRule.waitUntilDisplayed("Dan (19)")
    }

    @Test
    fun peopleListScreen_showsAllFourPeople() {
        composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob (24)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
    }

    @Test
    fun tappingAda_showsAdasDetails() {
        composeTestRule.onNodeWithText("Ada (36)").performClick()

        composeTestRule.onNodeWithText("Name: Ada").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age: 36").assertIsDisplayed()
    }

    @Test
    fun tappingBob_showsBobsDetails() {
        composeTestRule.onNodeWithText("Bob (24)").performClick()

        composeTestRule.onNodeWithText("Name: Bob").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age: 24").assertIsDisplayed()
    }

    @Test
    fun tappingCleo_showsCleosDetails() {
        composeTestRule.onNodeWithText("Cleo (41)").performClick()

        composeTestRule.onNodeWithText("Name: Cleo").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age: 41").assertIsDisplayed()
    }

    @Test
    fun tappingDan_showsDansDetails() {
        composeTestRule.onNodeWithText("Dan (19)").performClick()

        composeTestRule.onNodeWithText("Name: Dan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age: 19").assertIsDisplayed()
    }

    @Test
    fun backButton_returnsToPeopleList() {
        composeTestRule.onNodeWithText("Cleo (41)").performClick()
        composeTestRule.onNodeWithText("Name: Cleo").assertIsDisplayed()

        composeTestRule.onNodeWithText("Back").performClick()

        composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob (24)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
    }

    @Test
    fun systemBackGesture_returnsToPeopleList() {
        composeTestRule.onNodeWithText("Bob (24)").performClick()
        composeTestRule.onNodeWithText("Name: Bob").assertIsDisplayed()

        pressBack()

        composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob (24)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
    }

    @Test
    fun canVisitEveryPersonsDetailsInOneSession() {
        for (expected in listOf(
            "Ada (36)" to ("Name: Ada" to "Age: 36"),
            "Bob (24)" to ("Name: Bob" to "Age: 24"),
            "Cleo (41)" to ("Name: Cleo" to "Age: 41"),
            "Dan (19)" to ("Name: Dan" to "Age: 19")
        )) {
            val (listEntry, details) = expected
            val (nameText, ageText) = details

            composeTestRule.onNodeWithText(listEntry).performClick()
            composeTestRule.onNodeWithText(nameText).assertIsDisplayed()
            composeTestRule.onNodeWithText(ageText).assertIsDisplayed()

            composeTestRule.onNodeWithText("Back").performClick()
        }
    }

    @Test
    fun addingAPerson_appearsInTheListImmediately() {
        composeTestRule.onNodeWithText("Name").performTextInput("Eve")
        composeTestRule.onNodeWithText("Age").performTextInput("30")
        composeTestRule.onNodeWithText("Add").performClick()
        composeTestRule.waitUntilDisplayed("Eve (30)")

        composeTestRule.onNodeWithText("Eve (30)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob (24)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
    }

    @Test
    fun deletingAPerson_removesOnlyThatPersonFromTheList() {
        composeTestRule.onNodeWithContentDescription("Delete Bob").performClick()
        composeTestRule.waitUntilGone("Bob (24)")

        composeTestRule.onNodeWithText("Bob (24)").assertDoesNotExist()
        composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
    }

    @Test
    fun updatingAPerson_isReflectedOnDetailsScreenImmediately() {
        composeTestRule.onNodeWithText("Cleo (41)").performClick()
        composeTestRule.onNodeWithText("Name: Cleo").assertIsDisplayed()

        val nameField = composeTestRule.onNode(hasSetTextAction() and hasText("Cleo"))
        nameField.performTextClearance()
        nameField.performTextInput("Cleopatra")
        composeTestRule.onNodeWithText("Save").performClick()
        composeTestRule.waitUntilDisplayed("Name: Cleopatra")

        composeTestRule.onNodeWithText("Name: Cleopatra").assertIsDisplayed()
        composeTestRule.onNodeWithText("Age: 41").assertIsDisplayed()
    }

    private fun reseed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = PeopleDatabase.getInstance(context).personDao()
        runBlocking {
            dao.deleteAll()
            dao.insertAll(
                listOf(
                    Person(name = "Ada", age = 36),
                    Person(name = "Bob", age = 24),
                    Person(name = "Cleo", age = 41),
                    Person(name = "Dan", age = 19)
                )
            )
        }
    }

    private fun ComposeTestRule.waitUntilDisplayed(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeTestRule.waitUntilGone(text: String, timeoutMillis: Long = 5_000) {
        waitUntil(timeoutMillis) {
            onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
        }
    }
}
