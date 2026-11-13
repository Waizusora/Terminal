package com.waizusora.terminal

import android.content.res.AssetManager
import android.os.Build
import java.io.File
import java.io.IOException

internal object MyshInstaller {
    fun ensureInstalled(assets: AssetManager, filesRoot: File): File {
        val binaryDirectory = File(filesRoot, "bin")
        if (!binaryDirectory.isDirectory && !binaryDirectory.mkdirs() && !binaryDirectory.isDirectory) {
            throw IOException("Unable to create executable directory: ${binaryDirectory.absolutePath}")
        }

        val destination = File(binaryDirectory, "mysh")
        if (destination.isFile && destination.canExecute()) return destination

        val temporary = File(binaryDirectory, ".mysh.installing")
        var lastError: IOException? = null
        for (abi in Build.SUPPORTED_ABIS) {
            try {
                assets.open("mysh/$abi/mysh").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temporary.setExecutable(true, true)) {
                    throw IOException("Unable to mark the Mysh executable as executable.")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace the invalid Mysh executable.")
                }
                if (!temporary.renameTo(destination)) {
                    throw IOException("Unable to install the Mysh executable.")
                }
                return destination
            } catch (error: IOException) {
                temporary.delete()
                lastError = error
            }
        }

        throw IOException(
            "No bundled Mysh executable is available for supported ABIs: ${Build.SUPPORTED_ABIS.joinToString()}.",
            lastError
        )
    }
}
