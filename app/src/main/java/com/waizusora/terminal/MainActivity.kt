package com.waizusora.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
}

@Composable
private fun TerminalScreen() {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeId by remember { mutableStateOf(1) }
    var nextSessionId by remember { mutableStateOf(1) }
    var controlArmed by remember { mutableStateOf(false) }

    fun createSession() {
        val id = nextSessionId++
        val runtime = MyshProcess(context.filesDir)
        val emulator = TerminalEmulator()
        val session = TerminalSession(id, runtime, emulator)
        sessions.add(session)
        activeId = id
        runtime.start(
            onOutput = { bytes ->
                emulator.feed(bytes)
                val response = emulator.readResponse()
                if (response.isNotEmpty()) runtime.write(response)
                session.cells = emulator.snapshot()
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

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (!active?.output.isNullOrEmpty()) {
                Text(
                    text = active?.output.orEmpty(),
                    color = Color(0xFFE2E2E2),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
            if (active != null) {
                TerminalViewport(cells = active.cells)
            }
        }

        if (active != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("›", color = Color(0xFFB9D8C0), fontFamily = FontFamily.Monospace)
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
private fun TerminalViewport(cells: IntArray) {
    val rows = 24
    val columns = 80
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

private class MyshProcess(private val filesRoot: File) {
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
        val home = File(filesRoot, "home/user")
        val bin = File(filesRoot, "bin")
        val usrBin = File(filesRoot, "usr/bin")
        listOf(bin, usrBin, File(filesRoot, "etc"), home, File(filesRoot, "lib"))
            .forEach { it.mkdirs() }

        val shell = File(bin, "mysh")
        if (!shell.isFile || !shell.canExecute()) {
            onStatus("Mysh binary is not installed.\nExpected: ${shell.absolutePath}\n")
            return
        }

        executor.execute {
            var sessionTerminal: PseudoTerminal? = null
            try {
                sessionTerminal = PseudoTerminal(
                    shell = shell.absolutePath,
                    workingDirectory = home.absolutePath,
                    home = home.absolutePath,
                    prefix = filesRoot.absolutePath,
                    path = "${bin.absolutePath}:${usrBin.absolutePath}"
                )
                sessionTerminal.start()

                synchronized(this) {
                    if (closed) {
                        sessionTerminal.close()
                        return@execute
                    }
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
                if (!closed) onStatus("Unable to start Mysh PTY: ${error.message}\n")
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

    fun resize(rows: Int, columns: Int) {
        terminal?.resize(rows, columns)
    }

    fun close() {
        closed = true
        terminal?.close()
        executor.shutdownNow()
    }
}
