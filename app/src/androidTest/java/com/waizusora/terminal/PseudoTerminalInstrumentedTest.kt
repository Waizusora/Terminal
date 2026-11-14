package com.waizusora.terminal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PseudoTerminalInstrumentedTest {
    @Test
    fun launchesShellAndTransportsInputAndOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = context.filesDir
        val home = File(filesRoot, "home/pty-test")
        val bin = File(filesRoot, "bin")
        val sbin = File(filesRoot, "sbin")
        val usrBin = File(filesRoot, "usr/bin")
        val usrSbin = File(filesRoot, "usr/sbin")
        listOf(home, bin, sbin, usrBin, usrSbin).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Unable to create test directory: ${directory.absolutePath}"
            }
        }

        val shell = MyshInstaller.resolveExecutable(
            File(context.applicationInfo.nativeLibraryDir)
        )
        MyshInstaller.installRuntimeCommands(
            File(context.applicationInfo.nativeLibraryDir),
            bin
        )
        val terminal = PseudoTerminal(
            shell = shell.absolutePath,
            workingDirectory = home.absolutePath,
            home = home.absolutePath,
            prefix = filesRoot.absolutePath,
            path = listOf(bin, sbin, usrBin, usrSbin)
                .joinToString(File.pathSeparator) { it.absolutePath },
            rows = 24,
            columns = 80
        )

        try {
            terminal.start()
            terminal.resize(30, 100)
            terminal.write("echo PTY_RUNTIME_OK\n".toByteArray(Charsets.UTF_8))

            val output = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var receivedMarker = false
            while (System.nanoTime() < deadline && !receivedMarker) {
                val bytes = terminal.read() ?: continue
                if (bytes.isEmpty()) break
                output.write(bytes)
                receivedMarker = output.toString(Charsets.UTF_8.name())
                    .contains("PTY_RUNTIME_OK")
            }

            assertTrue(
                "PTY did not return shell output before timeout. Received: " +
                    output.toString(Charsets.UTF_8.name()),
                receivedMarker
            )

            output.reset()
            terminal.write("echo \"\$MYSH_PREFIX\"\n".toByteArray(Charsets.UTF_8))
            val prefixDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var receivedPrefix = false
            while (System.nanoTime() < prefixDeadline && !receivedPrefix) {
                val bytes = terminal.read() ?: continue
                if (bytes.isEmpty()) break
                output.write(bytes)
                receivedPrefix = output.toString(Charsets.UTF_8.name())
                    .contains(filesRoot.absolutePath)
            }
            assertTrue(
                "PTY did not expose MYSH_PREFIX to Mysh. Received: " +
                    output.toString(Charsets.UTF_8.name()),
                receivedPrefix
            )

            output.reset()
            terminal.write("mypk --version\n".toByteArray(Charsets.UTF_8))
            val mypkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var receivedMypkVersion = false
            while (System.nanoTime() < mypkDeadline && !receivedMypkVersion) {
                val bytes = terminal.read() ?: continue
                if (bytes.isEmpty()) break
                output.write(bytes)
                receivedMypkVersion = output.toString(Charsets.UTF_8.name())
                    .contains("mypk 0.1.0")
            }
            assertTrue(
                "PTY did not execute the bundled MYPK binary. Received: " +
                    output.toString(Charsets.UTF_8.name()),
                receivedMypkVersion
            )
        } finally {
            terminal.close()
        }
    }
    @Test
    fun installerRefreshesStaleRuntimeSymlinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val temporaryDirectory = java.nio.file.Files.createTempDirectory(
            context.cacheDir.toPath(),
            "runtime-link-test-"
        )
        try {
            val binDirectory = temporaryDirectory.resolve("bin").toFile()
            check(binDirectory.mkdirs())
            val coreutilsLink = File(binDirectory, "coreutils")
            val mypkLink = File(binDirectory, "mypk")
            java.nio.file.Files.createSymbolicLink(
                coreutilsLink.toPath(),
                temporaryDirectory.resolve("old-coreutils")
            )
            java.nio.file.Files.createSymbolicLink(
                mypkLink.toPath(),
                temporaryDirectory.resolve("old-mypk")
            )

            MyshInstaller.installRuntimeCommands(nativeDirectory, binDirectory)

            assertTrue(
                "Coreutils link was not refreshed",
                java.nio.file.Files.isSymbolicLink(coreutilsLink.toPath()) &&
                    java.nio.file.Files.readSymbolicLink(coreutilsLink.toPath()) ==
                    File(nativeDirectory, "libcoreutils.so").toPath().toAbsolutePath().normalize()
            )
            assertTrue(
                "MYPK link was not refreshed",
                java.nio.file.Files.isSymbolicLink(mypkLink.toPath()) &&
                    java.nio.file.Files.readSymbolicLink(mypkLink.toPath()) ==
                    File(nativeDirectory, "libmypk.so").toPath().toAbsolutePath().normalize()
            )
        } finally {
            temporaryDirectory.toFile().walkBottomUp().forEach { it.delete() }
        }
    }

    @Test
    fun installerRejectsUnexpectedFilesAtRuntimeCommandPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val temporaryDirectory = java.nio.file.Files.createTempDirectory(
            context.cacheDir.toPath(),
            "runtime-file-test-"
        )
        try {
            val binDirectory = temporaryDirectory.resolve("bin").toFile()
            check(binDirectory.mkdirs())
            val unexpectedFile = File(binDirectory, "coreutils")
            check(unexpectedFile.writeText("not a symlink").let { unexpectedFile.isFile })

            var rejected = false
            try {
                MyshInstaller.installRuntimeCommands(nativeDirectory, binDirectory)
            } catch (_: java.io.IOException) {
                rejected = true
            }
            assertTrue(
                "Installer must reject a regular file at the coreutils command path",
                rejected
            )
            assertTrue("Unexpected file must remain untouched", unexpectedFile.readText() == "not a symlink")
        } finally {
            temporaryDirectory.toFile().walkBottomUp().forEach { it.delete() }
        }
    }


    @Test
    fun closesPtyAndStartsANewIndependentSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filesRoot = context.filesDir
        val home = File(filesRoot, "home/pty-lifecycle")
        val bin = File(filesRoot, "bin")
        val sbin = File(filesRoot, "sbin")
        val usrBin = File(filesRoot, "usr/bin")
        val usrSbin = File(filesRoot, "usr/sbin")
        listOf(home, bin, sbin, usrBin, usrSbin).forEach { directory ->
            check(directory.isDirectory || directory.mkdirs()) {
                "Unable to create test directory: ${directory.absolutePath}"
            }
        }

        val nativeDirectory = File(context.applicationInfo.nativeLibraryDir)
        val shell = MyshInstaller.resolveExecutable(nativeDirectory)
        MyshInstaller.installRuntimeCommands(nativeDirectory, bin)
        val commandPath = listOf(bin, sbin, usrBin, usrSbin)
            .joinToString(File.pathSeparator) { it.absolutePath }

        fun newSession() = PseudoTerminal(
            shell = shell.absolutePath,
            workingDirectory = home.absolutePath,
            home = home.absolutePath,
            prefix = filesRoot.absolutePath,
            path = commandPath,
            rows = 24,
            columns = 80
        )

        val firstSession = newSession()
        var secondSession: PseudoTerminal? = null
        try {
            firstSession.start()
            val childPid = firstSession.processId()
            assertTrue("PTY returned an invalid child PID", childPid > 0)

            val closeStarted = System.nanoTime()
            firstSession.close()
            val closeDuration = System.nanoTime() - closeStarted
            assertTrue(
                "Closing the PTY session exceeded five seconds",
                closeDuration < TimeUnit.SECONDS.toNanos(5)
            )
            assertTrue(
                "A closed PTY session must not return stale output",
                firstSession.read()?.isEmpty() == true
            )
            firstSession.close()

            val processStillExists = try {
                Os.kill(childPid.toInt(), 0)
                true
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.ESRCH) false else throw error
            }
            assertFalse("PTY shell process $childPid is still alive after close", processStillExists)

            val restartedSession = newSession()
            secondSession = restartedSession
            restartedSession.start()
            restartedSession.write(
                "echo PTY_LIFECYCLE_SECOND\n".toByteArray(Charsets.UTF_8)
            )
            val secondOutput = awaitOutput(restartedSession, "PTY_LIFECYCLE_SECOND")
            assertTrue(
                "A new PTY session failed after the previous session closed: $secondOutput",
                secondOutput.contains("PTY_LIFECYCLE_SECOND")
            )
        } finally {
            firstSession.close()
            secondSession?.close()
        }
    }

    private fun awaitOutputMatching(terminal: PseudoTerminal, pattern: Regex): String {
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val bytes = terminal.read() ?: continue
            if (bytes.isEmpty()) break
            output.write(bytes)
            val currentOutput = output.toString(Charsets.UTF_8.name())
            if (pattern.containsMatchIn(currentOutput)) return currentOutput
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun awaitOutput(terminal: PseudoTerminal, marker: String): String {
        val output = ByteArrayOutputStream()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (System.nanoTime() < deadline) {
            val bytes = terminal.read() ?: continue
            if (bytes.isEmpty()) break
            output.write(bytes)
            val currentOutput = output.toString(Charsets.UTF_8.name())
            if (currentOutput.contains(marker)) return currentOutput
        }
        return output.toString(Charsets.UTF_8.name())
    }

}
