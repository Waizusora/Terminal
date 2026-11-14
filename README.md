# Terminal

Terminal is a native Android terminal application that runs the Mysh executable from its private application filesystem.

## Project identity

- Application name: Terminal
- Application ID: `com.waizusora.terminal`
- Minimum Android version: Android 8.0 (API 26)
- Language: Kotlin and C++
- UI toolkit: Jetpack Compose
- Shell runtime: Mysh
- Terminal engine: libvterm 0.3.3
- Application license: BSD 3-Clause

## User interface

The interface is focused on the terminal workspace. The top bar does not display an application title; it places sequentially numbered session tabs from the left and keeps the plus control at the right edge. Tab numbers are display positions derived from the current session order, while each session retains a separate stable internal ID for its process, PTY, and emulator state. Closing a session compacts the visible numbering without restarting or renaming the remaining sessions. When the final session is closed, the next newly created session is displayed as session 1. The session strip scrolls horizontally when its tabs exceed the available width, while the plus control remains fixed. Each session tab uses a consistent 96 dp by 40 dp rounded rectangle with a centered session number and a separate close control on the right. Closing a session requires confirmation before its shell process is terminated. Selecting a session switches the active process without terminating the other sessions. Tapping the terminal viewport requests input focus and opens the Android software keyboard. A compact terminal key row provides Escape, Enter, Control, Alt, Tab, cursor navigation, Home, End, Delete, and Control shortcuts for C, D, Z, and L. The top-level content applies the Android status-bar inset so the session tabs and fixed plus control remain below system UI.

## UI color system

The application interface uses the Obsidian Graphite palette: background `#101114`, secondary surface `#191C22`, inactive session `#242830`, active session `#343B48`, shortcut surface `#20242C`, accent `#8AB4F8`, primary text `#E6E8ED`, secondary text `#9299A5`, and destructive actions `#E57373`. These colors apply to application chrome and controls. Terminal-emulator ANSI and truecolor output remains mapped from the shell's color sequences and is not replaced by the application palette.


## Launcher icon

The Android launcher icon uses an Obsidian Graphite adaptive icon with a centered `>_` terminal prompt. Its graphite background, subtle border, and light foreground symbol are defined in Android resources under `app/src/main/res`. Both standard and round adaptive icon resource names are configured in the application manifest.

## Terminal emulation

Each session owns an independent libvterm instance. Mysh output bytes are passed to the emulator, which maintains a VT screen grid. The Compose renderer reads the cell grid and renders Unicode text, foreground and background colors, bold, italic, underline, strike-through, and reverse video. The cursor position and visibility are obtained from libvterm and rendered as a visible cursor bar in the active terminal viewport.

The application advertises the `xterm-256color` terminal profile and `truecolor` through the child process environment. Cursor movement and cursor visibility are tracked through libvterm screen callbacks and exposed to the Compose renderer. The native engine is built from the upstream libvterm 0.3.3 release archive, retrieved from the MacPorts distfiles mirror. CMake verifies the downloaded archive using its pinned SHA-256 digest.

### PTY process architecture

Each Mysh session uses an independent native pseudo-terminal (PTY) master/slave pair. The child process starts a new session, acquires the PTY slave as its controlling terminal, and receives the slave as standard input, output, and error. The native backend supports PTY input/output, an initial 24-by-80 window size, explicit resize requests, and process-group hangup during session shutdown. The previous Java `ProcessBuilder` pipe transport has been removed.

The PTY backend is necessary for full-screen interactive applications, but compatibility is not yet verified. Actual behavior depends on Mysh, terminal escape-sequence support, keyboard translation, terminal capabilities data (terminfo), and device testing. Vim, Nano, htop, and ncurses applications must not be described as supported until tested on an Android device.

The terminal grid is sized from the available Compose viewport using the renderer's monospace cell metrics. The calculated row and column counts are applied to each session's libvterm grid and PTY window size (`TIOCSWINSZ`), allowing foreground terminal applications to receive the kernel-generated `SIGWINCH` notification when the available area changes. All sessions are kept at the same viewport dimensions, including inactive sessions. The primary screen's scrollback is retained through libvterm screen callbacks and rendered above the active grid in a vertically scrollable viewport. History is bounded to 2,000 lines and 200,000 cells per session; older lines are discarded when either limit is exceeded. The viewport follows new output while already positioned at the bottom, but preserves the user's position when they have scrolled into history. Cursor drawing, mouse reporting, clipboard integration, and broader xterm extensions remain future work.

## Bundled Mysh runtime

The Android CI workflow checks out a pinned Mysh source revision, cross-compiles standalone release executables for `arm64-v8a`, `armeabi-v7a`, and `x86_64`, and packages them under the Android native-library layout as `lib/<abi>/libmysh.so`. The `.so` suffix is a packaging convention: Mysh remains a standalone PIE executable and is launched as a child process, not loaded into the application process with `System.loadLibrary`. Android Package Manager extracts the signed executable into `ApplicationInfo.nativeLibraryDir`, the platform-managed executable-code location. Do not copy the executable into writable `filesDir`; Android 10 and newer block `execve()` from the writable app home directory for apps targeting API 29 or higher. The shell binary is updated when the application package is updated. The bundled shell source revision is pinned in `.github/workflows/android.yml`; update that revision deliberately when integrating a newer Mysh release.

## Private filesystem

Runtime directory creation, ABI-specific Mysh installation, and executable verification run on the session worker thread, keeping filesystem initialization and executable copying off the UI thread. Before creating the interactive PTY, Terminal launches the packaged shell with `--version` and requires a successful exit within five seconds. This verifies that Android can load and execute the packaged binary for the current device ABI. Startup diagnostics, including the executable path and underlying process-launch error chain, are written into the libvterm screen grid rather than rendered in a separate UI text panel. Diagnostic line endings are passed as actual newline characters so the output follows terminal row and scrollback behavior.

The application uses its private files directory as the runtime prefix:

```text
files/
├── bin/             # Primary executable commands installed by the user
├── sbin/            # Administrative commands
├── lib/             # Shared libraries
├── libexec/         # Private executable helpers
├── include/         # Development headers
├── share/           # Shared application data
│   └── terminfo/    # Terminal capability database
├── etc/             # Runtime configuration
├── home/user/       # User home directory
├── tmp/             # Temporary files
├── var/             # Mutable runtime data
├── run/             # Runtime state
├── opt/             # Optional packages
├── dev/             # Private placeholder directory; no device nodes are created
├── proc/            # Private placeholder directory; no procfs is mounted
└── usr/
    ├── bin/         # Additional executable commands
    ├── sbin/        # Additional administrative commands
    ├── lib/         # Additional shared libraries
    ├── libexec/     # Additional private executable helpers
    ├── include/     # Additional development headers
    └── share/
        └── terminfo/ # Additional terminal capability data
```

The packaged Mysh executable is expected at `ApplicationInfo.nativeLibraryDir/libmysh.so`. The writable `files/` hierarchy is reserved for runtime data and user-managed files. The child process receives `PREFIX`, `HOME`, `PWD`, `SHELL`, `PATH`, `LD_LIBRARY_PATH`, `TMPDIR`, `TERMINFO`, and `TERMINFO_DIRS` values rooted in this private prefix. Mysh resolves command names by searching the `PATH` entries in order (`bin`, `sbin`, `usr/bin`, and `usr/sbin`); commands containing a slash are treated as explicit paths. Missing commands use status 127, while a found but non-executable command uses status 126. These lookup rules do not constitute a security boundary: explicit paths and process permissions remain subject to Android's application UID and filesystem policy. Shared library lookup searches `lib` and `usr/lib`. Terminfo data can be supplied in either `share/terminfo` or `usr/share/terminfo`. The directory layout is initialized and validated when a shell session starts.

This Unix-like hierarchy is an application-managed package prefix, not the Android root filesystem. The private `dev` and `proc` directories are placeholders only: the application does not mount device or proc filesystems there, and their presence does not expose kernel device nodes or process metadata. Runtime access remains subject to Android application sandbox permissions. Directory names alone do not provide POSIX behavior; shell semantics, executable ABI compatibility, runtime libraries, utilities, and operating-system interfaces must also be implemented and validated.

## Native dependencies and licensing

- libvterm 0.3.3 is distributed under the MIT License.
- The libvterm license text is included at `app/src/main/cpp/third_party/libvterm-LICENSE`.
- The libvterm release archive is fetched by CMake and verified against a fixed SHA-256 digest.
- The application remains licensed under BSD 3-Clause.
- No GPL dependency is intentionally included.

Third-party notices and license obligations must be preserved when redistributing the application.

## Build

Build the debug APK with:

```sh
gradle :app:assembleDebug
```

The project requires JDK 17, Android SDK 35, Android NDK 27.2.12479018, and CMake 3.22.1. CMake downloads the pinned libvterm source archive during native configuration, so the first build requires network access.

## CI and signed releases

GitHub Actions builds the debug APK for pushes to `main`, pull requests targeting `main`, and manual workflow dispatches. The debug APK is retained as a workflow artifact.

Signed release builds are optional. Configure all of the following repository Actions secrets to enable them:

- `ANDROID_KEYSTORE_BASE64`: the JKS/keystore file encoded as a single-line Base64 string.
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password.
- `ANDROID_KEY_ALIAS`: the signing key alias.
- `ANDROID_KEY_PASSWORD`: the signing key password.

For example, encode a keystore locally with `base64 -w 0 release.jks` on Linux. Do not commit the keystore or its passwords to the repository. The root `.gitignore` excludes common Java keystore formats and local signing material.

When all four secrets are present, the workflow decodes the keystore into the runner's temporary directory and builds `app-release.apk`. When one or more secrets are missing, signed release packaging is explicitly skipped while the debug CI build continues. A GitHub Release is published only for a pushed version tag matching `v*` and only when the signed APK was successfully built. The release receives the signed APK as an asset.

Create a version tag such as `v1.0.0` to trigger release packaging. The workflow does not create a tag automatically.

## Runtime status

The application manages independent Mysh processes, native PTY instances, and terminal emulator state for each session. The terminal engine parses VT control sequences and the renderer applies cell attributes and colors. The Mysh executable must be supplied for the target Android ABI before shell sessions can execute commands. The application reports a clear installation message when the executable is absent.

The native PTY and terminal engine integration has not been considered verified until the Android CI build and on-device behavior have both been checked.

## Security boundary

The configured PATH limits ordinary command lookup to the application's `bin` and `usr/bin` directories, but it is not a hard execution boundary. Mysh must independently validate canonical executable paths and reject absolute paths or traversal outside the private filesystem. Processes launched under the application UID retain that UID's Android permissions; this design is not an operating-system sandbox. Do not install untrusted executables.
