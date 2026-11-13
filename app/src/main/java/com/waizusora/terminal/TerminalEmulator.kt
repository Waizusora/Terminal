package com.waizusora.terminal

internal object NativeTerminal {
    init {
        System.loadLibrary("terminal_vterm")
    }

    @JvmStatic external fun nativeCreate(rows: Int, columns: Int): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeFeed(handle: Long, input: ByteArray)
    @JvmStatic external fun nativeResize(handle: Long, rows: Int, columns: Int)
    @JvmStatic external fun nativeReadCells(handle: Long): IntArray
    @JvmStatic external fun nativeReadScrollback(handle: Long): IntArray
    @JvmStatic external fun nativeScrollbackVersion(handle: Long): Long
    @JvmStatic external fun nativeReadResponse(handle: Long): ByteArray
}

internal class TerminalEmulator(
    rows: Int = 24,
    columns: Int = 80
) : AutoCloseable {
    var rows: Int = rows
        private set
    var columns: Int = columns
        private set
    private var handle: Long = NativeTerminal.nativeCreate(rows, columns)
    private var scrollbackVersion = -1L

    init {
        check(handle != 0L) { "Unable to initialize the native terminal emulator." }
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        if (handle != 0L && bytes.isNotEmpty()) {
            NativeTerminal.nativeFeed(handle, bytes)
        }
    }

    @Synchronized
    fun readResponse(): ByteArray {
        return if (handle != 0L) NativeTerminal.nativeReadResponse(handle) else byteArrayOf()
    }

    @Synchronized
    fun resize(newRows: Int, newColumns: Int) {
        if (handle == 0L || newRows <= 0 || newColumns <= 0) return
        if (rows == newRows && columns == newColumns) return
        NativeTerminal.nativeResize(handle, newRows, newColumns)
        rows = newRows
        columns = newColumns
    }

    @Synchronized
    fun snapshot(): IntArray {
        return if (handle != 0L) {
            NativeTerminal.nativeReadCells(handle)
        } else {
            IntArray(rows * columns * CELL_STRIDE)
        }
    }

    @Synchronized
    fun scrollbackSnapshot(): IntArray? {
        if (handle == 0L) return intArrayOf()
        val currentVersion = NativeTerminal.nativeScrollbackVersion(handle)
        if (currentVersion == scrollbackVersion) return null
        scrollbackVersion = currentVersion
        return NativeTerminal.nativeReadScrollback(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeTerminal.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        const val CELL_STRIDE = 10
    }
}
