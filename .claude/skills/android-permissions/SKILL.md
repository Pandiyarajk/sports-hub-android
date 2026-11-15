---
name: android-permissions
description: Every permission an Android app needs is requested by the app itself, never left for the user to grant by hand (user rule). Asking for Android runtime permissions without the failures that look like something else - an app that never prompts, a crash thrown by the first request rather than by the code that made it, a permission that reports "denied" forever because it does not exist on that API level, and a guarded read that crashes a screen on open; plus roles (the four components the default-SMS role needs, getDefaultSmsPackage returning null on Android 16 while the role is held, the WRITE_SMS app-op in "ignore" mode silently discarding inserts that still return an id) and pickers that avoid a permission. Use when building any Android app that needs a permission, when adding or reviewing a permission request, when a request dialog never appears, when a permission-dependent feature silently does nothing or reports an empty/degraded result forever, when a screen crashes on first open, or when deciding between asking at launch and asking at point of use.
---

# android-permissions

Runtime permissions are the part of an Android app most likely to be written once, tested on a
device that already granted everything, and shipped broken. Every failure below was measured on
a real phone (Xiaomi 23124RN87I, Android 15, `minSdk 31`), and most of them are invisible on an
emulator or a developer's own handset.

The unifying problem: **the code that asks and the code that fails are in different places.** A
missing permission surfaces as a `SecurityException` where the value is *read*, a broken request
surfaces as a crash in the activity base class, and a permission that cannot exist surfaces as a
permanent, un-actionable "not granted" in your own UI.

## The default: the app asks, never the user by hand (user rule, Nov-09-2026)

Every permission, special access or role the app needs is **wired and requested by the app
itself**, through the system dialog or the Settings screen the app opens. Never assume the user
will grant something by hand, and never ship a feature whose only route to its permission is a
README step, an `adb pm grant`, or "open Settings and allow X" text with no button behind it.

- Declare it in the manifest **and** request it in code, in the same change. A declared but
  never-requested runtime permission is denied forever and the feature silently does nothing.
- Pick the moment with the rules below (launch for what the app is about, point of use for the
  rest), but every gate must have a request reachable in one gesture from the feature.
- For special access and roles, the app opens the exact Settings page or role request intent and
  re-reads the state on resume. The Settings link is the fallback for a permanently refused
  permission, not the primary path.
- Test the flow from a fresh install with nothing granted (see "Verifying on a device"). A
  feature that only works because the developer's phone already granted it is not done.

## Decide where you ask, and know that "nowhere" is a decision

Deferring every request to the moment the feature is used is the textbook advice and it is not
free. An app whose first screen only *describes* what it needs, with a link into Settings, reads
to a user as an app that does not work:

> "I expected the app to ask permission on launching the app."

That was the actual reaction to a build that requested nothing until a button was pressed. Both
patterns are defensible; the mistake is arriving at "never asks" by accident because every
individual request was reasonably deferred.

A workable split:

- **At launch, once**, ask for the small set the app is *about*. A backup app asks for contacts,
  call log, messages and notifications.
- **At point of use**, ask for anything peripheral, where the reason is obvious only in context.
  The same app asks for `BLUETOOTH_CONNECT` when the Bluetooth control is tapped, because a
  backup app requesting Bluetooth on launch reads as malware.

Guard the launch-time ask so it fires once per launch and not once per recomposition:

```kotlin
// rememberSaveable, not remember: a rotation must not count as a fresh launch and re-prompt.
var asked by rememberSaveable { mutableStateOf(false) }
LaunchedEffect(missing) {
    if (!asked && missing.isNotEmpty()) {
        asked = true
        launcher.launch(missing.map { it.manifestName }.toTypedArray())
    }
}
```

## The crash: an old FragmentActivity cannot carry a modern permission request

```
java.lang.IllegalArgumentException: Can only use lower 16 bits for requestCode
    at com.example.MainActivity.k(SourceFile:10)
```

`ActivityResultRegistry`, which backs every `rememberLauncherForActivityResult`, allocates
request codes **above `0xFFFF`**. `FragmentActivity` before 1.3 still enforces the legacy 16-bit
limit in `startActivityForResult` and throws. So the app dies the first time it asks for
anything.

The trap is how you get there. Nobody chooses `FragmentActivity` for its own sake; you switch to
it because **`BiometricPrompt` requires one**, and `androidx.biometric:1.1.0` then quietly
resolves `androidx.fragment` to **1.2.5**. Verified:

```
$ ./gradlew :app:dependencies --configuration releaseRuntimeClasspath
+--- androidx.biometric:biometric:1.1.0
|    \--- androidx.fragment:fragment:1.2.5      <- the 16-bit check lives here
```

Fix by pinning fragment ahead of the transitive version:

```kotlin
implementation("androidx.fragment:fragment-ktx:1.8.5")
```

```
\--- androidx.fragment:fragment:1.2.5 -> 1.8.5   <- after
```

Two things make this expensive out of proportion to the fix:

- **It only fires when a request is actually made.** An app that never asks (see the section
  above) never crashes, so adding the launch-time prompt is what "introduces" a crash that was
  latent all along, and the new code takes the blame.
- **The stack trace names your activity**, not the library, and R8 has renamed the frame to
  `MainActivity.k`. Nothing in it says "fragment version".

Check `./gradlew :app:dependencies | grep androidx.fragment` whenever `BiometricPrompt`,
`FragmentActivity` or `AppCompatActivity` enters a Compose app.

**Make the build enforce it; a note in a skill did not.** On Nov-14-2026 `gitea-admin-android`
0.1.0 was built by an agent that had been told to read this skill. It added
`androidx.biometric:1.1.0`, did not pin fragment, passed 38 unit tests, `apksigner verify` and the
DEX secret scan, and was posted. First launch on an emulator threw the exception above. Put the
check in `app/build.gradle.kts` so it runs before every build:

```kotlin
tasks.register("checkFragmentFloor") {
    doLast {
        val old = project.configurations.getByName("releaseRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
            .filter { it.group == "androidx.fragment" && it.name == "fragment" }
            .map { it.version }
            .filter { v ->
                val p = v.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
                p.getOrElse(0) { 0 } < 1 || (p.getOrElse(0) { 0 } == 1 && p.getOrElse(1) { 0 } < 3)
            }
        check(old.isEmpty()) { "androidx.fragment resolves to $old: pin 1.8.5" }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("checkFragmentFloor") }
```

Verified both ways: with the pin it passes (`resolves to [1.8.5]`); with the pin commented out the
build fails with `androidx.fragment resolves to [1.2.5]`.

## A permission that does not exist on the device is denied forever

`POST_NOTIFICATIONS` and `NEARBY_WIFI_DEVICES` arrived in API 33. On an app with `minSdk 31`
running on Android 12 or 12L, `checkSelfPermission` returns `PERMISSION_DENIED` for both,
permanently, and requesting them does nothing. A permission-status UI built on the naive check
therefore lists two permissions as missing forever, beside a button that cannot work.

Carry the API level with the permission and treat "not present here" as satisfied:

```kotlin
enum class AppPermission(
    val manifestName: String,
    val sinceSdk: Int = Build.VERSION_CODES.S,
) {
    READ_SMS(Manifest.permission.READ_SMS),
    POST_NOTIFICATIONS(Manifest.permission.POST_NOTIFICATIONS, Build.VERSION_CODES.TIRAMISU),
    NEARBY_WIFI(Manifest.permission.NEARBY_WIFI_DEVICES, Build.VERSION_CODES.TIRAMISU);

    val appliesHere get() = Build.VERSION.SDK_INT >= sinceSdk

    // Absent on this device means there is nothing to ask for and nothing gated.
    fun isGranted(context: Context) = !appliesHere ||
        ContextCompat.checkSelfPermission(context, manifestName) == PERMISSION_GRANTED
}
```

Never filter this list by "which ones did the user deny"; filter by "which ones exist here".

## The guarded read crashes the screen, not the feature

A permission gates the *API*, not your request for it. Reading a guarded value without the
permission throws where the read happens, which is usually a state function called on resume:

```kotlin
// Crashes the whole Toggles screen on first open for every user who has not granted Bluetooth,
// which is every user, initially. BluetoothAdapter.isEnabled requires BLUETOOTH_CONNECT from
// API 31 and throws SecurityException without it.
bluetoothEnabled = adapter?.isEnabled == true

// Degrade instead: an unknown state is false, and the UI offers the grant.
bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
```

Audit every read in a screen's state builder against the permission it needs. The usual suspects
are `BluetoothAdapter.isEnabled`, `TelephonyManager` identifiers, `NetworkInterface` enumeration
and anything on `CallLog` or `Telephony`.

## Android stops showing the dialog, and the silence looks like an instant refusal

After the user refuses twice, the system stops presenting the dialog at all. The launcher still
runs and the callback still fires, immediately, with "denied". From inside the app a permanent
refusal is indistinguishable from a fast one, and a UI whose only affordance is "Grant" becomes
a button that visibly does nothing.

Always keep a route into Settings alongside the request, and say why it is there:

```kotlin
Button(onClick = { launcher.launch(missing) }) { Text("Grant permissions") }
Text("If nothing happens, Android has stopped asking because these were refused before. " +
     "Grant them in Settings instead.")
TextButton(onClick = { openAppDetails(context) }) { Text("Open app permissions") }
```

`shouldShowRequestPermissionRationale` returning false *can* mean this, but it also returns false
before the first ask, so it cannot distinguish "never asked" from "permanently refused" on its
own. Tracking your own "have asked" flag alongside it is what makes it usable.

## The request that exists, works, and is never reached

The most expensive permission bug in this collection was not a broken request. The launcher was
wired correctly, the system dialog appeared when invoked, and the grant callback worked. The
feature was still dead for every user, because reaching the request took **two separate
gestures** and nothing said the second one was required:

1. Turn on a "Weather" toggle in a settings screen.
2. *Then* notice a separate "Allow location" pill that appeared underneath, and tap it.

Miss step 2 and the feature silently degrades forever. In this case a widget tile read "no fix"
indefinitely, which is a plausible-looking answer — it never says "you skipped a step". The
report came back as "weather does not work", and the first instinct was to debug fetching,
location providers and caching, all of which were fine.

**A toggle that turns on a feature IS the user asking for that feature.** Request its permission
in the same gesture:

```kotlin
onToggle = { wanted ->
    onWeatherToggle(wanted)
    // Asked here, not behind a second control the user has to notice. Only when it
    // is actually missing, so a user who granted once is never prompted again.
    if (wanted && !hasPermission && manualFallback == null) {
        launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
```

The general rule: **count the gestures between "user expresses intent" and "system dialog
appears". If the answer is more than one, the feature has a silent failure mode.** A second tap
is only acceptable when the first gesture was not itself a request for the feature.

Two supporting habits, both learned the same way:

- **Never grant the permission with `adb pm grant` while testing the flow.** It makes the
  feature work on your device and hides the fact that no user can reach it. If you have granted
  it that way, `adb shell pm revoke <pkg> <permission>` before judging the flow, and check the
  grant flags afterwards: `USER_SET` in `dumpsys package <pkg>` proves a human granted it
  through the real dialog, while its absence means you granted it yourself.
- **An uninstall wipes runtime grants and all app storage.** Reinstalling mid-session therefore
  silently resets both the permission and any "feature enabled" flag, and the feature that
  "stopped working" was never re-enabled. Re-check both before investigating anything else.

## Three different gates that are not permissions, and each has its own flow

Modelling these as runtime permissions is a common and costly mistake:

| Gate | Granted by | How you learn the result |
|---|---|---|
| Runtime permission | `RequestPermission` contract | callback |
| **Special access** (`WRITE_SETTINGS`, DND policy, battery exemption) | a Settings screen you launch | **no callback**: re-read on `ON_RESUME` |
| **Role** (default SMS, dialer, home) | `RoleManager.createRequestRoleIntent` | activity result, and see below |

Special access has no result callback at all. The user leaves for a Settings page and returns,
so the only reliable read is on resume:

```kotlin
LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = controller.read() }
```

A remembered value instead leaves the warning on screen after it has been satisfied, which
teaches people to ignore warnings.

**A role additionally requires manifest components before it is offered at all.** The default SMS
role needs all four of these, each guarded by the stated permission:

| Component | Handles | `android:permission` |
|---|---|---|
| receiver | action `android.provider.Telephony.SMS_DELIVER` | `BROADCAST_SMS` |
| receiver | action `android.provider.Telephony.WAP_PUSH_DELIVER`, mime `application/vnd.wap.mms-message` | `BROADCAST_WAP_PUSH` |
| service | action `android.intent.action.RESPOND_VIA_MESSAGE`, schemes `smsto:` `mmsto:` | `SEND_RESPOND_VIA_MESSAGE` |
| activity | actions `SEND` and `SENDTO`, schemes `sms:` `smsto:` `mms:` `mmsto:` | none |

The service is the "respond via message" handler, not a `SENDTO` handler; `SENDTO` belongs to the
activity. This exact set got the role granted on a Redmi 7A (Android 10) and a Nothing A009P
(Android 16) in mforensics-seeder (Oct-30-2026). Declaring three of four fails exactly like
declaring none: `isRoleAvailable` returns false, the request intent does nothing, and there is no
error anywhere. An app can request the role but cannot hand it back; it can only open
`Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS`.

### Holding the role is not the same as being able to write

Measured on a Nothing A009P (Android 16), mforensics-seeder, Oct-30-2026:

- **`Telephony.Sms.getDefaultSmsPackage()` returned `null` while the app held the role.**
  `adb shell cmd role get-role-holders android.app.role.SMS` printed the app's package, and
  `settings get secure sms_default_application` printed `null`. A check written as
  `getDefaultSmsPackage(ctx) == packageName` therefore reported "not default" and disabled every
  write. Also accept `roleManager.isRoleHeld(RoleManager.ROLE_SMS)`, and re-read it on resume:
  the user can change the default SMS app in Settings with no callback to you.
- **The `WRITE_SMS` app-op can be `ignore`, and then inserts are silently discarded.**
  `adb shell appops get <pkg>` showed `WRITE_SMS: ignore`. In that mode
  `contentResolver.insert(Telephony.Sms.CONTENT_URI, ...)` neither throws nor returns null: it
  returned `content://sms/10576`, the app logged success, and the highest `_id` in the provider
  stayed 10575. Only a `SecurityException` (app-op `deny`/errored) is loud. After
  `adb shell cmd appops set <pkg> WRITE_SMS allow` the next insert (`_id` 10577) persisted. The
  setting can reset after a reboot or reinstall.
- **So read the row back before reporting success.** Query the returned `_id`; report Failed if
  it is not there. A returned Uri is not proof of a write to any system provider.
- **Call-log writes need only `WRITE_CALL_LOG`**, a normal runtime permission, not the SMS role.
- **Provider quirks that make an insert count lie:** the SMS provider keeps one draft (`TYPE=3`)
  per conversation, so a second draft to the same number replaces the first; a `VOICEMAIL`-type
  call row needs the hidden URI param `allow_voicemails=true` and is still refused for a normal
  app.

### A picker can replace a permission

To let the user choose one phone number, launch
`Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)` and query the
returned Uri for `Phone.NUMBER`. The picker grants read access to that one row only, so the app
needs no `READ_CONTACTS` at all (verified in mforensics-seeder 0.2.7, which declares none).
Normalise the number before validating it: Contacts stores spaces, dashes and missing country
codes.

## A permission added in an update can arrive already granted

Runtime permissions are granted per **group**, and a permission your update adds to a group the
user already granted is granted with it, with **no dialog**. Measured on a Nothing A009P
(Android 16): version 0.1.0 of an app held `NEARBY_WIFI_DEVICES` (granted by the user); 0.2.0
added `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`, which sit in the same "Nearby devices" group.
After `adb install -r` of 0.2.0, `dumpsys package` showed both `granted=true` with the `USER_SET`
flag, and the app's launch-time request never showed anything.

Two consequences:

- **The flow a new user sees cannot be tested by updating.** The dialog that never appeared on
  the update is the one every fresh install will show. To see it, uninstall (which wipes data)
  or `pm revoke` **every** permission in the group, not just the new ones, so the group as a
  whole is ungranted before you open the app.
- **`USER_SET` on a permission does not mean the user saw a prompt for it.** It means the user
  granted the group, possibly for a different permission, a version ago.

## Verifying on a device

The system's own record is the only trustworthy answer, and it distinguishes "never asked" from
"user refused" via the `USER_SET` flag, which your app cannot see:

```bash
adb -s "$dev" shell "dumpsys package com.example.app" \
  | sed -n '/runtime permissions:/,/^$/p'
```

```
android.permission.READ_SMS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|... ]
android.permission.READ_CALL_LOG: granted=true, flags=[ USER_SET|... ]
```

Reset state between runs **per package**:

```bash
adb -s "$dev" shell pm revoke com.example.app android.permission.READ_SMS
```

And verify the *route*, not only the grant. A request that works when invoked can still be
unreachable (see above), and no `dumpsys` output will show that. Revoke, then drive the app the
way a new user would and count the gestures:

```bash
adb -s "$dev" shell pm revoke com.example.app android.permission.ACCESS_COARSE_LOCATION
# then, on the device: open the app, turn the feature on, and stop.
# The system dialog must appear NOW, with no further tapping.
adb -s "$dev" shell "dumpsys package com.example.app" | grep ACCESS_COARSE_LOCATION
```

If the dialog did not appear at the moment the feature was switched on, the permission is
gated behind a step a real user will skip, and the feature has a silent failure mode regardless
of how correct the request code is.

**Never run bare `pm reset-permissions`.** It takes no package argument and resets runtime
permissions for **every app on the device**, so on a personal phone it silently re-prompts
unrelated apps. It is an easy command to reach for when you want a clean first-run state, and
the blast radius is the whole device.

Finally, confirm the app is actually on screen before concluding anything from its absence of
prompts: `adb shell dumpsys window | grep mCurrentFocus` naming a package other than yours makes
every other reading void. See the `android-deploy` skill for the rest of that trap.
