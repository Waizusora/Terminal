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
    @JvmStatic external fun nativeReadCursor(handle: Long): IntArray
    @JvmStatic external fun nativeReadScrollback(handle: Long): IntArray
    @JvmStatic external fun nativeScrollbackVersion(handle: Long): Long
    @JvmStatic external fun nativeReadResponse(handle: Long): ByteArray
}

internal data class TerminalFrame(
    val cells: IntArray,
    val cursor: IntArray,
    val scrollback: IntArray?,
    val response: ByteArray
)

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
    fun feedAndSnapshot(bytes: ByteArray): TerminalFrame {
        if (handle != 0L && bytes.isNotEmpty()) {
            NativeTerminal.nativeFeed(handle, bytes)
        }
        val response = if (handle != 0L) {
            NativeTerminal.nativeReadResponse(handle)
        } else {
            byteArrayOf()
        }
        return createFrame(response)
    }

    @Synchronized
    fun resizeAndSnapshot(newRows: Int, newColumns: Int): TerminalFrame {
        if (handle != 0L && newRows > 0 && newColumns > 0 &&
            (rows != newRows || columns != newColumns)
        ) {
            NativeTerminal.nativeResize(handle, newRows, newColumns)
            rows = newRows
            columns = newColumns
        }
        return createFrame(byteArrayOf())
    }

    @Synchronized
    fun currentFrame(): TerminalFrame = createFrame(byteArrayOf())

    private fun createFrame(response: ByteArray): TerminalFrame = TerminalFrame(
        cells = if (handle != 0L) NativeTerminal.nativeReadCells(handle)
            else IntArray(rows * columns * CELL_STRIDE),
        cursor = if (handle != 0L) NativeTerminal.nativeReadCursor(handle)
            else intArrayOf(0, 0, 0),
        scrollback = scrollbackSnapshot(),
        response = response
    )

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
    fun cursorSnapshot(): IntArray {
        return if (handle != 0L) NativeTerminal.nativeReadCursor(handle) else intArrayOf(0, 0, 0)
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
