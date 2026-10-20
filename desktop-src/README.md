# kv4p HT Desktop (Linux & macOS)

Desktop app for the [kv4p HT](https://kv4p.com) radio, connected over USB.
Targets Linux arm64 (Raspberry Pi OS, Debian 13 / ClockworkPi uConsole),
Linux x64 and macOS on Apple Silicon.

**Status: step 4 - receive, transmit, APRS, memories and scanning.** Connects, does the firmware
handshake, tunes simplex frequencies, plays received audio, shows the S-meter
and squelch, and transmits from the microphone: hold the PTT button, the space
bar, or the PTT button on the radio. TX is only allowed inside the selected
band plan (IARU Region 1 by default: 144-146 / 430-440 MHz) and stops
automatically after 3 minutes, like the Android app. 

**APRS** (APRS tab): received positions, messages, weather and objects with
distance from you; chat with automatic acks and delivery marks; position
beacon now or every 5-60 minutes from a Maidenhead locator or lat/lon (the
desktop has no GPS); optional WIDE1-1 fill-in digipeater. One click tunes
144.800 MHz.

**Memories** (Radio tab → Memories): channels with name, repeater offset,
TX/RX CTCSS tones, group and "skip during scan", same fields as the Android
app. Click to tune, scan the selected group (stops on activity, PTT stops the
scan on that channel). Stored as CHIRP CSV in `~/.kv4p-desktop/memories.csv`;
import CHIRP or RepeaterBook CSV, export CHIRP CSV.

On macOS the first transmit asks for microphone permission. If you denied it,
re-enable kv4p-ht-desktop in *System Settings > Privacy & Security > Microphone*
(builds before the ad-hoc signing fix never appeared there and recorded silence).

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
| `app/` | Compose Desktop UI, audio output, logging, packaging |

## Install

Download the build for your system from the repository's **Actions** tab
(latest *Desktop App* run → Artifacts).

**Linux (.deb)**

Recommended: install and update with the script (run it again for every
update, no need to uninstall first; needs the GitHub CLI once:
`sudo apt install gh && gh auth login`):

```sh
curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/install-linux.sh | bash
```

It picks the arm64 or x64 build, installs it with apt and adds you to the
`dialout` group (log out and back in once after that). Or by hand:

```sh
sudo apt install ./kv4p-ht-desktop_*.deb
sudo usermod -aG dialout $USER   # serial port access; log out and back in
```

The app is installed to `/opt/kv4p-ht-desktop/bin/kv4p-ht-desktop` and appears in the menu.

**macOS** - recommended: install and update with the script, which avoids the
"unidentified developer" warning (needs the GitHub CLI once: `brew install gh && gh auth login`):

```sh
curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/install-macos.sh | bash
```

Or download the `.dmg` from Actions by hand; the app is ad-hoc signed but not
notarized, so after dragging it to Applications run
`xattr -dr com.apple.quarantine "/Applications/kv4p-ht-desktop.app"` (or
right-click → *Open* the first time). Removing the warning completely needs
an Apple Developer ID (paid) for signing and notarization.

The first PTT asks for microphone access. Because each build has a new ad-hoc
signature, macOS may ask again after an update.
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
