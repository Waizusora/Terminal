package com.waizusora.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
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
}
