# kv4p HT Desktop - developer notes

Desktop app for the [kv4p HT](https://kv4p.com) radio (macOS Apple Silicon, Linux
arm64 and x64, including the uConsole and the Cardputer Zero). Features,
install and usage are in the [main README](../README.md); this file covers how
the code is put together.

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
- `ui/ToneHelper.java` - CTCSS tone table
- `aprs/parser/*` and `javAX25/ax25/Packet.java` - APRS parsing and AX.25 frames
  (the firmware does the AFSK1200 modem work itself)

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
| `core/.../DesktopRadioSession.java` | connection, handshake, RX/TX audio, PTT (desktop version of RadioAudioService) |
| `core/.../desktop/AprsController.java` | APRS receive/chat/acks/beacon/digipeat, ported from the Android app |
| `core/.../desktop/Memory*.java` | channel memories, CHIRP/RepeaterBook CSV |
| `app/` | Compose Desktop UI, audio in/out, logging, packaging |
| `app/.../cardputer/` | framebuffer output, evdev keyboard, compact 320x170 UI |
| `app/.../ui/MapPreview.kt`, `OfflineMaps.kt` | map tiles, offline download |

## Install, firmware, troubleshooting

See the [main README](../README.md#install). Install scripts are in `scripts/`;
`scripts/cardputer-test.sh` collects display, input, sound and memory details of
a small Linux device.

## Command-line options

| Option | What |
|---|---|
| `--list-ports` | list serial ports (`*` = looks like a kv4p HT) |
| `--probe <port> [--freq MHz] [--listen] [--seconds N]` | headless handshake check |
| `--framebuffer [--fb /dev/fbN] [--keyboard /dev/input/eventN]` | compact 320x170 UI on a framebuffer (`cardputer/`) |
| `-Dkv4p.audio=alsa\|javasound`, `-Dkv4p.audioTool=pacat\|pw-cat\|alsa`, `-Dkv4p.alsaDevice=…` | sound backend on Linux (`AlsaAudio.kt`) |

## Building

```sh
cd desktop-src
./gradlew :core:test        # protocol/handshake tests, no hardware needed
./gradlew :app:run          # run from source
./gradlew :app:packageDeb   # or packageDmg on macOS
./gradlew -PtargetOs=linux-arm64 :app:packageUberJarForCurrentOS   # Linux arm64 .jar from any OS
```

## Credits

- APRS station symbols: the aprs.fi symbol set by Heikki Hannikainen, OH7LZB,
  <https://github.com/hessu/aprs-symbols/> (`app/src/main/resources/aprs-symbols`).
- APRS map previews: topographic tiles © OpenTopoMap (CC-BY-SA), map data
  © OpenStreetMap contributors, cached in `~/.kv4p-desktop/tiles/opentopomap`.
  APRS settings → "Offline map" downloads 10-30 km around your position (zoom 6-14,
  one tile at a time, as OpenTopoMap asks to avoid mass downloads). Clicking a
  preview opens the position on mapy.com (hiking map); both are set in
  `MapConfig` (`app/.../ui/MapPreview.kt`).
