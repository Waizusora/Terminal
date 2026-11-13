# Terminal

Terminal is a native Android terminal application that runs the Mysh executable from its private application filesystem.

## Project identity

- Application name: Terminal
- Application ID: \`com.waizusora.terminal\`
- Minimum Android version: Android 8.0 (API 26)
- Language: Kotlin and C++
- UI toolkit: Jetpack Compose
- Shell runtime: Mysh
- Terminal engine: libvterm 0.3.3
- Application license: BSD 3-Clause

## User interface

The interface is intentionally limited to a terminal workspace. The top bar contains the title "Terminal" and a plus control for creating sessions. Sessions are identified by sequential numeric labels. The close control on each session terminates that session. A compact terminal key row provides Escape, Control, Alt, Tab, cursor navigation, Home, End, Delete, and Control shortcuts for C, D, Z, and L. Selecting a session switches the active process without terminating the other sessions.

## Terminal emulation

Each session owns an independent libvterm instance. Mysh output bytes are passed to the emulator, which maintains a VT screen grid. The Compose renderer reads the cell grid and renders Unicode text, foreground and background colors, bold, italic, underline, strike-through, and reverse video.

The application advertises the \`xterm-256color\` terminal profile and \`truecolor\` through the child process environment. The native engine is built from the upstream libvterm 0.3.3 release archive. CMake verifies the downloaded archive using its pinned SHA-256 digest.

The terminal grid is currently fixed at 24 rows by 80 columns. Scrollback storage, cursor drawing, mouse reporting, clipboard integration, dynamic terminal resizing, and broader xterm extensions remain future work.

### PTY process architecture

Each Mysh session uses an independent native pseudo-terminal (PTY) master/slave pair. The child process starts a new session, acquires the PTY slave as its controlling terminal, and receives the slave as standard input, output, and error. The native backend supports PTY input/output, an initial 24-by-80 window size, explicit resize requests, and process-group hangup during session shutdown. The previous Java `ProcessBuilder` pipe transport has been removed.

The PTY backend is necessary for full-screen interactive applications, but compatibility is not yet verified. Actual behavior depends on Mysh, terminal escape-sequence support, keyboard translation, terminal capabilities data (terminfo), and device testing. Vim, Nano, htop, and ncurses applications must not be described as supported until tested on an Android device.

The terminal grid is currently fixed at 24 rows by 80 columns. Automatic viewport-to-PTY resizing, scrollback storage, cursor drawing, mouse reporting, clipboard integration, and broader xterm extensions remain future work.

## Private filesystem

The application initializes the following directories under its private files directory:

\`\`\`text
files/
├── bin/
├── etc/
├── home/user/
├── lib/
└── usr/bin/
\`\`\`

The Mysh executable is expected at \`files/bin/mysh\`. External command lookup is restricted to the application's \`bin\` and \`usr/bin\` directories through the child process environment.

## Native dependencies and licensing

- libvterm 0.3.3 is distributed under the MIT License.
- The libvterm license text is included at \`app/src/main/cpp/third_party/libvterm-LICENSE\`.
- The libvterm release archive is fetched by CMake and verified against a fixed SHA-256 digest.
- The application remains licensed under BSD 3-Clause.
- No GPL dependency is intentionally included.

Third-party notices and license obligations must be preserved when redistributing the application.

## Build

Build the debug APK with:

\`\`\`sh
gradle :app:assembleDebug
\`\`\`

The project requires JDK 17, Android SDK 35, Android NDK 27.2.12479018, and CMake 3.22.1. CMake downloads the pinned libvterm source archive during native configuration, so the first build requires network access.

## Runtime status

The application manages independent Mysh processes, native PTY instances, and terminal emulator state for each session. The terminal engine parses VT control sequences and the renderer applies cell attributes and colors. The Mysh executable must be supplied for the target Android ABI before shell sessions can execute commands. The application reports a clear installation message when the executable is absent.

The native PTY and terminal engine integration has not been considered verified until the Android CI build and on-device behavior have both been checked.

## Security boundary

The configured PATH limits ordinary command lookup to the application's \`bin\` and \`usr/bin\` directories, but it is not a hard execution boundary. Mysh must independently validate canonical executable paths and reject absolute paths or traversal outside the private filesystem. Processes launched under the application UID retain that UID's Android permissions; this design is not an operating-system sandbox. Do not install untrusted executables.
