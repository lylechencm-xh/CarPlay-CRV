# CR-V selective port from DiPlay-Geely-Android43 (2026-10-08)

Reference: https://github.com/xikai6282/DiPlay-Geely-Android43

This is **not** a full Geely source or P21/P22 APK merge. Geely's README
states P21's archived source does not exactly match its running APK and
P22 was not publicly released/tested. The implementation below is an
independent, minimal adaptation of documented behavior.

## Adopted (Android 4.2.2 / API17)

- On a bounded H.264 decoder queue overflow, first try `MediaCodec.flush()`
  on the decoder worker, then request a fresh keyframe. A failed flush, input
  error, and output error still use release/recreate. Decoder reconfiguration
  or Surface replacement continues to use release/recreate.
- Raise receive/decode worker priorities to `THREAD_PRIORITY_URGENT_DISPLAY`
  on a best-effort basis. Screen TCP asks for `TCP_NODELAY` and a 512 KiB
  receive buffer, recording actual kernel value.
- For Opus on a CR-V without a usable `audio/opus` MediaCodec, use Concentus
  1.0.2 pure Java per-stream decoder and reuse its PCM sample buffers.
  Hardware codecs remain preferred; AAC-LC/LPCM are unchanged.
- Event/HID socket `TCP_NODELAY` already existed in
  `shared/airplay/AirPlaySession.kt`; deliberately not reimplemented.

## Not ported

- H52 `usbprotect` binder and sysfs/config transition logic.
- Geely Freescale-specific codec, H52 stream numbers 23/11/25, OEM Bluetooth,
  and proprietary Wi-Fi bridge.
- Geely's experimental MFi material, app identity, or API18 minSdk.
- Unpublished P22 smali/DEX: no claim that it is in the public GitHub main.

## Required validation

- Verify `minSdk=17`, package `com.shihab.diplay`, API17 Java method gate,
  unit tests, lint, final DEX and signature in existing CR-V CI.
- Vehicle: sustained H.264 decode, force-keyframe and flush recovery,
  Surface detach/reattach, navigation Opus, AAC music, telephone focus, touch
  latency, USB reconnection, OEM MediaCore auth and MFi ownership.
- New rendering changes do not address the current MFi/iAP blocking stage;
  a successful build does not imply full wired CarPlay is working.
