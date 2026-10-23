# 2021 Honda CR-V Android 4.2.2 CarPlay Test

This branch targets the 2021 Honda CR-V head unit running Android 4.2.2 / API17.

## Current source build under test

- Version: `0.2.28.1`
- Version code: `48`
- Package: `com.shihab.diplay`
- Commit: use the APK artifact built from the commit being tested
- Primary mode: Auto (USB first, Wi-Fi prepared when supported)

The earlier `v0.2.27-crv-api17` release does not contain the current NCM changes.

Build the APK locally or download an artifact from a successful GitHub Actions run for the current commit. APK files are not
stored in the source repository. Prefer the fixed-signed artifact when it is available; a
debug-signed artifact is suitable for compile/install diagnostics but may not satisfy the Honda
native installer.

## Follow-up for the 2026-10-23 LaneWatch and phone voice-message observations

The `.11` source drains hidden image messages without decrypting or decoding them. Nonces
are still allocated at receive time for every encrypted image, including discarded images;
configuration messages remain ordered. Hidden ciphertext is not passed to a content parser
or sink. Visible images still require valid authentication. A visibility epoch fences queued
and in-flight pictures from before a hide/show round trip. The existing Surface retirement
and fresh-keyframe recovery are retained; this does not keep a codec competing with LaneWatch.
The TCP reader no longer becomes a background-priority thread when the page is hidden.
`hiddenSkippedTotal` is a separate discard count; `agedFrames` now observes processed images
and must not be compared as if it included the same hidden work as `.10`.

Ownership locks are per audio stream and per screen. Slow cleanup on one stream must not
block another audio stream or video delivery; replacements of the SAME stream remain serialized,
and a retired owner cannot stop its successor. Renderer map/focus changes are short synchronized
transitions. Microphone-stop logs are emitted only when an actual uplink instance was retired.

The CR-V negotiated maxFPS is now 15 instead of 20. Resolution and touch coordinates are
unchanged. This is a sender request, not receiver-side interframe dropping, and must be verified
against received frame/byte rates. Native crypto acceleration is not part of this change.

Runtime media samples were previously omitted by the controller's debug-log allowlist.
The allowlist now forwards them to system logs, with sampler lifecycle/error events and
separate video/audio decrypt worker labels. Audio processing adds per-window packet/byte counts,
wall/CPU totals and `cleanupTotal` for queue shutdown; cleanup is not deadline expiry.
Totals cover successful decryptions; authentication failures remain a separate counter.

Vehicle acceptance: run 20 LaneWatch entry/return cycles, 10 phone WeChat voice-message cycles,
and fixed map dragging/zooming before and after 30 minutes. Repeat the combined sequence after
voice recording. Distinguish sender TEARDOWN silence from unexpected starvation after SETUP.
Record focus/route, audio sequence gaps, expiry increments, hardware inventory and rebuffering,
Runtime media CPU/scheduler data, hidden discards, return-to-first-frame, and external-video
touch-to-visible-response. A local HID write is not phone acknowledgement or screen latency.
Source tests cannot establish that vehicle audio, thermal behavior or display latency is fixed.

Automated validation on 2026-10-23: all 850 shared and 192 mobile unit tests passed,
including five new regression cases for hidden-image nonce/config continuity, visible-image
authentication failure, camera-round-trip queued-frame fencing, independent stream delivery
during blocked cleanup, and distinct/idempotent receive-queue cleanup accounting. Legacy/mobile
lint and the API17 source baseline/Java surface checks passed. The final `.11` source build
uses versionCode 58. No vehicle installation or live media performance measurement was made.
The ordinary debug APK is a source-validation artifact without standalone MFi authentication
assets; a vehicle package requires the explicitly provisioned standalone build described in
docs/BUILD.md.

## Follow-up for the 2026-10-22 vehicle observations

The user reports severe display delay/freezes and delayed, interrupted audio on `.9`.
The screen receiver now observes receive age instead of dropping compressed interframes
and requesting decoder recovery whenever processing exceeds 250 ms. This removes one
source of repeated flush/recreation while retaining ordered authentication, bounded queues,
and the decoder's backlog/output-stall recovery. `agedFrames` counts observations, including
hidden frames; it is not the old `expiredFrames` discard counter. The pipeline logs
`receiveAgePolicy=observe-only`. Non-secret configuration diagnostics use `configHash`.

Soft video recovery requests while already waiting for a keyframe now share a four-second
cooldown. A newly broken established reference chain and hard codec errors still recover
immediately. `recoverySuppressedTotal` reports coalesced requests; startup snapshots report
an unavailable input/output span as `unknown`.

API17 music now allows 160 ms in the pre-authentication receive queue, with a single
200 ms UDP-receive-to-renderer-queue deadline. The original receive timestamp follows the
packet through authentication and ownership checks; handoff cannot restart its age allowance.
Interactive audio keeps an 80 ms receive deadline and a 100 ms combined queue deadline.
These limits exclude PCM already in AudioTrack and do not establish end-to-end sound latency.
`receiveMaxAgeMs` and `queueClock=udp-receive` identify the actual policy in logs.

`Runtime media` samples the decrypt thread's current CPU, frequency, nice value, cumulative
scheduler run/wait time, raw thermal-zone 0–3 values and heap usage every ten seconds from a
separate worker. Missing OEM permissions/files are `unknown`; thermal units and the physical
meaning of each zone must be checked on the vehicle. Samples are snapshots, not continuous
traces or proof of thermal throttling. Sampling stops when the stream ends.

Repeat the same content/resolution for at least 90 minutes, including OEM page takeover
and return. Compare visible response/freezes, output throughput and recovery frequency,
decrypt CPU per MiB, audio receive expiry, rebuffering and listening results. The cause of
the observed decrypt CPU cost increase remains unresolved. Exclude deliberate disconnect
cleanup from connected-session rebuffer counts. Neither issue is vehicle-verified as fixed
by this source change.

Validation on 2026-10-22: all 845 shared and 192 CR-V mobile unit tests passed, including
real TCP/UDP authenticated delivery, timestamp ownership fencing, receive jitter and
combined queue expiry. CR-V legacy/mobile lint, API17 baseline and Java surface checks
passed. No new APK was assembled or installed in this validation run.

## Follow-up for the 2026-10-10 logs

Transport tuning now uses 1, 2, 4, 8, 16, then at most 20 ms pauses after a NOT_READY USB write,
resetting the pause for each packet. Bulk transfer deadlines and the 90-second terminal bound
remain unchanged; short/partial writes still fail rather than blindly retransmitting. Transient
TUN EAGAIN writes retain the current packet and retry in order, failing after five seconds of
congestion. Fatal writes still fail immediately. Bridge priorities are best-effort on OEM firmware.
Video drain batches display only the newest decoded output; `staleSkippedTotal` counts deliberately
skipped display outputs, not transport packet loss. Reference frames continue through the decoder.
Compare the same 45-minute music/navigation workload before and after: RTP gaps, UDP6 socket drops,
NCM transfer write time/failures, touch delivery time and video recovery frequency. No measured
throughput/latency improvement or zero-loss claim can be made before this vehicle comparison.

For touch/scroll lag, compare taps, long drags, quick flicks and two-finger gestures while music
and navigation are active. Touch socket writes now run on a dedicated worker. Only consecutive
pending MOVE positions are coalesced; down/up/cancel remain ordered during normal operation.
A full queue of gesture edges releases both fingers before resynchronizing, sacrificing old
gestures instead of retaining an unbounded backlog. `Touch delivery` reports queue depth,
coalesced moves, overload resets, send failures and maximum queue/send durations every five
seconds. A true enqueue result means accepted locally, not delivered or acknowledged by iPhone.
This change does not establish that video decode/render lag is fixed; compare with video receive
and decoder recovery diagnostics. APK build and vehicle validation remain outstanding.

For Amap plus NetEase Cloud Music, verify that spoken navigation remains clear while music
temporarily drops to 20% gain and returns smoothly after the prompt. The receiver detects actual
decoded prompt speech, including legacy type-101/default streams; silent retained prompt streams
do not hold music down. The last audible frame holds attenuation for 700 ms, and gain changes
over about 150 ms. Explicit duckAudio/unduckAudio commands use the same attenuation decision;
command parameters for custom gain/duration are not interpreted. Check `Audio music duck` logs,
overlapping prompts, music started during a prompt, repeated prompts and reconnects. This remains
pending vehicle validation.

The source fixes microphone socket cleanup/local binding and hotspot state/restoration handling.
These changes still require vehicle validation; audio continuity is not considered fixed merely
because the additional counters are present. Use the same phone and cable for a 45-minute
music/navigation run, repeat calls and voice input, and record intentional USB unplug times.

Check `Microphone: API17 PCM ready` and actual speech at the remote end of a call. A failure now
includes the UDP setup phase, address family and error message. For wireless testing, require
`Wi-Fi hotspot ready` before testing phone handoff; a stop request is not confirmed until
`Wi-Fi hotspot stopped`. Restoration failures retain the configuration in process and are retried
before a later hotspot start (the backup does not survive process death).
The station must reach DISABLED before AP startup. Shutdown waits up to eight seconds; restoration
requires matching configuration readback and a confirmed station state. A masked saved password
prevents starting a temporary hotspot because restoration cannot be verified safely.

Compare `Receive: audio` sequence gaps with `Audio playback` queue depth/high-water mark,
cumulative drops and per-window write maximum. `NCM transfer` reports per-window packets,
bytes, write failures and write maximum for each userspace bridge direction. `Audio receive ended`
reports cumulative receive/decryption totals. A kernel NCM session does not use the userspace
bridge counters. Include the tested APK's commit alongside its version when exporting logs.
`UDP6 receive` samples every five seconds outside the audio receiver thread. `socketDrops` is this
socket's kernel drop delta; `systemScope=allSockets` counters include other applications. `unknown`
means an initial, missing, ambiguous or reset reading, not zero losses. A positive socket delta
supports kernel receive loss; a zero delta cannot rule out loss earlier in the USB/NCM path.

## Vehicle observation (2026-10-09)

For the reported WeChat call interruption, repeat answering and ending a voice call without
unplugging USB, including while navigation/music is active. Record whether the display disappears,
returns to the app's connection screen or is replaced by an OEM call screen. The detach handler now
logs the broadcast device and active device, checks inventory after 300 ms and ignores stale or
unrelated events. A confirmed device disappearance still closes USB; software filtering cannot
prevent a physical/driver reset. Compare these events with microphone setup failures, screen-stream
termination, activity pauses and AirPlay control closing. This change is not yet a verified fix for
WeChat call interruptions on the vehicle.

If incoming calls open the OEM Honda phone UI while the iPhone is also Bluetooth-paired, compare
the wired run with the OEM Bluetooth connection temporarily disconnected. Record whether OEM UI
takeover stops and whether both call audio directions work through CarPlay. The CarPlay receiver
now advertises the available Android Bluetooth adapter address rather than the generated accessory
ID; `CarPlay Bluetooth association source=adapter` confirms the source, not successful HFP
coordination. Some OEM Bluetooth modules may expose a different identity. A derived fallback is
still reported when the adapter address is unavailable. The app does not disable Bluetooth or
force foreground takeover of the OEM call screen. Wireless handoff needs separate validation.

The local `0.2.28` standalone build connected wired CarPlay on the target CR-V and displayed
navigation. The exported log for that session was empty, so this observation is based on the
vehicle screen rather than a protocol trace. Audio, microphone, touch and long-term stability
remain to be tested separately.

## Automatic USB / Wi-Fi switching (experimental)

The default **Auto: USB first** mode starts wired CarPlay and, when the Honda
firmware permits, prepares a temporary Wi-Fi hotspot and a second AirPlay listener.
The iPhone must receive the Wi-Fi configuration before USB removal. Disconnecting
USB then retires the wired NCM/VPN path while the Wi-Fi listener waits up to 45
seconds for a wireless CarPlay session. Reconnecting USB closes that attempt and
starts a new wired session. If hotspot setup or dual listener binding fails, Auto
continues on USB only; use the session log to check which path was prepared.

Test in this order: establish wired video/audio/touch, verify the log records
`iap2 tx=0x5703`, unplug USB and verify wireless video/audio/touch, then reconnect
USB and verify wired video/audio/touch. Repeat while a call is incoming and after
sending a WeChat voice message. Capture the log and visible status if any step
fails. This source build has not yet been accepted on the target car for automatic
switching; the iPhone may require a fresh session during the transition.

## Standalone experimental Wi-Fi handoff

The home screen also offers **USB connection / Wi-Fi test mode**. Wireless mode first
uses the USB cable for Lockdown/iAP2 pairing and MFi authentication. The app starts
a temporary WPA2 hotspot with a random per-attempt password, sends its details to
the iPhone, and listens for AirPlay on the hotspot address. Leave USB connected
until the screen says **CarPlay active**. Then disconnect USB and check that video,
audio, touch, and reconnect remain usable over Wi-Fi. Use **Disconnect** to stop
the test. The app attempts to restore the vehicle's prior Wi-Fi station and
saved hotspot configuration. Check the car's Wi-Fi settings afterward.

This mode needs the Honda Android 4.2.2 firmware to expose and permit its legacy
hotspot state, configuration, and enable/disable APIs. It refuses to replace an
already active vehicle hotspot and fails if the saved configuration cannot be read
or restored. Firmware may still reject the hotspot request or omit Wi-Fi broadcast
information needed for an iPhone to join automatically. No CR-V hardware acceptance
has been recorded for wireless mode. If the phone does not join, keep the full
diagnostic log, the last visible stage, and the actual iPhone Wi-Fi state; never
include the hotspot password or MFi identity.

## Installation

Update the existing package instead of uninstalling it so saved Lockdown pairings and app-private
MFi provisioning survive:

```sh
adb install -r mobile-debug.apk
```

Confirm that the selected artifact reports package `com.shihab.diplay` and `minSdk=17` before using
it on the head unit.

## MFi identity provisioning

Ordinary source and CI builds contain no accessory identity. A standalone authorized test requires:

```text
offline-mfi/identity.pk8
offline-mfi/certificate.p7b
```

Place both files in the external-files directory printed by the application. The app validates the
pair, installs it into app-private storage and removes the external provisioning copy. Never commit,
upload or attach these files to a diagnostic report.

## Honda OEM and onboard MFi diagnostics

The MFi section of the diagnostic log records whether `link_iap_adapter` is registered, whether its
Binder can be obtained, its descriptor/AIDL candidates, and the unambiguous certificate/signature
methods selected. It also records the app UID/GID/groups and, for each `/dev/i2c-N`, Unix ownership,
mode, adapter/driver metadata, and the result of opening it read-write. The open probe immediately
closes the node and performs no I2C transaction.

To test one known board bus, place a text file named `i2c-node.txt` beside the `offline-mfi`
directory contents and put exactly one path such as `/dev/i2c-1` in it. Only that explicitly selected
node is used for the MFi register self-check; visible buses are never scanned blindly.

## Expected wired progression

```text
iPhone detected
Switching iPhone to CarPlay USB mode
Opening CarPlay USB data paths
USB bulk data path open
Opening CDC-NCM data path
USBMUX ready
iPhone Lockdown ready
iAP2 carkit channel ready
AirPlay listening
Transport pre-auth ready
MFi authentication ready
Starting iAP2 identification/MFi
CarPlay active
```

The implementation first tries the matching Honda kernel CDC-NCM interface. If it is unavailable,
it reports the reason and attempts the userspace NCM fallback.

The connection screen shows four plain-language steps and a recovery hint. Detailed
messages are written to the session log file rather than displayed live on the car screen.
The root `carplay-crv-v*.log` is now an overview of connection milestones and failures.
Detailed logs use the same session filename beneath `logs/connection`, `logs/network`,
`logs/audio`, `logs/video`, `logs/touch`, `logs/wireless`, `logs/system`, `logs/protocol` and
`logs/errors`. A fault is copied to errors as well as its own subsystem and the overview.
Copies retain identical wall time, elapsed time and sequence number for correlation.

Overview segments are limited to 512 KiB with at most eight files (about 4 MiB) in the root.
Each category has 256 KiB segments and at most four files (about 1 MiB); the active session
retains its newest two segments independently in each category. Total normal retained log
capacity is approximately 13 MiB. Startup/rotation pruning only removes matching CR-V session
files within the known directories; unrelated files remain untouched. Category files appear
when they first receive an entry. Sensitive message filtering happens before every destination.
Legacy root logs remain readable and follow the root retention limit.

## What to capture

- Exact APK filename and signing label (`fixed-signed` or `debug-signed`)
- Last status shown on screen
- Current root `carplay-crv-v*.log` and its `-previous.log`, plus the entire `logs` subdirectory,
  from the app external-files directory (copy the whole folder for complete categorized diagnostics)
- Head-unit Android/build information and iPhone/iOS version
- Whether Trust, CarPlay, USB and VPN prompts appeared
- Whether failure affected connection, video, audio, microphone or touch

Do not include MFi identities, Lockdown records, hotspot passwords or other credentials.

## Hardware acceptance

The iAP and video-settings TCP streams now use exact authenticated-frame reads and reusable
package buffers rather than repeatedly concatenating incoming fragments. Packages remain
bounded at the existing 4 MiB / 8 MiB limits, and invalid lengths or truncated frames/packages
end that channel explicitly. Video-settings replies request TCP_NODELAY; this affects local
small-packet sends, not the iPhone's outgoing video or the kernel's TCP acknowledgement policy.
Verify connection setup, navigation/music, touch, calls and settings after the change; compare
the categorized network/audio/video/touch logs under the same workload. No measured hardware
latency or packet-loss improvement is claimed before this comparison.

Online references checked on 2026-10-11:
[Android USB bulk-transfer limits](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection)
(legacy transfers cap at 16 KiB and the offset overload needs API18),
[Java TCP_NODELAY](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html#setTcpNoDelay-boolean-),
and [Android JNI allocation/copy guidance](https://developer.android.com/ndk/guides/jni-tips).
Keep API17 USB chunking and bounded packet retries; the control-buffer optimization does not
change the negotiated media format, USB framing or encrypted message order.

CI cannot establish successful operation of the Honda USB controller, kernel CDC-NCM network
device, MFi hardware/service, Android 4.2.2 VPN/TUN implementation, decoder, audio route or touch
panel. A release remains a hardware-validation build until the complete wired progression is
confirmed on the target CR-V and iPhone.
