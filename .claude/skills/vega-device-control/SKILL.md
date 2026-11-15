---
name: vega-device-control
description: 'Drive and observe a Vega OS Fire TV stick from a phone or PC: the remote service on port 8080 (woken by DIAL on 8009), the developer-mode ADB shell on 5555, which door can press which key, taking a TV screenshot, and finding the stick when its IP moves. Use when building or debugging a Fire TV remote, when the official Fire TV app does not work with a Vega stick, or when a remote command returns success but the TV does nothing.'
---

<!-- Author: Pandiyaraj Karuppasamy | Date: Oct-19-2026 -->

# vega-device-control

How to control a Vega OS stick and, harder, how to know the TV did what you asked. Everything
here was measured on one stick (product "callie", Vega OS 1.2) on Oct-19-2026. Where a claim is
a guess it says so. The full write-up with the raw outputs is chapter 10 and 11 of the
`tv_garden_vega` course and `VEGA_REMOTE_PROTOCOL.md` in `fire_webstick`.

Two facts decide everything else:

- **Vega is not Android.** `adb connect` works, then `getprop` is `command not found`. Code that
  detects "Android" by `getprop` is how you tell the two apart, and `input keyevent` does not exist.
- **Success output is not an effect.** An HTTP 200 and an exit code 0 both occurred for actions the
  TV ignored. Look at the TV.

## Two doors

| | Door A: remote service | Door B: developer-mode shell |
|---|---|---|
| Port | HTTPS 8080 | ADB over TCP 5555 |
| Needs developer mode | No | Yes |
| Setup | 4-digit PIN shown on the TV | none reported |
| Credential | `x-api-key: 0987654321` (fixed, public) plus a per-pairing `x-client-token` | the ADB key |

### Door A

- **It sleeps.** When idle, 8080 refuses connections. `POST http://<ip>:8009/apps/FireTVRemote`
  (DIAL, answers 201) wakes it in about a second. Do that before concluding the stick is down.
- Pair: `POST /v1/FireTV/pin/display {"friendlyName":".."}`, then `POST /v1/FireTV/pin/verify
  {"pin":".."}`; the response body is the token. Treat the token as a full remote credential.
- The certificate is issued by an Amazon lab CA, so no public chain validates it. Trust on first
  use: pin the leaf certificate's SHA-256 at pairing and refuse any other afterwards.
- Actions: `POST /v1/FireTV?action=` `dpad_up|dpad_down|dpad_left|dpad_right|select|back|home|menu|
  volume_up|volume_down|mute|power`, `POST /v1/media?action=play|pause|scan`, `POST /v1/FireTV/text`,
  `POST /v1/FireTV/app/<appId>`, `GET /v1/FireTV/apps` (capped at 12, no parameter raises it).
- An unknown action returns 400, so a 200 means the name was recognised. It does not mean it worked.

### Door B

```
adb connect <ip>:5555
adb -s <ip>:5555 shell "inputd-cli button_press KEY_RIGHT"
```

`inputd-cli` exits 0 even for a bad key name. Success is the text `Injecting Button Press` in the
output, not the exit status. Key names: `KEY_UP DOWN LEFT RIGHT ENTER BACK HOME MENU PLAYPAUSE
REWIND FASTFORWARD VOLUMEUP VOLUMEDOWN MUTE POWER`.

## Which door presses which key

| Key | Door A | Door B |
|---|---|---|
| D-pad, OK, Back | worked | accepted, never rigorously measured |
| Home | worked | accepted, no effect (the app on screen stayed) |
| Volume | worked, confirmed by ear | no effect |
| Mute | 200, no effect on this TV | accepted, no effect on this TV |
| Power | turned the TV off | no effect |

Rule used by the app: Home, volume, mute and Power go through door A; D-pad keys use whichever
door is available. Mute failed on both. The device log shows the injected mute and the physical
remote's mute reach the same audio-system volume-key client, so the key was not lost on the way in.
The owner later confirmed the **physical remote's mute does work** on this TV, so the TV can mute
and the failure is specific to injected mute, on both doors. Cause unknown. The earlier guess that a
TV ignores a CEC mute is therefore not supported; do not repeat it. Untested: whether the physical
remote sends a different key code or path than the injected `KEY_MUTE` and `action=mute`.

## Seeing the TV

Verify a control action by capturing the screen before and after.

```
adb -s <ip>:5555 shell "gwsi-tool-screenshooter /tmp/x.png"
adb -s <ip>:5555 pull /tmp/x.png
```

- `screenshooter` fails with Permission denied and `gwsi-tool-screenshooter2` is not the one;
  `gwsi-tool-screenshooter` works.
- **Volume never shows in a stick screenshot.** It is applied to the TV over CEC after the picture
  is composed. Confirm volume by ear or by asking.
- If someone else holds the physical remote, the picture changes without you. Measure a noise floor
  first (two captures with no key pressed) and require your change to beat it, or judge by eye. See
  the pixel-check section of [android-deploy](../android-deploy/SKILL.md).
- Logs: `loggingctl`. Packages: `vpm`. Launching an app from the shell: `vmsgr`. Sideloaded apps
  (absent from the 12-entry app list) were reported launching from the phone app, confirmed by the
  owner; which command path the app used for them was not re-checked here.

## Finding the stick when it moves

The stick changed from one /24 to another when the PC changed network, so a saved address is a
hint, not a fact.

1. Try the saved address. DIAL, 8080 or 5555 answering counts; 8080 alone is not enough while asleep.
2. Else scan the /24 for a host whose DIAL lists `FireTVRemote`. A printer or router with an open
   8080 does not.
3. Read the name and model from `http://<ip>:60000/dd.xml`.
4. If the pinned certificate fingerprint matches, it is the same TV at a new address: keep the pairing.

Step 4 was written and never exercised on a real move.

## Traps

- Reading a 200 or a zero exit as "the TV did it". Check the TV.
- Sending Home, volume or Power over door B and concluding the key does not exist.
- Giving up on port 8080 refusing connections without waking it through DIAL first.
- Building the Android app with the JDK 25 that Android Studio bundles. See the JDK section of
  `rules/android-app/ci-cost.mdc`.
- Pressing keys while someone is watching. It is their TV.

## Provenance

Extracted from `fire_webstick` (Oct-19-2026). One stick, one firmware. Re-check the key matrix on
another model before relying on it.
