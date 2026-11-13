package com.waizusora.terminal

import android.os.Bundle
import android.content.res.AssetManager
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Color(0xFF101114),
                surface = Color(0xFF101114),
                primary = Color(0xFFE6E6E6),
                onBackground = Color(0xFFE6E6E6),
                onSurface = Color(0xFFE6E6E6)
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
    var output by mutableStateOf("")
    var input by mutableStateOf("")
    var cells by mutableStateOf(emulator.snapshot())
    var cursor by mutableStateOf(emulator.cursorSnapshot())
    var scrollback by mutableStateOf(emulator.scrollbackSnapshot() ?: intArrayOf())
    var contentVersion by mutableStateOf(0)

    fun resize(rows: Int, columns: Int) {
        emulator.resize(rows, columns)
        runtime.resize(rows, columns)
        cells = emulator.snapshot()
        cursor = emulator.cursorSnapshot()
        emulator.scrollbackSnapshot()?.let { scrollback = it }
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
    var terminalRows by remember { mutableStateOf(24) }
    var terminalColumns by remember { mutableStateOf(80) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cellWidthPx = with(density) { 13.sp.toPx() * 0.602f }
    val lineHeightPx = with(density) { 19.sp.toPx() }

    fun createSession() {
        val id = nextSessionId++
        val runtime = MyshProcess(context.filesDir, context.assets, terminalRows, terminalColumns)
        val emulator = TerminalEmulator(terminalRows, terminalColumns)
        val session = TerminalSession(id, runtime, emulator)
        sessions.add(session)
        activeId = id
        runtime.start(
            onOutput = { bytes ->
                emulator.feed(bytes)
                val response = emulator.readResponse()
                if (response.isNotEmpty()) runtime.write(response)
                session.cells = emulator.snapshot()
                session.cursor = emulator.cursorSnapshot()
                emulator.scrollbackSnapshot()?.let { session.scrollback = it }
                session.contentVersion++
            },
            onStatus = { message -> session.output += message }
        )
    }

    fun closeSession(id: Int) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].runtime.close()
        sessions[index].emulator.close()
        sessions.removeAt(index)
        if (activeId == id) activeId = sessions.lastOrNull()?.id ?: 0
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) createSession()
    }

    DisposableEffect(Unit) {
        onDispose {
            sessions.forEach {
                it.runtime.close()
                it.emulator.close()
            }
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
            .background(Color(0xFF101114))
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Terminal",
                color = Color(0xFFF1F1F1),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TerminalKey(label = "+", onClick = { createSession() }, emphasized = true)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sessions.forEach { session ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            if (session.id == activeId) Color(0xFF303238) else Color(0xFF1B1D22),
                            MaterialTheme.shapes.small
                        )
                        .clickable { activeId = session.id }
                        .padding(start = 11.dp, end = 7.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Text(
                        text = session.id.toString(),
                        color = Color(0xFFEAEAEA),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    TerminalKey(label = "×", onClick = { closeSession(session.id) })
                }
            }
        }

        if (active != null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
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
                    if (active.output.isNotEmpty()) {
                        Text(
                            text = active.output,
                            color = Color(0xFFE2E2E2),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                    }
                    val historyRows = active.scrollback.size /
                        (terminalColumns * TerminalEmulator.CELL_STRIDE)
                    repeat(historyRows) { row ->
                        Text(
                            text = buildTerminalLine(
                                active.scrollback,
                                row,
                                terminalColumns
                            ),
                            color = Color(0xFFE2E2E2),
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
                        .padding(start = 8.dp),
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
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
                TerminalKey(label = "↵", onClick = {
                    active.runtime.write("\r")
                    active.input = ""
                })
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf("ESC", "CTRL", "ALT", "TAB", "↑", "↓", "←", "→", "HOME", "END", "DEL", "C", "D", "Z", "L").forEach { key ->
                    TerminalKey(
                        label = key,
                        emphasized = key == "CTRL" && controlArmed,
                        onClick = {
                            when (key) {
                                "CTRL" -> controlArmed = !controlArmed
                                "ESC" -> active.runtime.write("\u001B")
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
}

@Composable
private fun TerminalKey(
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false
) {
    Surface(
        onClick = onClick,
        color = if (emphasized) Color(0xFF454951) else Color(0xFF24262C),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            color = Color(0xFFECECEC),
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
                color = Color(0xFFE2E2E2),
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
                .background(Color(0xFFECECEC))
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

            var foreground = terminalColor(cells[offset + 8], Color(0xFFE2E2E2))
            var background = terminalColor(cells[offset + 9], Color(0xFF101114))
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
    private val assets: AssetManager,
    initialRows: Int,
    initialColumns: Int
) {
    @Volatile private var rows = initialRows
    @Volatile private var columns = initialColumns
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingInput = ByteArrayOutputStream()

    @Volatile
    private var terminal: PseudoTerminal? = null

    @Volatile
    private var closed = false

    fun start(
        onOutput: (ByteArray) -> Unit,
        onStatus: (String) -> Unit
    ) {
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

                val shell = MyshInstaller.ensureInstalled(assets, filesRoot)
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

                synchronized(this) {
                    if (closed) {
                        sessionTerminal.close()
                        return@execute
                    }
                    sessionTerminal.resize(rows, columns)
                    terminal = sessionTerminal
                    val queued = pendingInput.toByteArray()
                    pendingInput.reset()
                    if (queued.isNotEmpty()) sessionTerminal.write(queued)
                }

                while (!closed) {
                    val bytes = sessionTerminal.read()
                    if (bytes.isEmpty()) break
                    onOutput(bytes)
                }
            } catch (error: Exception) {
                if (!closed) onStatus("Unable to initialize Mysh runtime: ${error.message}\\n")
            } finally {
                synchronized(this) {
                    if (terminal === sessionTerminal) terminal = null
                }
                sessionTerminal?.close()
            }
        }
    }

    @Synchronized
    fun write(value: String) = write(value.toByteArray(Charsets.UTF_8))

    @Synchronized
    fun write(value: ByteArray) {
        if (closed || value.isEmpty()) return
        val activeTerminal = terminal
        if (activeTerminal != null) {
            activeTerminal.write(value)
        } else {
            pendingInput.write(value)
        }
    }

    @Synchronized
    fun resize(rows: Int, columns: Int) {
        if (rows <= 0 || columns <= 0) return
        this.rows = rows
        this.columns = columns
        terminal?.resize(rows, columns)
    }

    fun close() {
        closed = true
        terminal?.close()
        executor.shutdownNow()
    }
}
