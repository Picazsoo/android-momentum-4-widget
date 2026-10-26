# MOMENTUM 4 Control — Android app + home-screen widget

> **Handoff doc.** Implementation plan and design decisions for the app. The protocol
> facts it relies on are summarised under *Protocol notes* below.
> Visual references (open in a browser): [`widget-mockup.html`](widget-mockup.html)
> for the widget, [`app-mockup.html`](app-mockup.html) for the app.

## Context

A small native Android app with a home-screen widget to change Sennheiser
MOMENTUM 4 settings — noise control (Off / Adaptive / Custom mix), EQ presets,
Bass Boost — and show battery and codec, without opening Smart Control Plus.
Target firmware **3.38.3** (the version it was built and tested against).

Prior work: the open-source projects
[`f3Y0/momentum4-control`](https://github.com/f3Y0/momentum4-control) (Python, MIT) and
[`zaval/sennheiser-desktop-client`](https://github.com/zaval/sennheiser-desktop-client)
(C++) drive the same GAIA v3 protocol. No code is taken from either; the protocol facts
below were cross-checked against them and confirmed on a real MOMENTUM 4.

- **Distribution: sideload only** (`adb install`). Not built for Google Play.
- App name **"M4 Control"**, applicationId **`cz.metlicka.m4control`** (debug builds
  `cz.metlicka.m4control.debug`). The ID and the release signing key are the app's permanent
  identity: never change either after a release, or updates stop installing.
- **Kotlin**, `minSdk 29` (Android 10), `compileSdk 36`, `targetSdk 35`.
  Build host has SDK platforms 35/36/37, build-tools 35/36, Java 25. **Pin the
  Gradle Java toolchain to 21** (`kotlin { jvmToolchain(21) }`) — AGP/Kotlin lag
  new JDKs.
- Out of scope: firmware update, factory reset,
  pairing-list changes, DSP coefficients, Sound Zones (app-side geofencing in the
  official app; no headphone command exists), comfort mode, legacy EQ.

## Protocol notes

Facts this app relies on, confirmed on a MOMENTUM 4 with firmware 3.38.3 (frame logs in
Settings → Frame log).

- **Transport:** Bluetooth Classic RFCOMM, located through SDP by the service UUID
  `a2129ff3-081b-4c45-8afe-469d9c4842ec` (it appears in the headset's SDP record).
- **Frame:** `FF 03`, flags byte (`00`), payload length (1 byte), vendor (2 bytes,
  `0x0495` for Sennheiser commands), command id (2 bytes), payload. Big-endian.
- **Command id:** feature = `id >> 9`; bits 7–8 are the type: 0 command,
  1 notification (`id | 0x0080`), 2 response (`id | 0x0100`), 3 error (`id | 0x0180`,
  payload byte 0 = error code).
- **Commands used** (base id; set / get / notification):

| Property | Ids | Payload |
|----------|-----|---------|
| Firmware, model | get `1201`, `1206` | three u16 (major, minor, patch); model string |
| Register notifications | `0007` | 1 byte feature: 3 battery, 4 audio, 8 EQ, 13 noise control |
| Noise control on/off | `1A04` / `1A05` / `1A85` | bool |
| Noise modes | `1A00` / `1A01` / `1A81` | set `[mode, state]`; read: mode/state pairs. Mode 1 wind (0 off, 1 max, 2 auto), 3 adaptive (0/1) |
| Transparency mix | `1A02` / `1A03` / `1A83` | 0–100; 0 = full noise cancelling |
| EQ configuration | get `1000` | `[bands, minGain, maxGain, …]`, gains in signed tenths of a dB (±6.0 dB) |
| EQ band gain | `1001` / `1002` / `1082` | set `[band, i8]`; get `[band]` → `[i8]`; notification = all bands |
| EQ submode (Bass Boost) | `1008` / `1009` / `1089` | bitmask, bit 0 = Bass Boost |
| Audio mode | `0803` / `0804` / `0884` | u16: 0 bypass, 1 user EQ, 2 podcast, 3 personalized |
| Battery, charging | get `0603`, `0602`; notif `0683`, `0682` | byte 0 = headband %, `FF` unknown; 0/1/2 not/charging/full |
| Codec | get `0800`; notif `0880` | 0 SBC, 1 AAC, 2 aptX, … , `FF` none |
| Optional settings | `080C`/`080D`, `0400`/`0401`, `1606`/`1607`, `0805`/`0806`, `1800`/`1801` | Smart Pause, on-head detection, touch lock, sidetone, pause at full transparency |

Graphic EQ gains only take effect in audio mode 1. Turning Adaptive on resets the
transparency mix to 0.

## Architecture

### One connection owner

There is a single owner of the socket. Implement
**`ConnectionManager`** as a process-wide singleton (created in `Application`):

- owns the RFCOMM socket and one continuous **receive loop** (coroutine on
  `Dispatchers.IO`) that parses frames and routes them: responses to the
  outstanding request, notifications to `DeviceStore`;
- owns the **serialized transaction queue** — one transaction executes at a time;
- is used by **everything**: MainActivity, `CommandService` (widget actions) and
  the ACL receiver. Nobody else opens a socket.

`CommandService` exists only to keep the process alive (foreground,
`connectedDevice`) while the manager works on behalf of the widget; it doesn't own
any connection logic.

### Connecting

- `createInsecureRfcommSocketToServiceRecord(UUID("a2129ff3-081b-4c45-8afe-469d9c4842ec"))`
  — **SDP only**. No SPP fallback, no channel probing. If SDP/connect fails →
  `UNAVAILABLE`.
- `connect()` blocks: run it on IO and enforce `CONNECT_TIMEOUT` by **closing the
  candidate socket** from a watchdog — cancelling the coroutine alone doesn't
  unblock it.
- **Session init** on every new connection: start receiver → read
  firmware + model → profile check (below) → register notifications for features
  3, 4, 8, 13 → read noise-control state, EQ configuration + gains, EQ submode,
  audio mode, battery, codec → publish one synchronized snapshot. Subscriptions
  are redone on every session.
- **Profile check** (our policy): firmware
  3.38.3 → supported. Any other version → still allowed, app shows an "Untested
  firmware x.y.z" banner, widget unaffected. Unknown model string → log it and
  record it (open task: pin the expected model string). EQ commands are only used
  after the EQ configuration read succeeds with 5 bands.

### Framing and correlation

Covered by the `stream_cases` in `app/src/test/resources/protocol-vectors.json`:

- byte 2 is **flags**, must be `00`; byte 3 is the 1-byte length. A non-zero flags
  byte is rejected — never read as a length high byte.
- Fragmented headers/payloads, several frames per read, sync-like bytes inside an
  incomplete payload (don't resync inside it), bounded buffer + assembly deadline.
- A response matches **vendor and base command**. A response from vendor `0x001D`
  never satisfies a `0x0495` request.
- Notification ids come from the table above — never computed from a Set.
- **Timeouts:** on a Set timeout, read the corresponding Get. If a late response
  to the timed-out request could be confused with a newer same-command request,
  **end the session** (close socket, reconnect on next use) rather than reuse that
  command slot.

### Transactions and state

Every user action becomes one **transaction** (a short ordered list of commands)
in the queue:

- **Coalescing** happens on whole transactions: while one runs, newer requests for
  the same control replace the *pending* target; frames are never mixed between
  transactions.
- **Failure:** stop at the failed step, then reconcile by re-reading every
  property the transaction touched. Never assume earlier steps were undone
 .
- **`DeviceStore`** (DataStore) keeps, per property: **desired**, **confirmed**,
  **pending** flag, and **lastSyncedAt**. The UI renders desired values
  optimistically but shows them as **pending** until confirmed (mockup: dimmed
  value + thumb).
- **Timeouts**:
  `CONNECT_TIMEOUT = 4 s`, `RESPONSE_TIMEOUT = 1.5 s`, `IDLE_KEEPALIVE = 4 s`,
  started only when the queue is empty.

### Connection and freshness states

Two independent things are shown: **link state** (the dot) and
**freshness** (the "updated … ago" label).

- The dot = **audio link**: `ACTION_ACL_CONNECTED/DISCONNECTED` for the stored MAC,
  refreshed from `BluetoothA2dp`/`BluetoothHeadset.getConnectionState()` (via
  `getProfileProxy`) at app start, widget update and service start.
- Freshness: `lastSyncedAt` of the snapshot; cached values are always labeled
  with it, whether or not the audio link is up.

Checked in this order; the first that applies wins:

| State | Trigger | Widget shows |
|-------|---------|--------------|
| `NO_PERMISSION` | `BLUETOOTH_CONNECT` not granted | "Tap to set up" → MainActivity; no controls |
| `NO_DEVICE` | no stored MAC, or device no longer bonded | "Tap to choose headphones" → MainActivity; no controls |
| `DISCONNECTED` | no audio link | grey dot, controls dimmed, "Not connected · last known settings" |
| `UNAVAILABLE` | audio link up, control connection fails (SDP/connect/IO) | deep-orange dot, **"Control connection unavailable"** — no guess at which app holds it |
| `ERROR` | error response, or reconcile after timeout failed | brief error line; reconciled values shown |
| `CONNECTED` | none of the above | green dot, live values |

`NO_DEVICE`, `NO_PERMISSION` and `DISCONNECTED` short-circuit before any socket
is opened. In every state the widget's controls still enqueue their action, which
fails fast with the state above.

### Keeping state fresh

- **ACL receiver** (manifest; ACL broadcasts are exempt from implicit-broadcast
  limits; needs `BLUETOOTH_CONNECT`): on connect/disconnect it **only** updates the
  link state, marks the snapshot **stale** and redraws the widget. No socket, no
  multi-command refresh inside the broadcast.
- The snapshot is refreshed by the next session: opening the app, or the next
  widget action (session init runs first, then the action).
- While a session is open, notifications keep the store live.
- Optional later: associate via `CompanionDeviceManager` to get background
  foreground-service starts and `onDeviceAppeared`; then a full refresh on
  reconnect becomes allowed. Not in v1.

### Foreground service rules

- Widget taps use `PendingIntent.getForegroundService` → `CommandService`
  (`connectedDevice`, low-importance notification channel). Starting from a widget
  tap is allowed on Android 12+ (user-interaction exemption).
- Check `BLUETOOTH_CONNECT` **before** `startForeground` (it throws on API 34+
  without it); handle `ForegroundServiceStartNotAllowedException` and any start
  rejection by setting an error line instead of crashing.
- The service stops when the manager reports the queue empty and
  `IDLE_KEEPALIVE` has passed.

## Feature behavior (UI rules)

### Noise control — Off / Adaptive / Custom

The device has three noise modes; wind reduction is separate and is
never changed implicitly.

| UI mode | Device state | How the user selects it |
|---------|--------------|-------------------------|
| **Off** | master off | the switch |
| **Adaptive** | master on, adaptive on | the **Adaptive stop** (a separate stop left of the slider) |
| **Custom** | master on, adaptive off, transparency = `100 − L` | any slider stop |

- **Slider level L**: 100 = full noise cancelling (left, blue),
  50 = balanced, 0 = full transparency (right, orange). 7 stops
  `[100, 83, 67, 50, 33, 17, 0]`, ties snapped toward 50. Readout: `L > 50` →
  "Noise Cancelling (L−50)·2 %", `L < 50` → "Transparency (50−L)·2 %", `L = 50` →
  "Balanced". The percentages are relative UI positions, not acoustic values.
- **Switch:** off → master off. On → restores the last **requested** mode
  (Adaptive, or Custom at the last requested L), as one transaction.
- **Selecting a stop while Off or Adaptive** requests Custom: enable
  master, disable adaptive, set transparency — skipping only steps already
  confirmed in this session.
- **Adaptive is shown explicitly** — Adaptive stop highlighted, readout
  "Adaptive", **no thumb** on the slider (never a remembered custom value).
- **Off** — readout "Off", switch off, slider greyed with the last custom level
  as a grey thumb (it's what a stop tap would restore).
- Remembered values (last requested custom transparency, last requested mode) are
  stored separately from confirmed device state.

### EQ, presets and audio mode

- **Presets are app records** (nothing preset-related is stored on the headphones),
  seeded from `app/src/main/assets/presets.json`: Warm, Bright, Vocal, Deep Bass,
  Loudness, Soft, Acoustic (the app's own values). Users can add/rename/delete their
  own; there's no limit beyond storage. Fields: id, name, five gains.
- **Favorites:** the user picks **4 favorites** in the app (default: Warm, Bright,
  Vocal, Deep Bass). The widget shows only those 4; the app shows the full list, a
  5-band editor (63 / 250 / 1k / 4k / 8k Hz labels; range from the device's EQ
  configuration), "Flat EQ" and "Bypass".
- **Applying a preset**: validate all 5 gains against the device range
  first (out of range → refuse with a message, no silent clamping), write the
  changed bands, set audio mode 1 (user EQ) if needed, reconcile.
- **What the EQ label shows**, in order:
  - audio mode ≠ 1 → the mode name: "Bypass", "Podcast", "Personalized"
    (unknown values → "Unknown mode").
  - mode 1, gains match a stored preset (and its optional bass/mode fields) →
    preset name.
  - mode 1, all gains 0 → "Flat EQ".
  - otherwise → "Custom".
- A Set-gain ack alone never displays as "EQ active" — only audio mode 1 does
 .

### Bass Boost

- Bit 0 of the EQ submode bitmask. Read the mask if unknown, then write
  `mask | 1` / `mask & 0xFE` — other bits preserved.
- Enabling Bass Boost while audio mode ≠ 1 also selects mode 1, **and the UI shows
  it** (the EQ label changes, e.g. "Podcast" → preset/Custom). Disabling bass
  restores the remembered previous mode, if bass caused the switch.

### Battery and codec

Battery: byte 0 of the battery response (headband); `FF` → "—". Charging status
shown as a small bolt when 1 (charging). Codec name from the  table in the
large widget's footer ("Connected · AAC"); unknown/255 → omitted.

### Optional controls (app only, v1)

Smart Pause, on-head detection, touch lock, sidetone, pause at full transparency
 — on the app's settings screen; each control disables itself if its
command errors. Not on the widget.

## Project structure

```
app/
  build.gradle.kts            Kotlin, Compose + androidx.compose.material, DataStore, jvmToolchain 21
  src/main/AndroidManifest.xml
  src/main/assets/presets.json            built-in presets (seed)
  src/main/java/cz/metlicka/m4control/
    M4App.kt                  Application: creates ConnectionManager
    gaia/
      Frame.kt, FrameParser.kt   framing, stream parser, type/base decode (pure Kotlin)
      Commands.kt                command IDs + encoders/decoders, one table
    device/
      ConnectionManager.kt       socket owner, receive loop, queue, session init, correlation
      Transactions.kt            noise, preset, bass, … as ordered steps + reconcile sets
      NoiseControl.kt            Off/Adaptive/Custom model, L ↔ transparency, stops, readout
      EqModel.kt                 presets, favorites, label rules, validation
    state/
      DeviceStore.kt             desired / confirmed / pending / lastSyncedAt, link state
    service/
      CommandService.kt          foreground keep-alive for widget actions
      AclReceiver.kt             link state + stale marking only
    widget/
      MomentumWidgetProvider.kt  one resizable widget, 3 layouts
    ui/
      MainActivity.kt, screens/ (device picker, controls, presets, settings), theme/
  src/main/res/layout/widget_momentum_{compact,medium,large}.xml
  src/main/res/xml/momentum_widget_info.xml
  src/main/res/values{,-night}/colors.xml
  src/test/java/.../            see Verification
```

## Key implementation points

1. **Frame/parser** — as in *Protocol notes*. Unit-tested against every entry of
   `protocol-vectors.json` (`vectors` and `stream_cases`).
2. **Commands.kt** — one table of base IDs, response/event IDs and codecs from the
   protocol notes; signed `i8` decode for gains; big-endian `u16` for audio mode and
   firmware.
3. **ConnectionManager** — as above. Exposes `suspend fun run(tx: Transaction)`
   and a `StateFlow` of link/session state.
4. **Transactions** — e.g. `NoiseCustom(L)`: [master on]? → [adaptive off]? →
   transparency `100 − L`; reconcile set = {master, mode pairs, transparency}.
   `ApplyPreset(p)`: validate → changed bands → [audio mode 1]? ; reconcile =
   {gains, audio mode, submode}. Mode/state pairs parsed by mode ID; unknown pairs
   kept; odd-length lists rejected.
5. **CommandService** — see Foreground service rules.
6. **MomentumWidgetProvider** — one resizable widget:
   - `momentum_widget_info.xml`: `resizeMode="horizontal|vertical"`, min size
     4×1, `maxResizeHeight` 4×3, `targetCellWidth="4"` / `targetCellHeight="2"`,
     default layout medium.
   - API 31+: `RemoteViews(mapOf(SizeF(…) to compact, … to medium, … to large))`.
     API 29–30: `onAppWidgetOptionsChanged` → pick by `OPTION_APPWIDGET_MIN_HEIGHT`
     (< ~110 dp compact, < ~180 dp medium, else large).
   - **PendingIntents:** `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`, unique
     `requestCode` per control: `100 + stop`, `150` switch, `160` Adaptive,
     `200` bass, `300 + favorite`. Identical Intents otherwise collapse.
   - `NO_PERMISSION` / `NO_DEVICE`: header + one "Tap to …" line opening the app.
7. **MainActivity (Compose, `androidx.compose.material`)** — layout per
   [`app-mockup.html`](app-mockup.html). Same Material 1 look as the widget:
   colored top app bar (Blue Grey 700 light / `#212121` dark), cards with 16 dp
   corners on `#FAFAFA` / `#303030`, teal accent, light/dark from the system.
   Screens:
   - **Home** — banners (link state, untested firmware); device card (name, link
     dot, battery, codec, freshness); noise card (on/off switch, Adaptive / Custom
     buttons, continuous `Slider` 0–100 with the same direction and center fill as
     the widget, end labels, wind reduction Off / Max / Auto); EQ card (current
     label, the 4 widget favorites, "All presets", Bass Boost). Moving the slider
     selects Custom.
   - **Equalizer** — current label; sound mode (User EQ / Podcast / Personalized /
     Bypass); 5-band editor (−6 to +6 dB in 0.5 dB steps — exact range from the
     device's EQ configuration), Flat, Save as preset (also the FAB); full preset
     list with apply (radio) and favorite (star, max 4, snackbar when exceeded).
     Editing a band selects User EQ.
     **Header:** the top bar's title is the EQ being edited — the preset name, "Flat EQ",
     the sound mode when not User EQ, or "<preset> · edited" once bands moved away from the preset
     the user last applied (falls back to "Custom"); "Equalizer" is the subtitle.
     **Reordering:** each preset row has a drag handle left of its radio button; dragging moves
     the row live, the order is saved on release (also "Move up/down" accessibility actions).
     Widget and home-screen favorites appear in this list order.
     **Naming presets:** "Save as preset" opens a name dialog (prefilled "My preset
     N", text selected). User presets show a pencil button that opens the same
     dialog as "Edit preset" (rename + Delete); the built-in presets from
     `presets.json` can't be renamed or deleted. Rules: trimmed, 1–30 characters, unique among all presets
     (case-insensitive) — errors shown under the field ("Enter a name", "A preset
     with this name already exists"); Enter saves, Escape / Cancel / tapping
     outside discards. Favorites and the widget reference the preset `id`, so a
     rename keeps the favorite and the widget picks up the new name on its next
     redraw.
     **Deleting presets:** Delete in the "Edit preset" dialog removes the preset
     immediately — no confirmation dialog — and shows a snackbar "Deleted X" with
     **Undo** for 5 s (undo restores the preset at its list position and its
     favorite slot). If it was a favorite, its slot is refilled with the first
     built-in preset that isn't a favorite yet, so the widget keeps 4 buttons; the
     snackbar says which one was added. Deleting only touches the app's record:
     the headphones keep their current gains, and if those matched the deleted
     preset, the EQ label becomes "Custom". The deletion is committed to storage
     when the snackbar times out or the screen is left.
   - **Settings** — optional controls, headphones (→ picker),
     firmware + support status, model, frame log (debug builds), version.
   - **Choose headphones** — bonded devices with "MOMENTUM 4" preselected and
     marked recommended, hint to pair in Android settings, "Use this device".
   - Snackbars for side effects the user didn't directly ask for ("Switched to
     User EQ for Bass Boost", "Restored Podcast").
8. **Manifest** — `BLUETOOTH_CONNECT` (API 31+, runtime), `BLUETOOTH` +
   `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`), `FOREGROUND_SERVICE`,
   `FOREGROUND_SERVICE_CONNECTED_DEVICE`; service
   (`foregroundServiceType="connectedDevice"`), ACL receiver, widget receiver.

## Widget design

Visual reference: [`widget-mockup.html`](widget-mockup.html) — three sizes in light
and dark, with switches to preview link states and the device's audio mode.

- **Style: Material Design 1 with rounded corners.** Flat surfaces, M1 elevation,
  Roboto, M1 text opacities (87 / 54 / 38 %), uppercase button labels, M1 switch
  and discrete slider. Card corners **16 dp** (`system_app_widget_background_radius`
  on API 31+), inner controls 8 dp.
- **Light and dark follow the system.** Light: white card. Dark: M1 dark theme,
  `#424242` card. Colors only as `@color` resources with `values-night` variants.
- **Palette** (light / dark): ANC Blue 600 / 300, Transparency Orange 600 / 300,
  accent (switches, Adaptive, connected dot) Teal 500 / A200, unavailable Deep
  Orange.
- **Noise row:** `[switch] [AUTO] [7-stop slider]`.
  - Slider: 4 dp grey track, 7 tick dots; colored fill grows from the balanced
    center toward the thumb (blue left / orange right); thumb takes the side's
    color; hollow M1 "zero" ring at 50.
  - **AUTO** = the Adaptive stop: an outlined 8 dp pill; filled teal when Adaptive
    is active (slider then has no thumb and no fill).
  - Off: switch off, fill hidden, grey thumb at the remembered level.
  - End labels (medium/large): Cancelling · Balanced · Transparency.
  - RemoteViews: switch and AUTO are `ImageButton`s with state drawables; slider
    = 7 equal-weight cells (tick + thumb slot + transparent tap target) + 6 track
    segment `ImageView`s (`seg_anc` / `seg_trans` / `seg_idle`), thumbs
    `thumb_anc` / `thumb_trans` / `thumb_bal` / `thumb_idle`; one thumb visible
    (or none in Adaptive). RemoteViews can't position views freely below API 31.
- **Pending values**: a value requested but not yet confirmed is drawn
  at ~55 % opacity (readout, thumb, chip) until confirmed or reconciled.
- **EQ row (medium/large):** caption "Equalizer" + current label (preset name /
  Custom / Flat EQ / Bypass / Podcast / Personalized); below it the 4 favorites as
  an M1 toggle-button group — highlighted only when that preset is what's active.
- **Bass Boost:** M1 list row with icon and switch.
- **Header:** link dot, name, battery % (+ charging bolt).
- **Status line** under the header for `DISCONNECTED` / `UNAVAILABLE` / `ERROR`;
  controls dimmed to ~45 %.
- **Sizes** (cell heights vary by launcher; ~70 dp per row):
  - **Compact 4×1:** header with short readout ("ANC 67%", "Adaptive", "Off"),
    then the noise row. No labels.
  - **Medium 4×2:** header; "Noise control" + readout; noise row; end labels;
    EQ caption + 4 favorites; Bass Boost.
  - **Large 4×3:** medium + footer "Connected · codec" and "updated … ago".
- **Lock touch controls** (large size only): a row below Bass Boost toggling the headphones'
  touch lock (`1606`/`1607`). It has no notification on the registered features, so widget
  sessions read it (one extra request); dimmed while unknown or pending.
- **Optional lock** (Settings → "Lock button on widget", off by default): a lock icon at the header's
  right end, per widget instance (`widget_locked_<appWidgetId>`). Unlocked: faint open lock. Locked: a
  grey veil covers the whole card and swallows every tap — controls and opening the app; a tap on it only
  shows "Locked — tap the lock to unlock" for 2.5 s. The closed lock sits above the veil in an undimmed
  card-coloured circle with a teal icon so it clearly stays live. Lock/hint taps are broadcasts to the
  provider (no foreground service) and return immediately — holding the receiver for the hint
  (goAsync) would queue later taps, including the unlock. Repeated taps extend the hint.
  Turning the setting off unlocks every widget.
- **Tap targets:** with switch and AUTO the compact slider cells are ~30 dp wide —
  below the 48 dp guideline; acceptable for a strip, verify on a real launcher. Don't
  add stops.
- Type: Roboto, tabular numerals.

## Verification

- **Unit (`./gradlew test`):**
  - `FrameParserTest` — every `vectors` entry (encode and decode) and every
    `stream_cases` entry in `app/src/test/resources/protocol-vectors.json`, plus malformed lengths and
    an oversized buffer hitting the bound.
  - `CommandsTest` — signed gains (`E7` = −2.5 dB), audio mode `u16`, firmware
    3.38.3 decode, battery 1-byte and `FF`, mode/state pairs by ID incl. unknown
    pairs and odd lengths.
  - `NoiseControlTest` — L ↔ transparency for 0–100, stops and tie snapping,
    readouts, Off/Adaptive/Custom transitions incl. "stop while Adaptive → Custom"
    and skipped steps only when confirmed.
  - `EqModelTest` — preset validation (no clamping), name rules (trim, empty,
    30-char limit, case-insensitive duplicates, renaming to its own name, built-ins
    not renamable, rename keeps favorite by id), delete (built-ins refused,
    favorite slot refilled, undo restores position + favorite, active preset →
    "Custom"), label rules, favorites,
    bass bitmask preserving other bits, bass → mode 1 → restore.
  - `ConnectionManagerTest` (fake socket) — interleaved notifications during a
    request, vendor mismatch, Set timeout → Get read, late response → session
    end, partial transaction failure → reconcile, coalescing of whole
    transactions.
- **Build:** `./gradlew :app:assembleDebug`.
- **On device** (real phone paired with the M4 on firmware 3.38.3; emulator can't
  do RFCOMM):
  - noise: switch, Adaptive, each stop; audible change across the mix; stop while
    Adaptive turns it off; wind reduction untouched;
  - each built-in preset; label switches to "Custom" after changing a band in the
    official app; Bypass and Podcast shown when set elsewhere;
  - Bass Boost from Podcast mode → mode 1 shown → disable → Podcast restored;
  - battery, charging bolt, codec;
  - headphone button changes ANC → app updates live; widget marked stale after
    reconnect and refreshed on next use;
  - Smart Control Plus connected → `UNAVAILABLE`, no crash; headphones off →
    `DISCONNECTED`; revoke permission → `NO_PERMISSION`; clear data → `NO_DEVICE`;
  - resize 4×1 → 4×3 (Android 12+, and 10–11 if available); dark mode switch;
  - record firmware/model strings and the frame log for the open questions.

## Open questions

1. **Expected model string** for the profile check — recorded as `M4AEBT Black` on
   the test device; likely colour-dependent.
2. **Firmware policy** outside 3.38.3 — currently "allow with banner"; revisit
   after testing other versions.
3. **Error codes** — meanings unknown; kept and shown as hex for now.
4. **`1800` naming** — exposed as "Pause at full transparency"; zaval's command map
   calls it "TransparentHearing_Mode". Confirm what it does before relying on it.
5. **Compact tap targets** with switch + AUTO + 7 stops — check on a real launcher;
   fallback is moving AUTO into the header.

## Implementation status (2026-10-26)

Built in this repo (`app/`, Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.0, Compose BOM 2025.11.01; built with
the host's JDK 25 — no JDK 21 is installed, and it works). Unit tests: `./gradlew :app:testDebugUnitTest`
(43 tests, including all vectors and stream cases in `protocol-vectors.json`). Widget layouts are generated by
`python tools/gen_widget.py`.

Verified on a Galaxy S24 (Android 16) with MOMENTUM 4 firmware 3.38.3:
- SDP connect by the vendor UUID works (the UUID is in the headset's SDP record); session init, all
  reads, noise Off / Adaptive / Custom, presets (only changed bands sent), Bass Boost bitmask, wind,
  notifications, idle close after 4 s, widget taps through the foreground service.
- Model string is **`M4AEBT Black`** (open question 1: probably colour-dependent; match on `M4AE`).
- Registering features pushes the current state as notifications (master, modes, transparency, gains,
  submode, audio mode, battery, charging, codec), so widget sessions skip those reads: tap → confirmed
  change ≈ 0.6 s including connect.
- Registration also pushes undocumented events `0898`, `089A`, `108B`, `108D`, `108F`, `1091`, `1093`
  (kept in the frame log, ignored).
- Turning Adaptive on resets the device's transparency to 0 (the app keeps the remembered custom level).
- Codec reads `FF` (unspecified) when no audio is playing.

Deviations from the sections above:
- **Medium widget (4×2)** is header (with readout) + noise row + favorites with a **BASS** pill: a 4×2
  cell on One UI is only ~167 dp tall. Captions, end labels, the Bass Boost row and the footer are in
  the large size only.
- Persistence uses SharedPreferences, not DataStore (synchronous reads in the widget provider).
- No `cancelDiscovery()` before connecting — it needs `BLUETOOTH_SCAN`.
- Settings has "Add widget to home screen" (`requestPinAppWidget`).
- Release builds use R8 (minify + resource shrinking, about 2.2 MB) and are signed from a git-ignored
  `keystore.properties`; enum names are kept (`proguard-rules.pro`) because they are persisted.

Not yet checked on device: large widget size, light mode, the Equalizer and Settings screens
(rename/delete/undo), the `UNAVAILABLE` path with Smart Control Plus connected, reconnect after the
headphones were off.
