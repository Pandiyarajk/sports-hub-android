---
name: android-radio-survey
description: Recording what an Android phone hears over Wi-Fi, Bluetooth (BLE and Classic) and cellular, with a location and time on each, without the traps that produce plausible wrong data - cached Wi-Fi scan results placed where you are now instead of where they were heard, a location request with a minimum distance that stops delivering fixes when you stand still, Classic discovery reporting BLE adverts as public so random-address phones are counted as dual-mode devices, unfiltered BLE scans that pause with the screen off or quietly degrade after 30 minutes, neighbour cells with no identity, an accuracy gate that stores nothing indoors, and 6 GHz assumed rather than asked. Use when building a Wi-Fi or Bluetooth scanner, a wardriving or survey app, a BLE beacon or tracker detector, a cell logger, or anything that joins scan results to GPS fixes.
---

# android-radio-survey

Learned building Signal Atlas (`D:\GitHub\signal-atlas-android`), a personal Wi-Fi, BLE, Classic
and cell survey app, on a Nothing A009P (Android 16). Every item below produced data that looked
right: plausible counts, plausible positions, no error. Items marked **measured** were seen on the
phone; the rest were caught in review before they shipped, and say so.

Permissions for all of this are their own topic: see the
[android-permissions](../android-permissions/SKILL.md) skill. Wi-Fi scan results need
`ACCESS_FINE_LOCATION` on every API level (`NEARBY_WIFI_DEVICES` alone is refused on the A009P),
and Location Services off returns an empty list rather than an error.

## Join every observation by its own timestamp, on the boot clock

`WifiManager.getScanResults()` returns the **cached** list. It can hold access points heard tens
of seconds ago, somewhere else, from an earlier scan or a scan another app triggered. A scan whose
`startScan()` was throttled still fires `SCAN_RESULTS_AVAILABLE` with the old list
(`EXTRA_RESULTS_UPDATED` is false).

Joining "when the list arrived" to "the latest fix" puts those old APs where you are now. Instead:

- Each `ScanResult.timestamp` is **microseconds since boot**; BLE `ScanResult.getTimestampNanos()`
  and `Location.getElapsedRealtimeNanos()` are nanoseconds since boot; `CellInfo.getTimeStamp()`
  too. Keep a ring buffer of fixes keyed by that clock and **interpolate** between the fixes either
  side of each observation. Wall-clock time can jump; this clock cannot.
- Drop results older than about 10 s by their own timestamp, and drop observations with no fix
  within 10 s. A dropped observation is better than a misplaced one.

```kotlin
val nowMicros = SystemClock.elapsedRealtimeNanos() / 1_000
val fresh = results.filter { nowMicros - it.timestamp <= 10_000_000 }
val at = fixBuffer.locate(result.timestamp * 1_000)   // ns, interpolated, or null
```

## A location request must not have a minimum distance

*Caught in review.* With `setMinUpdateDistanceMeters` (or `requestLocationUpdates(..., 5f, ...)`),
standing still delivers no fixes. The last fix ages past the join window and **every observation
at the places you stop is dropped**, which are exactly the places that matter. Request by time
only (`LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, 2000)`, no distance), and thin the stored
points yourself (one per 5 s or 10 m) if storage matters.

## Classic discovery reports BLE adverts, and the stack calls them public

**Measured twice on the A009P (Android 16).** `BluetoothAdapter.startDiscovery()` also runs an LE
phase, and `ACTION_FOUND` delivers BLE devices with `getType()` UNKNOWN or DUAL and the
uncategorised class `0x1F00`. Merged with the BLE scan they become "BLE+Classic": 13 of 14 in a
first survey, and 25 of 37 in a second.

The first fix was wrong. It dropped results that `BluetoothDevice.getAddressType()` (API 35+) called
random. **The stack reported `ADDRESS_TYPE_PUBLIC` for all 37**, including Xiaomi devices at
`75:5B:D0:...` and `4D:A6:F2:...` (top bits `01`, a resolvable private address). Discovery does not
know the address type of an LE device it only overheard, and answers "public".

What does separate them, checked against the 37 exported devices:

- A **public** Bluetooth address is IEEE-assigned, so its **OUI is registered**. A random address
  almost never matches a registered OUI (roughly 1 in 100 by chance).
- A real Classic device sets a **class of device** (audio `0x404`, phone `0x204`/`0x20C`, watch
  `0x704`, TV `0x43C`). A BLE advert has none: `0x1F00`.

So: **a discovery result with the uncategorised class and no registered OUI is a BLE advert**,
whatever the stack says. Result on the real data: 12 of 37 kept (TVs, watches, a phone, a headset,
set-top boxes), 25 reclassified as BLE, all 7 true Classic phones and earbuds kept. Believe a
`DEVICE_TYPE_CLASSIC` stack type even without a class; before the OUI table has loaded, fall back to
whether the stack reported an address type at all.

Repair rows stored under the old rule when the app starts (after the table loads, idempotent):
set the type back to BLE and re-infer the address type from the top two bits, since the stored
"public" is the stack's wrong answer.

Name, class and RSSI come from the `ACTION_FOUND` intent extras, so `BLUETOOTH_CONNECT` is only
needed for `getType()`.

## BLE scanning degrades quietly

*Platform behaviour, from review; the screen-off pause is in AOSP's `ScanManager`.*

- A scan with **no filters** (or only empty `ScanFilter`s) is **paused while the screen is off**.
  Filters would narrow what is heard, so for a survey keep the screen on during a session and say
  "BLE paused" when it is off.
- A scan running **over 30 minutes is demoted to opportunistic**. Restart it every ~25 minutes.
- More than **5 starts in 30 seconds** is refused (`SCAN_FAILED_SCANNING_TOO_FREQUENTLY`) or, on
  some OEMs, returns nothing silently. Count starts; never restart from `onScanFailed` in a loop.
- Bluetooth 5 extended adverts are only heard with `setLegacy(false)` and
  `setPhy(PHY_LE_ALL_SUPPORTED)`, which need `isLeExtendedAdvertisingSupported`.
- Take BLE names from `ScanRecord.deviceName`: `BluetoothDevice.getName()` needs
  `BLUETOOTH_CONNECT` on API 31+.
- Most phones, earbuds and watches advertise a resolvable private address that changes about
  every 15 minutes: one device becomes many rows. Classify the address (top two bits of a random
  address: 11 static, 01 resolvable, 00 non-resolvable; a registered OUI means public) and hide
  rotating ones by default rather than counting them as distinct devices.

## Wi-Fi scan throttling is the main limit on density

Android allows **4 `startScan()` calls per 2 minutes** for a foreground app (a foreground service
counts). On a personal device the user can lift it: *Developer options > Wi-Fi scan throttling*.
Detect it from `startScan()` returning false and tell the user, rather than reading settings keys.
Always consume the passive `SCAN_RESULTS_AVAILABLE` broadcasts as well; they are free.

## Cells: most neighbours have no identity

`TelephonyManager.requestCellInfoUpdate()` (API 29+, fine location only) asks the modem for a
fresh list. Many neighbour cells report only a physical cell id: MCC, MNC, area or cell id come
back as `Int.MAX_VALUE` (`CellInfo.UNAVAILABLE`) or `Long.MAX_VALUE`. Skip them; a PCI names
nothing. `CellInfo.getCellIdentity()` exists only from API 30: below that read each subclass's
own identity (`CellInfoLte.cellIdentity` and so on, API 28 for `mccString`).

## An accuracy gate stores nothing indoors

**Measured.** Indoors at home the fused fix sat at 33 to 56 m. A 50 m gate, right for keeping
devices on the right street, stored **zero** sightings in a session that dropped 46 observations for want of a good fix. Make
the gate a setting (20, 50, 100, 200 m), show "N heard without a good fix" so the empty session
explains itself, and default it for outdoor use.

## Ask about 6 GHz; do not assume it

6 GHz Wi-Fi is not licensed everywhere (India among them) and many phones lack the radio. Show a
6 GHz category only when `WifiManager.is6GHzBandSupported()` (API 30+) is true or a 6 GHz AP has
actually been recorded; keep it in the data model for phones that have it.

## Verifying on a device

- Compare the AP count with `adb shell cmd wifi list-scan-results`; its `Age(sec)` column shows
  how stale the cached list is.
- Stand still for two minutes during a session and check sightings keep landing (the minimum
  distance trap).
- Turn the screen off and check whether BLE rows stop arriving.
- Pull the database from a debug build (`adb exec-out run-as <pkg> cat databases/<db>` plus the
  `-wal` file) and query it; counts in the UI alone hide misplaced and duplicated rows.
