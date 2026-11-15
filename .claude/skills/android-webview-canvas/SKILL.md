---
name: android-webview-canvas
description: 'A canvas or WebGL app inside an Android WebView (Tauri, Capacitor, Cordova, a hybrid screen) that scrolls "heavy" or stutters: why gfxinfo cannot see it, how to measure inside the WebView with a temporary debuggable build and the DevTools protocol (frame log, CPU profile, trace of frames begun vs drawn), why a script-panned canvas never feels native and the transparent native-scroller fix, the full-page GPU re-upload a texture atlas costs per new tile, and the Tauri Android build that dies symlinking into jniLibs on Windows. Use when a WebView canvas scrolls or flings badly on a phone, when profiling any WebView page on a device, when building a pan or scroll camera for a canvas, or when `tauri android build` fails with "Creation symbolic link is not allowed".'
---

# android-webview-canvas

Learned on PhotoAtlas (`D:\GitHub\photo-atlas`, Tauri 2 + SvelteKit + PixiJS 8), Oct-30-2026, on
a Nothing A009P (Android 16, Snapdragon SM6650, 120 Hz, WebView 156). The user's whole report was
"scroll holds the page" and then "still scroll looks heavy". The first fix aimed at stutter and
missed. The second came from measuring inside the WebView rather than guessing.

## 1. `gfxinfo` measures the wrong thing

`dumpsys gfxinfo <pkg>` times the host app's Android frames. A WebView draws through Chrome's own
compositor, so the Android frame can be on time while the content inside it is late.

```
gfxinfo, the user's own scrolling of 1.0.2:      Janky frames: 20 (2.02%)   90th percentile: 12ms
Chrome trace, scripted swipes, same code, 7 s:   DroppedFrame 79
```

The two readings are **not** from the same seconds. What they show is that `gfxinfo` gave no
warning for a scroll the user called heavy, not an exact count of how far it was off. Use
`gfxinfo` for native screens only. For WebView content, measure inside it (section 2).

**Count drops directly.** The same trace had 845 `BeginFrame` and 544 `Display::DrawAndSwap`. That
gap is **not** 301 lost frames: a frame with nothing new to show is begun and not drawn, and the 7 s
included pauses between swipes. The loss is the `DroppedFrame` count.

## 2. Measuring inside the WebView

A release WebView exposes no DevTools socket, so you need a **temporary** build:

```kotlin
// MainActivity.onCreate, after super.onCreate. TEMP perf probe, never commit or ship.
android.webkit.WebView.setWebContentsDebuggingEnabled(true)
```

Then, with the app in front:

```powershell
$pid = (adb shell pidof com.example.app).Trim()
adb shell "cat /proc/net/unix | grep -a devtools_remote"     # webview_devtools_remote_<pid>
adb forward tcp:9333 localabstract:webview_devtools_remote_$pid
(Invoke-WebRequest http://127.0.0.1:9333/json -UseBasicParsing).Content   # webSocketDebuggerUrl
```

Run a script while swiping (`adb shell input swipe 540 1800 540 700 120` for a flick,
`... 600` for a slow drag). Gate on the app being in front before and after, as
[android-deploy](../android-deploy/SKILL.md) says.

- [`scripts/cdp_probe.mjs`](scripts/cdp_probe.mjs) logs rAF intervals and long tasks, and
  records a sampled CPU profile with the top self-time functions.
- [`scripts/cdp_trace.mjs`](scripts/cdp_trace.mjs) records a Chrome trace and counts frames begun,
  drawn and dropped.

Read the two together:

| probe says | trace says | meaning |
|---|---|---|
| rAF p99 > budget, long tasks | drops | your JavaScript is too slow: read the CPU profile |
| rAF steady at refresh rate, profile mostly `(idle)` | drops anyway, every thread lightly loaded | not a CPU problem: the frame pipeline or the interaction model (section 3) |

PhotoAtlas gave the second row:

- **Probe:** 1,014 frames at p50 8.3 ms, 2 over 17 ms, 87% `(idle)`.
- **Trace:** the busiest thread was the in-process GPU thread, at about 17% busy.

Optimising the scripts further would have changed nothing.

**After profiling:** remove the switch, rebuild, and reinstall. The collect hook copies the probe
build into `D:\GitHub\apk` under the release name; see android-deploy.

## 3. A canvas panned by script never feels native: put a native scroller over it

The usual camera works like this:

- `touch-action: none` on the canvas;
- `pointermove` moves `scrollY`;
- a hand-written fling runs after the finger lifts.

That keeps Android's scroll machinery out entirely. Even with good physics it feels heavy:

- the finger is followed by script, frames behind;
- the fling curve is not Android's;
- there is no overscroll.

Tuning the fling (velocity tracker, 325 ms decay, catch-to-stop) was the first PhotoAtlas fix. It
measured fine in a browser and still felt heavy on the phone.

The fix that hands it back to the platform: a **transparent scroller over the canvas**. The
browser pans and flings that layer itself, and the canvas draws at its offset.

```ts
const scroller = document.createElement('div');
Object.assign(scroller.style, { position: 'absolute', inset: '0', overflowY: 'scroll',
  overscrollBehavior: 'contain', scrollbarWidth: 'none', touchAction: 'pan-y' });
const spacer = document.createElement('div');            // the content size, nothing painted
spacer.style.height = `${contentHeight * zoom}px`;
scroller.appendChild(spacer);
host.append(canvas, scroller);                            // scroller on top, receives input
scroller.addEventListener('scroll', () => { camera.y = scroller.scrollTop; needsDraw = true; },
  { passive: true });
```

The parts that are easy to get wrong:

- **Do not `setPointerCapture` a touch on the scroller.** Capture keeps the browser from taking
  the pan. Capture mouse pointers only, so a desktop mouse drag can still pan by script.
- **Expect `pointercancel`** when the browser takes the gesture over. Cancel long-press timers and
  pending taps there.
- **Writing `scrollTop` stops a running fling.** Write it only when the camera moved by itself
  (zoom, reveal, layout change), and only when it differs by more than about a pixel. Never write
  it back from inside the scroll handler.
- **A touch that stops a moving list is not a tap.** Remember the time of the last `scroll` event
  and ignore a tap that starts within about 120 ms of it. Otherwise stopping the list opens
  whatever photo was under the finger.
- **Size the spacer only when it changes.** Each style write costs a layout.
- **Keep a plain wheel native** on desktop: return before `preventDefault` unless Ctrl is held for
  zoom.
- **Views with no scroll axis** (a map, a wheel) keep `touch-action: none` and the scripted
  pan, pinch and fling.

**Trade-off:** with `touch-action: pan-y`, a two-finger pinch on a scrolling view can be taken by
the native pan. If pinch-zoom matters there, test it on a device.

**Status:**

- Shipped in PhotoAtlas 1.0.3 (commit `4afb9b6`).
- Verified in headless Chrome: the scroller is `pan-y` over an 11,685 px spacer, and the canvas
  follows a `scrollTop` change.
- **Not yet re-traced on the phone.** If you take that trace, add the numbers here.

## 4. A texture atlas re-uploads the whole page for every tile

PixiJS `CanvasSource.update()` (and any `texImage2D` of the backing canvas) uploads the entire
atlas page, not the cell that changed. PhotoAtlas drew 256 px tiles into 2048 x 2048 pages with
mipmaps. Its frame loop called `atlas.flush()` every frame, so each arriving tile meant:

- a 16 MB upload and a mipmap rebuild;
- repeated on every frame while thumbnails streamed in, which they do while you scroll.

Two fixes, both in `src/lib/stage/Stage.ts`:

- **Batch the uploads:** at most every 120 ms, or every 400 ms while the camera is moving.
- **Switch sprites late:** move a sprite to its atlas cell when its page is uploaded, not when
  the tile was drawn. Otherwise it shows a cell the GPU has not received.

The same commit also removed an O(n) retint of every sprite per arriving tile. With 1,520
photos that was about 2.3 million tint writes per full load.

## 5. Tauri Android on Windows: the jniLibs symlink

Without Windows Developer Mode, `npx tauri android build` compiles the Rust library and then
fails with:

```
Failed to create a symbolic link from "...\libapp_lib.so" to file "...\jniLibs\arm64-v8a\libapp_lib.so"
Creation symbolic link is not allowed for this system.
```

The `.so` is already built at that point. Copy it into `app/src/main/jniLibs/<abi>/`, copy
`tauri.conf.json` into the assets the build expects, then run Gradle with the Rust task skipped:

```powershell
.\gradlew assembleUniversalRelease -PabiList=arm64-v8a -ParchList=arm64 -PtargetList=aarch64 -x rustBuildArm64Release
```

- **Reference script:** `D:\GitHub\photo-atlas\tools\build_apk.ps1`.
- **Collecting:** the machine's collect hook matches only a task named exactly `assembleRelease`,
  so a `assembleUniversalRelease` build must collect itself. That script does.
- **Disk space:** set `CARGO_TARGET_DIR` on a drive with room. A Tauri Android target folder
  filled D: to 0 bytes once.
