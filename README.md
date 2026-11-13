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

The interface is intentionally limited to a terminal workspace. The top bar contains the title "Terminal" and a plus control for creating sessions. Sessions are identified by sequential numeric labels. The close control on each session terminates that session. A compact terminal key row provides keys that are not normally available on the Android software keyboard.

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
./gradlew :app:assembleDebug
```

The project requires JDK 17 and Android SDK 35.

## Runtime status

The Android application scaffold initializes private directories and manages independent Mysh process sessions. The Mysh executable must be supplied for the target Android ABI before shell sessions can execute commands. This initial implementation reports a clear installation message when the executable is absent.

## Security boundary

The executable lookup policy is an application-level path restriction, not an operating-system security sandbox. Processes launched under the application UID may retain the permissions granted to that UID. Do not install untrusted executables.
