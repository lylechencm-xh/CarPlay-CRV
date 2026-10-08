# CR-V simulator

`crv-simulator` is a JVM-only record/replay test harness for the wired CR-V
CarPlay path. It deliberately has no Android SDK or hardware dependency, so it
can run before an APK build and fail quickly when a protocol request changes.

The virtual path is exercised in the same order as the head unit:

```
USB -> NCM -> USBMUX -> Lockdown -> MFi -> CarPlay SETUP/session
```

Each boundary exchanges opaque bytes through `CrvExchange`. A
`CrvRecordingExchange` records request, response, signal and fault events;
`CrvReplayExchange` then requires the same layer, channel, order and bytes.
This catches accidental protocol drift without pretending that a JVM model is
a substitute for final USB electrical and timing tests in the vehicle.

## Run the virtual CR-V gate

From the repository root on Windows:

```powershell
.\gradlew.bat -p crv-simulator test
```

Create and replay a deterministic demonstration trace:

```powershell
.\gradlew.bat -p crv-simulator run --args="record-demo build/traces/happy.crvtrace"
.\gradlew.bat -p crv-simulator run --args="replay build/traces/happy.crvtrace"
```

The test suite covers a complete session, byte-level replay drift, trace CRC
corruption, and the observed failure class where the peer closes during
CarPlay `SETUP`. The CI workflow runs this gate before Android compilation.

## Import and replay a production car log

New APK builds write sanitized `CRVTRACE` milestone records into the normal CR-V
field log. Import and replay one without retaining protocol payloads:

```powershell
.\gradlew.bat -p crv-simulator run --args="import-log _carlogs/car.log build/traces/car.crvtrace"
.\gradlew.bat -p crv-simulator run --args="replay-production build/traces/car.crvtrace"
```

Production replay reports `ACTIVE`, `RECOVERY_REQUIRED`, `FAILED`, or
`INCOMPLETE`. The checked-in `crv-2021-setup-eof.production.txt` fixture is a
sanitized projection of the observed sequence: MFi succeeds, a 503-byte SETUP
arrives, the session ends before Active, and controller restart is required.
## Trace format and safety

Traces start with `CRVTRACE\t1`. Every event stores its timestamp, layer,
direction, logical channel, attributes and Base64URL payload, followed by a
CRC-32. The format is line-oriented so a failed vehicle capture can be kept as
a small regression fixture and reviewed in source control.

Production capture adapters should map the existing USB, NCM, USBMUX,
Lockdown, MFi and RTSP/session boundaries onto `CrvExchange`. Before sharing a
trace, redact pairing records, certificates, private keys, device identifiers
and user data. Private MFi key material must never enter a trace.

## Design references

The implementation borrows architecture patterns, not source code:

- [xcertplay](https://github.com/shilapi/xcertplay): explicit wired-CarPlay layers and Android integration boundaries.
- [LIVI](https://github.com/supermal/livi): replaceable transport/session layers and deterministic native session handling.
- [ocbm](https://github.com/lvalen91/ocbm): channelized envelopes, integrity checks and replayable lifecycle events.

