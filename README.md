# SNEScend

A Super Nintendo (SNES / Super Famicom) emulator for Android, built on the SNES core of
[MesenCE](https://github.com/nesdev-org/MesenCE), with the frontend of
[Ametrine](https://github.com/kiraponz/Ametrine).

## Features

- Game library from a folder you pick (`.sfc`, `.smc`, `.swc`, `.fig`, `.bs`, `.st`, and `.zip` or
  `.7z` holding one), with favorites, search and play time
- Every enhancement chip MesenCE emulates: SA-1, Super FX, DSP-1 to DSP-4, Cx4, S-DD1, SPC7110,
  ST010/011/018, OBC-1, Satellaview, Sufami Turbo (the DSP and ST chips, the Satellaview and the
  Sufami Turbo need their firmware, which you add yourself)
- Global settings with per-game overrides: aspect ratio, sharp or integer scaling, NTSC and
  smoothing filters, scanlines, run-ahead, overclocking
- Touch controls you can rearrange and resize, and physical controllers
- Battery saves written a second after the game saves, safely (a crash leaves the old save or
  the new one), with the last three versions kept; a copy goes to `snes_savedata` in the game
  folder or to a folder you pick, named after the game, for other emulators
- Save states, rewind, fast forward, Game Genie and Pro Action Replay cheats, memory search
- Backup and restore of settings and saves
- Optional: ask Google Gemini about the current screen

## Building

Requires Android Studio (for its JDK) and the NDK 29.0.14206865.

```bash
git submodule update --init
./gradlew :app:assembleDebug
```

From the command line, point `JAVA_HOME` at Android Studio's JDK (on macOS,
`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).

## License

SNEScend is free software under the [GNU General Public License v3.0](LICENSE). MesenCE is
GPL-3.0 too; the licenses of the code it carries are listed in the app (About, Open source
licenses).

SNEScend includes no games or firmware. It is not affiliated with Nintendo.
