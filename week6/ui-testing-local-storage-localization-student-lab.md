# Lab: Local Storage with DataStore, and Localization

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| UI | User Interface |
| API | Application Programming Interface |
| SDK | Software Development Kit |

**Project for this lab:** `MobileProgramming26/labs/JetPackLanguagePreference` — a brand-new project, built from scratch in this lab (it does not extend any previous lab's project).
**You will need:** Android Studio (or the command-line SDK tools) with an Android SDK installed, and a running emulator or physical device.

By the end of this lab you will have a small app that lets the user pick between English and Spanish, saves that choice to the device's disk using Jetpack **DataStore**, and survives the app being fully closed and reopened — opening directly in whichever language was last selected.

---

## Table of contents

- [Learning objectives](#learning-objectives)
- [1. Create the project](#1-create-the-project)
- [2. Why not just a variable in memory?](#2-why-not-just-a-variable-in-memory)
- [3. Set up DataStore](#3-set-up-datastore)
  - [3.1 Add the dependency](#31-add-the-dependency)
  - [3.2 Declare the DataStore and a key](#32-declare-the-datastore-and-a-key)
  - [3.3 Write and read helper functions](#33-write-and-read-helper-functions)
- [4. Localized strings with resource qualifiers](#4-localized-strings-with-resource-qualifiers)
- [5. Build the UI](#5-build-the-ui)
- [6. Checkpoint: the build succeeds, but nothing looks different yet](#6-checkpoint-the-build-succeeds-but-nothing-looks-different-yet)
- [7. Wire it into `onCreate`, and why it has to be this way](#7-wire-it-into-oncreate-and-why-it-has-to-be-this-way)
- [8. Checkpoint: run it, and prove it actually persists](#8-checkpoint-run-it-and-prove-it-actually-persists)
- [9. A bug worth seeing on purpose: the infinite-loop trap](#9-a-bug-worth-seeing-on-purpose-the-infinite-loop-trap)
- [Wrap-up](#wrap-up)
- [Self-check](#self-check)
- [Exercise](#exercise)

---

## Learning objectives

By the end of this lab, you should be able to:

1. Explain why a hardcoded or in-memory value can't survive the app being closed, and what Jetpack DataStore adds that it can't.
2. Read and write a value with DataStore, using `.edit { ... }` to write, `.data` (a `Flow`) for a standing read, and `.data.first()` for a one-off read.
3. Explain what an extension property is, and how Kotlin's `by`-delegation applies to one the same way it applies to a local `var`.
4. Explain the difference between `LocalContext.current` (inside a composable) and an Activity's own `this` (inside `onCreate`), and why each is needed where it is.
5. Explain Android's resource-qualifier localization mechanism (`values/` vs. `values-es/`) and apply a stored language preference with `AppCompatDelegate.setApplicationLocales`.
6. Recognize the specific failure mode where a `LaunchedEffect` reacting to a `collectAsState` default value causes an infinite Activity-recreation loop, and know the fix.

---

## 1. Create the project

Create a new, empty Android Studio project (Empty Activity template, Compose), named **JetPackLanguagePreference**, package `com.example.jetpacklanguagepreference`. This lab builds it up from there — unlike previous labs, there is no existing project to extend.

---

## 2. Why not just a variable in memory?

Every example so far (`count`, `fieldValue`, `people`) has lived only in memory — a `remember { mutableStateOf(...) }` is gone the instant the app's process dies. That's fine for a counter, but a **user preference** (like "I want the app in Spanish") needs to survive the app being closed, the phone being restarted, days passing. That requires writing to the device's actual storage, not just RAM.

`SharedPreferences` is the older Android API for this (small key-value storage) — still common, but synchronous by default (which can block the UI thread) and with a history of subtle bugs around concurrent writes. **Jetpack DataStore (Preferences)** is Google's modern replacement: asynchronous, built on Kotlin `Flow` and coroutines, and the recommended default for new code.

---

## 3. Set up DataStore

### 3.1 Add the dependency

In `app/build.gradle.kts`, inside `dependencies { ... }`:

```kotlin
implementation("androidx.datastore:datastore-preferences:1.1.1")
```

Sync Gradle.

### 3.2 Declare the DataStore and a key

Open `MainActivity.kt` and add, at file scope (outside any class):

```kotlin
import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.dataStore by preferencesDataStore(name = "settings")

private val LANGUAGE_KEY = stringPreferencesKey("language")
private const val LANGUAGE_ENGLISH = "en"
private const val LANGUAGE_SPANISH = "es"
```

**Unpack this line carefully — it packs a few ideas together:** `val Context.dataStore by preferencesDataStore(name = "settings")`.

- **It's an *extension* property, not a regular one.** You've seen `by`-delegation before — `var count by remember { mutableIntStateOf(0) }` from an earlier lab works the same way: `by` is not assignment, it means every read of `count` actually calls a delegate object's `getValue(...)` behind the scenes. This line does the same thing, except the delegated property is attached to a *type* (`Context`) instead of being a local variable scoped to one function. `Context.dataStore` means "every `Context` now has a `.dataStore` property," the same relationship an extension function has to the type it extends — just for a property instead of a function. That's why this line lives at file top level, outside `MainActivity`: it's adding `.dataStore` to the `Context` type itself, not to `MainActivity` specifically.
- **The delegate does the caching for you.** `preferencesDataStore(name = "settings")` returns a delegate that lazily creates one `DataStore` the first time anything reads `.dataStore`, opens the backing file on disk (`settings.preferences_pb`), and hands back that *same* cached instance on every later read — no matter how many places in the app read `.dataStore`. This matters because opening two separate `DataStore` instances pointed at the same file is a documented way to corrupt it; the pattern makes that mistake structurally impossible instead of relying on you remembering not to do it.

✅ **Checkpoint:** this alone won't do anything visible yet — it just makes `.dataStore` available. Continue to the next section.

### 3.3 Write and read helper functions

Still in `MainActivity.kt`:

```kotlin
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

private suspend fun saveLanguage(context: Context, language: String) {
    context.dataStore.edit { preferences ->
        preferences[LANGUAGE_KEY] = language
    }
}
```

`.edit { ... }` is a `suspend` function — writing always happens off the main thread, asynchronously, never blocking the UI.

**Where does `preferences` come from, in `preferences -> preferences[LANGUAGE_KEY] = language`?** Nobody in your code passes it — that whole `{ preferences -> ... }` block is a lambda (an anonymous function) that *you* define and hand to `edit` as its one argument; you never call it yourself. `edit`'s own implementation, inside the DataStore library, is what actually calls your lambda: it reads the current on-disk data, wraps it in a mutable snapshot, and calls your lambda with that snapshot as the argument — that call is the step that supplies `preferences`. You've already seen this exact shape, just with a list instead of a single value: `list.forEach { item -> println(item) }` — nobody passes `item` by hand there either; `forEach`'s implementation loops over the list and calls the lambda once per element, supplying `item` each time. `.edit { ... }` does the same thing, just calling your lambda exactly once instead of many times.

You'll also read a value in two different ways later in this lab:

```kotlin
// As a Flow — keeps emitting whenever the value changes:
context.dataStore.data.map { preferences -> preferences[LANGUAGE_KEY] ?: LANGUAGE_ENGLISH }

// Read once (e.g. at startup, before any UI exists yet):
context.dataStore.data.first()[LANGUAGE_KEY] ?: LANGUAGE_ENGLISH
```

`.data` is a `Flow<Preferences>` — the same reactive shape as `mutableStateOf`, just backed by disk instead of memory. `.data.first()` collects just the next (or current, if already cached) value and stops — a one-off, synchronous-feeling read. You'll use the second form, not the first, in section 7 — and section 9 covers exactly why.

---

## 4. Localized strings with resource qualifiers

Android's built-in localization mechanism is **resource qualifiers**: the same string resource name, defined once per language, in parallel `values-<language>/` folders.

`app/src/main/res/values/strings.xml` (default — used when no better match exists):

```xml
<resources>
    <string name="app_name">JetPackLanguagePreference</string>
    <string name="greeting">Hello!</string>
    <string name="language_label">Language</string>
    <string name="language_english">English</string>
    <string name="language_spanish">Spanish</string>
</resources>
```

Create `app/src/main/res/values-es/strings.xml` (Spanish):

```xml
<resources>
    <string name="app_name">JetPackLanguagePreference</string>
    <string name="greeting">¡Hola!</string>
    <string name="language_label">Idioma</string>
    <string name="language_english">Inglés</string>
    <string name="language_spanish">Español</string>
</resources>
```

In Compose, reading one of these is `stringResource(id = R.string.greeting)` — the framework resolves which file to read from based on the **current locale configuration**, automatically, every time resources are resolved. You'll use this in section 5.

By default, "current locale" means the phone's system-wide language setting. This lab's goal is different: **a per-app language choice**, stored by this app, independent of the phone's system language. That's what `AppCompatDelegate.setApplicationLocales` is for — covered next.

**One fact about it to keep in mind for the rest of this lab:** calling `AppCompatDelegate.setApplicationLocales(...)` **recreates the Activity**, so that resource resolution picks up the new locale. This isn't optional or configurable — it's how the mechanism works — and it's the detail that section 9's bug hinges on.

**One more thing worth knowing before you see it in code (section 5):** `AppCompatDelegate.setApplicationLocales` doesn't take a plain string like `"es"` directly — it takes a `LocaleListCompat`, AndroidX's wrapper around Android's own locale representation (which can rank more than one locale by preference, though this lab only ever sets one at a time). `LocaleListCompat.forLanguageTags("es")` builds one from a string; `.toLanguageTags()`, called on whatever `AppCompatDelegate.getApplicationLocales()` returns, goes the other way, turning the currently-applied locale back into a plain string for comparison. `"es"`/`"en"` are IETF BCP 47 language tags — the same short codes used in HTML's `lang` attribute and HTTP's `Accept-Language` header. This conversion is the one place in the whole lab where code is genuinely talking to the Android OS's own locale system, not just to this app's own saved preference — easy to miss, since it's buried inside a small helper function (`applyLanguage`) that otherwise looks like ordinary app logic.

---

## 5. Build the UI

In `app/build.gradle.kts`, also add AppCompat (needed for `AppCompatDelegate`, used below):

```kotlin
implementation("androidx.appcompat:appcompat:1.7.0")
```

Add a theme — `app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.JetPackLanguagePreference" parent="Theme.AppCompat.DayNight.NoActionBar" />
</resources>
```

Wire it into `AndroidManifest.xml`:

```xml
<application
    android:label="JetPackLanguagePreference"
    android:allowBackup="true"
    android:theme="@style/Theme.JetPackLanguagePreference">
```

**Why this theme is needed — and not optional:** `AppCompatDelegate.setApplicationLocales` only works from an `AppCompatActivity`, and `AppCompatActivity` in turn *requires* the app's theme to descend from `Theme.AppCompat`. Skip this step and the app will build, install, and then crash immediately on launch with `"You need to use a Theme.AppCompat theme (or descendant) with this activity."` — a real crash hit while preparing this lab, included here so you see the fix instead of the crash.

Now the screen itself. Replace `MainActivity.kt`'s UI with:

```kotlin
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.launch

@Composable
fun LanguagePreferenceScreen(initialLanguage: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedLanguage by remember { mutableStateOf(initialLanguage) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = stringResource(id = R.string.greeting), fontSize = 32.sp)

        Text(
            text = stringResource(id = R.string.language_label),
            fontSize = 20.sp,
            modifier = Modifier.padding(top = 32.dp, bottom = 8.dp)
        )

        Column(modifier = Modifier.selectableGroup()) {
            LanguageOption(
                label = stringResource(id = R.string.language_english),
                selected = selectedLanguage == LANGUAGE_ENGLISH,
                onSelect = {
                    selectedLanguage = LANGUAGE_ENGLISH
                    scope.launch { saveLanguage(context, LANGUAGE_ENGLISH) }
                    applyLanguage(LANGUAGE_ENGLISH)
                }
            )
            LanguageOption(
                label = stringResource(id = R.string.language_spanish),
                selected = selectedLanguage == LANGUAGE_SPANISH,
                onSelect = {
                    selectedLanguage = LANGUAGE_SPANISH
                    scope.launch { saveLanguage(context, LANGUAGE_SPANISH) }
                    applyLanguage(LANGUAGE_SPANISH)
                }
            )
        }
    }
}

@Composable
private fun LanguageOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(text = label, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun applyLanguage(language: String) {
    val currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    if (currentLanguage != language) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language))
    }
}
```

**`rememberCoroutineScope()` and `scope.launch { ... }`:** `saveLanguage` is a `suspend fun`, and a `suspend` function can't be called from ordinary code — it needs a coroutine to run in. Inside a composable, `rememberCoroutineScope()` hands back a `CoroutineScope` tied to that composable's own lifetime, and `scope.launch { ... }` starts a new coroutine in it to run the write — fire-and-forget, the tap handler returns immediately without waiting for the write to finish. This is the ordinary way to run suspend work from inside a composable.

**`LocalContext.current`:** `LanguagePreferenceScreen` is a plain `@Composable` function, not a method on any `Context` subclass, so there's no implicit `Context` available to it the way there would be inside `MainActivity`. `LocalContext` is Compose's way of handing a `Context` down to a composable anyway — it's a `CompositionLocal`, a value Compose makes implicitly available to every composable in a subtree (set once near the root by `setContent`, using the hosting Activity's own `Context`), read anywhere below with `.current`. The alternative — passing `Context` as a regular parameter through every composable that might eventually need it — would mean threading it through functions that don't otherwise care about it just to get it to the one that does; `LocalContext.current` avoids that.

✅ **Checkpoint:** `./gradlew assembleDebug` should succeed here too (see section 6 for why that's not obviously true, and what's still missing before the app actually *does* anything new). `LANGUAGE_ENGLISH`/`LANGUAGE_SPANISH` need to already exist from section 3.2.

---

## 6. Checkpoint: the build succeeds, but nothing looks different yet

Try `./gradlew assembleDebug` right now, before touching `MainActivity`'s class body. **It will actually succeed** — don't expect an error here. `LanguagePreferenceScreen`, `LanguageOption`, `saveLanguage`, and `applyLanguage` are all valid, fully-compiling Kotlin; they're just not *called* by anything yet, because `MainActivity` is still the Empty Activity template's `ComponentActivity`, calling `setContent { Greeting() }`. Kotlin has no problem with unused top-level functions sitting in a file — this is not like an unresolved-reference error, it genuinely builds.

If you install and run the app at this exact point, you'll still see the old "Hello!" template screen — section 5's UI exists in the file but is dead code until `onCreate` actually calls `setContent { LanguagePreferenceScreen(...) }`. That wiring is section 7, and it's also where `MainActivity` needs to stop being a `ComponentActivity` and become an `AppCompatActivity` instead — required for `AppCompatDelegate.setApplicationLocales` (section 5) to work at all.

---

## 7. Wire it into `onCreate`, and why it has to be this way

Replace `MainActivity`'s class body:

```kotlin
import android.os.Bundle
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedLanguage = runBlocking {
            dataStore.data.first()[LANGUAGE_KEY] ?: LANGUAGE_ENGLISH
        }
        applyLanguage(savedLanguage)

        setContent {
            LanguagePreferenceScreen(initialLanguage = savedLanguage)
        }
    }
}
```

Two details here are easy to miss and worth slowing down on.

**First — `MainActivity` now extends `AppCompatActivity`, not `ComponentActivity`.** This is required for `AppCompatDelegate.setApplicationLocales` to work at all (section 5). Because `AppCompatActivity` (through its own superclass chain) *is* a `Context`, the bare word `dataStore` inside `onCreate` is really `this.dataStore` — the extension property from section 3.2, called with `this` as its receiver. No `LocalContext` needed here, unlike inside the composable in section 5 — `onCreate` already has a `this` that's a `Context`, a composable doesn't.

**Second — `runBlocking { ... }`, not `scope.launch { ... }`.** `onCreate` is a plain Kotlin method, not a composable — there's no `rememberCoroutineScope()` available to call. More importantly, `onCreate` can't "fire and forget" this read the way `onSelect`'s `scope.launch` could in section 5: it genuinely needs the result (the saved language) *before* it can call `applyLanguage(...)` and `setContent(...)` on the very next lines — there's no "continue now, update the screen later once the value arrives" option available here, because by then `setContent` has already built the first UI. `runBlocking { ... }` starts a coroutine and **blocks the current thread until it finishes**, then returns its result as a normal value — the opposite of what coroutines are normally for (not blocking), which is why it's used sparingly, almost always only in `main()` functions, tests, or — as here — to bridge into the coroutine world for one line from a place that has no coroutine already running.

**Why blocking is acceptable here, specifically:** DataStore caches the last value it read in memory after the first read, so by the time the app is running normally, `.data.first()` resolves near-instantly — no real disk wait. This is not a general license to use `runBlocking` elsewhere; it's justified here because this one read happens to be cheap, and `onCreate` has nowhere else to put a read that must finish before the first UI is built.

✅ **Checkpoint:** `./gradlew assembleDebug` should now succeed.

---

## 8. Checkpoint: run it, and prove it actually persists

Build and install:

```bash
./gradlew installDebug
adb shell am start -n com.example.jetpacklanguagepreference/.MainActivity
```

✅ **Checkpoint 1:** the app opens showing "Hello!" with "Language" and two radio buttons, "English" (selected) and "Spanish".

Tap **Spanish**.

✅ **Checkpoint 2:** the UI instantly switches to "¡Hola!" / "Idioma" / "Inglés" / "Español", with "Español" now selected.

Now the real test — force-stop and relaunch:

```bash
adb shell am force-stop com.example.jetpacklanguagepreference
adb shell am start -n com.example.jetpacklanguagepreference/.MainActivity
```

✅ **Checkpoint 3:** the app opens **directly in Spanish** — no flash of English, no delay. This proves the preference was genuinely written to disk, not just held in memory: a fresh process, with no in-memory state left over, still knows the saved choice.

Tap **English** to switch back, and confirm that direction works too.

**If your build fails or the app crashes, check in this order:**
1. Does `AndroidManifest.xml`'s `<application>` tag have `android:theme="@style/Theme.JetPackLanguagePreference"`, and does that style exist in `themes.xml`, descending from `Theme.AppCompat` (section 5)?
2. Does `MainActivity` extend `AppCompatActivity`, not `ComponentActivity` (section 7)?
3. Is `val Context.dataStore by preferencesDataStore(name = "settings")` declared at file scope, outside any class (section 3.2)?
4. Are both `androidx.datastore:datastore-preferences` and `androidx.appcompat:appcompat` present in `app/build.gradle.kts`'s `dependencies` block?

---

## 9. A bug worth seeing on purpose: the infinite-loop trap

This section describes a bug that is **easy to introduce if you try to "simplify" section 7** by resolving the saved language reactively, inside the composable, instead of up front in `onCreate`. It's worth understanding even though the working version above never has it, because the reactive-looking version is the one most people reach for first.

**Don't actually write this** — it's shown so you recognize the shape and avoid it:

```kotlin
// DON'T do this — causes an infinite loop
val savedLanguage by context.dataStore.data
    .map { it[LANGUAGE_KEY] ?: LANGUAGE_ENGLISH }
    .collectAsState(initial = LANGUAGE_ENGLISH)   // <- the bug starts here

LaunchedEffect(savedLanguage) {
    applyLanguage(savedLanguage)
}
```

This looks reasonable, builds cleanly, and even appears to work on the very first tap — but it causes the Activity to relaunch continuously, several times per second, forever, with only a black flash visible on screen. Tracing through why:

1. User taps Spanish → DataStore write → `savedLanguage` recomposes to `"es"` → `LaunchedEffect` calls `applyLanguage("es")` → **the Activity recreates** (recall from section 4: `setApplicationLocales` always recreates the Activity — this is not optional).
2. The **new** Activity instance starts a **new** `collectAsState(initial = LANGUAGE_ENGLISH)` — and `collectAsState`'s `initial` value is emitted **synchronously, immediately**, before the real value from the DataStore `Flow` has had a chance to arrive asynchronously.
3. For one recomposition, `savedLanguage` is `"en"` (the hardcoded default) — wrong, but real as far as Compose is concerned.
4. `LaunchedEffect(savedLanguage)` sees `"en"`, calls `applyLanguage("en")` → Activity recreates **again**.
5. The next new Activity instance repeats step 2 with the *correct* value this time, calls `applyLanguage("es")` → recreates again...
6. This repeats forever.

**The actual fix is exactly what section 7 already does:** never let a transient, hardcoded default value reach a `LaunchedEffect` that has the power to recreate the Activity. Resolve the real, persisted value **before** `setContent` is ever called — in `onCreate`, with the one-off `runBlocking` read — so `selectedLanguage` starts out already correct via a plain `remember { mutableStateOf(initialLanguage) }`, with no `collectAsState`/`LaunchedEffect` reacting to a changing `Flow` at all.

**The lesson, stated generally:** a `LaunchedEffect` that reacts to a piece of state by doing something *expensive or disruptive* (here: recreating the whole Activity) is dangerous if that state can ever pass through a wrong transient value on its way to the right one. `collectAsState(initial = ...)` is exactly this kind of trap — its `initial` parameter is a real, if temporary, emission, not a placeholder Compose silently skips past.

---

## Wrap-up

DataStore gives an app a safe, asynchronous way to persist small values to disk, using `Flow` for a standing subscription and `.data.first()` for a one-off read. Android's own localization mechanism is resource qualifiers (`values-es/`), and `AppCompatDelegate.setApplicationLocales` lets an app override the locale per-app instead of relying on the system-wide setting — at the cost of recreating the Activity every time it changes, which is exactly the detail that makes resolving the saved preference *before* `setContent`, rather than reactively inside composition, the difference between this lab working correctly and the infinite loop in section 9.

---

## Self-check

1. What's the practical difference between `context.dataStore.data` (a `Flow`) and `context.dataStore.data.first()`? When would you use each?
2. Why does `val Context.dataStore by ...` need to be declared outside any class, rather than as a property of `MainActivity`?
3. Inside `onCreate`, the code just writes `dataStore`, with no receiver. Inside the composable, it writes `context.dataStore`, with `context` coming from `LocalContext.current`. Why the difference?
4. Why does `onCreate` use `runBlocking`, while the composable's tap handler uses `scope.launch`? Could they be swapped?
5. Trace through, step by step, why resolving `savedLanguage` reactively with `collectAsState` + `LaunchedEffect` (section 9) causes an infinite loop, and why resolving it once in `onCreate` (section 7) doesn't.

---

## Exercise

Add a third language (French) to `JetPackLanguagePreference`:

1. A new `values-fr/strings.xml`, with French translations of all four strings.
2. A third `LANGUAGE_FRENCH = "fr"` constant.
3. A third `LanguageOption` in `LanguagePreferenceScreen`, wired up exactly like English and Spanish.

Confirm it persists across a force-stop/relaunch the same way English and Spanish do.
