# kv4p HT Desktop - macOS & Linux fork

> **This is a fork of [VanceVagell/kv4p-ht](https://github.com/VanceVagell/kv4p-ht)
> that builds desktop apps for macOS (Apple Silicon) and Linux (arm64 and x64).**
> The radio connects over USB, as with the Android app. It runs even on small
> handhelds: the **ClockworkPi uConsole** and the **M5Stack Cardputer Zero**
> (on its built-in 320x170 screen).
>
> All credit for the radio, firmware and Android app goes to the original
> kv4p HT project and its contributors. Please report radio, firmware and
> Android issues there.

This `desktop-app` branch is where the desktop app lives (`desktop-src/`).
Developer notes - how it stays in sync with upstream, code layout, building -
are in [`desktop-src/README.md`](desktop-src/README.md).

## Features

- **Radio** - tune by frequency or memory, received audio, S-meter, squelch,
  volume, high/low power. Transmit with the PTT button, the space bar or the
  radio's own PTT button. TX is only allowed inside the selected band plan
  (IARU Region 1 by default: 144-146 / 430-440 MHz) and stops after 3 minutes,
  like the Android app.
- **Memories** - name, repeater offset, TX/RX CTCSS tones, groups, "skip during
  scan", scanning. Import CHIRP or RepeaterBook CSV, export CHIRP CSV.
- **APRS** - received positions, messages, weather and objects with station
  symbols and distance from you; chat with automatic acks and delivery marks;
  position beacon now or every 5-60 minutes (Maidenhead locator, lat/lon, or
  this computer's location); optional WIDE1-1 fill-in digipeater.
- **Maps** - a small topographic map preview (OpenTopoMap) next to every APRS
  station; a click opens the position on mapy.com. The area around you can be
  downloaded for offline use.
- **Compact mode** for tiny screens without a desktop (Cardputer Zero): a
  keyboard-only 320x170 UI drawn straight to the framebuffer.

| Feature | Status |
|---|---|
| USB connection, firmware handshake | ✅ macOS, Linux |
| Receive and transmit | ✅ macOS, Linux (uConsole) |
| APRS, maps, offline maps | ✅ macOS (Linux builds, less tested) |
| Memories, scanning | ✅ macOS, Linux |
| Cardputer Zero compact mode | 🧪 being tested (screen, keys, PTT work; sound in progress) |
| FreeDV 2400B digital voice | waits for the next upstream firmware release |
| Firmware flashing | planned (use the web flasher on kv4p.com meanwhile) |

Works with the official **v2.0.0.1** release firmware (v17) - the one the Google Play
app and the kv4p.com web flasher install. If the app reports an old firmware,
update it with the Android app or the web flasher.

## Install

Builds come from this repository's
[Actions tab](https://github.com/LukasFrinn/kv4p-ht/actions/workflows/desktop.yml)
(latest *Desktop App* run → *Artifacts*). The install scripts fetch them for you;
they need the GitHub CLI once.

**Linux** (Raspberry Pi OS, Debian, Ubuntu, uConsole, Cardputer Zero) -
`sudo apt install gh && gh auth login`, then (again for every update):

```sh
curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/install-linux.sh | bash
```

It picks the arm64 or x64 build, installs it to `/opt/kv4p-ht-desktop` (menu
entry included) and adds you to the `dialout` group for the USB serial port
(log out and back in once). By hand: `sudo apt install ./kv4p-ht-desktop_*.deb`.

**macOS** (Apple Silicon) - `brew install gh && gh auth login`, then:

```sh
curl -fsSL https://raw.githubusercontent.com/LukasFrinn/kv4p-ht/desktop-app/desktop-src/scripts/install-macos.sh | bash
```

The script avoids the "unidentified developer" warning. The `.dmg` from Actions
works too: the app is ad-hoc signed but not notarized, so after dragging it to
Applications run `xattr -dr com.apple.quarantine "/Applications/kv4p-ht-desktop.app"`.
The first PTT asks for microphone access (again after updates, as each build is
newly signed). "Use this computer's location" needs Wi-Fi turned on and asks for
Location Services permission. The radio's CP2102N should appear as
`/dev/cu.usbserial-…`; if not, install the Silicon Labs CP210x VCP driver.

**Any system with Java 17+** - the `.jar` artifact runs with `java -jar <file>.jar`.

### ClockworkPi uConsole

Install with the Linux script. On screens shorter than 900 px the window opens
maximized, in a landscape layout with the memories beside the radio. Scrollbars
can be dragged (no scroll wheel needed), and the space bar is PTT unless you are
typing in a text field.

### M5Stack Cardputer Zero

The Linux script also adds **kv4p HT** to APPLaunch, the launcher on the built-in
screen. That entry runs the app with `--framebuffer`: a compact UI drawn straight
to `/dev/fb0` with keys read from `/dev/input` (no X11/Wayland) and a small JVM
heap. Sound goes through PipeWire.

| Key | Action |
|---|---|
| Space (hold) | PTT |
| Tab | Radio → Memories → APRS |
| Fn + F / X, Fn + Z / C | up / down, left / right (memory, list, 12.5 kHz step) |
| digits, Enter | tune a frequency |
| `[` `]`, Fn + S / D | squelch, volume |
| Enter, R, M, B | APRS: map, reply, message, beacon |
| H | all keys |
| Esc Esc, Ctrl+Q | quit |

It connects by itself when the radio is plugged in. Settings, memories, the APRS
station and offline maps are shared with the full app (for example on HDMI),
which is where they are edited.

## Troubleshooting

Logs are in `~/.kv4p-desktop/kv4p.log` (previous run: `kv4p.log.1`) and in the
app's Debug window. Headless checks, handy over SSH:

```sh
kv4p-ht-desktop --list-ports
kv4p-ht-desktop --probe /dev/ttyUSB0 --freq 146.52 --listen --seconds 30
```

## License and credits

GNU GPL v3, the same as the original project (see [LICENSE](LICENSE)).
APRS symbols: aprs.fi set by Heikki Hannikainen, OH7LZB. Map tiles © OpenTopoMap
(CC-BY-SA), map data © OpenStreetMap contributors. Details in
[`desktop-src/README.md`](desktop-src/README.md#credits).

From the original project: PCB files in `pcb/`, 3D-printed cases in
`3d-print-case/`, common problems in [FAQ.md](FAQ.md).
