# Terminal

Terminal is a native Android terminal application built around the **Mysh** shell. It provides an interactive terminal workspace with independent shell sessions, native pseudo-terminals (PTY), and VT terminal emulation through **libvterm**.

## Features

- **Multiple sessions** — create, switch between, and close independent shell sessions.
- **Mysh runtime** — runs the bundled Mysh executable and provides a private Unix-like runtime prefix.
- **Terminal emulation** — libvterm handles VT screen state, cursor movement, text attributes, ANSI colors, and scrollback.
- **PTY support** — interactive input/output, process lifecycle management, and terminal window resizing.
- **Keyboard shortcuts** — a compact terminal key row for Escape, Enter, Control, Alt, Tab, navigation, and common control shortcuts.
- **Android-native UI** — Jetpack Compose Material 3 with the Obsidian Graphite color palette.

## Runtime

The application bundles these native executables for each supported ABI:

| Component | Purpose |
| --- | --- |
| Mysh (`libmysh.so`) | Interactive shell |
| uutils Coreutils (`libcoreutils.so`) | Common command-line utilities |
| MYPK (`libmypk.so`) | Package-management runtime; currently supports `--help` and `--version` only |

The `.so` filenames are packaging conventions. Mysh, Coreutils, and MYPK are standalone executables, not libraries loaded into the Android app process.

The writable application-private runtime prefix is created under the app's `files/` directory. The Mysh executable itself is launched from Android's package-managed native library directory, not copied into writable storage. This follows Android's executable-file restrictions for modern target SDKs.

The runtime prefix is Unix-like, but it is **not** a rooted Linux environment. It does not mount a separate root filesystem, `/proc`, or `/dev`; commands and processes remain subject to the Android application sandbox and UID permissions.

## Platform and build

- Minimum Android version: **Android 8.0 (API 26)**
- Compile / target SDK: **35**
- Languages: **Kotlin and C++**
- UI: **Jetpack Compose**
- Terminal engine: **libvterm 0.3.3**
- Build tools: **JDK 17**, Android NDK **27.2.12479018**, CMake **3.22.1**, Gradle **8.13**
- Supported ABIs: **arm64-v8a** and **x86_64**

Build the debug APK with:

~~~sh
gradle :app:assembleDebug
~~~

Run Android instrumentation tests on a connected device or emulator with:

~~~sh
gradle :app:connectedDebugAndroidTest
~~~

The first native build may require network access to retrieve the pinned libvterm source archive.

## Project status

The project is under active development. PTY startup, input/output, session shutdown, and terminal resize are covered by Android instrumentation tests. Compatibility with full-screen interactive applications (for example, Vim, Nano, htop, and other ncurses programs) is not yet guaranteed and must be validated separately on target devices.

## License

The application is licensed under the **BSD 3-Clause License**. libvterm is distributed under the **MIT License**; its license notice is included in the source tree. Preserve third-party notices when redistributing the application.
