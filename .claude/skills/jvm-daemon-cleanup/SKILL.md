---
name: jvm-daemon-cleanup
description: Reclaim RAM from idle Gradle and Kotlin compile daemons without killing a live build, an IDE language server, or an adb session, by classifying every java.exe from its command line before terminating anything. Use when asked to stop running builds or release Java processes, when a machine is short on memory and java.exe is holding gigabytes, when a Gradle build hangs or a daemon is suspected stale, or before switching away from Android work on a constrained machine.
---

# jvm-daemon-cleanup

Stop idle JVM build daemons and report what was freed, having first proved
which processes are idle. Optimised for the case that actually bites: a
`java.exe` list where the biggest consumers are stale and one entry is a
colleague to your own work, or a build in a repo you were not thinking about.

## When to use

- The user asks to stop running builds, kill Gradle, or release Java processes.
- Memory pressure, and `java.exe` is at the top of the process list.
- A Gradle build hangs, or a daemon is suspected of holding a stale lock or an
  outdated toolchain.
- Finishing Android work on a machine where 12 GB of idle daemon matters.

## Why this exists, the failure it was distilled from

A request to "stop any running build and release java process" on a machine
with six `java.exe` processes holding about 12.6 GB. `Get-Process` gives Id,
name, path and working set, and every one of the six looked identical: the
same JDK, the same executable. On that evidence the obvious action is
`Stop-Process -Name java -Force`.

That would have been wrong. Reading the command lines showed the six were
**four different kinds of thing**:

| PID | Working set | What it actually was |
|---|---|---|
| 2640, 8572 | ~8.0 GB | Gradle daemons, idle |
| 8304, 21236 | ~4.3 GB | Kotlin compile daemons (2.0.21 and 2.1.0), idle |
| 26384 | 122 MB | **A live `assemblePilotRelease` in a different repo** |
| 6872 | 173 MB | **VS Code's Gradle extension language server** |

The two that mattered were the two smallest. A kill-all would have aborted an
unrelated release build the user had not mentioned and was not thinking about,
and broken the IDE's Gradle integration until a window reload, while the user's
actual goal, reclaiming memory, was served entirely by the four large ones.

**The lesson: `java.exe` is not a process kind, it is a runtime.** Path,
name and memory tell you nothing about whether termination is safe. Only the
command line distinguishes a daemon parked between builds from a build that is
running right now. Classify before you kill, and say what you are leaving
alone and why.

A second trap follows from the first. Two Kotlin daemons for two different
compiler versions is **normal**, not duplication to clean up: each Kotlin
version gets its own daemon, and repos pinned to different versions each keep
one alive. Do not treat the pair as evidence something is wrong.

## Procedure

### 1. Inventory, with memory

```powershell
Get-Process -Name java,javaw,gradle,adb,kotlin* -ErrorAction SilentlyContinue |
  Select-Object Id,ProcessName,StartTime,
    @{N='WS_MB';E={[math]::Round($_.WorkingSet64/1MB,1)}},Path |
  Format-Table -AutoSize
```

`StartTime` is worth having: a daemon from days ago is a stronger stale
candidate than one started ten minutes ago. Note that this cmdlet exits
non-zero when any requested name has no match, which is not a failure.

### 2. Classify every one from its command line

This is the step the whole skill exists for. Never skip it, even when the list
is short.

```powershell
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  $cl = $_.CommandLine
  $tag = 'OTHER'
  if     ($cl -match 'GradleDaemon')                    { $tag = 'GRADLE-DAEMON' }
  elseif ($cl -match 'KotlinCompileDaemon')             { $tag = 'KOTLIN-DAEMON' }
  elseif ($cl -match 'GradleWorkerMain')                { $tag = 'GRADLE-WORKER' }
  elseif ($cl -match 'GradleWrapperMain|gradle-wrapper'){ $tag = 'GRADLE-LAUNCHER' }
  "{0}`t{1}`t{2}" -f $_.ProcessId, $tag, $cl.Substring(0, [Math]::Min(220, $cl.Length))
}
```

Then read every `OTHER` in full, because that bucket is where the dangerous
ones hide. Both of the must-not-kill processes above landed in it.

```powershell
Get-CimInstance Win32_Process -Filter "ProcessId=<pid>" |
  ForEach-Object { $_.CommandLine }
```

What the tags mean for safety:

| Tag | Safe to kill | Why |
|---|---|---|
| `GRADLE-DAEMON` | Yes, when idle | Parked between builds; a new build starts a fresh one |
| `KOTLIN-DAEMON` | Yes, when idle | Same. One per Kotlin version is normal |
| `GRADLE-WORKER` | **No** | A worker exists only during a build, so its presence means a build is running |
| `GRADLE-LAUNCHER` | **No** | The `gradlew` wrapper. Read its arguments: they name the repo and the task |
| `OTHER` | **Read it** | IDE language servers, Kotlin LSP, app servers, anything else |

A `GRADLE-LAUNCHER` command line carries the repo path and task
(`-jar "D:\GitHub\<repo>\gradle\wrapper\gradle-wrapper.jar" assemblePilotRelease`).
That is how you learn a build is running in a repo the user never mentioned.

### 3. Prefer the graceful stop for daemons you own

When the goal is a clean slate rather than raw memory, ask Gradle to do it:

```powershell
.\gradlew.bat --stop
```

**`--stop` is scoped by Gradle version, not by project.** It stops every daemon of
that wrapper's Gradle version, whichever repo started it, and `--status` says so:
`Only Daemons for the current Gradle version are displayed.` On this machine
23 repos had wrappers on `gradle-8.13` (Oct-30-2026), so `--stop` from one of them
can end a build another session is running. Treat it as step 4 below (confirm
scope first), not as the safe option.

**To get past a wedged daemon for one build, use `--no-daemon` instead.** It forks a
single-use daemon for that build and touches no other. Seen Oct-30-2026 in
`flash-sms`: `mergeDexRelease` failed twice with `classes.dex: The process cannot
access the file because it is being used by another process`, the user refused
`--stop`, and `gradlew --no-daemon testDebugUnitTest assembleRelease` built clean,
then kept doing so on every later build. It costs a JVM start per build (about
1.5 to 2 minutes there), which is cheap beside stopping someone else's release.

Go to `Stop-Process` when Gradle itself is wedged, when daemons from several
projects must go, or when the wrapper would start a JVM you are trying to avoid.

### 4. Confirm scope before terminating anything you did not start

If the classification turned up a live build, an IDE server, or anything in
`OTHER` you cannot identify, **ask**. Aborting an unrelated release build is
not recoverable by the person who asked you to free memory, and they have no
reason to expect it from the request they made.

Kill by explicit PID, never by name, so the set cannot widen between the
classification and the action:

```powershell
$targets = 2640,8572,8304,21236
foreach ($id in $targets) {
  $p = Get-Process -Id $id -ErrorAction SilentlyContinue
  if ($null -eq $p) { "PID $id already gone" }
  else {
    try   { Stop-Process -Id $id -Force -ErrorAction Stop; "PID $id stopped" }
    catch { "PID $id FAILED: $($_.Exception.Message)" }
  }
}
```

`Stop-Process -Name java` is the wrong tool here for the same reason
`tasklist` ANDing its filters is wrong in `temp-cleanup`: it answers a question
you did not ask.

### 5. Leave adb alone unless asked

`adb` is not a build process. Killing the server drops every device
connection, and on a wireless setup reconnecting means finding the current
pairing port again, which costs more than the 22 MB it frees. Stop it only on
an explicit request or when adb itself is misbehaving. See
`android-deploy` for what reconnecting actually involves.

### 6. Report the reclaim, and the survivors

```powershell
Start-Sleep -Seconds 2
Get-Process -Name java,javaw -ErrorAction SilentlyContinue |
  Select-Object Id,@{N='WS_MB';E={[math]::Round($_.WorkingSet64/1MB,1)}} |
  Format-Table -AutoSize
```

State the memory freed, and list what you deliberately left running with the
reason. "Killed the Java processes" without that list hides the decision that
mattered.

## Reporting honestly when a killed daemon had a client

If you stop a daemon while another process was talking to it, that client's
outcome is now **uncertain**, and saying so is the whole job.

In the session this came from, the live `assemblePilotRelease` launcher was
left alone deliberately, yet it was gone from the next process listing. Its
APK timestamp (08:09) sat well before the kill (08:27), which is the shape of a
build that had already finished and was winding down. That is inference, not
proof, and it was reported as inference.

Do the same. Check for output artifacts and their timestamps, state what the
evidence supports, and name the part you cannot confirm. A Gradle build is
cheap to rerun; a false "it completed fine" is not cheap to discover later.

## Prevention

1. **Cap daemon memory.** A Gradle daemon grows to its `-Xmx` and keeps it.
   Setting `org.gradle.jvmargs=-Xmx2g` in `gradle.properties` bounds the
   footprint instead of leaving 4 GB parked per project.
2. **Expect one daemon per project configuration.** Different JDKs, different
   `jvmargs` and different Kotlin versions each get their own. Several is
   normal; several from projects you have not touched in days is the cleanup.
3. **Let the timeout work.** Gradle retires idle daemons after about three
   hours (`org.gradle.daemon.idletimeout`). Shorten it on a constrained machine
   rather than killing by hand every session.
4. **`--stop` when switching projects,** especially after changing a wrapper
   or toolchain version, so a stale daemon cannot serve the next build.

## Related

- `android-deploy`, what an adb reconnect costs, and why step 5
  leaves the server running.
- `temp-cleanup`, the same measure-classify-report discipline applied to disk
  rather than memory.
- `rules/core/powershell.mdc`, PowerShell 5.1 constraints that apply to every
  snippet here (no `&&`, no ternary, no null-coalescing).
