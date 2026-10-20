# Clamshell DS

A Nintendo DS / DSi emulator for Android handhelds, built for dual-screen clamshell devices like the
**Anbernic RG DS** and the **AYN Thor**. It works on regular single-screen devices too.

- **Looks like a DSi.** It opens to a DSi-style home menu. The top screen shows your name, the date, a
  ticking clock, your battery level and the selected game's banner. The bottom screen has the DSi icon
  carousel with a scroll bar and the real icons from your games. The accent colour follows your
  DS/DSi profile colour.
- **Finds your games automatically.** On launch it scans `ClamshellDS/roms`, `Roms/NDS`, `ROMs/ds`,
  `Download` and similar folders on internal storage and SD cards. You can add more folders in Settings.
- **Uses both screens.** On the RG DS and AYN Thor, the DS top screen goes on the top panel and the
  bottom (touch) screen on the bottom panel. On one-screen devices the two screens are stacked or
  placed side by side.
- **Full DSi mode.** Add dumps from your own DSi and it boots as a DSi, including the real DSi Menu
  ("System Menu" icon).
- **Can be your home screen.** Turn on *Settings → Use Clamshell DS as home screen* and the handheld
  boots straight into the DSi menu.
- Save states (5 slots, plus quick save/load), fast forward, a "resume where you left off" option,
  button remapping, and automatic battery-backed saves.

Emulation is provided by [melonDS](https://melonds.kuribo64.net) 1.1, with its ARM64 JIT and
threaded software renderer enabled.

## Install

1. Open the **Releases** page of this repository and download `ClamshellDS.apk` from the
   **latest** release. GitHub Actions rebuilds it on every push.
2. Open the APK on your handheld and allow installs from that source.
3. Start **Clamshell DS** and tap **Allow** when it asks for file access. It needs "All files access"
   to find and load your games.

## Where things go

```
/storage/emulated/0/ClamshellDS/
├── roms/     put .nds files here (other common ROM folders are scanned too)
├── saves/    in-game saves (.sav). An existing .sav next to a ROM is imported automatically
├── states/   save states
└── bios/     optional BIOS / firmware / NAND dumps (see bios/README.txt)
```

### DSi mode (optional)

DS games run without any extra files. For DSi mode, put these dumps from **your own** consoles in
`ClamshellDS/bios/`, then pick *Settings → Console mode → Nintendo DSi*:

| File | What |
|---|---|
| `bios9.bin`, `bios7.bin` | DS ARM9 / ARM7 BIOS |
| `dsi_bios9.bin`, `dsi_bios7.bin` | DSi ARM9i / ARM7i BIOS |
| `dsi_firmware.bin` | DSi firmware |
| `dsi_nand.bin` | DSi NAND |

See [dsi.cfw.guide](https://dsi.cfw.guide) for how to dump them with GodMode9i. *Settings → BIOS / DSi
files* shows which files were found.

## Controls

| Button | DS / action |
|---|---|
| A B X Y, D-pad / left stick, L1 R1, Start, Select | same DS buttons |
| L2 | fast forward (hold) |
| L3 / R3 | quick save / quick load |
| Back / Home-menu key | pause menu (save states, reset, filter, quit) |
| Touch on the bottom screen | DS touchscreen |

Use *Settings → Controls → Remap buttons* to change any of these.

In the home menu: use the D-pad or swipe to move, A or Start (or tap the centre icon) to launch,
L/R to jump, X to rescan for games, and Y/Select to open Settings.

## Tuning for the RG DS

The RG DS has an RK3568 chip and two 640×480 screens. The defaults are already set for it:

- **JIT** on, **threaded 3D** on, **audio sync** on.
- **Screen filter: Sharp (2x).** 640×480 is a 2.5× scale of the DS screen, so the image is scaled up
  2× with sharp pixels and then smoothed the rest of the way. Choose *Smooth* for the lowest CPU cost.
- The first time the menu opens on a two-screen device, tap whichever screen is physically on top.
  The app remembers it. On the RG DS, Android's main display is the bottom panel, and the app
  already accounts for that. You can change it later in *Settings → Swap physical screens*, or
  with *Swap screens* in the in-game menu (also remembered).
- **DS mode is faster than DSi mode.** Use DSi mode only for DSi-enhanced/DSiWare titles or the DSi
  Menu.
- Heavy 3D games can run below full speed on this chip. Hold L2 to fast-forward through slow parts.

## Building

```
git clone --recursive <this repo>
./gradlew assembleRelease
```

You need JDK 17 and the Android SDK (AGP downloads the NDK and CMake). Only arm64-v8a is built.

## Legal

Clamshell DS is free software under the GNU GPL v3 (see `LICENSE`), as is the melonDS core it uses.
It ships no Nintendo code or games. Use games and system files that you dumped yourself. It is not
affiliated with Nintendo.
