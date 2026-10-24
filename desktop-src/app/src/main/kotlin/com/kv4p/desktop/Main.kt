package com.kv4p.desktop

import android.util.Log
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.runtime.LaunchedEffect
import java.awt.Dimension
import com.kv4p.desktop.ui.Kv4pTheme
import com.kv4p.desktop.ui.LogPanel
import com.kv4p.desktop.ui.MainScreen
import com.kv4p.desktop.ui.LandscapeMinWidth
import com.kv4p.desktop.ui.MaxContentWidth
import com.kv4p.desktop.ui.TextFocus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vagell.kv4pht.radio.DesktopRadioSession
import com.vagell.kv4pht.radio.Protocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    AppLog.init()
    when {
        "--list-ports" in args -> listPorts()
        "--probe" in args -> probe(args)
        else -> runUi()
    }
}

private fun runUi() {
    val vm = RadioViewModel()
    val appIcon = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream("kv4p.png")
        ?.use { BitmapPainter(loadImageBitmap(it)) }
    application {
        Window(
            onCloseRequest = {
                vm.shutdown()
                exitApplication()
            },
            title = "kv4p HT",
            icon = appIcon,
            state = rememberWindowState(
                size = initialWindowSize(),
                placement = if (isSmallScreen()) WindowPlacement.Maximized else WindowPlacement.Floating,
            ),
            // Space bar = PTT. Checked before the focused control sees the key, so a button or
            // checkbox clicked earlier doesn't take it; only text fields keep the space bar.
            onPreviewKeyEvent = { event ->
                if (event.key == Key.Spacebar && !TextFocus.active) {
                    when (event.type) {
                        KeyEventType.KeyDown -> vm.pttDown()
                        KeyEventType.KeyUp -> vm.pttUp()
                    }
                    true
                } else {
                    false
                }
            },
        ) {
            // Below this the radio face starts squeezing its text into one-letter columns.
            LaunchedEffect(Unit) { window.minimumSize = Dimension(MIN_WIDTH, MIN_HEIGHT) }
            Kv4pTheme { MainScreen(vm) }
        }
        if (vm.debugOpen) {
            Window(
                onCloseRequest = { vm.debugOpen = false },
                title = "kv4p HT - Debug log",
                icon = appIcon,
                state = rememberWindowState(size = DpSize(760.dp, 520.dp)),
            ) {
                Kv4pTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        LogPanel(vm, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * Short screens (uConsole, 1280x720) get a wide window with the memories beside the radio;
 * taller ones a narrow, tall window with the memories below.
 */
private fun initialWindowSize(): DpSize {
    val screen = screenBounds() ?: return DpSize(MaxContentWidth + 20.dp, 900.dp)
    if (screen.height < SMALL_SCREEN_HEIGHT) {
        return DpSize(screen.width.coerceAtLeast(LandscapeMinWidth.value.toInt()).dp, screen.height.dp)
    }
    return DpSize(MaxContentWidth + 20.dp, (screen.height - 20).coerceAtMost(960).dp)
}

private fun listPorts() {
    val ports = DesktopRadioSession.listPorts()
    if (ports.isEmpty()) println("No serial ports found.")
    ports.forEach { println((if (it.likelyKv4p) "* " else "  ") + it) }
}

/**
 * Headless check for troubleshooting without the UI:
 *   kv4p-ht-desktop --probe /dev/ttyUSB0 [--freq 146.52] [--listen] [--seconds 20]
 * Prints the handshake and radio state; the same output goes to ~/.kv4p-desktop/kv4p.log.
 */
private fun probe(args: Array<String>) {
    fun arg(name: String): String? = args.indexOf(name).takeIf { it >= 0 && it + 1 < args.size }?.let { args[it + 1] }
    Log.setMinStderrPriority(Log.DEBUG)

    val port = arg("--probe") ?: DesktopRadioSession.listPorts().firstOrNull { it.likelyKv4p }?.systemPath
    if (port == null || port.startsWith("--")) {
        println("No port given and no kv4p-like port found. Use --list-ports.")
        exitProcess(2)
    }
    val freq = arg("--freq")?.toFloatOrNull()
    val seconds = arg("--seconds")?.toLongOrNull() ?: 20L
    val audio = if ("--listen" in args) AudioOutput().also { it.start() } else null

    val done = CountDownLatch(1)
    var exitCode = 1
    lateinit var session: DesktopRadioSession
    session = DesktopRadioSession(object : DesktopRadioSession.Listener {
        override fun onConnectionState(state: DesktopRadioSession.ConnectionState, detail: String?) {
            println("STATE $state ${detail.orEmpty()}")
            when (state) {
                DesktopRadioSession.ConnectionState.CONNECTED -> {
                    exitCode = 0
                    if (freq != null) session.tuneSimplex(freq)
                }
                DesktopRadioSession.ConnectionState.CONNECTING,
                DesktopRadioSession.ConnectionState.HANDSHAKE -> Unit
                else -> done.countDown()
            }
        }

        override fun onDeviceState(state: Protocol.DeviceState, sMeter: Int, squelched: Boolean, txActive: Boolean) {
            println("DEVICE rx=${state.freqRx} tx=${state.freqTx} mode=${state.mode} " +
                "S=$sMeter squelched=$squelched err=${state.lastError}")
        }

        override fun onRxAudio(pcm: FloatArray, samples: Int) {
            audio?.write(pcm, samples)
        }
    })
    println("Probing $port for $seconds s…")
    session.connect(port)
    done.await(seconds, TimeUnit.SECONDS)
    session.close()
    audio?.stop()
    println(if (exitCode == 0) "OK: radio answered." else "FAILED - see ${AppLog.file}")
    exitProcess(exitCode)
}

private const val MIN_WIDTH = 720
private const val MIN_HEIGHT = 560
private const val SMALL_SCREEN_HEIGHT = 900

private fun screenBounds() = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
}.getOrNull()

/** Small screens such as the uConsole's open the window maximized. */
private fun isSmallScreen() = screenBounds()?.let { it.height < SMALL_SCREEN_HEIGHT } == true
