# VVFile

[中文](README.md) · English

A dual-pane file manager for Android. v1.0.

![Main screen](docs/images/main-ui.png)

One pane for folders, one for files. The pane you touch becomes the active pane.

## What it does

**Browsing**

- Dual pane / single pane, switch any time; panes activate on touch
- Tap the path bar to jump anywhere, with path suggestions
- Sort by name / size / type / modified time, folders first, hidden files toggle, extension filter
- Pull to refresh, back and forward history
- Thumbnails for images and videos in the list (memory + disk cache; the type icon stays until a thumbnail is ready)
- 17 file-type icons: image, video, audio, APK, archive, text, PDF, Word, spreadsheet, slides, source code, config, script, database, font, and so on
- "Locate in folder" from other apps jumps straight to the file

**File operations**

- Copy, move, delete, rename, batch rename, new folder / new file
- Zip and unzip; open a zip and browse inside it without extracting first
- Properties for a single item or a multi-selection
- Permission editor: 9-cell rwx grid, real permissions for shell scripts
- Share, open with
- NIO on the file-system layer; optional root channel reaches `/data`, `/system` and friends

**Viewers**

- Built-in text editor with syntax highlighting and chunked reading for large files
- Built-in image viewer, video player, audio player (minimises to a bottom bar)
- Built-in terminal: real PTY with VT emulation, sticky modifier keys, adjustable font size
- Installed apps list: package, version, size, data directory, one tap to browse it

## Install

```powershell
adb install -r app\build\outputs\apk\release\app-release.apk
```

- Package: `com.vvfile.app`
- Minimum: Android 8.0 (API 26), targetSdk 36
- Stack: Kotlin · Jetpack Compose · Material 3

## Build from source

```powershell
.\gradlew.bat :app:assembleRelease
# output: app\build\outputs\apk\release\app-release.apk
```

Unit tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## On reverse engineering

This app is about managing files on a phone. Decompiling APKs, unpacking packers and
repacking builds are far more comfortable on a PC, especially with AI tooling: bigger
screen, proper scripting, a complete toolchain. Doing that on a phone is a toy, so VVFile
stays where it belongs: file management.

## License

**Source-available, non-commercial.** Read it, modify it, use it, share it — but no
commercial use of any kind (selling, bundling into commercial products, paid distribution,
or offering paid services built on it). Full terms in [LICENSE.md](LICENSE.md).

## Author and disclaimers

![Dafeiyu](docs/brand/dfy.png)

- Developer: **大肥鱼 (Dafeiyu)**, model `DeepSeek-v4.1-flash-1017` & **VVLing**
- Tooling: **dsh** (DeepSeek Harness)
- The whole codebase was written by AI; the human only gave suggestions and did acceptance checks
- No line-by-line human review. Provided "as is"; the author takes no responsibility for
  any consequences of using it
- Assets: Android system resources plus locally generated Qwen-Image 2.1 artwork
  (app icon, file-type icons)
- UI inspiration: **MT Manager**; no code, icons or assets from it are used, and the
  interface and colour scheme are an independent design

## Credits

| Project | Used for |
| --- | --- |
| [Termux](https://github.com/termux/termux-app) | Terminal: VT emulation, PTY, `libtermux.so` (Apache-2.0, see [NOTICE.md](NOTICE.md)) |
| [MT Manager](https://mt2.cn/) | Inspiration for the dual-pane workflow |
| [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) | The developer persona "Dafeiyu" — the picture above comes from that project |

