---
name: android-about-page
description: Every Android app gets an About tab with app name, version number and build date from its first build (user rule). Adding an About screen to an Android app that reports the app name, version and the build timestamp, without the traps - a naive timestamp that defeats incremental builds and recompiles the app on every configuration, a version number that cannot answer "is the phone running what I just built", a BuildConfig field that fails to compile because buildConfig is off by default since AGP 8, and java.* references that do not resolve in a Kotlin DSL build file. Use when building any new Android app, when adding an About page, when a device test result might have come from a stale APK, or when rolling the same About screen across several app repos.
---

# android-about-page

An About screen looks like the most trivial thing in an app and contains one genuinely useful
fact. The name and the version answer *which app this is*. Only the **build timestamp** answers
*is the phone running what I just built*, and that is the question every device test starts with.

A version number cannot answer it. `versionName` changes when somebody decides to change it,
which is roughly never during a day of debugging, so ten builds in an afternoon all report `1.1`
and a stale APK is indistinguishable from a fresh one. Two device sessions were lost in one week
to results that turned out to have come from an older install.

So the timestamp is the point of the screen, and it is also the only part with a trap in it.

## Required on every app (user rule, Nov-09-2026)

Every Android app Claude builds gets an **About tab** from its first version, showing at least
the **app name, version number and build date**. It is part of the first build, not a later
polish item.

- **Where:** a tab or bottom-navigation destination named "About". If the app has no navigation,
  add a minimal two-destination bar (main screen, About) or an About entry in the top-bar
  overflow menu, in that order of preference. An existing Settings screen may host it as its
  last section, as long as "About" is reachable in one tap from the main screen.
- **Rows:** app name, version (`versionName (versionCode)`), build date (`BUILD_TIME` below).
  Package and variant are recommended extras.
- **Check it on the device:** after installing, open the About tab and confirm the build date is
  the one you just built. That is the screen's job.

## The trap: a naive timestamp makes every build a full build

The obvious implementation is wrong:

```kotlin
// Do NOT do this.
buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
```

Gradle evaluates that at **configuration time**, on every single invocation. So the generated
`BuildConfig` differs every time, and everything that reads `BuildConfig` recompiles. An
incremental build becomes a full one, for a string nobody is looking at. It is worse on a big
module and worst in exactly the situation this screen exists to support: a tight edit, build,
install, test loop.

Freeze it to something that changes when the *code* changes:

```kotlin
// At the very top of app/build.gradle.kts, before any statement. Kotlin DSL will not resolve
// `java.text.SimpleDateFormat` written inline: it needs a real import.
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * When the code this APK was built from last changed.
 *
 * Not System.currentTimeMillis(): that changes on every configuration, so BuildConfig differs on
 * every build and everything reading it recompiles. Taken from the newest source file instead, so
 * it moves when the code moves and is stable when the code is not.
 */
val buildStamp: String = run {
    val newest = fileTree("src") { include("**/*.kt", "**/*.java", "**/*.xml") }
        .files.maxOfOrNull { it.lastModified() } ?: System.currentTimeMillis()
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.UK).format(Date(newest))
}

android {
    defaultConfig {
        buildConfigField("String", "BUILD_TIME", "\"$buildStamp\"")
    }
    buildFeatures {
        // Off by default since AGP 8. Without this the field is simply never generated and the
        // failure is "unresolved reference: BUILD_TIME" in the UI file, which reads as a typo.
        buildConfig = true
    }
}
```

**Verify both halves, because both were wrong on the first attempt:**

```bash
./gradlew :app:generateDebugBuildConfig
grep BUILD_TIME app/build/generated/source/buildConfig/debug/<pkg path>/BuildConfig.java
./gradlew :app:generateDebugBuildConfig    # must now say UP-TO-DATE
```

The first run proves the field exists and holds a real date. The second proves the fix worked: a
naive timestamp can never be `UP-TO-DATE`, because its input changed by definition.

## Two things that will not compile, and their misleading errors

**`java.*` written inline in a `.kts` file.** `java.text.SimpleDateFormat("...")` inside a
`build.gradle.kts` fails with `Unresolved reference 'text'` and `Unresolved reference 'util'`,
pointing at a column in the middle of a working expression. The Kotlin DSL needs the import, and
imports in a `.kts` file must be at the very top, above every statement including the plugins
block.

**`buildConfig` off by default.** Since AGP 8 the flag defaults to false, so a `buildConfigField`
silently generates nothing. The error appears later, in the Kotlin file that reads it, as an
unresolved reference to the constant.

## The screen itself

Compose, four or five rows, on the About tab (see "Required on every app" for where it goes).
It is a reference rather than a setting, and nothing on it is actionable.

```kotlin
@Composable
private fun AboutPanel() {
    Panel {
        Text("About", style = MaterialTheme.typography.titleSmall)
        AboutRow("App", "Your App Name")
        AboutRow("Package", BuildConfig.APPLICATION_ID)
        AboutRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        AboutRow("Build", BuildConfig.BUILD_TIME)
        AboutRow("Variant", if (BuildConfig.DEBUG) "debug" else "release")
    }
}
```

`BuildConfig` must be imported explicitly (`import <applicationId>.BuildConfig`) even from inside
the same app module, if the UI code sits in a subpackage.

### Include the variant, and know why

Debug and release are signed with different keys, so they cannot upgrade each other: installing
one over the other fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, which reads like a corrupt
package rather than a key mismatch. A row saying which one is on the phone turns that into a
one-line diagnosis.

### What not to put on it

- **A build time that is the install time.** `packageManager.getPackageInfo().lastUpdateTime`
  answers when the APK was *installed*, not when it was built, so a reinstall of an old APK looks
  brand new. That is the exact confusion the screen exists to remove.
- **A git SHA, unless the build is reproducible from it.** Reading `git rev-parse` at
  configuration time reintroduces the incremental-build problem (the value changes on every
  commit, which is fine) *and* is misleading with a dirty tree, which is the normal state while
  debugging. A timestamp derived from source mtimes is honest about a dirty tree in a way a SHA is
  not.
- **A version code you have to maintain by hand in two places.** Read it from `BuildConfig`, never
  retype it into a string.

## Rolling it across several repos

Check first, then apply. The variation that matters is only whether `buildConfig = true` is
already set and where the settings screen lives:

```bash
# Which repos are Android app projects
for d in */; do [ -f "$d/app/build.gradle.kts" ] && echo "$d"; done

# Which already have an About screen, and which already set a build time
grep -rl "BUILD_TIME" */app/build.gradle.kts
find . -iname "*about*" \( -name "*.kt" -o -name "*.xml" \) | grep -v build/
```

Do them one at a time and run `generateDebugBuildConfig` after each. The failure modes above are
per-repo (a module without `buildConfig = true`, a settings screen with a different container
composable), and a batch edit hides which repo broke.
