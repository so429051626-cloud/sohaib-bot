<p align="center">
  <img src="branding/madix-beam-icon-512.png" width="128" alt="Madix Beam icon" />
</p>

<h1 align="center">Madix Beam</h1>

<p align="center"><strong>Scan once, transfer at Wi-Fi Direct speed — encrypted, with no internet.</strong><br/>
امسح مرة واحدة وانقل بسرعة Wi-Fi Direct، بتشفير تام ودون إنترنت.</p>

---

## Overview

Madix Beam moves photos, videos, documents and text between Android devices. It offers two
transfer methods, both end-to-end encrypted and neither using the internet:

1. **Wi-Fi Direct (default, very fast).** The receiver hosts a Wi-Fi Direct group (5 GHz when
   available) and displays a QR invitation containing the group credentials, its listening port
   and an ephemeral P-256 public key. The sender scans it, joins the group without any system
   dialog (Android 10+), and streams the files over an encrypted TCP link at the full speed of
   the radio link. Devices on the same Wi-Fi network connect instantly over that network.
2. **QR codes (fully optical).** Animated QR codes carry the data from screen to camera with a
   rateless fountain code, for situations where no radio may be used.

| Capability | Implementation |
|---|---|
| Direct link | Wi-Fi Direct group with explicit credentials, LAN fallback, 256 KiB AES-256-GCM records, 1 MiB socket buffers |
| Link security | Ephemeral ECDH P-256 bound to the scanned invitation, HKDF-SHA-256 per-direction keys, encrypted acknowledgement before any content is sent |
| Optical transport | Base45-encoded alphanumeric QR frames with CRC-32, up to two codes per frame |
| Loss tolerance | Systematic fountain code with incremental GF(2) Gaussian elimination |
| Integrity | Authenticated records plus a SHA-256 digest for every file, confirmed back to the sender |
| Privacy | No servers, no analytics, private storage excluded from backups, optional app lock and screenshot protection |
| Languages | English and Arabic (formal), with full right-to-left support |

## Transfer modes

1. **Device pairing (recommended).** The receiver displays a pairing code containing an
   ephemeral public key. The sender scans it, derives a shared key and starts broadcasting.
   Both devices display the same verification code, which rules out interception.
2. **Password.** The payload key is derived from a password shared in person.
3. **Unencrypted.** Any receiver can read the content; integrity is still verified.

## Speed profiles

| Profile | Codes on screen | Payload per code | Error correction | Default rate | Nominal throughput |
|---|---|---|---|---|---|
| Reliable | 1 | 360 B | M | 10 fps | 3 KB/s |
| Balanced | 1 | 800 B | L | 15 fps | 11 KB/s |
| Fast | 2 | 900 B | L | 15 fps | 24 KB/s |
| Turbo | 2 | 1,100 B | L | 20 fps | 40 KB/s |

The frame rate can be adjusted live during a transmission. The *Instant* photo quality
re-encodes a typical 3–5 MB photo as a WebP image of roughly 60 KB, which the Turbo profile
transfers in about two seconds under good conditions. Optical transfer remains far slower than
radio links: large videos take minutes.

## Architecture

```
MadixBeam/
├── core/protocol         Pure Kotlin/JVM library, fully unit tested
│   ├── codec             Base45 (RFC 9285), bounds-checked byte I/O
│   ├── frame             Header, data and pairing frames; QR text wrapping
│   ├── fountain          Segment layout, repair planner, encoder, GF(2) decoder
│   ├── crypto            HKDF, PBKDF2, ECDH P-256, chunked AES-GCM, verification codes
│   ├── container         Manifest format, verified unpacking, file-name sanitising
│   └── session           Transfer preparation, broadcast scheduler, receiver engine
└── app                   Android application (Jetpack Compose, Material 3)
    ├── camera            CameraX preview with the on-device ML Kit QR detector
    ├── qr                ZXing QR rendering at one pixel per module
    ├── data              Settings (DataStore), history (atomic JSON), storage, sources
    ├── security          App lock state and biometric prompt
    └── ui                Splash, onboarding, lock, home, send, receive, history, settings
```

### Wire format

```
frame    := 0xB1 | type | session(4) | body | crc32(4)
header   := mode | flags | cipherLength | blockSize | totalBlocks | segmentBlocks | chunkSize
            | salt(16) | noncePrefix(7) | iterations | senderKey | receiverKeyId(8) | padding
data     := segment(2) | symbol(4) | payload(blockSize)
pairing  := receiverKey | deviceName
qr text  := "MDX1" Base45(frame)
```

Every tenth frame is a header. The remaining frames cycle through the source blocks,
interleaved across segments, with one repair symbol every four slots. A receiver can join at
any time and completes after collecting slightly more frames than there are blocks; the test
suite measures 0–25 % overhead at up to 50 % frame loss.

## Building

Requirements: JDK 17 and the Android SDK (API 35).

```bash
./gradlew :core:protocol:test        # protocol test suite
./gradlew :app:assembleRelease       # release APK
```

Release signing is read from `keystore.properties` (never committed):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

The GitHub Actions workflow `.github/workflows/android.yml` runs the tests, assembles the
APKs and signs the release build. It uses the repository secrets
`MADIX_KEYSTORE_BASE64`, `MADIX_KEYSTORE_PASSWORD`, `MADIX_KEY_ALIAS` and
`MADIX_KEY_PASSWORD` when present; otherwise the build is signed with a one-time key.
Running the workflow manually with *publish_release* enabled attaches the APK to a GitHub
release.

## Requirements

- Android 8.0 (API 26) or later; Wi-Fi Direct transfers require Android 10 or later
- A rear camera on the scanning device

## Licence

© 2026 Madix. All rights reserved. Third-party components are listed in the application under
*About Madix Beam*. IBM Plex Sans Arabic is distributed under the SIL Open Font License 1.1
(`branding/FONT-LICENSE-OFL.txt`).
