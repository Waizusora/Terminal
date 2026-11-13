# Terminal

Terminal is a native Android terminal application that runs the Mysh executable from its private application filesystem.

## Project identity

- Application name: Terminal
- Application ID: `com.waizusora.terminal`
- Minimum Android version: Android 8.0 (API 26)
- Language: Kotlin
- UI toolkit: Jetpack Compose
- Shell runtime: Mysh
- License: BSD 3-Clause

## User interface

The interface is intentionally limited to a terminal workspace. The top bar contains the title "Terminal" and a plus control for creating sessions. Sessions are identified by sequential numeric labels. The close control on each session terminates that session. A compact terminal key row provides Escape, Control, Alt, Tab, cursor navigation, Home, End, Delete, and Control shortcuts for C, D, Z, and L. Selecting a session switches the active process without terminating the other sessions.

## Private filesystem

The application initializes the following directories under its private files directory:

```text
files/
├── bin/
├── etc/
├── home/user/
├── lib/
└── usr/bin/
```

The Mysh executable is expected at `files/bin/mysh`. External command lookup is restricted to the application's `bin` and `usr/bin` directories through the child process environment.

## Build

Build the debug APK with:

```sh
gradle :app:assembleDebug
```

The project requires JDK 17 and Android SDK 35.

## Runtime status

The Android application initializes private directories and manages independent Mysh process sessions. Session output and input state are maintained independently while the process is alive. The current viewport is a plain text stream; full VT/ANSI terminal emulation is not yet implemented. The Mysh executable must be supplied for the target Android ABI before shell sessions can execute commands. The application reports a clear installation message when the executable is absent.

## Security boundary

The configured PATH limits ordinary command lookup to the application's `bin` and `usr/bin` directories, but it is not a hard execution boundary. Mysh must independently validate canonical executable paths and reject absolute paths or traversal outside the private filesystem. Processes launched under the application UID retain that UID's Android permissions; this design is not an operating-system sandbox. Do not install untrusted executables.
