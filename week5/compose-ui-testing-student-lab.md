# Lab: Introduction to UI Testing in Jetpack Compose

## Abbreviations used in this document

| Abbreviation | Full term |
|---|---|
| UI | User Interface |
| JUnit | a Java/Kotlin unit testing framework (not itself an abbreviation, but conventionally capitalized this way) |
| BOM | Bill of Materials (a Gradle mechanism for pinning a consistent set of library versions) |

**Based on:** https://developer.android.com/develop/ui/compose/testing, plus its linked Semantics and Testing cheatsheet pages.
**Projects for this lab:** `MobileProgramming26/labs/JetPackDisplay0` and `MobileProgramming26/labs/JetPackIncreasingCounter`.
**You will need:** both projects already building and running from earlier labs, and a running emulator or physical device.

By the end of Part 2 of this lab you will have written your first automated UI tests: one that checks a static screen, and one that simulates a button tap and checks the result — with no manual tapping through the app required to verify either.

---

## Table of contents

- [Learning objectives](#learning-objectives)
- [Recap: why testing, now](#recap-why-testing-now)
- [Part 2: Introduction to UI testing in Jetpack Compose](#part-2-introduction-to-ui-testing-in-jetpack-compose)
  - [2.0 Why Compose UI tests are different from a plain unit test](#20-why-compose-ui-tests-are-different-from-a-plain-unit-test)
  - [2.1 Setup: dependencies and the test source set](#21-setup-dependencies-and-the-test-source-set)
  - [2.2 Semantics: how a test "sees" the UI](#22-semantics-how-a-test-sees-the-ui)
  - [2.3 Your first test: `JetPackDisplay0`](#23-your-first-test-jetpackdisplay0)
  - [2.4 Finders, matchers, actions, assertions](#24-finders-matchers-actions-assertions)
  - [2.5 A test with a user action: `JetPackIncreasingCounter`](#25-a-test-with-a-user-action-jetpackincreasingcounter)
  - [2.6 When `onNodeWithText` isn't enough: ambiguous matches](#26-when-onnodewithtext-isnt-enough-ambiguous-matches)
  - [2.7 A note on synchronization](#27-a-note-on-synchronization)
- [Part 2 wrap-up](#part-2-wrap-up)

---

## Learning objectives

By the end of Part 2 of this lab, you should be able to:

1. Explain what the semantics tree is, and why Compose UI tests query it instead of the Compose UI tree directly.
2. Add the correct test dependencies and `androidTest` source set to a Compose project that has none yet.
3. Write a complete Compose UI test using `createComposeRule()`, `setContent {}`, a finder, and an assertion.
4. Use `performClick()` to simulate a user action and verify its effect with a follow-up assertion.
5. Explain why `onNodeWithText(...)` can fail with "found multiple nodes" and how `Modifier.testTag(...)` + `onNodeWithTag(...)` fixes it.

---

## Recap: why testing, now

Every project so far in this course has been verified **manually** — build it, run it, tap through it, look at the screen. Today's goal: write the first automated tests in this course — code that launches a composable, interacts with it the way a user would, and asserts the result is correct, with no human needing to look at a screen.

---

## Part 2: Introduction to UI testing in Jetpack Compose

### 2.0 Why Compose UI tests are different from a plain unit test

A plain JUnit test calls a function and checks its return value — no UI involved. A **Compose UI test** actually **composes** real composables, runs them, and queries and interacts with the result the way a person would. This is why Compose UI tests run as **instrumented tests** (`androidTest`, not `test`) — they need an actual Android environment to run real composition.

### 2.1 Setup: dependencies and the test source set

Neither `JetPackDisplay0` nor `JetPackIncreasingCounter` has a test source set yet. Two things are needed in `app/build.gradle.kts`.

**1. Add the test dependencies:**

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
```

- `androidTestImplementation(platform(...))` — the same Compose BOM, applied to tests too, so test artifact versions stay in sync with the main ones.
- `androidx.compose.ui:ui-test-junit4` — the actual testing library.
- `androidx.test.ext:junit` — plain AndroidX JUnit4 integration, needed by every instrumented test.
- `debugImplementation("androidx.compose.ui:ui-test-manifest")` — note `debugImplementation`, not `androidTestImplementation`. Needed because `createComposeRule()` (used below) launches a throwaway host activity that this artifact provides a manifest entry for. It's `debugImplementation` so it never ends up in a release build.

**2. Point the project at a test runner.** Add this inside `defaultConfig`:

```kotlin
defaultConfig {
    applicationId = "com.example.jetpackdisplay0"
    minSdk = 21
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
}
```

**3. Create the test source set.** Instrumented tests live in `app/src/androidTest/java/<package>/` — a sibling to `app/src/main/java/<package>/`, not under `app/src/test/`.

```
JetPackDisplay0/
└── app/src/
    ├── main/java/com/example/jetpackdisplay0/
    │   └── MainActivity.kt
    └── androidTest/java/com/example/jetpackdisplay0/      ← new
        └── DisplayZeroTest.kt                              ← new
```

✅ **Checkpoint:** sync Gradle after the dependency changes. There's nothing to run yet.

### 2.2 Semantics: how a test "sees" the UI

A Compose UI test never looks at pixels, and never walks the Compose UI tree directly. It queries a separate, parallel structure called the **semantics tree**:

- The semantics tree is generated **alongside** the UI as it composes.
- Many composables **automatically** expose something to it: `Text("0")` exposes its text, "0"; `Button(onClick = {...}) { Text("Click") }` exposes both the "Click" text *and* the fact that this node has a click action.
- This is the **same framework accessibility services use** — a screen reader reads this exact tree.
- You can add/override what's exposed with `Modifier.semantics { contentDescription = "..." }`, useful for icon-only buttons with no text of their own.

**Why this matters:** tests written against semantics ("a clickable thing labeled Click") survive a visual redesign that doesn't change the app's behavior. Tests written against raw layout structure would break on every redesign, even ones that change nothing a user would call "the app's behavior."

### 2.3 Your first test: `JetPackDisplay0`

`DisplayZero()` has no state, no parameters, always renders one `Text("0")` — the simplest possible first example.

Create `app/src/androidTest/java/com/example/jetpackdisplay0/DisplayZeroTest.kt`:

```kotlin
package com.example.jetpackdisplay0

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.Rule
import org.junit.Test

class DisplayZeroTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun displaysZero() {
        composeTestRule.setContent {
            DisplayZero()
        }

        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }
}
```

**What each piece does:**
- `@get:Rule val composeTestRule = createComposeRule()` — a JUnit **Rule**, run automatically before and after every `@Test`. `createComposeRule()` has no activity access, used here because `DisplayZero()` doesn't need one.
- `composeTestRule.setContent { DisplayZero() }` — composes `DisplayZero()` directly, in isolation, not the whole app.
- `composeTestRule.onNodeWithText("0")` — a **finder**: searches the semantics tree for a node whose text is exactly `"0"`, returns a handle to it.
- `.assertIsDisplayed()` — an **assertion**: fails the test if no such node exists, or if it exists but isn't visible.

✅ **Checkpoint:** run this test (right-click the file → Run, or `./gradlew connectedAndroidTest`) on a connected emulator/device. It should pass.

**Try this on purpose:** temporarily change `Text(text = "0", ...)` to `Text(text = "zero", ...)` in `DisplayZero()`, and re-run the test. It should **fail**, with:
```
java.lang.AssertionError: Assert failed: The component is not displayed!
```
Revert the change afterward.

### 2.4 Finders, matchers, actions, assertions

Four categories, each with a distinct job:

**Finders** — locate node(s):
- `onNodeWithText(text)`, `onNodeWithContentDescription(description)`, `onNodeWithTag(tag)` (section 2.6), `onNode(matcher)` (general-purpose form), `onAllNodes(matcher)`/`onAllNodesWithText(...)` (plural forms).

**Matchers** — boolean conditions, passed into `onNode(...)`/`onAllNodes(...)`:
- `hasText(text)`, `hasTestTag(tag)`, `hasContentDescription(text)`, `hasClickAction()`, `isDisplayed()`.

**Actions** — simulate a user interacting with a found node:
- `performClick()` (section 2.5), `performTextInput(text)` (typing into a field), `performScrollTo()` (scrolls a node into view in a `LazyColumn`/`LazyRow`).

**Assertions** — verify something about a found node:
- `assertIsDisplayed()`, `assertExists()` (weaker — true even if scrolled off-screen), `assertTextEquals(text)` (useful after an action changes a node's text), `assertCountEquals(n)`/`assertAny(matcher)`/`assertAll(matcher)` (collection forms, used with `onAllNodes(...)`).

**The shape to remember:** almost every Compose UI test follows **find → (optionally act) → assert**. Section 2.3's test was find → assert. Section 2.5, next, is find → act → find again → assert.

### 2.5 A test with a user action: `JetPackIncreasingCounter`

**Note:** `JetPackIncreasingCounter`'s actual composable is `TwoCounters()` — two side-by-side numbers (`$count` and `${-count}`) with one shared "Click" button, not a single-counter version:

```kotlin
@Composable
fun TwoCounters() {
    var count by remember { mutableIntStateOf(0) }
    Column(/* ... */) {
        Row(/* ... */) {
            Column(/* ... */) { Text(text = "$count", fontSize = 72.sp) }
            Column(/* ... */) { Text(text = "${-count}", fontSize = 72.sp) }
        }
        Button(onClick = { count += 1 }, /* ... */) {
            Text(text = "Click")
        }
    }
}
```

Set up `JetPackIncreasingCounter`'s `app/build.gradle.kts` the same way as section 2.1, then create `app/src/androidTest/java/com/example/jetpackincreasingcounter/TwoCountersTest.kt`:

```kotlin
package com.example.jetpackincreasingcounter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextEquals
import org.junit.Rule
import org.junit.Test

class TwoCountersTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickingButtonIncrementsCount() {
        composeTestRule.setContent {
            TwoCounters()
        }

        composeTestRule.onNodeWithText("Click").performClick()

        composeTestRule.onNodeWithText("1").assertTextEquals("1")
        composeTestRule.onNodeWithText("-1").assertTextEquals("-1")
    }
}
```

**What's new:**
- `onNodeWithText("Click")` finds the button by the text *inside* it — the test doesn't know or care it's specifically a `Button` composable, just that there's a node labeled "Click."
- `.performClick()` — the **action**. Simulates a real tap, runs the `onClick` lambda (`{ count += 1 }`), and triggers real recomposition.
- The test re-queries with `onNodeWithText("1")`/`onNodeWithText("-1")` **after** the click — you cannot reuse a handle from before the click and expect it to reflect the new state. Each `onNodeWithText(...)` call re-searches the current tree at the moment it's called.

✅ **Checkpoint:** run `TwoCountersTest`. It should pass.

### 2.6 When `onNodeWithText` isn't enough: ambiguous matches

**Try this on purpose:** right after `setContent { TwoCounters() }`, before clicking anything, add:
```kotlin
composeTestRule.onNodeWithText("0").assertTextEquals("0")
```
At the start (`count == 0`), **both** `Text("$count")` and `Text("${-count}")` show `"0"` — genuinely identical text. This **fails**:
```
java.lang.AssertionError: Failed to assert the following: (Text + EditableText = [0])
Reason: Expected exactly '1' node but found '2' nodes that satisfy: (Text + EditableText contains '0' (ignoreCase: false))
```

**This is `onNodeWithText`'s biggest limitation:** it only knows about *text content*, not *position* or *identity*. The moment two nodes have the same text — which happens here at `count == 0` — a singular finder can't tell them apart.

**Note:** `onNodeWithText("1")`/`onNodeWithText("-1")` after the click (section 2.5) were each individually unambiguous — "1" and "-1" are different text. The ambiguity only exists at the single starting instant where both counters coincidentally show the same value.

**The fix: `Modifier.testTag(...)` + `onNodeWithTag(...)`.** Add a stable, test-only identifier to each composable:

```kotlin
Text(
    text = "$count",
    fontSize = 72.sp,
    modifier = Modifier.testTag("positiveCount")
)
// ... and similarly:
Text(
    text = "${-count}",
    fontSize = 72.sp,
    modifier = Modifier.testTag("negativeCount")
)
```

```kotlin
composeTestRule.onNodeWithTag("positiveCount").assertTextEquals("0")
composeTestRule.onNodeWithTag("negativeCount").assertTextEquals("0")

composeTestRule.onNodeWithText("Click").performClick()

composeTestRule.onNodeWithTag("positiveCount").assertTextEquals("1")
composeTestRule.onNodeWithTag("negativeCount").assertTextEquals("-1")
```

✅ **Checkpoint:** with `testTag` added and the test updated, every query is unambiguous at every point — not just by coincidence of which numbers happen to differ.

**Worth noting:** `testTag` is test-only scaffolding added to production code — it has zero effect on what a user sees, but it's a real, permanent addition to `MainActivity.kt` made purely to make this composable testable. This is a normal, accepted tradeoff in real Compose code, not a hack.

### 2.7 A note on synchronization

Neither test above needed a wait, sleep, or polling loop between `performClick()` and the following assertion — `performClick()` returns only *after* Compose has finished processing the resulting recomposition, not merely after the click event was dispatched. The testing framework automatically waits for Compose to be "idle" (no pending recomposition, layout, or animation) before every action and assertion. This is why you'll never see `Thread.sleep(500)`-style code in a Compose UI test — and if you ever find yourself reaching for one, it's a sign something else about the test is structured wrong, not a sign you need to wait longer.

---

## Part 2 wrap-up

A Compose UI test composes a real composable, finds real nodes in its semantics tree, simulates a real user action, and asserts on the result — no screen, no human, no sleep statement required. When a finder can't tell two nodes apart, give the ambiguous nodes distinct `testTag`s, the same way you'd give two otherwise-identical variables distinct names.
