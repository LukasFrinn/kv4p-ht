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
import com.kv4p.desktop.ui.Kv4pTheme
import com.kv4p.desktop.ui.LogPanel
import com.kv4p.desktop.ui.MainScreen
import com.kv4p.desktop.ui.MaxContentWidth
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
            state = rememberWindowState(size = initialWindowSize()),
            // Space bar = PTT. onKeyEvent only sees keys no focused text field used.
            onKeyEvent = { event ->
                if (event.key == Key.Spacebar) {
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

/** Narrow, tall window that still fits short screens such as the uConsole's 1280x720. */
private fun initialWindowSize(): DpSize {
    val screen = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    }.getOrNull()
    val height = screen?.height?.minus(20)?.coerceIn(600, 960) ?: 900
    return DpSize(MaxContentWidth + 20.dp, height.dp)
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
