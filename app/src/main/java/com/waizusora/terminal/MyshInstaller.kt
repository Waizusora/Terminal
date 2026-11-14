package com.waizusora.terminal

import java.io.File
import java.io.IOException

internal object MyshInstaller {
    private const val EXECUTABLE_NAME = "libmysh.so"

    fun resolveExecutable(nativeLibraryDirectory: File): File {
        val executable = File(nativeLibraryDirectory, EXECUTABLE_NAME)
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException(
                "Packaged Mysh executable is missing or not executable in the Android native library directory: ${executable.absolutePath}"
            )
        }
        return executable
    }

    fun verifyExecutable(executable: File): String =
        verifyRuntimeExecutable(executable, "Mysh")

    private fun verifyRuntimeExecutable(executable: File, name: String): String {
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException("$name is missing or is not executable: ${executable.absolutePath}")
        }

        val process = try {
            ProcessBuilder(executable.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
        } catch (error: IOException) {
            val detail = generateSequence(error as Throwable?) { it.cause }
                .mapNotNull { cause ->
                    cause.message?.takeIf(String::isNotBlank)
                        ?: cause.javaClass.simpleName.takeIf(String::isNotBlank)
                }
                .distinct()
                .joinToString(": ")
            throw IOException(
                "Android could not launch packaged $name at ${executable.absolutePath}: ${detail.ifBlank { "unknown execution error" }}",
                error
            )
        }

        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw IOException("$name version check timed out.")
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (process.exitValue() != 0 || output.isBlank()) {
            throw IOException("$name executable verification failed: ${output.ifBlank { "no version output" }}")
        }
        return output
    }

    @Synchronized
    fun installRuntimeCommands(nativeLibraryDirectory: File, binDirectory: File) {
        val coreutils = File(nativeLibraryDirectory, "libcoreutils.so")
        val mypk = File(nativeLibraryDirectory, "libmypk.so")
        installCommandLink(coreutils, File(binDirectory, "coreutils"))
        installCommandLink(mypk, File(binDirectory, "mypk"))
        verifyRuntimeExecutable(coreutils, "Coreutils")
        verifyRuntimeExecutable(mypk, "MYPK")
    }

    private fun installCommandLink(executable: File, link: File) {
        if (!executable.isFile || !executable.canExecute()) {
            throw IOException(
                "Packaged runtime command is missing or not executable: ${executable.absolutePath}"
            )
        }

        val target = executable.toPath().toAbsolutePath().normalize()
        val linkPath = link.toPath()
        if (java.nio.file.Files.exists(linkPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (!java.nio.file.Files.isSymbolicLink(linkPath)) {
                throw IOException(
                    "Runtime command path is occupied by an unexpected file: ${link.absolutePath}"
                )
            }
            if (java.nio.file.Files.readSymbolicLink(linkPath) == target) return
            java.nio.file.Files.delete(linkPath)
        }

        try {
            java.nio.file.Files.createSymbolicLink(linkPath, target)
        } catch (error: IOException) {
            throw IOException(
                "Unable to link runtime command ${link.absolutePath} to ${executable.absolutePath}",
                error
            )
        }
    }
}
