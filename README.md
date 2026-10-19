# kv4p HT Desktop - Linux & macOS fork

> **This is a fork of [VanceVagell/kv4p-ht](https://github.com/VanceVagell/kv4p-ht).**
> It was created to bring the kv4p HT app to the desktop: **Linux** (including
> arm64 boards such as the Raspberry Pi and the ClockworkPi uConsole) and
> **macOS** on Apple Silicon, connected to the radio over USB.
>
> All credit for the radio, firmware and Android app goes to the original
> kv4p HT project and its contributors. Please report radio, firmware and
> Android issues there.

## What this fork adds

- **`desktop-src/`** - a desktop app built with Kotlin and Compose Multiplatform,
  styled after the Android app.
- The radio logic is **not rewritten**: the desktop app compiles the protocol,
  radio-state and audio-codec code directly from `android-src/`, so fixes made
  upstream flow into the desktop app when this fork is synced.
- **CI** (`.github/workflows/desktop.yml`) builds ready-to-install packages:
  `.deb` for Linux arm64 and x64, `.dmg` for macOS Apple Silicon. A weekly job
  checks that the desktop app still builds against the latest upstream code.

Development happens on the [`desktop-app`](https://github.com/LukasFrinn/kv4p-ht/tree/desktop-app)
branch; see [`desktop-src/README.md`](https://github.com/LukasFrinn/kv4p-ht/blob/desktop-app/desktop-src/README.md)
there for installing, usage and building.

### Status

| Feature | Status |
|---|---|
| USB connection, firmware handshake | ✅ done (testing on hardware) |
| Receive: tuning, audio, S-meter, squelch | ✅ done (testing on hardware) |
| Transmit (PTT, microphone) | 🔜 next |
| Memories / channels | planned |
| APRS | planned |
| FreeDV 2400B digital voice | planned |
| Firmware flashing | planned (use the web flasher on kv4p.com meanwhile) |

Works with the official **v2.0.0.1** release firmware (v17) - the one the Google Play
app and the kv4p.com web flasher install. The desktop branch follows upstream
releases, not upstream `main`.

Licensed under the GNU GPL v3, the same as the original project (see [LICENSE](LICENSE)).

### Downloads

Open the [Actions tab](https://github.com/LukasFrinn/kv4p-ht/actions/workflows/desktop.yml),
pick the latest successful *Desktop App* run and download the artifact for your
system from the *Artifacts* section.

- PCB files: `pcb/`
- 3D-printed cases: `3d-print-case/`
- Common problems: [FAQ.md](FAQ.md)
