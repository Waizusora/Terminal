package com.waizusora.terminal

import java.io.IOException

internal object NativePty {
    init {
        System.loadLibrary("terminal_vterm")
    }

    @JvmStatic
    external fun nativeStart(
        shell: String,
        workingDirectory: String,
        home: String,
        prefix: String,
        path: String,
        rows: Int,
        columns: Int
    ): Long

    @JvmStatic external fun nativeRead(handle: Long, maximumBytes: Int): ByteArray?
    @JvmStatic external fun nativeWrite(handle: Long, input: ByteArray)
    @JvmStatic external fun nativeResize(handle: Long, rows: Int, columns: Int)
    @JvmStatic external fun nativeClose(handle: Long)
}

internal class PseudoTerminal(
    private val shell: String,
    private val workingDirectory: String,
    private val home: String,
    private val prefix: String,
    private val path: String,
    private val rows: Int = 24,
    private val columns: Int = 80
) : AutoCloseable {
    @Volatile
    private var handle: Long = 0L

    @Synchronized
    fun start() {
        check(handle == 0L) { "PTY has already been started." }
        handle = NativePty.nativeStart(
            shell, workingDirectory, home, prefix, path, rows, columns
        )
        if (handle == 0L) throw IOException("Unable to create the PTY process.")
    }

    @Synchronized
    fun read(): ByteArray? {
        val current = handle
        return if (current != 0L) NativePty.nativeRead(current, 4096) else byteArrayOf()
    }

    @Synchronized
    fun write(input: ByteArray) {
        val current = handle
        if (current != 0L && input.isNotEmpty()) {
            NativePty.nativeWrite(current, input)
        }
    }

    @Synchronized
    fun resize(newRows: Int, newColumns: Int) {
        val current = handle
        if (current != 0L && newRows > 0 && newColumns > 0) {
            NativePty.nativeResize(current, newRows, newColumns)
        }
    }

    @Synchronized
    override fun close() {
        val current = handle
        if (current != 0L) {
            handle = 0L
            NativePty.nativeClose(current)
        }
    }
}
