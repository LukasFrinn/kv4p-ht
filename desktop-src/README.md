# kv4p HT Desktop (Linux & macOS)

Desktop app for the [kv4p HT](https://kv4p.com) radio, connected over USB.
Targets Linux arm64 (Raspberry Pi OS, Debian 13 / ClockworkPi uConsole),
Linux x64 and macOS on Apple Silicon.

**Status: step 1 - receive only.** Connects, does the firmware handshake,
tunes simplex frequencies, plays received audio, shows the S-meter and squelch.
Transmit (PTT), memories, APRS and FreeDV come next.

## How it stays in sync with the Android app

This branch is based on the latest **upstream release** (currently
`v2.0.0.1`), not on upstream `main`. Releases are what people have on their
radios: upstream `main` can speak a newer, unreleased protocol while still
reporting the same firmware version (for example, `main` moved live audio from
Opus to ADPCM after v2.0.0.1 without bumping firmware v17).

The radio logic is not rewritten. `core` compiles these files straight from
`../android-src` (see `core/build.gradle.kts`):

- `Protocol.java` - KISS/kv4p framing, HELLO, device state, flow control
- `RadioModuleController.java` - desired/applied radio state
- `OpusUtils.java` - audio codec (Concentus, pure Java Opus)
- `MicGainBoost.java`, `RadioMode.java`

`android.util.Log`, `androidx.annotation` and usb-serial's
`SerialInputOutputManager` are stubbed so those files compile unchanged.
When upstream publishes a new release, `git merge <tag>` brings its protocol
changes in. The weekly *upstream-check* job in `.github/workflows/desktop.yml`
does that merge in CI, builds, and lists Android files that changed but are
not shared (those may need porting to the desktop UI).

Desktop-only code:

| Path | What |
|---|---|
| `core/.../SerialPortTransport.java` | USB serial via jSerialComm, ESP32 reset |
| `core/.../DesktopRadioSession.java` | connection, handshake, RX (desktop version of RadioAudioService) |
| `app/` | Compose Desktop UI, audio output, logging, packaging |

## Install

Download the build for your system from the repository's **Actions** tab
(latest *Desktop App* run → Artifacts).

**Linux (.deb)**

```sh
sudo apt install ./kv4p-ht-desktop_*.deb
sudo usermod -aG dialout $USER   # serial port access; log out and back in
```

The app is installed to `/opt/kv4p-ht-desktop/bin/kv4p-ht-desktop` and appears in the menu.

**macOS (.dmg)** - the build is not signed. After dragging the app to
Applications, right-click it → *Open* the first time (or run
`xattr -dr com.apple.quarantine "/Applications/kv4p-ht-desktop.app"`).
The board's CP2102N USB chip should appear as `/dev/cu.usbserial-…`; if it
doesn't, install the Silicon Labs CP210x VCP driver.

**Any system with Java 17+** - the `.jar` artifact runs with `java -jar <file>.jar`.

## Firmware

The app needs the firmware from the official v2.0.0.1 release (firmware v17,
the one the Google Play app and the kv4p.com web flasher install). If it reports an old firmware, update it with the Android app or
the web flasher on kv4p.com.

## Troubleshooting

Logs are written to `~/.kv4p-desktop/kv4p.log` (previous run: `kv4p.log.1`)
and shown in the app's log panel.

Headless check, useful over SSH:

```sh
kv4p-ht-desktop --list-ports
kv4p-ht-desktop --probe /dev/ttyUSB0 --freq 146.52 --listen --seconds 30
```

## Building

```sh
cd desktop-src
./gradlew :core:test        # protocol/handshake tests, no hardware needed
./gradlew :app:run          # run from source
./gradlew :app:packageDeb   # or packageDmg on macOS
```
