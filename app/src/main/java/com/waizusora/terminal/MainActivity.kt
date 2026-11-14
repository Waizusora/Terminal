package com.waizusora.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect

private object TerminalPalette {
    val Background = Color(0xFF101114)
    val Surface = Color(0xFF191C22)
    val SessionInactive = Color(0xFF242830)
    val SessionActive = Color(0xFF343B48)
    val Shortcut = Color(0xFF20242C)
    val Accent = Color(0xFF8AB4F8)
    val TextPrimary = Color(0xFFE6E8ED)
    val TextSecondary = Color(0xFF9299A5)
    val Destructive = Color(0xFFE57373)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = TerminalPalette.Background,
                surface = TerminalPalette.Surface,
                surfaceVariant = TerminalPalette.Shortcut,
                primary = TerminalPalette.Accent,
                onBackground = TerminalPalette.TextPrimary,
                onSurface = TerminalPalette.TextPrimary,
                onSurfaceVariant = TerminalPalette.TextSecondary,
                error = TerminalPalette.Destructive
            )) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TerminalScreen()
                }
            }
        }
    }
}

private class TerminalSession(
    val id: Int,
    val runtime: MyshProcess,
    val emulator: TerminalEmulator
) {
    var input by mutableStateOf("")
    var cells by mutableStateOf(intArrayOf())
    var cursor by mutableStateOf(intArrayOf(0, 0, 0))
    var scrollback by mutableStateOf(intArrayOf())
    var contentVersion by mutableStateOf(0)
    private var closed = false

    init {
        applyFrame(emulator.currentFrame())
    }

    @Synchronized
    fun acceptOutput(bytes: ByteArray): ByteArray {
        if (closed) return byteArrayOf()
        val frame = emulator.feedAndSnapshot(bytes)
        applyFrame(frame)
        return frame.response
    }

    @Synchronized
    fun acceptStatus(message: String) {
        if (closed) return
        applyFrame(emulator.feedAndSnapshot(message.toByteArray(Charsets.UTF_8)))
    }

    @Synchronized
    fun resize(rows: Int, columns: Int) {
        if (closed) return
        try {
            runtime.resize(rows, columns)
            applyFrame(emulator.resizeAndSnapshot(rows, columns))
        } catch (error: java.io.IOException) {
            applyFrame(
                emulator.feedAndSnapshot(
                    "PTY resize failed: ${error.message}\n".toByteArray(Charsets.UTF_8)
                )
            )
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        runtime.close()
        emulator.close()
    }

    private fun applyFrame(frame: TerminalFrame) {
        cells = frame.cells
        cursor = frame.cursor
        frame.scrollback?.let { scrollback = it }
        contentVersion++
    }
}

@Composable
private fun TerminalScreen() {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeId by remember { mutableStateOf(1) }
    var nextSessionId by remember { mutableStateOf(1) }
    var controlArmed by remember { mutableStateOf(false) }
    var pendingCloseId by remember { mutableStateOf<Int?>(null) }
    var terminalRows by remember { mutableStateOf(24) }
    var terminalColumns by remember { mutableStateOf(80) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val terminalInputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val cellWidthPx = with(density) { 13.sp.toPx() * 0.602f }
    val lineHeightPx = with(density) { 19.sp.toPx() }

    fun createSession() {
        val id = nextSessionId++
        val runtime = MyshProcess(
            filesRoot = context.filesDir,
            nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir),
            initialRows = terminalRows,
            initialColumns = terminalColumns
        )
        val emulator = TerminalEmulator(terminalRows, terminalColumns)
        val session = TerminalSession(id, runtime, emulator)
        sessions.add(session)
        activeId = id
        runtime.start(
            onOutput = { bytes ->
                val response = session.acceptOutput(bytes)
                if (response.isNotEmpty()) runtime.write(response)
            },
            onStatus = { message ->
                session.acceptStatus(message)
            }
        )
    }

    fun closeSession(id: Int) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].close()
        sessions.removeAt(index)
        if (activeId == id) activeId = sessions.lastOrNull()?.id ?: 0
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) createSession()
    }

    DisposableEffect(Unit) {
        onDispose {
            sessions.forEach { it.close() }
        }
    }

    val active = sessions.firstOrNull { it.id == activeId }
    val terminalScrollState = rememberScrollState()
    var followTerminalOutput by remember { mutableStateOf(true) }

    LaunchedEffect(terminalScrollState) {
        snapshotFlow {
            terminalScrollState.value >= terminalScrollState.maxValue - 48
        }.collect { atBottom ->
            followTerminalOutput = atBottom
        }
    }

    LaunchedEffect(active?.id) {
        followTerminalOutput = true
        terminalScrollState.scrollTo(terminalScrollState.maxValue)
    }

    LaunchedEffect(active?.id, active?.contentVersion) {
        if (followTerminalOutput) {
            terminalScrollState.scrollTo(terminalScrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalPalette.Background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    sessions.forEachIndexed { index, session ->
                        Surface(
                            onClick = { activeId = session.id },
                            modifier = Modifier
                                .width(96.dp)
                                .height(40.dp),
                            color = if (session.id == activeId) TerminalPalette.SessionActive else TerminalPalette.SessionInactive,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 10.dp, end = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (index + 1).toString(),
                                        color = TerminalPalette.TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Surface(
                                    onClick = { pendingCloseId = session.id },
                                    modifier = Modifier.size(30.dp),
                                    color = TerminalPalette.Shortcut,
                                    shape = CircleShape
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "×",
                                            color = TerminalPalette.Destructive,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TerminalKey(label = "+", onClick = { createSession() }, emphasized = true)
        }

        if (active != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .clickable {
                        terminalInputFocusRequester.requestFocus()
                        keyboardController?.show()
                    }
                    .onSizeChanged { size ->
                        if (size.width > 0 && size.height > 0) {
                            val columns = (size.width / cellWidthPx).toInt().coerceIn(1, 500)
                            val rows = (size.height / lineHeightPx).toInt().coerceIn(1, 300)
                            if (rows != terminalRows || columns != terminalColumns) {
                                terminalRows = rows
                                terminalColumns = columns
                                sessions.forEach { it.resize(rows, columns) }
                            }
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(terminalScrollState)
                ) {
                    val historyRows = active.scrollback.size /
                        (terminalColumns * TerminalEmulator.CELL_STRIDE)
                    repeat(historyRows) { row ->
                        Text(
                            text = buildTerminalLine(
                                active.scrollback,
                                row,
                                terminalColumns
                            ),
                            color = TerminalPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = 1
                        )
                    }
                    TerminalViewport(
                        cells = active.cells,
                        rows = terminalRows,
                        columns = terminalColumns,
                        cursor = active.cursor
                    )
                }
            }
        }

        if (active != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = active.input,
                    onValueChange = { value ->
                        if (value.isNotEmpty()) active.runtime.write(value)
                        active.input = ""
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(terminalInputFocusRequester)
                        .padding(start = 8.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TerminalPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = { active.runtime.write("\r") }
                    ),
                    decorationBox = { inner ->
                        if (active.input.isEmpty()) {
                            Text(" ", color = Color.Gray)
                        }
                        inner()
                    }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf("ESC", "↵", "CTRL", "ALT", "TAB", "↑", "↓", "←", "→", "HOME", "END", "DEL", "C", "D", "Z", "L").forEach { key ->
                    TerminalKey(
                        label = key,
                        emphasized = key == "CTRL" && controlArmed,
                        onClick = {
                            when (key) {
                                "CTRL" -> controlArmed = !controlArmed
                                "ESC" -> active.runtime.write("\u001B")
                                "↵" -> active.runtime.write("\r")
                                "ALT" -> active.runtime.write("\u001B")
                                "TAB" -> active.runtime.write("\t")
                                "↑" -> active.runtime.write("\u001B[A")
                                "↓" -> active.runtime.write("\u001B[B")
                                "→" -> active.runtime.write("\u001B[C")
                                "←" -> active.runtime.write("\u001B[D")
                                "HOME" -> active.runtime.write("\u001B[H")
                                "END" -> active.runtime.write("\u001B[F")
                                "DEL" -> active.runtime.write("\u007F")
                                "C", "D", "Z", "L" -> {
                                    if (controlArmed) {
                                        val controlCode = when (key) {
                                            "C" -> 0x03
                                            "D" -> 0x04
                                            "Z" -> 0x1A
                                            else -> 0x0C
                                        }
                                        active.runtime.write(controlCode.toChar().toString())
                                    }
                                    controlArmed = false
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    pendingCloseId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { pendingCloseId = null },
            title = { Text("Close session ${sessions.indexOfFirst { it.id == sessionId } + 1}?") },
            text = { Text("The shell process in this session will be terminated.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        closeSession(sessionId)
                        pendingCloseId = null
                    }
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseId = null }) {
                    Text("Cancel")
                }
            },
            containerColor = TerminalPalette.Surface,
            titleContentColor = Color(0xFFE6E8ED),
            textContentColor = TerminalPalette.TextSecondary
        )
    }
}

@Composable
private fun TerminalKey(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    Surface(
        onClick = onClick,
        color = if (emphasized) TerminalPalette.Accent else TerminalPalette.Shortcut,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            color = if (emphasized) TerminalPalette.Background else TerminalPalette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun TerminalViewport(cells: IntArray, rows: Int, columns: Int, cursor: IntArray) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cursorX = with(density) { 13.sp.toDp() * 0.602f * cursor.getOrElse(1) { 0 }.coerceAtLeast(0) }
    val cursorY = with(density) { 19.sp.toDp() * cursor.getOrElse(0) { 0 }.coerceAtLeast(0) }
    val cursorHeight = with(density) { 19.sp.toDp() }
    Box(modifier = Modifier.fillMaxWidth()) {
    Column {
        repeat(rows) { row ->
            Text(
                text = buildTerminalLine(cells, row, columns),
                color = TerminalPalette.TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = 1
            )
        }
    }
    if (cursor.getOrElse(2) { 0 } != 0 && cursor.getOrElse(0) { 0 } in 0 until rows && cursor.getOrElse(1) { 0 } in 0 until columns) {
        Box(
            modifier = Modifier
                .offset(x = cursorX, y = cursorY)
                .size(width = 1.5.dp, height = cursorHeight)
                .background(Color(0xFF8AB4F8))
        )
    }
    }
}

private fun buildTerminalLine(cells: IntArray, row: Int, columns: Int) =
    buildAnnotatedString {
        for (column in 0 until columns) {
            val offset = (row * columns + column) * TerminalEmulator.CELL_STRIDE
            if (offset + 9 >= cells.size) break
            val width = cells[offset + 6]
            if (width <= 0) continue

            var foreground = terminalColor(cells[offset + 8], TerminalPalette.TextPrimary)
            var background = terminalColor(cells[offset + 9], TerminalPalette.Background)
            val attributes = cells[offset + 7]
            if ((attributes and 16) != 0) {
                val previousForeground = foreground
                foreground = background
                background = previousForeground
            }

            val decorations = buildList {
                if ((attributes and 2) != 0) add(TextDecoration.Underline)
                if ((attributes and 32) != 0) add(TextDecoration.LineThrough)
            }
            val style = SpanStyle(
                color = foreground,
                background = background,
                fontWeight = if ((attributes and 1) != 0) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if ((attributes and 4) != 0) FontStyle.Italic else FontStyle.Normal,
                textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) }
            )
            pushStyle(style)
            var hasCharacter = false
            for (index in 0 until 6) {
                val codePoint = cells[offset + index]
                if (codePoint == 0) break
                append(String(Character.toChars(codePoint)))
                hasCharacter = true
            }
            if (!hasCharacter) append(" ")
            pop()
        }
    }

private fun terminalColor(value: Int, default: Color): Color {
    if (value == 0) return default
    return when (value and -0x1000000) {
        0x01000000 -> xtermPalette(value and 0xFF)
        0x02000000 -> Color(0xFF000000.toInt() or (value and 0xFFFFFF))
        else -> default
    }
}

private fun xtermPalette(index: Int): Color {
    val basic = intArrayOf(
        0x000000, 0x800000, 0x008000, 0x808000,
        0x000080, 0x800080, 0x008080, 0xC0C0C0,
        0x808080, 0xFF0000, 0x00FF00, 0xFFFF00,
        0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF
    )
    val rgb = when {
        index < 16 -> basic[index]
        index < 232 -> {
            val value = index - 16
            val levels = intArrayOf(0, 95, 135, 175, 215, 255)
            (levels[value / 36] shl 16) or
                (levels[(value / 6) % 6] shl 8) or
                levels[value % 6]
        }
        else -> {
            val gray = 8 + (index - 232) * 10
            (gray shl 16) or (gray shl 8) or gray
        }
    }
    return Color(0xFF000000.toInt() or rgb)
}

private class MyshProcess(
    private val filesRoot: File,
    private val nativeLibraryDirectory: File,
    initialRows: Int,
    initialColumns: Int
) {
    @Volatile private var rows = initialRows
    @Volatile private var columns = initialColumns
    private val executor = Executors.newSingleThreadExecutor()
    private val writerExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(64),
        { task -> Thread(task, "Mysh-PTY-writer") },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val pendingInput = ByteArrayOutputStream()
    private var queuedInputBytes = 0L

    private companion object {
        const val MAX_PENDING_INPUT_BYTES = 64 * 1024
        const val MAX_QUEUED_INPUT_BYTES = 1024 * 1024
    }

    @Volatile
    private var statusCallback: ((String) -> Unit)? = null

    @Volatile
    private var terminal: PseudoTerminal? = null

    @Volatile
    private var closed = false

    @Synchronized
    fun start(
        onOutput: (ByteArray) -> Unit,
        onStatus: (String) -> Unit
    ) {
        if (closed) return
        statusCallback = onStatus
        executor.execute {
            var sessionTerminal: PseudoTerminal? = null
            try {
                val home = File(filesRoot, "home/user")
                val bin = File(filesRoot, "bin")
                val sbin = File(filesRoot, "sbin")
                val library = File(filesRoot, "lib")
                val libexec = File(filesRoot, "libexec")
                val include = File(filesRoot, "include")
                val share = File(filesRoot, "share")
                val etc = File(filesRoot, "etc")
                val temporary = File(filesRoot, "tmp")
                val variable = File(filesRoot, "var")
                val run = File(filesRoot, "run")
                val opt = File(filesRoot, "opt")
                val dev = File(filesRoot, "dev")
                val proc = File(filesRoot, "proc")
                val usr = File(filesRoot, "usr")
                val usrBin = File(usr, "bin")
                val usrSbin = File(usr, "sbin")
                val usrLibrary = File(usr, "lib")
                val usrLibexec = File(usr, "libexec")
                val usrInclude = File(usr, "include")
                val usrShare = File(usr, "share")

                listOf(
                    bin, sbin, library, libexec, include, share, File(share, "terminfo"),
                    etc, temporary, variable, run, opt, dev, proc, home,
                    usrBin, usrSbin, usrLibrary, usrLibexec, usrInclude,
                    usrShare, File(usrShare, "terminfo")
                ).forEach { directory ->
                    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                        throw java.io.IOException("Unable to create runtime directory: ${directory.absolutePath}")
                    }
                }

                MyshInstaller.installRuntimeCommands(nativeLibraryDirectory, bin)
                val shell = MyshInstaller.resolveExecutable(nativeLibraryDirectory)
                val shellVersion = MyshInstaller.verifyExecutable(shell)
                onStatus("$shellVersion\n")
                sessionTerminal = PseudoTerminal(
                    shell = shell.absolutePath,
                    workingDirectory = home.absolutePath,
                    home = home.absolutePath,
                    prefix = filesRoot.absolutePath,
                    path = listOf(bin, sbin, usrBin, usrSbin)
                        .joinToString(File.pathSeparator) { it.absolutePath },
                    rows = rows,
                    columns = columns
                )
                sessionTerminal.start()

                var startupQueueStatus: String? = null
                synchronized(this) {
                    if (closed) {
                        sessionTerminal.close()
                        return@execute
                    }
                    sessionTerminal.resize(rows, columns)
                    terminal = sessionTerminal
                    val queued = pendingInput.toByteArray()
                    pendingInput.reset()
                    if (queued.isNotEmpty()) {
                        startupQueueStatus = enqueueWrite(sessionTerminal, queued)
                    }
                }
                startupQueueStatus?.let(onStatus)

                while (!closed) {
                    val bytes = sessionTerminal.read() ?: continue
                    if (bytes.isEmpty()) break
                    onOutput(bytes)
                }
            } catch (error: Exception) {
                if (!closed) onStatus("Unable to initialize Mysh runtime: ${error.message}\n")
            } finally {
                synchronized(this) {
                    if (terminal === sessionTerminal) terminal = null
                }
                sessionTerminal?.close()
            }
        }
    }

    fun write(value: String) = write(value.toByteArray(Charsets.UTF_8))

    fun write(value: ByteArray) {
        if (value.isEmpty()) return
        var statusMessage: String? = null
        var callback: ((String) -> Unit)? = null
        synchronized(this) {
            if (closed) return
            val activeTerminal = terminal
            if (activeTerminal != null) {
                statusMessage = enqueueWrite(activeTerminal, value.copyOf())
            } else if (value.size <= MAX_PENDING_INPUT_BYTES - pendingInput.size()) {
                pendingInput.write(value)
            } else {
                statusMessage = "PTY startup input limit exceeded; input was rejected.\n"
            }
            if (statusMessage != null) callback = statusCallback
        }
        statusMessage?.let { callback?.invoke(it) }
    }

    private fun enqueueWrite(target: PseudoTerminal, input: ByteArray): String? {
        if (closed || input.isEmpty()) return null
        if (input.size > MAX_QUEUED_INPUT_BYTES - queuedInputBytes) {
            return "PTY input queue limit exceeded; input was rejected.\n"
        }

        queuedInputBytes += input.size
        try {
            writerExecutor.execute {
                try {
                    if (!closed) target.write(input)
                } catch (error: Exception) {
                    if (!closed) {
                        statusCallback?.invoke(
                            "PTY input failed: ${error.message}\n"
                        )
                    }
                } finally {
                    synchronized(this) {
                        queuedInputBytes -= input.size
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            queuedInputBytes -= input.size
            if (!closed) {
                return "PTY input queue is unavailable.\n"
            }
        }
        return null
    }

    @Synchronized
    fun resize(rows: Int, columns: Int) {
        if (rows <= 0 || columns <= 0) return
        terminal?.resize(rows, columns)
        this.rows = rows
        this.columns = columns
    }

    fun close() {
        val activeTerminal = synchronized(this) {
            if (closed) return
            closed = true
            terminal.also { terminal = null }
        }
        executor.shutdownNow()
        writerExecutor.shutdownNow()
        pendingInput.reset()
        if (activeTerminal != null) {
            Thread(
                { activeTerminal.close() },
                "Mysh-PTY-shutdown"
            ).start()
        }
    }
}
