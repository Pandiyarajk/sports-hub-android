---
name: android-deploy
description: 'Install an Android build onto a phone (USB preferred, wireless adb as fallback; with no USB phone, boot an emulator and smoke-test first launch before the APK is posted; checks that need real hardware wait until the user asks), prove it actually works, then publish it: rotating mDNS ports and stale records, OEM install prompts that block adb with no output, the on-device measurements that report success while measuring nothing, and collecting a release APK to D:\GitHub\apk plus posting it to the Telegram channel, after scanning the DEX for the credentials an unminified build bakes in and confirming the signature with apksigner. Use when deploying or testing an APK on a phone over USB or wireless debugging, when no device is attached, when `adb install` hangs or the app is installed but adb never returns, when a screenshot or gfxinfo reading looks like a pass and cannot be, when driving an app''s UI over adb on a phone other sessions may also be using (gate every tap on the app in front, tap by label with scripts/adb-ui.ps1), when a screen-off test needs the screen actually off, when sending or sharing an APK with anyone, after a release build that should reach testers, or when old APK posts must be deleted from the channel (the bot cannot delete posts older than 48 hours; delete as the user account).'
---

# android-deploy

<!-- Modified: Nov-15-2026 (black screenshot causes: FLAG_SECURE, still starting; APK name back to <appname>_<versionName>.apk) -->

Getting a build onto a phone (USB first, wireless debugging as the fallback), and — the harder half — believing what you then
measure on it. Four of the failures below **report success**: adb hangs after the install has
already completed, a screenshot returns a valid PNG of nothing, a frame-timing report shows a
perfect zero, and a tap arrives as a drag. Each reads as a code problem and is not.

## Choosing the connection: USB first (user rule, Nov-09-2026)

Prefer a **USB** connection over wireless debugging whenever a phone is plugged in. USB has no
rotating ports, no stale mDNS records, no subnet mismatch, and `svc power stayon true` works on
it because the phone is charging. Wireless is the fallback, and the rest of this skill covers its
traps.

```bash
adb devices -l     # USB serials are plain (e.g. 3B0A1F2E); wireless ones are ip:port or adb-XXXX._adb-tls-connect._tcp
```

- If a USB serial is listed in `device` state, use it with `-s <serial>`, even when a wireless
  entry for the same phone is also connected.
- If USB shows `unauthorized`, ask the user to accept the RSA prompt on the phone; do not fall
  back to wireless silently.
- Use wireless only when no USB device is listed, and say in the reply which one you used.
- **When the device vanishes mid-task** (`device not found`, or only `offline`; adb dropped Helm's
  phone repeatedly on Nov-14-2026 after USB-mode and Wi-Fi changes), do not retry the same
  install or tap blindly. Run `adb devices -l` once, then say what is needed (replug USB, accept
  the RSA prompt, run `adb tcpip 5555`, or the phone's current IP) and stop that step.

## No USB phone: use the emulator, then post (corrected Nov-14-2026)

The Nov-09 rule said a missing phone defers every device test. That let a release APK reach the
team channel that crashed on first launch and drew black text on the dark theme: 38 unit tests,
`apksigner verify` and the DEX scan were all green, and an emulator found both in under two
minutes. A missing phone is not a missing device.

1. Complete the code, unit tests and the signed release build, and run the static checks in this
   skill (DEX secret scan, `apksigner verify`, `aapt2 dump permissions`).
2. `adb devices -l`. If a phone is listed, use it (USB first). If not, use an emulator:
   ```bash
   SDK=<sdk.dir from local.properties>
   $SDK/emulator/emulator -list-avds            # reuse one, else create:
   echo no | $SDK/cmdline-tools/latest/bin/avdmanager create avd -n smoke        -k "system-images;android-36;google_apis;x86_64" -d pixel_6
   $SDK/emulator/emulator -accel-check           # "WHPX ... is installed and usable" on Windows
   $SDK/emulator/emulator -avd smoke -no-snapshot -no-audio -gpu swiftshader_indirect &
   adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '
')" = 1 ]; do sleep 5; done
   ```
   Booted and installed within about a minute on 14 Nov 2026 (Windows 11, WHPX).
3. Smoke test, every build: `adb install -r app.apk`; `adb shell pm clear <package>` for a fresh
   install; **`adb logcat -c -b crash` first** (the crash buffer keeps the previous run's
   exception and looks like a new failure); launch; walk the first-run flow and each top-level
   screen with `adb exec-out screencap -p > shot.png`; then `adb logcat -d -b crash | grep -c FATAL`
   must be 0. Read the screenshots: black text, clipped cells and missing prompts show there.
4. Only then post the APK. Say in the reply what ran where ("emulator, API 36") and which checks
   still need real hardware (OEM install prompt, biometric hardware, background limits, the
   screenshot-on-device rule in the android-app rule). Do not claim those.
5. Do not keep polling for a phone. Run the hardware-only checks when the user asks to test.

## Connecting: every mDNS record is stale until proven otherwise

The advertised port **rotates every time wireless debugging is toggled**, and old records linger
in adb's cache. So `adb mdns services` routinely lists a dead entry alongside a live one:

```
adb-XXXX (2)  _adb-tls-connect._tcp  172.25.138.180:43235   <- live
adb-XXXX      _adb-tls-connect._tcp  172.25.138.180:40225   <- dead, cached
```

Connecting to the dead one gives `actively refused it. (10061)`, which looks like the phone has
wireless debugging off when it does not.

Collect every record, try newest first, and **verify the device reached `device` state** — don't
trust `adb connect`'s exit code:

```bash
adb connect "$addr"
adb devices | grep -E "$addr\s+device" || continue   # else it didn't really connect
```

If none connect, the records are all stale: toggle wireless debugging off and on. Pairing is
separate and only needed once per PC — and the *pairing* port rotates every time that screen
opens, so read it fresh.

Prefer an already-connected device over rediscovery. Check `adb devices` first.

### First: can the PC reach the phone at all?

Read the error before hunting for ports. **"Actively refused" (10061)** means the phone answered
and nothing listens on that port: a stale or wrong port. **"Did not properly respond" (10060)**
means nothing came back at all, which is the route, not the port. On Oct-30-2026 every
`adb connect` to a Nothing A009P timed out with 10060, `adb mdns services` was empty and a
30000-49999 scan found no open port, all because the PC's Wi-Fi was `192.168.1.7/24` and the
phone had joined another network (`192.168.29.100`, read over USB with `adb shell ip route`).
Compare the two before anything else:

```powershell
Get-NetIPAddress -AddressFamily IPv4 | Select-Object InterfaceAlias, IPAddress, PrefixLength
adb -s <usb-serial> shell ip route        # the phone's subnet; or read it in Wi-Fi settings
```

Different subnets and no ping reply: put both on the same Wi-Fi; no adb setting will fix it.
A pairing port is also short-lived: it closes when the pairing pop-up closes, and `adb pair` to
it then fails with `protocol fault (couldn't read status message)`. Pair while the pop-up is
still showing its six-digit code.
`D:\GitHub\tools\adb-reconnect` now runs this subnet-and-ping check before scanning.

### When mDNS lists nothing at all

Sometimes `adb mdns services` returns an **empty list** while the phone is plainly up (it answers
ping on its Wi-Fi address) and wireless debugging is on: multicast does not cross the network
segment, or the phone stopped advertising. The port is still open, so find it rather than
walking over to the phone:

```powershell
# Every port seen on an A009P (Android 16) fell in 30000-49999: 33459, 40225, 41883, 42909,
# 43235. An observation, not a documented range. 1000 async connects per batch, about 25 s total.
$ip = "192.168.1.28"; $found = @()
foreach ($start in 30000..49999 | Where-Object { $_ % 1000 -eq 0 }) {
    $t = @{}; foreach ($p in $start..($start + 999)) { $c = [System.Net.Sockets.TcpClient]::new(); $t[$p] = @($c, $c.ConnectAsync($ip, $p)) }
    Start-Sleep -Milliseconds 700
    foreach ($p in $t.Keys) { $c, $task = $t[$p]; if ($task.Status -eq 'RanToCompletion' -and $c.Connected) { $found += $p }; $c.Dispose() }
}
$found
```

That scan found **two** open ports. `adb connect` reached `device` on one (41883) and left the
other `offline` (40511), so an open port proves nothing: connect each and keep the one in
`device` state, then `adb disconnect` the offline one so it does not shadow the live serial.

The scan can also be wrong in the other direction: it can **miss a live port**. On Oct-19-2026 it
printed `open: ` (nothing) while the phone was listening on 40521, which `adb mdns services` listed
as the `(2)` record and `adb connect` reached seconds later; the same phone had used five different
ports in 51 minutes (39829, 45783, 36101, 38853, 40521). Neither source is reliable alone, so treat
the scan as a hint: after an empty result, read `adb mdns services` again, try each candidate, and
accept only a line that `adb devices` shows as `device`.

Two serial traps in scripts:

- adb names the second record `adb-XXXX (2)._adb-tls-connect._tcp`, **with a space**. Splitting
  `adb devices` output on whitespace yields `adb-XXXX` and `-s` then reports "device not found".
  Split on the tab, or quote the whole serial.
- After the port rotates, the old `ip:port` serial in a script is dead ("actively refused it").
  Re-read `adb devices` rather than reusing a serial from earlier in the session.

## Installing: adb can hang long after the install succeeded

On MIUI/Xiaomi and several other skins, `adb install` blocks on an on-screen **"Install via
USB"** prompt. It emits nothing while blocked, so it is indistinguishable from a slow transfer.
Worse, the install can complete and adb still never return.

Check the package rather than waiting on the command:

```bash
adb -s "$dev" shell pm list packages | grep -q com.example.app && echo installed
```

Say so up front in any deploy script — "if this hangs, look at the phone" — because the person
running it is watching a terminal, not the device. And name the real failures:

| Symptom | Cause |
|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | MIUI restriction — **retry once before changing any setting** (see below); if it persists, *Developer options → Install via USB* is off |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, "signatures do not match" | debug vs release key — uninstall first |
| adb hangs, package present | OEM prompt; the install already worked |

`INSTALL_FAILED_USER_RESTRICTED` is not reliably a settings problem. On a Xiaomi 23124RN87I the
same APK failed with it and then installed on a bare retry seconds later, nothing changed in
between — an on-screen prompt had taken the first attempt. Retry once and look at the phone
before sending anyone into Developer options. What makes it expensive is *when* it strikes: the
first install after a debug→release key swap, i.e. straight after the uninstall, when the device
has no working copy of the app and the obvious reading is that the new build is broken.

When the retry fails too, it usually is the setting, and changing it may need a Mi account sign-in
on the phone. A route that does not need it: put the APK where the phone's file manager can
open it, and install from there.

```powershell
& $adb -s $dev push $apk /sdcard/Download/   # PowerShell: Git Bash rewrites /sdcard/...
& $adb -s $dev shell ls -l /sdcard/Download/<name>.apk   # confirm the size before handing over
```

Seen Oct-29/30-2026 on a Redmi 7A (Android 10): three refusals, retry included. The pushed copy
arrived at the right size (10,918,625 bytes), but the install from it was left to the phone's owner
and **is not confirmed**: no `pm list packages` was captured. It is a manual step, so say so, and
confirm the install afterwards with `pm list packages` rather than taking the user's tap as done.

Debug and release are signed with different keys, so switching between them needs an uninstall,
which takes the app's saved data with it. Detect it and warn rather than failing opaquely.

**Save that data first — the debug build is your only way in.** `run-as` works on the installed
debug build and will not work on the release build replacing it, so this is a one-way door:

```bash
# MSYS_NO_PATHCONV=1 or Git Bash rewrites /data/data/... into C:/Program Files/...,
# and the error ("ls: C:/Program: No such file") blames your command, not the rewrite.
MSYS_NO_PATHCONV=1 adb -s "$dev" shell \
  "run-as com.example.app cat /data/data/com.example.app/files/datastore/prefs.preferences_pb" \
  > prefs-backup.pb
```

Preferences protobuf is mostly plain strings, so grepping printable runs out of the dump is
enough to tell the user what they are about to lose ("7 binned stations, 3 categories"). You
cannot write it back — but a list beats a shrug.

Also: `run-as` only works on a **debuggable** build, so a release APK's private files cannot be
inspected that way (`run-as: package not debuggable`). Don't build verification around it.

## Quoting: `adb shell` re-splits your arguments on the device

`adb shell` joins its arguments with spaces and hands the resulting **string** to the shell on the
phone, which splits it again. Anything that must survive as one word needs quoting for that
*second* parse — quoting it for Python, or for bash on the host, only gets it past the first.

Measured on a Xiaomi 23124RN87I (Android 15) over wireless adb. Passing the format as separate
argv elements:

```bash
adb -s "$dev" shell stat -c "%s %n" "$p1" "$p2"
```

```
stat: '%n': No such file or directory
4732539
124226339
```

`-c` took `%s` alone and `%n` became a **file operand**. The sizes are right and the names are
gone. Quote the whole command for the device's own shell instead:

```bash
adb -s "$dev" shell "stat -c '%s %n' '$p1' '$p2'"
```

```
4732539 /data/app/~~stze1bVoTfmA9t2ZQ6jM1A==/fm.isai.radio-kso2nsofsbMxpK7CCy0ohw==/base.apk
124226339 /data/app/~~cJIkYwQkTegs1uL_i8645g==/com.whatsapp-3IgdhcbFGIIrRfK3LMdJmg==/base.apk
```

The consequence is the point. The calling code mapped sizes back to apps **by path**; with no
paths in the output every lookup missed, so every app reported 0 bytes and the UI displayed
`0.0 MB` for any selection. Nothing errored — the adb call exited 0. It shipped, and came back
from a user as a UI bug, not as a command bug.

Build **one** command string and quote both the format and every path. A helper is worth having,
because a path with a space falls into the same trap:

```python
def shell_quote(text: str) -> str:
    return "'" + text.replace("'", "'\\''") + "'"

command = "stat -c '%s %n' " + " ".join(shell_quote(p) for p in paths)
```

Note the escaping idiom for an embedded single quote — close, escaped quote, reopen: `'\''`.

| Symptom | Cause |
|---|---|
| command exits 0, output is missing a field you asked for | the format string was split by the device shell |
| `stat: '%n': No such file or directory` | same thing — the format's second word became a file operand |
| a path containing a space reports "No such file or directory" | unquoted path split into two operands on the device |

And **never send binary through `adb shell`** — it rewrites LF to CRLF and corrupts images and
archives. Use `adb exec-out` (as with the `adb exec-out screencap -p` below), or have the device
write to `/data/local/tmp` and `adb pull` the file.

## Locating the SDK

Read `local.properties` first — it is what Gradle itself uses, so anything else risks installing
an APK built against a different SDK than the adb doing the install. Un-escape it: Gradle writes
`D\:\\Users\\...`, and `java.util.Properties` reads those escapes back.

Falling back to `%LOCALAPPDATA%\Android\Sdk` is a guess; the SDK is often on another drive. And
when *writing* `local.properties`, prefer forward slashes — a single-backslash path fails at
configuration time with the unhelpful `java.io.IOException: Invalid file path`.

## Verifying: the measurements that lie

This is the part that wastes the most time. Each of these looks like a pass.

### A screenshot of a locked screen is a valid PNG of nothing

`adb exec-out screencap -p` on a lock screen returns solid black — Android blocks capture there.
It is not an error and the file is well-formed. The tell is that repeated captures are
**byte-identical** (e.g. 7904 bytes every time) while a real frame varies.

Check state before trusting a capture:

```bash
adb shell dumpsys window | grep mCurrentFocus     # NotificationShade == locked
adb shell dumpsys power  | grep mWakefulness=     # Asleep == screen off
```

`mFocusedApp` can name your activity while `mCurrentFocus` is the lock screen — the app is
foreground but not visible. Capture needs `mCurrentFocus` to be your window.

### A black screenshot of an unlocked phone: FLAG_SECURE, or an app still starting

An app that sets `WindowManager.LayoutParams.FLAG_SECURE` makes `adb shell screencap` return solid
black even on an unlocked phone, status bar included, and the owner's own gallery screenshots of the
app are black too. Seen Nov-15-2026 in Helm: `MainActivity` sets the flag only while its phone-side
auth build flag is on (`BuildConfig.PHONE_AUTH`). With it on, `screencap -p` pulled a 15 KB
all-black PNG, while `uiautomator dump` still returned the full element tree of the real screen. A
test build with the flag off gave normal screenshots (214 KB). Tell the causes apart:

| Cause | Tell |
|---|---|
| Lock screen | `adb shell dumpsys window \| findstr isKeyguardShowing` says `true` (or `mCurrentFocus` is NotificationShade) |
| FLAG_SECURE | `isKeyguardShowing=false`, `mCurrentFocus` is the app's window, capture still black, and `uiautomator dump` lists the app's text, content-desc and bounds |
| App still starting | one black frame right after a (re)launch; the same capture is fine a few seconds later, so retry after about 5 s before concluding it is secure |

To check the layout of a secure app without pixels, read the dump: `adb shell uiautomator dump
/sdcard/w.xml`, then `adb exec-out cat /sdcard/w.xml` and parse each node's `text`, `content-desc`
and `bounds`. `scripts/adb-ui.ps1` already does the dump and taps by label (`dump`, `tap -Match`),
so reuse it rather than writing another parser. Do not call a screen "verified" from a black shot.

The only sanctioned way to see real pixels is a clearly labelled TEST build with the secure flag
off, installed temporarily, never posted to Telegram and never merged, after which the real build is
reinstalled. That test build overwrites the collected `D:\GitHub\apk\<app>_<ver>.apk` (same name),
so rebuild the real release before posting it, and confirm with `apksigner` that the copy you post
is the real one.

(The `dumpsys window` and `uiautomator dump` commands above were run on the attached phone and work.
The FLAG_SECURE black capture and the test-build comparison were observed in the Helm session, not
re-run here.)
### Frame-timing counters report a perfect score for an invisible app

`dumpsys gfxinfo <pkg>` on a backgrounded or screen-off app returns:

```
Total frames rendered: 0
Janky frames: 0 (0.00%)
```

Zero jank, because zero frames. This is the single most misleading output in the toolkit: it is
exactly what a perfectly optimised app would print, and it means the app was never on screen.

Always gate a measurement on focus **before and after** the run, and treat a run that lost focus
as void rather than as data:

```bash
focused() { adb shell dumpsys window | grep -q "mCurrentFocus.*$PKG"; }
focused || { echo "not focused - void"; exit 1; }
adb shell dumpsys gfxinfo "$PKG" reset
sleep 60
focused || { echo "lost focus - void"; exit 1; }
adb shell dumpsys gfxinfo "$PKG" | grep -E 'Total frames|Janky|90th|99th'
```

Reset the counters **after** warm-up, or the numbers are dominated by process start, JIT and
`ProfileInstaller` rather than steady state. And read the absolute count alongside the
percentage: a run with few frames can show a *worse* percentage while doing strictly less work,
because there are no cheap frames diluting the ratio.

Also note `p99` over a small sample is one or two frames — not a trend.

### `gfxinfo` does not see a WebView's content

For an app whose screen is a WebView (Tauri, Capacitor, Cordova, a hybrid page), `gfxinfo` times
the host's Android frames, not what the page draws into its canvas. Measured Oct-30-2026 on a
Nothing A009P (Android 16, 120 Hz), PhotoAtlas, a Tauri app scrolling a WebGL canvas the user
called "heavy":

```
gfxinfo, the user's own scrolling:          Janky frames: 20 (2.02%)   90th percentile: 12ms
Chrome trace, scripted swipes, same code:   DroppedFrame 79 in 7 s
```

The two readings are not from the same seconds, so this is not an exact disagreement. It shows
that the platform counter had no warning to give about a scroll the person holding the phone
called heavy. For WebView content, measure inside the WebView instead:
see [android-webview-canvas](../android-webview-canvas/SKILL.md) for the temporary debuggable
build and the CDP scripts.

### A before and after pixel check needs a noise floor

A script that sends an input, captures the screen before and after, and reports the share of pixels
that changed will call every input effective on a screen that moves by itself: a video, an
animation, a live clock, a progress bar. Oct-19-2026, on a Fire TV stick playing YouTube, Home read
`46.1% CHANGED` and the app had not left YouTube; five other keys read between 11.5% and 48.9%.
The reference implementation is `firetv-remote/tools/tv_verify.py` in the `fire_webstick` repo.

Capture twice with **no input between them** first, call the difference the noise floor, and count an
input as visible only if it beats twice that. When the floor is high enough that no input can beat it
(above about 50%), the right answer is "cannot tell, read the before and after pair", not a verdict.
Checked on synthetic frames: a still scene gave a 0.0% floor and a busy one 94.9% to 96.1%.

Test the check with a frame you know is busy, and build that frame from blocks larger than the
shrink factor. A first test frame of single random pixels on a 4 pixel lattice (one pixel in sixteen)
averaged away when the frame was shrunk to 480 by 270, scored a 0.0% floor, and so passed a check it
should have failed; solid 4 pixel cells gave 32.2%. A "busy" frame that reads a floor near zero is a
broken test frame, not a quiet scene.

### Keeping the screen on

`svc power stayon true` only applies **while charging**, so it does nothing over wireless adb
where there is no power source. Raise the timeout instead, and restore it afterwards:

```bash
adb shell settings get system screen_off_timeout        # save it
adb shell settings put system screen_off_timeout 1800000
```

Tell the user what you changed on their device, and how to undo it.

### Turning the screen off may not work at all

A "keeps working with the screen off" test needs the screen off, and adb may not be able to do it.
On a Nothing A009P on battery (Oct-18-2026), `input keyevent KEYCODE_SLEEP` and then
`KEYCODE_POWER` both left `mWakefulness=Awake` and `mScreenState=ON` 20 to 30 s later, with
`mStayOn=false` and no charger. Two transfers run in that window passed and proved nothing about
screen-off behaviour.

So read `dumpsys power | grep mWakefulness=` **after** the key event and before the test, and void
the run unless it says `Asleep` (or `Dozing`). If it never does, say the check is not done and hand
it to the user: lock the phone by hand and run the transfer.

### Synthetic input is not a reliable click

`adb shell input tap` is delivered as a short drag; the log shows `moveCount:10`, and Compose may
treat it as a scroll rather than a click. A blind tap that "did nothing" may have missed, hit the
row instead of the button, or scrolled — you cannot tell from the absence of an effect.

If the user is also touching the phone, the input stream mixes and the run is not attributable
(the log shows more `ACTION_DOWN` events than you sent). For anything that must be certain,
prefer a deterministic check over driving the UI: read the artifact, the mapping file, the
persisted file, or ask the user to perform the gesture.

### Gate every tap on the app in front, and tap by label

A test phone is often shared: the user, or another session driving the same device, can bring a
different app to the front between two of your commands. A coordinate tap then lands in **their**
app and changes it. Seen on Oct-17-2026: an agent's tap meant for a Settings tab switched a tab in
another of the user's apps that a second session had opened. On Oct-18 the gate below caught the
same thing mid-run (the other app was in front) and refused, which is the outcome you want.

So, for every UI action, not once per run:

1. Read `dumpsys activity activities | grep topResumedActivity` and act only if the package is
   your app or a system screen it opened (Settings for a special-access page, `documentsui` for a
   folder picker, `permissioncontroller` for a runtime prompt). Otherwise stop, say so, and finish
   the work without the UI (for example revoke a special access with
   `appops set --uid <pkg> MANAGE_EXTERNAL_STORAGE default`).
2. Find the element by its label in `uiautomator dump` and tap the centre of its `bounds`, rather
   than reusing coordinates from an earlier screenshot. Labels survive scrolling and layout
   changes; coordinates do not, and a dump also tells you whether the element exists at all.
3. Treat "no match" as information: a system dialog you meant to answer may already have been
   answered, possibly by someone else. Report that rather than retrying blind.

[`scripts/adb-ui.ps1`](scripts/adb-ui.ps1) does all three (`dump`, `tap -Match`, `shot`), taking
the serial and an allow-list regex for the app under test. Three behaviours worth knowing, each
fixed after a real failure of the first version:

- **The package match is anchored.** `-Allow 'com\.example\.app'` allows exactly that package,
  not `com.example.app.other` or `org.thirdparty.com.example.app`, which the first version let
  through because it searched the whole activity line (found Oct-19-2026 by feeding the gate
  made-up package names). The system screens (settings, document picker, permission dialogs) are
  exact package names built in, so do not add them to `-Allow`.
- **An unreachable phone is named as one.** It checks `adb get-state` first. Before that, a phone
  on a new wireless-debugging port made the foreground query print nothing and the script
  answered "phone asleep or locked?", with `device offline` printed on the line above it.
- **`dump` masks.** Text over 24 characters and any password field is masked in what is printed
  (matching still uses the full text); `-Full` prints it all.

Text read back from a dump is real data: revealing a password on screen and dumping it puts the
password in your output. Keep the masking on, and tell the user if a secret was shown. The first
version of this skill said to mask long text but the script did not; a sentence of advice that the
tool does not implement drifts, so if you change one, change the other.

### Synthetic back cannot exercise predictive back

`adb shell input keyevent KEYCODE_BACK` and `adb shell input swipe` from a screen edge do **not**
go through the predictive-back path — the one that is enabled by default for apps targeting
API 35. So a back-navigation bug that only reproduces under a real finger will pass a synthetic
back test, repeatedly and convincingly.

Observed on a Compose app whose back gesture exited to the launcher instead of going up one
screen: `input swipe 5 900 400 900 100` followed by `dumpsys activity activities` reported the
*correct* `topResumedActivity` afterwards, so the broken build was called verified — twice.
The bug was reproducible only by hand.

The same run also demonstrated the failure mode in the section above, in its purest form. Three
consecutive readings all returned:

```
topResumedActivity=ActivityRecord{… com.miui.home/.launcher.Launcher t4390}
```

The device was sitting on the launcher; the taps had gone nowhere and the app under test was
never on screen. Every reading was internally consistent and the whole run was worthless — and
it was reported as a pass, because nothing in the output says "this is not your app".

So, for any back/up/navigation behaviour:

- **Gate on focus first**, exactly as for `gfxinfo`. A `dumpsys` reading that names a package
  other than yours is void, not data — check the package name in the output, do not just grep for
  the field.
- **Confirm the app is actually installed before drawing conclusions from a quiet log.** An
  `install` that failed (see `INSTALL_FAILED_USER_RESTRICTED` above) leaves the *previous* build
  running; an empty `logcat` filter then reads like "no errors" when it means "your code never
  ran". `adb shell pm list packages | grep <pkg>` after every install.
- **Ask the user to perform the gesture.** For predictive back specifically there is no synthetic
  substitute, so a green automated result here is not evidence and should not be reported as one.

## Publishing the build: collect it, then post it

A release build that only exists in `app/build/outputs/` is one `clean` away from gone, and a
tester cannot install what they cannot reach. Two steps, in this order, after every **release**
build:

1. Copy it to `D:\GitHub\apk\<appname>_<versionName>.apk` (Gradle `rootProject.name`, an
   underscore, then the version: `usbdisplay_0.1.1.apk`; a debug build adds `-debug`),
   overwriting a file of the same name. A rebuild of the same version replaces it; a new version
   adds a file, so delete superseded versions when tidying. Leave the Gradle output in place; this
   is a copy. On this machine `~/.gradle/init.d/collect-release-apk.gradle` does it after every
   `assembleRelease`: look for its `collect-release-apk: ... -> D:\GitHub\apk\...` line instead of
   copying by hand. The build should name its own output the same way; see
   [release-build](../../rules/android-app/release-build.mdc).
   **A probe build overwrites the real one.** The copy is keyed only on name and version, so a
   throwaway release build of the same version (one with WebView debugging switched on to
   profile it, say) silently replaces the clean APK in `D:\GitHub\apk`. Happened on PhotoAtlas
   1.0.2, Oct-30-2026: the debuggable probe sat in the collection under the release name. After
   any probe, delete that file or rebuild clean, and never post a file collected during one.
2. Post it to Telegram with [tgpost](https://github.com/pandiyarajk/tgpost).
   `D:\github\tgpost\scripts\send_webservice_data.py` posts to the **AI work updates** channel (formerly "Sharing")
   (`-1004406329437`) as `@global_tgpost_bot` by default; both the APK and the
   too-large-to-attach notice below go there. Point a project at its own channel with
   `--chat-id`, or `TGPOST_CHAT_ID` in the build environment, rather than editing the script. Pass `--file` as an ABSOLUTE path: the script runs from its own folder, so a relative path fails with "no such file".

```powershell
$meta = Get-Content app\build\outputs\apk\release\output-metadata.json | ConvertFrom-Json
$el   = $meta.elements[0]
$apk  = "app\build\outputs\apk\release\$($el.outputFile)"
$size = (Get-Item $apk).Length
$name = $el.outputFile   # the build names it <appname>_<versionName>.apk, e.g. usbdisplay_0.1.1.apk
Copy-Item $apk "D:\GitHub\apk\$name" -Force   # the init script already does this

$caption = "{0} {1} ({2}) release, {3:N1} MB, {4}" -f `
    $meta.applicationId, $el.versionName, $el.versionCode, ($size / 1MB), (git rev-parse --short HEAD)

if ($size -le 50MB) {
    python D:\github\tgpost\scripts\send_webservice_data.py --file $apk --text $caption
} else {
    python D:\github\tgpost\scripts\send_webservice_data.py --text `
        "$caption - too large to attach (Bot API limit is 50 MB). Collected at D:\GitHub\apk\$name"
}
```

**Branch on the size yourself; do not let the sender decide.** Over the limit, tgpost skips the
file, prints a warning to stderr and exits 1 — correct behaviour for a batch, wrong here, because
the run then posts *nothing* and the channel shows no build at all. The `else` arm above is the
point of the whole section: an announcement naming the version, size and collection path is worth
far more than silence, and a release APK crosses 50 MB routinely. (If you want the file itself,
a [local Bot API server](https://github.com/tdlib/telegram-bot-api) raises the limit to 2000 MB;
point tgpost at it with `--base-url`.)

**Read `applicationId` and the version from `output-metadata.json`, not from the filename or the
Gradle DSL.** AGP writes it next to the APK on every build, so it already accounts for flavours,
`applicationIdSuffix` and whatever the version was resolved to. Parsing the APK's file name tells
you nothing reliable, and re-reading `build.gradle.kts` re-derives a value the build has already
computed — which is how a flavour ends up published under the wrong id.

**Send the Gradle output, not the collected copy.** Telegram preserves the uploaded file name, so
an output named `<appname>_<versionName>.apk` (see release-build) keeps the app and version visible
in the channel. If the build still writes `app-release.apk`, post the named copy instead. Posting the Gradle output also proves it is the build you just made, not whatever
copy happens to be sitting in `D:\GitHub\apk`.

**Scan the DEX for credentials before posting, and treat the scan as the gate.** A Telegram
upload is irreversible: every member can see it and it cannot reliably be unsent. An app that
seeds development credentials through `BuildConfig` ships them *readable in the DEX* whenever
`isMinifyEnabled = false`, which is the default for a release build that has not set up R8. The
APK looks completely ordinary.

```powershell
python - <<'PY'
import json, zipfile, re, sys
out = "app/build/outputs/apk/release/"
p = out + json.load(open(out + "output-metadata.json"))["elements"][0]["outputFile"]
blob = b"".join(zipfile.ZipFile(p).read(n)
                for n in zipfile.ZipFile(p).namelist() if n.endswith(".dex"))
pats = {
    "Atlassian token": rb"ATATT[A-Za-z0-9_\-]{20,}",
    "JWT":             rb"eyJ[A-Za-z0-9_\-]{20,}",
    "GitHub PAT":      rb"gh[pousr]_[A-Za-z0-9]{20,}",
    "AWS key":         rb"AKIA[0-9A-Z]{16}",
    "Slack token":     rb"xox[baprs]-[A-Za-z0-9-]{10,}",
    "Google API key":  rb"AIza[0-9A-Za-z_\-]{35}",
    "private key":     rb"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    "email address":   rb"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}",
}
bad = False
for label, pat in pats.items():
    hits = re.findall(pat, blob, re.I)
    if "email" in label:
        hits = [h for h in hits if not h.lower().endswith((b".png", b".xml", b".dtd"))]
    if hits:
        bad = True
        print(f"  {label}: {len(hits)} found, prefix {hits[0][:10]!r}")
print("NOT SAFE - do not publish" if bad else "clean")
sys.exit(1 if bad else 0)
PY
```

Print **prefixes only**, never a whole match: the output goes into a terminal, a transcript and
possibly a chat log, and a secret pasted there is disclosed just as surely as one uploaded.

To produce a clean build, empty the seed sources and rebuild with `--rerun-tasks` (a cached
`BuildConfig` is the trap), then **re-scan the artefact rather than trusting the procedure**.

**Emptying the properties file is not enough on its own.** A seed helper that falls back to an
environment variable — `localProps.getProperty(k).ifEmpty { System.getenv(env) }`, a common and
sensible pattern so a token already exported for `gh` need not be duplicated — will happily bake
the ambient value in after `local.properties` has been stripped. Measured on devdash-android: the
Jira, Tempo and Zephyr tokens went away and a GitHub PAT stayed, because `GITHUB_TOKEN` was
exported in the shell. Blank every fallback variable in the build shell too, and let the re-scan,
not the checklist, decide whether it worked.

Restore the credentials immediately after the build, and verify the restore (`(Get-Content
local.properties).Count`). A half-restored `local.properties` is a confusing failure an hour
later, and a backup copy left lying beside it is a second copy of the secrets.

**Publish only signed release builds.** An unsigned release APK installs nowhere
(see [release-build](../../rules/android-app/release-build.mdc)), and a debug APK handed to a
tester collides with the release key later. Both look fine as attachments. Verify before posting:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\apksigner.bat" verify --print-certs $apk
```

**Use `apksigner`, not a `META-INF` listing, to decide whether an APK is signed.** Checking for
`META-INF/*.RSA` or `*.SF` answers a v1 (JAR signing) question, and a modern AGP release is signed
with **v2 only** — the signature lives in a block before the central directory, and `META-INF`
holds nothing. A correctly signed, installable APK therefore reads as unsigned to that test, which
sends you rebuilding something that was never broken.

**The token is read from the environment at process start.** `TGPOST_BOT_TOKEN` must be set in the
shell running the build; a terminal (or VS Code) opened before the variable was set or rotated
keeps serving the old value and the post fails with `401 Unauthorized` while the build itself looks
clean. Refresh in place with
`$env:TGPOST_BOT_TOKEN = [Environment]::GetEnvironmentVariable('TGPOST_BOT_TOKEN','User')`.

The bot must be an **administrator with "post messages"** in the channel; a bot that is not a
member gets `Bad Request: chat not found`, which reads like a wrong chat id.

## Cleaning up the channel: the bot cannot delete what it posted last week

Posting an APK per build fills the channel, and sooner or later someone asks for the old ones to
go. **The bot cannot do it.** Two Bot API limits, neither of which admin rights lift:

1. **A bot cannot delete a channel post older than 48 hours, even as an administrator with
   `can_delete_messages`.** The `deleteMessage` docs read as if that right allows "any message"
   in a channel; it does not override the age limit. Measured Oct-17-2026 on the AI work updates
   channel: `getChatMember` returned `administrator`, `can_delete_messages: true`, and every one of
   49 APK posts dated Sep-22 to Oct-14 came back `Bad Request: message can't be deleted`, while the
   18 posted inside the last 48 hours deleted cleanly.
2. **A bot cannot read channel history**, so it cannot even list what it posted. `getUpdates`
   holds 24 hours of `channel_post` at most, and tgpost does not log message ids. The only way a
   bot sees an old post is to edit it: `editMessageReplyMarkup` returns the full message,
   document included. Probing every id that way works, but it is slow (two edits per post plus
   rate-limit waits) and briefly shows a button on each post.

**Delete as your own account instead.** A user session (MTProto, through Telethon) reads the
history directly and has no age limit: the same 49 posts went in one call, 49 of 49. Both
approaches are in [`D:\GitHub\tools\tg-purge-files`](file:///D:/GitHub/tools/tg-purge-files/README.md):

```powershell
python D:\GitHub\tools\tg-purge-files\tg_user_purge.py            # list APK posts with dates
python D:\GitHub\tools\tg-purge-files\tg_user_purge.py --delete   # delete them; --ext for other types
```

It needs `TG_API_ID` / `TG_API_HASH` from my.telegram.org (an app per account, title must not
contain "Telegram") and a one-time phone-and-code login in a real terminal (`--login-only`; it
cannot run in a tool shell, which has no TTY). The saved `.session` file is a full login to the
account: keep it out of every repo and never paste it. List first, delete second: a channel
deletion cannot be undone.

## Reporting honestly

Say which measurements were void and why. "0 frames rendered, but the phone was locked, so this
proves nothing" is worth more than a clean-looking table, and a percentage quoted from an
unfocused run will be believed and then acted on.

Note the variant too: a **debug** build's Compose performance is materially worse than release,
so debug numbers are a floor, not a verdict.
