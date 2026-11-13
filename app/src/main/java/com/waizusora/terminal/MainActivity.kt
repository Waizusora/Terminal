package com.waizusora.terminal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException
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

private data class TerminalSession(
    val id: Int,
    val runtime: MyshProcess,
    var output: String = "",
    var input: String = ""
)

@Composable
private fun TerminalScreen() {
    val context = LocalContext.current
    val sessions = remember { mutableStateListOf<TerminalSession>() }
    var activeId by remember { mutableStateOf(1) }
    var controlArmed by remember { mutableStateOf(false) }

    fun createSession() {
        val id = (sessions.maxOfOrNull { it.id } ?: 0) + 1
        val runtime = MyshProcess(context.filesDir)
        val session = TerminalSession(id, runtime)
        sessions.add(session)
        activeId = id
        runtime.start { text ->
            val index = sessions.indexOfFirst { it.id == id }
            if (index >= 0) sessions[index].output += text
        }
    }

    fun closeSession(id: Int) {
        val index = sessions.indexOfFirst { it.id == id }
        if (index < 0) return
        sessions[index].runtime.close()
        sessions.removeAt(index)
        if (activeId == id) activeId = sessions.lastOrNull()?.id ?: 0
    }

    LaunchedEffect(Unit) {
        if (sessions.isEmpty()) createSession()
    }

    DisposableEffect(Unit) {
        onDispose { sessions.forEach { it.runtime.close() } }
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
            Text(
                text = active?.output.orEmpty(),
                color = Color(0xFFE2E2E2),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
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
                    onValueChange = { active.input = it },
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
                    active.runtime.write(active.input + "\n")
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
                listOf("ESC", "CTRL", "ALT", "TAB", "↑", "↓", "←", "→", "HOME", "END", "DEL", "C").forEach { key ->
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
                                "C" -> {
                                    if (controlArmed) active.runtime.write("\u0003")
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
    androidx.compose.foundation.clickable
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

private class MyshProcess(private val filesRoot: File) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var process: Process? = null

    fun start(onOutput: (String) -> Unit) {
        val home = File(filesRoot, "home/user")
        val bin = File(filesRoot, "bin")
        val usrBin = File(filesRoot, "usr/bin")
        listOf(bin, usrBin, File(filesRoot, "etc"), home, File(filesRoot, "lib"))
            .forEach { it.mkdirs() }

        val shell = File(bin, "mysh")
        if (!shell.isFile || !shell.canExecute()) {
            onOutput("Mysh binary is not installed.\nExpected: ${shell.absolutePath}\n")
            return
        }

        executor.execute {
            try {
                val builder = ProcessBuilder(shell.absolutePath)
                    .directory(home)
                    .redirectErrorStream(true)
                builder.environment()["HOME"] = home.absolutePath
                builder.environment()["PREFIX"] = filesRoot.absolutePath
                builder.environment()["PATH"] = "${bin.absolutePath}:${usrBin.absolutePath}"
                val child = builder.start()
                process = child
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> onOutput("$line\n") }
                }
            } catch (error: IOException) {
                onOutput("Unable to start Mysh: ${error.message}\n")
            }
        }
    }

    fun write(value: String) {
        try {
            process?.outputStream?.apply {
                write(value.toByteArray())
                flush()
            }
        } catch (_: IOException) {
            Unit
        }
    }

    fun close() {
        process?.destroy()
        executor.shutdownNow()
    }
}
