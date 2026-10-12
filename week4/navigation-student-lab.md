# Lab: Introduction to Jetpack (Safe) Navigation

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| API | Application Programming Interface |
| DSL | Domain-Specific Language |

**Project for this lab:** `MobileProgramming26/labs/JetPackNavigatePeople` — extends `JetPackListPeople` (sections 8–9 of the previous lab) with two screens and navigation between them. See that project's own `README.md` for build/run instructions.

By the end of this lab you will have wired up navigation between two Compose screens with `NavController`/`NavHost`, understood why that recomposition mechanism is nothing new, and seen firsthand what "safe" navigation protects against by breaking it on purpose.

---

## Table of contents

- [Learning objectives](#learning-objectives)
- [Recap: where we left off](#recap-where-we-left-off)
- [1. Core Navigation Compose concepts](#1-core-navigation-compose-concepts)
  - [1.1 A note on `PeopleListScreen(people = people, ...)`](#11-a-note-on-peoplelistscreenpeople--people-)
  - [1.2 Why this actually works: `NavHost` is just a composable reading state](#12-why-this-actually-works-navhost-is-just-a-composable-reading-state)
  - [1.3 `PersonDetailsScreen` does not receive `navController` — and that's deliberate](#13-persondetailsscreen-does-not-receive-navcontroller--and-thats-deliberate)
  - [1.4 This is an instance of "state hoisting"](#14-this-is-an-instance-of-state-hoisting)
- [2. Why "safe" navigation, and what "unsafe" would look like](#2-why-safe-navigation-and-what-unsafe-would-look-like)
- [3. Try it: run the app, then break it on purpose](#3-try-it-run-the-app-then-break-it-on-purpose)

---

## Learning objectives

By the end of this material, you should be able to:

1. Explain why an ad-hoc `mutableStateOf<Person?>`-style flag doesn't scale to multi-screen navigation (no back stack, no system Back integration).
2. Use `NavController`, `NavHost`, and `composable(route)` to wire up navigation between two screens.
3. Explain why `NavHost` recomposing to show a different screen is the same mechanism as any other composable recomposing on state change — not a separate, special-cased system.
4. Explain what "safe" means in "Jetpack safe navigation," and the tradeoffs between string routes, `navArgument`-typed arguments, and the type-safe `@Serializable` DSL.
5. Explain why a screen composable should receive a plain callback (e.g. `onBackClick: () -> Unit`) rather than the `NavController` itself, and name this pattern (state hoisting).

---

## Recap: where we left off

`JetPackListPeople` had a single screen: a `LazyColumn` of `Person` rows (`data class Person(id, name, age)`). There was only ever one composable on screen — `setContent { PeopleList(people) }` — so there was nothing to "navigate" between.

Today's goal: clicking a row in the list **navigates to a new screen** showing that person's details, and from there the user can **navigate back** to the list — using both an on-screen "Back" button and the system back gesture/button.

---

## 1. Core Navigation Compose concepts

Three pieces, and only three, are needed for the simplest case:

1. **`NavController`** — the object that knows the current back stack and can `navigate(route)` or `popBackStack()`. Created once per screen hierarchy with `rememberNavController()`.
2. **`NavHost`** — a composable that *hosts* the currently active screen. It takes the `NavController` and a `startDestination`, and swaps its content as the controller navigates.
3. **`composable(route) { ... }`** — declares one screen (a *destination*) inside the `NavHost`, identified by a `route` string.

A **route** is just a string identifier for a screen — think of it like a URL path. Routes can be static (`"peopleList"`) or carry a parameter (`"personDetails/{personId}"`), the same way a URL might be `/people/42`.

```kotlin
val people = listOf(
    Person(id = 1, name = "Ada", age = 36),
    Person(id = 2, name = "Bob", age = 24),
    Person(id = 3, name = "Cleo", age = 41),
    Person(id = 4, name = "Dan", age = 19)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PeopleNavHost()
        }
    }
}

@Composable
fun PeopleNavHost() {
    val navController: NavHostController = rememberNavController()

    NavHost(navController = navController, startDestination = "peopleList") {
        composable(route = "peopleList") {
            PeopleListScreen(
                people = people,
                onPersonClick = { person ->
                    navController.navigate("personDetails/${person.id}")
                }
            )
        }
        composable(
            route = "personDetails/{personId}",
            arguments = listOf(navArgument("personId") { type = NavType.IntType })
        ) { backStackEntry ->
            val personId = backStackEntry.arguments?.getInt("personId") ?: -1
            val person = people.first { it.id == personId }
            PersonDetailsScreen(
                person = person,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
```

`MainActivity` itself barely changed from `JetPackListPeople`: there, `onCreate` called `setContent { PeopleList(people) }` directly; here, it calls `setContent { PeopleNavHost() }` instead. The Activity doesn't know or care how many screens live inside `PeopleNavHost` — from `MainActivity`'s point of view, it is just setting the content to *one* composable, same as before. All of the multi-screen logic is encapsulated inside `PeopleNavHost`.

`PeopleNavHost` only *wires up* the navigation graph — it doesn't draw anything on screen itself. Each `composable(route) { ... }` block delegates to a separate composable that does the actual drawing for that screen:

```kotlin
@Composable
fun PeopleListScreen(people: List<Person>, onPersonClick: (Person) -> Unit) {
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

@Composable
fun PersonDetailsScreen(person: Person, onBackClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Name: ${person.name}", fontSize = 24.sp)
        Text(text = "Age: ${person.age}", fontSize = 24.sp, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = onBackClick, modifier = Modifier.padding(top = 24.dp)) {
            Text("Back")
        }
    }
}
```

`PeopleListScreen` is exactly the same `LazyColumn` composable from last class's `JetPackListPeople`, with one addition: each row now has `.clickable { onPersonClick(person) }` on its `Modifier`, so tapping a row invokes the callback the caller passed in. `PersonDetailsScreen` is new — a plain, non-list screen that just displays one `Person`'s fields and a `Button` that invokes `onBackClick` when tapped.

### 1.1 A note on `PeopleListScreen(people = people, ...)`

This line calls `PeopleListScreen` using **named-argument syntax**: `paramName = value`. `PeopleListScreen` declares a parameter called `people` (of type `List<Person>`); the call passes it the value of the top-level `val people` list declared just above `PeopleNavHost`. The fact that both the parameter and the variable happen to be spelled `people` is just a naming choice — it is not special syntax, and nothing would break if we renamed the top-level list to, say, `allPeople` and wrote `PeopleListScreen(people = allPeople, ...)` instead. It reads naturally here specifically because the names match, but the `=` is doing the same job it always does: binding an argument to a parameter by name rather than by position.

### 1.2 Why this actually works: `NavHost` is just a composable reading state

This is the key mental model to land, and it connects directly back to sections 1 and 4 of the previous class's `composables-recomposition-lazylist-instructor-guide.md` (composition, recomposition, state):

- `NavHost` **is a composable**, exactly like `Text`, `Column`, or `PeopleListScreen`. Nothing about it is magic or special-cased outside the Compose rules we already know.
- `navController`, the object `rememberNavController()` hands back, **holds state** — specifically, the current back stack (which route is currently "on top"). `NavHost` reads that state to decide which `composable(route) { ... }` block's content to show.
- When we call `navController.navigate("personDetails/1")` (or `navController.popBackStack()`), we are **changing that state** — pushing (or popping) an entry on the back stack held inside `navController`.
- Because `NavHost` reads that state, changing it triggers **recomposition of `NavHost`** — and on this recomposition, `NavHost` shows whatever route is now on top of the stack, and disposes the composable for whatever route used to be on top.

In other words: **there is no separate, custom "screen switching" mechanism here.** `NavHost` is not doing anything conceptually different from `Text(text = "$count")` recomposing when `count` changes. The "screen" is just the value `NavHost` reads and displays, and `navController` is just another piece of state — one that happens to live inside a `NavController` object instead of a `mutableStateOf<Int>`, and happens to represent "a stack of routes" instead of a number. `navigate(...)` and `popBackStack()` are the "setters" for that state, playing exactly the same role `count += 1` played for our counter example — the only difference is *what* is being mutated and *how many* composables end up depending on it.

### 1.3 `PersonDetailsScreen` does not receive `navController` — and that's deliberate

Look again at how `PersonDetailsScreen` is called, inside `PeopleNavHost`:

```kotlin
PersonDetailsScreen(
    person = person,
    onBackClick = { navController.popBackStack() }
)
```

and how it's declared:

```kotlin
@Composable
fun PersonDetailsScreen(person: Person, onBackClick: () -> Unit) {
    ...
    Button(onClick = onBackClick, ...) { Text("Back") }
}
```

Notice what is *not* passed here: `PersonDetailsScreen` never receives `navController` itself. It would have been entirely possible to write `PersonDetailsScreen(person = person, navController = navController)` and have the screen call `navController.popBackStack()` directly, from inside its own `Button`'s `onClick`. **We did not do that, and this is a deliberate, recommended practice — not an accident.**

Instead, `PeopleNavHost` passes down a plain function value, `onBackClick: () -> Unit`, and `PersonDetailsScreen` just calls whatever function it was given, with no idea what that function actually does. From `PersonDetailsScreen`'s point of view, "go back" is just an opaque callback someone handed it — it could pop the back stack, log an analytics event, or (in a test) do nothing at all.

**Why prefer this over handing the screen the whole `navController`?**

- **`PersonDetailsScreen` stays reusable and testable.** It has zero dependency on Navigation Compose — no import of `NavController`, nothing nav-related in its signature at all. It could be dropped into a totally different app with no navigation library and still work, or previewed/tested in isolation with a fake `onBackClick = {}`.
- **The screen can't do more than it's allowed to.** If it received the full `navController`, nothing would stop `PersonDetailsScreen` from calling `navController.navigate(...)` to jump to some *other*, unrelated route — an implicit, hidden coupling between a "details" screen and the rest of the navigation graph. By only handing it a single `() -> Unit` callback, we've limited it to exactly one capability: "signal that the user wants to go back." That is the **principle of least privilege**, applied to composables.
- **Navigation stays centralized.** All the "which route do we go to next" decisions live in exactly one place, `PeopleNavHost`. Individual screens never need to know route strings, argument names, or that `NavController` exists at all.

### 1.4 This is an instance of "state hoisting"

This pattern — a composable owns a piece of state (or, as here, owns the *thing that changes* state, `navController`) and passes both the *current value* and a *way to change it* down to child composables as plain parameters, instead of handing children the state-holder itself — is called **state hoisting** in Jetpack Compose. "Hoisting" means lifting ownership of the state up to a common ancestor, so that:

- The composable that owns the state (here, `PeopleNavHost`, which owns `navController`) is the single source of truth.
- Every descendant composable only ever receives plain values and plain callback functions (`Person`, `() -> Unit`, `(Person) -> Unit`) — never the mutable state-holder itself.

We already saw a smaller-scale version of this in `JetPackChangeName` last class: the `OutlinedTextField` didn't own `fieldValue` itself — its caller owned `var fieldValue by remember { ... }` and handed the field `value = fieldValue` plus `onValueChange = { fieldValue = it }`. Same shape here: `PeopleNavHost` owns `navController`; `PeopleListScreen` and `PersonDetailsScreen` each only receive the specific plain values and callbacks they need (`people`/`onPersonClick`, `person`/`onBackClick`) — never `navController` itself. **State hoisting is the general Compose pattern; "pass a callback instead of the NavController" is just that same pattern applied to navigation.**

### Key points

1. **The back stack is automatic.** Every call to `navController.navigate(route)` pushes a new entry onto a back stack the library maintains. The system Back button and gesture are wired to pop that stack automatically — the app needed **zero extra code** to make the hardware/gesture Back button work correctly.
2. **Only the current destination is composed.** Just like `LazyColumn` only composes the rows currently in view, `NavHost` only composes the screen for the route currently on top of the back stack. Navigating away recomposes `NavHost` (because the state it reads — the back stack — changed), which disposes the old screen's composable (and its `remember`ed state, unless it's hoisted elsewhere) and composes the new one in its place.
3. **Passing data via the route, not via shared mutable state.** We do **not** pass the whole `Person` object through navigation. We pass just `person.id` as a `String`-encoded path segment (`"personDetails/${person.id}"`), declared in the route pattern as `{personId}`. The details screen looks the `Person` back up from the same in-memory `people` list using that id. This is deliberate — see section 2.
4. **`popBackStack()` vs. system Back.** Our own "Back" `Button` calls `navController.popBackStack()` explicitly — this is exactly what the system Back gesture also triggers internally. Both are just two different callers of the same state-mutating operation on `navController`; you'll confirm both trigger the same result in the exercise in section 3.

---

## 2. Why "safe" navigation, and what "unsafe" would look like

The word "safe" in "Jetpack **safe** navigation" specifically refers to **type-safe argument passing** between destinations — avoiding a whole class of bugs that come from routes being *plain strings*.

### The unsafe pattern (what we're avoiding)

With plain string routes (what the code above actually does, at the `NavHost` level — this is the *classic*, pre-type-safe API, still extremely common and worth understanding first):

- The route `"personDetails/{personId}"` and the call `navController.navigate("personDetails/${person.id}")` must match **by hand**. Nothing stops you from misspelling the route, forgetting to interpolate the id, or mismatching the argument name (`personId` vs. `personID`) between the `composable()` declaration and the `navArgument()` name and the string template. Any mismatch is a **runtime crash**, not a compile error.
- On the receiving side, `backStackEntry.arguments?.getInt("personId")` looks the value up **by string key** — again, a typo compiles fine and crashes at runtime.

### The safer pattern: `navArgument` + typed extraction (what this project already does)

Our code already takes a step in the safer direction by:
- Declaring the argument's type explicitly: `navArgument("personId") { type = NavType.IntType }`. This means Navigation validates and parses the segment as an `Int` for us — a non-numeric value in that URL segment fails at the framework level with a clear error, rather than silently becoming a bad string downstream.
- Extracting with the typed accessor `getInt("personId")` instead of the generic `getString(...)` + manual `toIntOrNull()`.

### Full type safety: Navigation's Kotlin DSL (`androidx.navigation:navigation-compose` 2.8+)

The newest Navigation Compose releases (2.8+) add a fully type-safe API: routes become `@Serializable` Kotlin objects/classes instead of string templates, e.g.:

```kotlin
@Serializable
object PeopleList

@Serializable
data class PersonDetails(val personId: Int)

// navigate:
navController.navigate(PersonDetails(personId = person.id))

// declare destination:
composable<PersonDetails> { backStackEntry ->
    val details: PersonDetails = backStackEntry.toRoute()
    val person = people.first { it.id == details.personId }
    ...
}
```

Here, a typo in a field name is a **compile error**, not a runtime crash — the whole point of "safe" navigation. This lab's project uses the string-route + `navArgument` style (the more widely-deployed, still-supported approach) so the mechanics stay visible while you're learning them; keep the type-safe DSL in mind as the recommended default once you start a new project of your own.

### Key points

1. **"Safe" = compile-time checked routes/arguments**, as opposed to stringly-typed routes where mismatches only surface when a user actually triggers that navigation path at runtime.
2. **`JetPackNavigatePeople` is a deliberate middle ground**: plain string routes (so the mental model of "a route is a string identifying a screen" is crystal clear) combined with `navArgument`'s typed parsing (so at least the *argument value* is checked, even though the *route/argument names* are not).
3. **Why not just pass the whole `Person` object through the nav graph?** Two reasons worth stating explicitly: (a) navigation arguments are meant to be simple, saveable values (they can survive process death / configuration changes, which requires them to go into a `Bundle`-like structure — a full custom object needs to be `Serializable`/`Parcelable` to do this safely); (b) it keeps a single source of truth — the details screen re-reads the `Person` from the same list the list screen reads from, rather than carrying around a possibly-stale copy.

---

## 3. Try it: run the app, then break it on purpose

1. Build and run `JetPackNavigatePeople` (see its `README.md` for the exact commands). You should see the same 4-person list as `JetPackListPeople` — Ada (36), Bob (24), Cleo (41), Dan (19) — but rows are now tappable.
2. Tap **Cleo (41)**.

   ✅ **Checkpoint:** the screen navigates to a details screen showing "Name: Cleo", "Age: 41", and a **Back** button.
3. Tap **Back**.

   ✅ **Checkpoint:** you're back at the list. This calls `navController.popBackStack()` explicitly, in the code you just read in section 1.
4. Navigate into a person again, then use the **system Back gesture/button** instead of the on-screen Back button.

   ✅ **Checkpoint:** same result — you're back at the list. This confirms Navigation Compose wires the back stack to the system Back mechanism automatically, with no extra code needed from you.
5. **Now break it on purpose.** In `MainActivity.kt`, change the lookup line from `getInt("personId")` to `getInt("personID")` (note the capital `D`) — but leave `navArgument("personId")` unchanged. Rebuild.

   ✅ **Checkpoint:** it **compiles with zero errors or warnings**. Run it and tap any row.

   ✅ **Checkpoint:** the app crashes immediately, kicked back to the home screen. Check Logcat — you should see:

   ```
   FATAL EXCEPTION: main
   java.util.NoSuchElementException: Collection contains no element matching the predicate.
       at MainActivityKt$PeopleNavHost$1$3.invoke(MainActivity.kt:115)
   ```

   **What just happened:** `getInt("personID")` doesn't find that key in the arguments `Bundle`, so it silently returns its default value `0` (not `null`, not an error) — the `?:` fallback never even triggers, since the call itself "succeeds." Then `people.first { it.id == 0 }` finds no match (no person has id `0`) and throws. Two silent failures chained together, both invisible until you actually tapped a row — exactly the class of bug "safe" (type-checked) navigation is designed to catch at compile time instead.

6. **Revert your change** (`getInt("personId")`, lowercase `d`) before moving on, so the project is back in its working state.

## Wrap-up

Navigation Compose gives you a `NavController` that manages a back stack for you — call `navigate(route)` to push a screen, `popBackStack()` (or the system Back button/gesture, which does the same thing) to pop one off. "Safe" navigation is about making the *route and its arguments* checkable — ideally at compile time — instead of matching plain strings by hand and hoping nothing is mistyped.

**Review questions:**

1. Why doesn't a `mutableStateOf<Person?>` flag scale to more than two screens?
2. What state does `NavHost` actually read, and what changes it?
3. Why does `PersonDetailsScreen` receive `onBackClick: () -> Unit` instead of the `NavController` itself?
4. What's the difference between a string route, a `navArgument`-typed route, and the `@Serializable` DSL, in terms of when a mistake is caught?

**Exercise:** add a third screen — e.g. an "edit person" screen reachable from the details screen, with its own route and its own plain callback(s) passed down from `PeopleNavHost`, following the same pattern as `PersonDetailsScreen`.
