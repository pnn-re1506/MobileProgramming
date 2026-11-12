# Lab: Full CRUD with Room, and Testing Database-Backed Apps

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| UI | User Interface |
| SDK | Software Development Kit |
| APK | Android Package (the installable app file format) |
| DB | Database |
| DAO | Data Access Object |
| SQL | Structured Query Language |
| CRUD | Create, Read, Update, Delete |
| KSP | Kotlin Symbol Processing (Room's annotation-processor toolchain) |

**Starting project:** `JetPackNavigatePeopleRoomCrudStarter.zip` — a renamed copy of `JetPackNavigatePeopleRoom` from an earlier lab: Room, KSP, the `Person` entity, `PersonDao`, and `PeopleDatabase` are all already in place and already wired into `MainActivity`. The app builds, runs, and shows the four seeded people — but **it can only read**. There is no way to add, delete, or edit anyone yet; that's entirely today's work. **Nothing about the Gradle setup changes in this lab** — no new dependencies, no new plugins. Everything you need for all three writes, and for real tests, is already on the classpath.

**You will need:** a running emulator or physical device (API level 23 or higher), and about two and a half hours.

By the end of this lab, a person can be added, deleted, and edited from the running app, all three writes land in the real on-device database, and you'll have a working instrumented test suite that doesn't contaminate itself between runs, doesn't race its own writes, and doesn't collide with the real app's own data.

---

## Table of contents

- [Learning objectives](#learning-objectives)
- [0. Unpack the starter and confirm it builds](#0-unpack-the-starter-and-confirm-it-builds)
- [1. State hoisting: the pattern you're about to repeat three times](#1-state-hoisting-the-pattern-youre-about-to-repeat-three-times)
- [2. Add a person](#2-add-a-person)
- [3. Delete a person](#3-delete-a-person)
- [4. Update a person](#4-update-a-person)
- [5. Checkpoint: run it, prove all three writes are real](#5-checkpoint-run-it-prove-all-three-writes-are-real)
- [6. Testing a database-backed app: why this is different](#6-testing-a-database-backed-app-why-this-is-different)
- [7. The isolation bug: tests contaminating each other](#7-the-isolation-bug-tests-contaminating-each-other)
- [8. Don't test against the production database](#8-dont-test-against-the-production-database)
- [9. The synchronization race: asserting before the write lands](#9-the-synchronization-race-asserting-before-the-write-lands)
- [10. Writing the add, delete, and update tests](#10-writing-the-add-delete-and-update-tests)
- [11. Three ways to test a Room-backed app](#11-three-ways-to-test-a-room-backed-app)
- [Wrap-up](#wrap-up)
- [Self-check](#self-check)
- [Exercise](#exercise)

---

## Learning objectives

By the end of this lab, you should be able to:

1. Add `@Insert`, `@Delete`, and `@Update` methods to a Room DAO, and explain why the latter two match rows by primary key rather than by content.
2. Wire an add, a delete, and an edit action into an existing Compose screen without touching the screen composables themselves.
3. Explain why a database-backed instrumented test suite is a harder testing problem than anything you've tested before in this course, and fix the hazards that come with it.
4. Keep an instrumented test suite from ever touching the same database file a real install of the app uses.
5. Diagnose and fix a test that races its own asynchronous write, without reaching for `Thread.sleep` or an unbounded wait.

---

## 0. Unpack the starter and confirm it builds

Unzip `JetPackNavigatePeopleRoomCrudStarter.zip`. Open it in Android Studio and let Gradle sync. Then:

```bash
./gradlew assembleDebug
```

✅ **Checkpoint:** this succeeds with no changes from you. Install and run it — you'll see the same four seeded people as always (Ada, Bob, Cleo, Dan), and tapping one still shows their details. If the build fails, stop here and resolve that first — everything below assumes a working build.

Take two minutes to look at what's already there, in `app/src/main/java/.../`:

- **`Person.kt`** — the `@Entity`, unchanged from the Room-migration lab.
- **`PersonDao.kt`** — has `getAll()` (a `Flow`), `getById`, `count`, and `insertAll`. **No `insert`, `delete`, or `update` yet** — the DAO can only read, plus the one bulk write used to seed the table on first launch.
- **`PeopleDatabase.kt`** — the thread-safe singleton that hands out one shared connection.
- **`MainActivity.kt`** — `PeopleNavHost(dao: PersonDao)` already holds the `dao` and reads from it:

```kotlin
LaunchedEffect(Unit) {
    if (dao.count() == 0) {
        dao.insertAll(seedPeople)
    }
}

val people by dao.getAll().collectAsState(initial = emptyList())
```

There is no `onAddPerson`, no `AddPersonForm`, nothing on `PeopleListScreen` beyond a plain `Text` per row, and no edit affordance anywhere on `PersonDetailsScreen`. All three of today's writes start from nothing.

---

## 1. State hoisting: the pattern you're about to repeat three times

**Notice where `dao` lives, and where it doesn't.** `PeopleListScreen` and `PersonDetailsScreen` — the actual screen composables — have never seen `dao`, never called `scope.launch`, and never will. They only ever receive plain values (`people: List<Person>`, `person: Person`) and plain callbacks (`onPersonClick`, and soon `onAddPerson`/`onDeletePerson`/`onUpdatePerson`). `PeopleNavHost` is the *only* composable that will hold `dao`, and the *only* place any database write will happen. This is called **state hoisting**: the state (and the authority to change it) is hoisted up and out of the screens' scope, into the one composable above them that owns it.

This is why swapping the data source under these screens — hardcoded list, then `NavController`, then Room — across this course has never required touching `PeopleListScreen` or `PersonDetailsScreen` themselves. All three writes you're about to add will follow the identical shape: a DAO method, a callback parameter added to a screen, one line of wiring inside `PeopleNavHost`. Add is the first one, and once you've done it, delete and update really are just two more repetitions of the same pattern.

---

## 2. Add a person

### 2.1 Add the DAO method

In `PersonDao.kt`, alongside the existing `insertAll`:

```kotlin
@Insert
suspend fun insert(person: Person)
```

**Why `suspend fun`, the same as every other write you'll add today.** `getAll()` returns a `Flow` because it's a standing subscription — Room re-emits automatically on every write to `"people"`, and `Flow` genuinely cannot be marked `suspend` (Room's processor rejects that combination at compile time). `insert`, and later `delete`/`update`, are each a single write — "run this once, off the main thread, tell me when it's done" — which is exactly what `suspend fun` is for.

### 2.2 Build the add form

In `MainActivity.kt`, give `PeopleListScreen` a new callback and a form above the list:

```kotlin
@Composable
fun PeopleListScreen(
    people: List<Person>,
    onPersonClick: (Person) -> Unit,
    onAddPerson: (name: String, age: Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AddPersonForm(onAddPerson = onAddPerson)
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = people, key = { person -> person.id }) { person ->
                Text(
                    text = "${person.name} (${person.age})",
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { onPersonClick(person) }
                        .padding(16.dp)
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun AddPersonForm(onAddPerson: (name: String, age: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var ageText by remember { mutableStateOf("") }

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
                val age = ageText.toIntOrNull()
                if (name.isNotBlank() && age != null) {
                    onAddPerson(name.trim(), age)
                    name = ""
                    ageText = ""
                }
            },
            enabled = name.isNotBlank() && ageText.toIntOrNull() != null,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Add")
        }
    }
}
```

You'll need a handful of new imports for this — `Button`, `OutlinedTextField`, `Row`, `fillMaxWidth`, `KeyboardOptions`, `remember`/`mutableStateOf`/`getValue`/`setValue`, and `KeyboardType`. Let Android Studio's autocomplete add them, or add them by hand; either way, notice they're all ordinary Compose/Kotlin imports — nothing Room-specific.

**`AddPersonForm` owns its own text-field state, and that's deliberate — it is not hoisted.** `name`/`ageText` are purely transient UI state (what's currently typed, before it's submitted); they have no reason to live anywhere else, and no reason to survive past the moment `onAddPerson` is called and the fields are cleared. Contrast this with `people`, which *is* hoisted, because it's real data with a lifetime longer than any one composable's. Not everything needs hoisting — only state that other parts of the app need to see or that needs to outlive the composable holding it.

**Validation happens locally, before the callback is ever invoked.** `enabled = name.isNotBlank() && ageText.toIntOrNull() != null` disables the button until both fields are usable, and the `onClick` body checks the same condition again before calling `onAddPerson` with already-parsed, clean values (`name.trim()`, an `Int`, not a `String`). `onAddPerson` itself never has to handle "age wasn't a number" — by the time it's called, that's already been ruled out.

### 2.3 Wire the write

In `MainActivity.kt`'s `PeopleNavHost`, you'll need a `CoroutineScope` to launch the write from — add it alongside the existing `navController`:

```kotlin
@Composable
fun PeopleNavHost(dao: PersonDao) {
    val navController: NavHostController = rememberNavController()
    val scope = rememberCoroutineScope()

    // ... existing LaunchedEffect(Unit) { ... } seed check, unchanged ...

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
                }
            )
        }
        // ... composable<PersonDetails> { ... } unchanged for now ...
    }
}
```

You'll need `import androidx.compose.runtime.rememberCoroutineScope` and `import kotlinx.coroutines.launch`.

**`scope.launch { dao.insert(...) }`, not a `LaunchedEffect`.** `LaunchedEffect` runs automatically, as a side effect of composition — appropriate for the seed check above it, which should happen once, unconditionally, when the screen first appears. Adding a person is different: it happens *in response to a user action* (tapping "Add"), so it belongs in a plain `onClick`-style callback, launched into a `CoroutineScope` you control directly. **No manual "refresh the list" call exists anywhere** — the insert alone is what makes `getAll()`'s `Flow` emit a new list; `people` updates automatically because `collectAsState` is still subscribed to it.

✅ **Checkpoint:** run the app. Type a name and age, tap Add — the new person appears in the list immediately, with no visible delay and no manual refresh. Tap into their details to confirm the data actually landed. Force-stop and relaunch the app — they're still there. That's your first proof a write actually reached the database, not just in-memory Compose state.

---

## 3. Delete a person

### 3.1 Add the DAO method

In `PersonDao.kt`:

```kotlin
@Delete
suspend fun delete(person: Person)
```

**`@Delete` matches by primary key, not by content.** Room generates `DELETE FROM people WHERE id = ?`, using the `id` field of the `Person` object you pass in — `name` and `age` are read off the object but never used to locate the row. This means you must pass the **actual** `Person` you got back from `getAll()` (which carries its real database-assigned `id`), never a freshly constructed `Person(name = ..., age = ...)` — that object's `id` defaults to `0`, and `DELETE FROM people WHERE id = 0` deletes nothing, silently.

### 3.2 Add a delete affordance to each row

You'll need one new import for the delete icon:

```kotlin
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
```

Change `PeopleListScreen`'s signature to accept a new callback:

```kotlin
@Composable
fun PeopleListScreen(
    people: List<Person>,
    onPersonClick: (Person) -> Unit,
    onAddPerson: (name: String, age: Int) -> Unit,
    onDeletePerson: (Person) -> Unit
) {
```

And change each row from a plain `Text` into a `Row` with a delete icon alongside it:

```kotlin
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
        Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete ${person.name}")
    }
}
```

**Two details worth not skipping:**

- **`contentDescription = "Delete ${person.name}"`, not a generic `"Delete"`.** With four or more delete icons on screen at once, a generic description makes every one of them indistinguishable — to accessibility tools, and to the instrumented tests you'll write later in this lab, which will need to find *one specific person's* delete button.
- **The nested `clickable`.** The `Row`'s own `.clickable { onPersonClick(person) }` covers the whole row, and `IconButton` is itself clickable, nested inside it. Compose resolves this correctly on its own — tapping the icon fires only `onDeletePerson`, never both — but it's worth tapping around in the running app to see for yourself that it behaves the way you'd hope.

### 3.3 Wire the write

In `MainActivity.kt`, add a sibling to the existing `onAddPerson`:

```kotlin
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
```

Exactly the same shape as `onAddPerson` — one line, no manual list refresh. The delete alone is what makes `getAll()`'s `Flow` emit a list one shorter; nothing re-fetches or re-renders anything by hand.

✅ **Checkpoint:** run the app, tap the delete icon on one person. They disappear immediately; the other three are unaffected. Force-stop and relaunch the app (or just kill and reopen it) — the deleted person stays gone. That's your proof this was a real database write, not local Compose state that would've reset on relaunch.

---

## 4. Update a person

### 4.1 Add the DAO method

In `PersonDao.kt`:

```kotlin
@Update
suspend fun update(person: Person)
```

**Same primary-key matching as `@Delete`, opposite direction.** `@Update` generates `UPDATE people SET name = ?, age = ? WHERE id = ?` — it locates the row by `id`, then overwrites every other column with whatever the `Person` object currently holds. So an update call needs a `Person` with the **right `id`** and the **new** `name`/`age` — in practice, `person.copy(name = updatedName, age = updatedAge)`: take the object you already have (its `id` is already correct) and change only the fields that actually changed.

### 4.2 Build an edit form on the details screen

`PersonDetailsScreen` is where one person's full data is already in scope, so that's where editing belongs:

```kotlin
@Composable
fun PersonDetailsScreen(
    person: Person,
    onBackClick: () -> Unit,
    onUpdatePerson: (name: String, age: Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Name: ${person.name}", fontSize = 24.sp)
        Text(text = "Age: ${person.age}", fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp))
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
                val age = ageText.toIntOrNull()
                if (name.isNotBlank() && age != null) {
                    onUpdatePerson(name.trim(), age)
                }
            },
            enabled = name.isNotBlank() && ageText.toIntOrNull() != null,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Save")
        }
    }
}
```

**Stop and look at `remember(person.id)` — this is the one new idea in this section, and it's worth getting wrong once on purpose so the fix actually sticks.**

Here's the intuitive-but-wrong expectation: "navigating to a different person's details calls `EditPersonForm` fresh, so its `remember` obviously starts blank every time — why would a key even matter?" Try it: write `var name by remember { mutableStateOf(person.name) }` with **no key**, run the app, edit Ada's name, save, go back, then tap into Cleo's details. **Cleo's form will show whatever you just typed for Ada.**

Why: Compose doesn't key a `remember` by *which data you passed in* — it keys it by the call site's **position in the composition** (concretely: "the `EditPersonForm(...)` call made from inside this `composable<PersonDetails>` block"). Navigating from Ada's details to Cleo's details doesn't necessarily tear that slot down and rebuild it from nothing — the navigation graph can and does reuse the same call site for different route arguments. A bare `remember { mutableStateOf(person.name) }` sees "I already have a value remembered for this call site" and hands it back — even though a completely different `Person` is now being passed in.

A follow-up question worth asking yourself before reading on: *the initializer does read `person.name` — isn't that enough, even without a key?* No — and the reason is specific: `remember { initializer }`'s initializer lambda only ever runs **once per slot**, the first time that call site is entered. On every later recomposition of that *same* slot, Compose skips the initializer entirely and just returns the `MutableState` it already built. So `{ mutableStateOf(person.name) }` does read `person.name` — but only the very first time, for whichever person happened to be on screen then. The key doesn't change *what* the initializer reads; it changes *whether Compose is willing to run it again at all*. `remember(person.id)` tells Compose: "if `person.id` differs from last time, this is a new logical instance — discard the old value and run the initializer fresh." That's the only thing that makes `person.name` get re-read for the new person.

### 4.3 Wire the write

In `MainActivity.kt`'s `composable<PersonDetails>` block:

```kotlin
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
```

**Why this also updates the list screen, with zero extra code.** `person` comes from `people.first { it.id == details.personId }`, and `people` is the same `collectAsState`-backed list `PeopleListScreen` reads too. The update writes to the same `"people"` table `getAll()`'s `Flow` is watching — the details screen recomposes with a new `person` the moment `people` changes, and the list screen updates for the identical reason. One write, one `Flow`, two screens correctly reflecting it.

---

## 5. Checkpoint: run it, prove all three writes are real

```bash
./gradlew installDebug
```

1. Add a new person via the form at the top of the list — they appear immediately, with the right name and age.
2. Tap into Cleo's details, change her age, tap Save — the details screen updates immediately. Tap Back — the list screen already shows the new age too, with no separate refresh step.
3. Delete one person from the list, then force-stop and relaunch the app. They're still gone, and everyone else's data (including any adds/edits you made) survived.

If any of these don't work, check in this order: is `PersonDao` actually declaring `@Insert`/`@Delete`/`@Update` with the right signatures? Is `MainActivity`'s `onAddPerson`/`onDeletePerson`/`onUpdatePerson` actually calling `dao.insert(...)`/`dao.delete(person)`/`dao.update(person.copy(...))` — and for update, is it `person.copy(...)`, not a freshly constructed `Person(...)` with `id = 0`?

---

## 6. Testing a database-backed app: why this is different

Every prior testing lab in this course tested state that either never persisted (a hardcoded list) or persisted exactly one simple value (a single DataStore string). Neither of those apps had a way for one test run to leave behind contamination that would change the *starting conditions* of a later test run.

**A Room-backed app with insert/delete/update is different in two separate, independent ways — and you're about to hit both of them.** The database file is real, on disk, and outlives the test process. `createAndroidComposeRule<MainActivity>()` boots the actual `MainActivity`, which calls the actual `PeopleDatabase.getInstance(applicationContext)` against a real on-device SQLite file. Nothing about running this under test creates a fresh, disposable database per run, unless you write the test code to make that happen.

As long as the app's *only* write was the first-run seed (`if (dao.count() == 0) dao.insertAll(seedPeople)`), this didn't matter — every test run found either an empty table (seeded it) or the same four seeded rows (left them alone). **Now that add, delete, and update exist, a test that deletes Bob leaves a 3-person table behind, and the seed guard never re-fires, because `count() == 0` is false.** Every test after that point starts from the wrong assumption.

Section 7 below fixes that. Section 8 covers a second, completely separate hazard that's easy to miss entirely if you only think about test *isolation* and never about test *targets*.

---

## 7. The isolation bug: tests contaminating each other

Create `PeopleNavHostTest.kt` (or extend the one from the starter) with a `@Before` method that resets the table to a known state before every single test:

```kotlin
@Before
fun resetDatabase() {
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
```

`dao.deleteAll()` doesn't exist yet — add it to `PersonDao.kt`:

```kotlin
// Test-only convenience: wipe the table so each instrumented test can
// reseed from a known state. Nothing in the app's own UI ever calls this.
@Query("DELETE FROM people")
suspend fun deleteAll()
```

**Is it acceptable for a DAO method to exist only for test convenience, with no production caller?** Genuinely ask yourself this before moving on. The position this lab takes is yes: `@Query` methods are trivial and declarative — there's no business logic to get wrong in `"DELETE FROM people"` — and the alternative (reimplementing the same SQL inside test code, or looping `getAll()` + `delete()` once per row) is strictly worse: slower, and it tests nothing additional. A reasonable counter-position exists too (test-only production code is still production code someone has to maintain) — there's no single correct answer, but there is a correct *reason* to prefer one side, and "it compiles" isn't it.

**Why this runs *before* `MainActivity` ever launches.** JUnit4 executes a test's `@Before` methods, *then* enters whatever `@Rule`s wrap the test body. `createAndroidComposeRule<MainActivity>()` is a `@Rule`, and (for most rules) its wrapped statement is what actually calls `onCreate()` — so `resetDatabase()` finishes, with the table back to exactly four known rows, before `MainActivity.onCreate()` ever reads from it. Section 8 next has an important exception to this ordinary rule — don't assume it always holds.

✅ **Checkpoint:** write (or carry over) a test like `peopleListScreen_showsAllFourPeople` asserting all four names are on screen, and run `./gradlew connectedDebugAndroidTest` several times in a row. It should pass every time, regardless of what the previous run left behind in the table.

---

## 8. Don't test against the production database

**Here's the hazard most people miss entirely, because nothing about it looks wrong until it actually happens to someone.** `createAndroidComposeRule<MainActivity>()` doesn't run some sandboxed, disposable copy of your app — it runs the *actual app*, under the actual app's package, against whatever device or emulator is connected. By default, that means the real on-device file it opens is `"people.db"` — **the exact same file name a genuine install of this app uses.**

Think through what that means concretely: if you ever ran this instrumented suite against a real phone that also happened to have the real app installed — for manual testing, say — `resetDatabase()`'s `dao.deleteAll()` would silently wipe out whatever was actually entered on that phone. No warning, no confirmation. As far as Room is concerned, it's the same file either way.

**The fix: give the test suite its own database file name, distinct from production, without changing anything about how the app itself asks for its database.** In `PeopleDatabase.kt`:

```kotlin
companion object {
    @Volatile
    private var instance: PeopleDatabase? = null

    // Overridden by instrumented tests, before MainActivity is ever launched.
    // Production code never sets this — it only ever reads the default.
    @Volatile
    var databaseName: String = "people.db"

    fun getInstance(context: Context): PeopleDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PeopleDatabase::class.java,
                databaseName
            ).build().also { instance = it }
        }
    }
}
```

`MainActivity.onCreate()`'s call site — `PeopleDatabase.getInstance(applicationContext)` — doesn't change at all. It still just asks for "the database," with no idea whether it's running under test.

**Now the part that's genuinely easy to get wrong — don't just copy this, reason through why it has to go exactly here.** The obvious place to set the override is the first line of `resetDatabase()`'s `@Before` body, reasoning from the ordinary JUnit4 lifecycle you just learned in section 7: `@Before` runs before any `@Rule` does its thing, so surely that's early enough. **It is not.** `createAndroidComposeRule<MainActivity>()` is built on `ActivityScenarioRule`, and `ActivityScenarioRule`'s *constructor* launches the activity immediately — not lazily, when its wrapped statement runs, which is what ordinary `TestRule`s do. Constructing `composeTestRule` is a property initializer, and Kotlin runs a class's property initializers top to bottom, in declaration order, as part of constructing the test instance — which happens *before* any `@Before` method. Put the override in `@Before`, and `MainActivity.onCreate()` will have already called `getInstance()` with the untouched production name, by the time `@Before` even starts.

The actual fix: declare the override as its own property, **above** `composeTestRule` in the class body:

```kotlin
@RunWith(AndroidJUnit4::class)
class PeopleNavHostTest {

    // Must run BEFORE composeTestRule's own property initializer — see above.
    private val useTestDatabase = run { PeopleDatabase.databaseName = "people_test.db" }

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetDatabase() {
        // ... deleteAll() + insertAll(), exactly as in section 7
    }
}
```

Because `getInstance` caches a single `instance` for the whole process, whichever name is in effect at that very first call is the one every later call shares — both the test's own `dao` and `MainActivity`'s `dao` end up on the same cached instance, just not the production one.

✅ **Checkpoint — verify this for real, don't just trust the code compiles.** Install and launch the app manually first (`adb shell am start -n <applicationId>/.MainActivity`), so a real `people.db` exists with real data. Then run the instrumented suite. Afterward, inspect the device directly:

```bash
adb shell run-as <applicationId> ls /data/data/<applicationId>/databases/
adb shell run-as <applicationId> sqlite3 /data/data/<applicationId>/databases/people.db "SELECT name, age FROM people;"
```

You should see **both** `people.db` and `people_test.db` as separate files, and `people.db`'s contents should be exactly what you manually left there — untouched by the test run.

---

## 9. The synchronization race: asserting before the write lands

With sections 7 and 8 done, write a delete test the obvious way:

```kotlin
@Test
fun deletingAPerson_removesOnlyThatPersonFromTheList() {
    composeTestRule.onNodeWithContentDescription("Delete Bob").performClick()

    composeTestRule.onNodeWithText("Bob (24)").assertDoesNotExist()
    // ...
}
```

Run this several times in a row. **It will probably pass most of the time, and fail occasionally — and that intermittency is itself the clue, not noise to ignore.**

Here's what's actually happening. Delete is wired through `rememberCoroutineScope().launch { dao.delete(person) }` — a plain `CoroutineScope`, not a `LaunchedEffect`. `dao.delete(person)` is a suspend function Room generates, and its generated implementation dispatches the real SQL execution onto **Room's own internal query executor** — a background thread pool Room owns and manages itself, entirely separate from `Dispatchers.Main`, and invisible to Compose's test-synchronization clock. `performClick()` returning only proves Compose currently has *no scheduled recomposition pending* — not that the click's side effects are done. At the instant the click returns, the write may not have reached the table yet, so there's nothing yet for Compose's idling to see as pending. Assert immediately after, with nothing in between, and you're racing a background write Compose has no way to observe.

**Two tempting fixes, both worth trying and ruling out yourself, before reaching for the real one:**

- *Wrap the click in `runBlocking`.* `performClick()` is already a plain, non-suspending call that returns immediately once the tap is dispatched — wrapping it in `runBlocking { performClick() }` blocks on nothing, because `performClick()` never suspended on the write in the first place. The write happens on a separate coroutine this call has no handle on at all.
- *Ask the database directly, instead of asking Compose.* Something like `runBlocking { dao.getAll().first { people -> people.none { it.name == "Bob" } } }`, suspending on the same `Flow` the UI observes until a matching list is emitted. This genuinely fixes the race — but introduces a worse one: if the condition is simply never satisfied (a real bug, a typo in the predicate), `Flow.first` waits forever, and `runBlocking` has no timeout. The test doesn't fail with a readable message — it just hangs, until a build-level timeout eventually kills it with a confusing "build timed out," nothing more specific. Try commenting out the real `dao.delete(...)` call and watching a test built this way hang instead of fail, to see this for yourself.

**The actual fix: poll the real UI, with a bounded wait.** Add two small helpers to the test class:

```kotlin
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
```

`composeTestRule.waitUntil(timeoutMillis) { condition }` re-triggers Compose's own synchronization on every poll and keeps checking until the condition is true or the timeout elapses — the same underlying idling machinery as before, just not trusted as a single snapshot taken once. Use it right after every write-triggering click, before asserting:

```kotlin
composeTestRule.onNodeWithContentDescription("Delete Bob").performClick()
composeTestRule.waitUntilGone("Bob (24)")

composeTestRule.onNodeWithText("Bob (24)").assertDoesNotExist()
```

**Why the timeout here isn't the same mistake as the bug itself.** The original problem was asserting with *zero* wait. A `waitUntil` timeout isn't trying to guess how long the write *normally* takes — it only matters when something is genuinely broken, in which case any reasonable value (5 seconds, far longer than any real write) produces the same outcome: a fast, readable failure instead of a silent hang. Shrinking it toward zero reintroduces exactly the original problem; growing it only makes a genuine failure slower to notice. Neither direction changes anything on the happy path, where the condition is satisfied almost instantly and the loop exits the moment it is.

✅ **Checkpoint:** run the full suite several times in a row. It should pass every time now, including the full round-trip test you'll write in the next section.

---

## 10. Writing the add, delete, and update tests

With the helpers from section 9 in place, write:

```kotlin
@Test
fun addingAPerson_appearsInTheListImmediately() {
    composeTestRule.onNodeWithText("Name").performTextInput("Eve")
    composeTestRule.onNodeWithText("Age").performTextInput("30")
    composeTestRule.onNodeWithText("Add").performClick()
    composeTestRule.waitUntilDisplayed("Eve (30)")

    composeTestRule.onNodeWithText("Eve (30)").assertIsDisplayed()

    // The four original people are all still there too.
    composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Bob (24)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
}
```

This one has exactly the same race as delete and update — `onAddPerson` also goes through `scope.launch { dao.insert(...) }` — so it needs the same `waitUntilDisplayed(...)` immediately after the click, before asserting. If you skip it and the test still passes every time you try it, that's not proof it's safe; it's proof the race just hasn't lost yet. Try running it ten times in a row without the wait before moving on, so you've actually seen it fail at least once rather than taking this on faith.

```kotlin
@Test
fun deletingAPerson_removesOnlyThatPersonFromTheList() {
    composeTestRule.onNodeWithContentDescription("Delete Bob").performClick()
    composeTestRule.waitUntilGone("Bob (24)")

    composeTestRule.onNodeWithText("Bob (24)").assertDoesNotExist()

    // Everyone else is still there, unaffected by Bob's removal.
    composeTestRule.onNodeWithText("Ada (36)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Cleo (41)").assertIsDisplayed()
    composeTestRule.onNodeWithText("Dan (19)").assertIsDisplayed()
}
```

**Checking the other three people is not optional padding.** A delete implementation with a bug in its `WHERE` clause — or one that accidentally called `deleteAll()` instead of `delete(person)` — would still make `assertDoesNotExist("Bob (24)")` pass, because Bob genuinely would be gone, along with everyone else. Asserting the untouched rows survived is what actually distinguishes "deleted the right row" from "deleted everything."

```kotlin
@Test
fun updatingAPerson_isReflectedOnDetailsScreenImmediately() {
    composeTestRule.onNodeWithText("Cleo (41)").performClick()
    composeTestRule.onNodeWithText("Name: Cleo").assertIsDisplayed()

    composeTestRule.onNodeWithText("Name").performTextClearance()
    composeTestRule.onNodeWithText("Name").performTextInput("Cleopatra")
    composeTestRule.onNodeWithText("Save").performClick()
    composeTestRule.waitUntilDisplayed("Name: Cleopatra")

    composeTestRule.onNodeWithText("Name: Cleopatra").assertIsDisplayed()
    composeTestRule.onNodeWithText("Age: 41").assertIsDisplayed()
}
```

**`performTextClearance()` before `performTextInput(...)` — don't skip it.** `EditPersonForm`'s `name` field starts pre-filled with `person.name` ("Cleo"); typing "Cleopatra" without clearing first produces "CleoCleopatra." **Asserting `Age: 41` is unchanged, not just that the name changed**, is the same principle as the delete test above — it's what rules out an update that accidentally resets every field instead of merging only the one that changed.

For extra practice, write `updatingAPerson_isReflectedBackOnTheListScreenAfterNavigatingBack`: edit a person's age on the details screen, assert the change there, tap Back, and *only then* assert the list screen shows the new age. This test is stronger than the one above in one specific way — walk through why before writing it: if an update somehow only mutated local Compose state on the details screen, without a real database write, would the first test above catch that? Would this one?

---

## 11. Three ways to test a Room-backed app

You've just written instrumented tests running on-device, against a real database. Worth knowing where that sits on a speed/fidelity spectrum, even though it's the only one you've used today:

1. **Instrumented, on-device, against a real database** — what you just did. **Slowest**: each test boots a real Activity, Compose, and touches real disk I/O — a few seconds per test, not milliseconds. **Highest fidelity**: it exercises Room's actual generated code, actual SQL, actual Compose recomposition, actual navigation, all together. If the real integration between any of those pieces is broken, this is the only one of the three that can catch it.
2. **In-memory Room database** (`Room.inMemoryDatabaseBuilder(context, PeopleDatabase::class.java).build()`) — not used in this lab, but worth knowing it exists. Still runs real SQL through Room's real generated DAO implementation, so it still catches a broken `@Query` string or a wrong primary-key assumption — but the database lives in RAM, with no file to leak state between runs, and no disk I/O latency. The tradeoff: it tests the DAO layer in isolation, saying nothing about whether `MainActivity`/Compose actually wire that DAO correctly into the UI.
3. **Mocking the DAO entirely** (a fake `PersonDao`, handing back canned `Flow`s with no real SQL) — fastest, useful for testing UI logic in isolation without needing Room, KSP, or a device at all. The tradeoff is total: a bug in an actual `@Query` string would never be caught, because no real SQL ever runs.

**Faster tests test less of the real system.** The right choice depends on exactly what you're trying to catch, and a mature test suite for an app like this would likely use more than one of these three, at different layers — not just one.

---

## Wrap-up

Today completed the CRUD picture — `@Insert`/`@Delete`/`@Update`/`@Query` — with the same state-hoisting discipline holding across all three new writes: screens get plain values and callbacks, `PeopleNavHost` holds `dao` and does every write. The testing half covered three separate hazards any database-backed app's test suite eventually runs into: an instrumented test must never be allowed to share the production database file with a real install, test state persisting across runs needs a deterministic per-test reset, and a test-synchronization primitive like Compose's idling only ever watches the layer it was built to watch — not an arbitrary background executor a lower layer dispatches work onto. All three hide behind results that pass *most* of the time, which is exactly why they're worth catching deliberately rather than discovering by accident, on someone's real phone, months from now.

---

## Self-check

1. Why must the `Person` passed to `dao.delete(...)` be one actually read back from the database, rather than a freshly constructed `Person(name = ..., age = ...)`?
2. What does `remember(person.id) { mutableStateOf(person.name) }` protect against in `EditPersonForm`? What would go wrong without the `person.id` key, and why does the form's initializer reading `person.name` not already solve the problem on its own?
3. Trace exactly why `dao.count() == 0` stops correctly guarding the seed once add/delete/update exist.
4. Why does `PeopleNavHostTest.kt` set `PeopleDatabase.databaseName` in a property initializer declared above `composeTestRule`, instead of as the first line of `resetDatabase()`'s `@Before` body? What specifically goes wrong with the `@Before` placement?
5. What does `composeTestRule.waitUntilGone(...)` actually wait for, and why does it need a timeout at all, when `waitForIdle()` doesn't take one?
6. Name the three approaches to testing a Room-backed app from section 11, and for each, state one thing it would catch that DAO mocking would not.

---

## Exercise

Add a confirmation step to delete: instead of deleting immediately on tap, show an `AlertDialog` (or a second "are you sure?" screen) that only calls `onDeletePerson(person)` if the user confirms. Keep `PeopleListScreen` itself free of any `dao`/`scope` knowledge — the confirmation state can live inside `PeopleListScreen` (it's pure UI state, not data), but the actual deletion still has to happen through the same `onDeletePerson` callback, wired the same way, in `PeopleNavHost`.

Then write a test for it: tapping delete should **not** immediately remove the person; only confirming should. Think about where `waitUntilGone(...)` needs to move in that test, and why.
