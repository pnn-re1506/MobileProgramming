# Lab: Migrating to Type-Safe Navigation

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| API | Application Programming Interface |
| DSL | Domain-Specific Language |
| AGP | Android Gradle Plugin |

**Based on:** https://developer.android.com/guide/navigation/type-safe-destinations (Steps 1–3 and the surrounding Overview/Prerequisites/Best Practices only)
**Project for this lab:** `MobileProgramming26/labs/JetPackNavigatePeople` — the same project from the previous lab, migrated in place from string routes to type-safe `@Serializable` routes.
**You will need:** `JetPackNavigatePeople` already building and running from the previous lab.

By the end of Part 1 of this lab you will have migrated `JetPackNavigatePeople` from plain string routes to Navigation's type-safe Kotlin DSL, without changing anything about what the app actually does.

---

## Table of contents

- [Learning objectives](#learning-objectives)
- [Recap: where we left off](#recap-where-we-left-off)
- [Part 1: Migrating `JetPackNavigatePeople` to type-safe navigation](#part-1-migrating-jetpacknavigatepeople-to-type-safe-navigation)
  - [1.0 Why migrate at all?](#10-why-migrate-at-all)
  - [1.1 Prerequisites](#11-prerequisites)
  - [1.2 Step 1: define your destinations](#12-step-1-define-your-destinations)
  - [1.3 Step 2: update the `NavHost` configuration](#13-step-2-update-the-navhost-configuration)
  - [1.4 Step 3: implement type-safe navigation calls](#14-step-3-implement-type-safe-navigation-calls)
  - [1.5 Checkpoint: run it](#15-checkpoint-run-it)
  - [1.6 Best practices and tips](#16-best-practices-and-tips)
- [Part 1 wrap-up](#part-1-wrap-up)

---

## Learning objectives

By the end of Part 1 of this lab, you should be able to:

1. Explain what becomes a compile-time error instead of a runtime crash when migrating from string routes to type-safe routes.
2. Add the Kotlin serialization plugin and dependency to a project without a Gradle version catalog.
3. Replace a string-route destination (`data object`) and an argument-carrying destination (`data class`) with `@Serializable` types.
4. Update `NavHost`/`composable(...)` and `navController.navigate(...)` calls to use the type-based API.
5. Explain why `object` (not `class`) is used for argument-free destinations.

---

## Recap: where we left off

`JetPackNavigatePeople` currently uses **string routes** plus `navArgument`-typed extraction:

```kotlin
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
```

Last lab's "break it on purpose" exercise showed exactly how this can fail silently: renaming `getInt("personId")` to `getInt("personID")` **compiles with zero errors**, then crashes the moment a user taps a row. Today's goal: migrate this code so that exact class of mistake becomes a compile error instead.

---

## Part 1: Migrating `JetPackNavigatePeople` to type-safe navigation

### 1.0 Why migrate at all?

Route **identity** (`"personDetails/{personId}"` vs. `"personDetails/${person.id}"`) and **argument name** (`"personId"`, spelled out in three separate places) currently have to match *by hand*. Nothing checks them against each other until a user actually triggers that code path. After this migration, all of that collapses into one Kotlin type — a typo in a field name, a missing argument, or a type mismatch becomes a compile error, not a runtime surprise.

### 1.1 Prerequisites

`JetPackNavigatePeople` does not use a Gradle version catalog (`libs.versions.toml`) — you'll add these dependencies directly in `build.gradle.kts`, not through a catalog.

**Note:** `JetPackNavigatePeople` is already on Kotlin 2.0.21, AGP 8.6.1, and the `org.jetbrains.kotlin.plugin.compose` Compose Compiler plugin — that's a course-wide setup you already have, not something this lab changes. `androidx.navigation:navigation-compose` is also already on `2.8.0` (the minimum the type-safe DSL requires) — nothing to bump there either. The only thing actually missing for today is the serialization plugin and dependency, below.

**Add the Kotlin serialization plugin and dependency.**

In the **top-level** `build.gradle.kts`, add the serialization plugin alongside the three already there (matching the Kotlin version already in use, `2.0.21`):

```kotlin
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

In **`app/build.gradle.kts`**, apply the plugin and add the runtime dependency:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ...

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
```

✅ **Checkpoint:** sync Gradle. The project should still build — this step only changes versions and adds a plugin/dependency; nothing uses them yet, so you won't see any visible change.

### 1.2 Step 1: define your destinations

Replace the two string routes with `@Serializable` types. Add this near the top of `MainActivity.kt` (alongside the `data class Person` declaration):

```kotlin
import kotlinx.serialization.Serializable

@Serializable
object PeopleList

@Serializable
data class PersonDetails(val personId: Int)
```

**Rule of thumb:** no arguments → `object`; one or more arguments → `data class`. `PeopleList` carries no data, so it's an `object` — exactly one instance ever exists. `PersonDetails` carries `personId`, so it's a `data class`.

### 1.3 Step 2: update the `NavHost` configuration

Replace:

```kotlin
NavHost(navController = navController, startDestination = "peopleList") {
    composable(route = "peopleList") {
        PeopleListScreen(/* ... */)
    }
    composable(
        route = "personDetails/{personId}",
        arguments = listOf(navArgument("personId") { type = NavType.IntType })
    ) { backStackEntry ->
        val personId = backStackEntry.arguments?.getInt("personId") ?: -1
        val person = people.first { it.id == personId }
        PersonDetailsScreen(/* ... */)
    }
}
```

with:

```kotlin
NavHost(navController = navController, startDestination = PeopleList) {
    composable<PeopleList> {
        PeopleListScreen(/* ... */)
    }
    composable<PersonDetails> { backStackEntry ->
        val details: PersonDetails = backStackEntry.toRoute()
        val person = people.first { it.id == details.personId }
        PersonDetailsScreen(/* ... */)
    }
}
```

What changed:
- `"peopleList"` (a string) → `PeopleList` (a reference to the object from Step 1).
- `composable(route = "...")` → `composable<Route>` — a generic type parameter instead of a string.
- The whole `arguments = listOf(navArgument(...))` block is gone — that information now lives entirely in the `PersonDetails` data class.
- `backStackEntry.arguments?.getInt("personId") ?: -1` → `backStackEntry.toRoute<PersonDetails>()` — one call reconstructs the whole route object, with no string key and no manual fallback value.

You'll need to add this import:
```kotlin
import androidx.navigation.toRoute
```

### 1.4 Step 3: implement type-safe navigation calls

Replace:

```kotlin
navController.navigate("personDetails/${person.id}")
```

with:

```kotlin
navController.navigate(PersonDetails(personId = person.id))
```

This is an ordinary Kotlin object construction — same syntax, same compiler checks (right number of arguments, right types, right names) as constructing any other `data class`.

**Nothing else changes.** `PeopleListScreen` and `PersonDetailsScreen` — the actual screen composables — are untouched. They only ever received plain values and callbacks (`people`/`onPersonClick`, `person`/`onBackClick`), never a route string or the `NavController` itself, so the migration is fully contained inside `PeopleNavHost`.

### 1.5 Checkpoint: run it

✅ **Checkpoint:** build and run `JetPackNavigatePeople`. Behavior should be **identical** to before the migration: list → tap a row → details screen → Back (both the on-screen button and the system gesture) → back to the list.

**If it doesn't compile**, check in this order:
1. Did both `build.gradle.kts` files get the serialization plugin, and did you actually sync Gradle after editing them?
2. Is `navigation-compose` really bumped to `2.8.0` (not just edited but also re-synced)?
3. Did you import `kotlinx.serialization.Serializable` and `androidx.navigation.toRoute`?
4. Are `PeopleList`/`PersonDetails` spelled identically everywhere they're referenced (the `NavHost`, both `composable<...>` calls, and the `navigate(...)` call)?

**Try this on purpose:** rename `personId` to `personID` in just the `navController.navigate(PersonDetails(personID = person.id))` call, leaving the `data class PersonDetails(val personId: Int)` declaration unchanged. Confirm it **fails to compile** — compare this to last lab's version of the same mistake, which compiled fine and crashed at runtime instead.

### 1.6 Best practices and tips

- **Sealed hierarchies:** for a graph with many destinations, group routes under a `sealed interface`/`sealed class`. Not needed yet for this two-screen app, but worth knowing for the next project you build with more screens.
- **Always `object`, never `class`, for argument-free destinations** — `object` guarantees exactly one shared instance; a `class` would allocate a new one every time.
- **Nullable argument types** are supported with automatic default values, e.g. `data class Search(val query: String? = null)`.
- **Testing:** `navController.currentBackStackEntry?.hasRoute<PersonDetails>()` checks the current destination's type safely, without comparing route strings.

---

## Part 1 wrap-up

Migrating `JetPackNavigatePeople` to type-safe navigation didn't change what the app does — it changed *when* a mistake in a route or its arguments gets caught. String routes catch mistakes when a user happens to trigger that exact path at runtime. `@Serializable` route objects catch the same mistake the moment you try to build the project.
