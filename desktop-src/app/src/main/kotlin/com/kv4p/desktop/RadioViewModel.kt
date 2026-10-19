package com.kv4p.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vagell.kv4pht.radio.DesktopRadioSession
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState
import com.vagell.kv4pht.radio.Protocol
import com.vagell.kv4pht.radio.SerialPortTransport
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.SwingUtilities

/**
 * UI state for the main screen. Radio callbacks arrive on the radio thread and
 * are moved to the UI thread before touching Compose state.
 */
class RadioViewModel {
    private val prefs = Preferences.userRoot().node("kv4p-ht-desktop")
    private val audio = AudioOutput()

    var ports by mutableStateOf<List<SerialPortTransport.PortInfo>>(emptyList())
        private set
    var selectedPort by mutableStateOf<String?>(prefs.get(PREF_PORT, null))
    var connection by mutableStateOf(ConnectionState.DISCONNECTED)
        private set
    var connectionDetail by mutableStateOf("")
        private set
    var firmware by mutableStateOf<Protocol.FirmwareVersion?>(null)
        private set
    var rxFrequency by mutableStateOf<Float?>(null)
        private set
    var sMeter by mutableStateOf(0)
        private set
    var squelched by mutableStateOf(true)
        private set
    var txActive by mutableStateOf(false)
        private set
    var squelch by mutableStateOf(prefs.getInt(PREF_SQUELCH, 1))
        private set
    var volume by mutableStateOf(prefs.getFloat(PREF_VOLUME, 0.8f))
        private set

    /** True while we are transmitting (app PTT, keyboard or the radio's PTT button). */
    var transmitting by mutableStateOf(false)
        private set
    var txMessage by mutableStateOf("")
        private set
    var txAllowed by mutableStateOf(false)
        private set
    var micLevel by mutableStateOf(0f)
        private set
    var micGain by mutableStateOf(prefs.get(PREF_MIC_GAIN, "None"))
        private set
    var txRegion by mutableStateOf(prefs.get(PREF_TX_REGION, REGION_1))
        private set

    private val mic = MicInput(
        onFrame = { frame -> session.sendTxAudio(frame) },
        onLevel = { level -> ui { micLevel = level } },
    )
    val logLines = mutableStateListOf<String>()

    private val session = DesktopRadioSession(object : DesktopRadioSession.Listener {
        override fun onConnectionState(state: ConnectionState, detail: String?) = ui {
            connection = state
            connectionDetail = detail.orEmpty()
            if (state == ConnectionState.CONNECTED) {
                onConnected()
            } else if (state != ConnectionState.HANDSHAKE && state != ConnectionState.CONNECTING) {
                audio.stop()
            }
        }

        override fun onHello(version: Protocol.FirmwareVersion) = ui { firmware = version }

        override fun onDeviceState(
            state: Protocol.DeviceState,
            sMeter: Int,
            squelched: Boolean,
            txActive: Boolean,
        ) = ui {
            if (state.hasRadioConfig()) rxFrequency = state.freqRx
            this@RadioViewModel.sMeter = sMeter
            this@RadioViewModel.squelched = squelched
            this@RadioViewModel.txActive = txActive
            this@RadioViewModel.txAllowed = session.isTxAllowed
        }

        override fun onTxChanged(transmitting: Boolean, reason: String) {
            // Radio thread: start/stop the microphone right away, then update the UI.
            if (transmitting) {
                audio.muted = true
                if (!mic.start()) {
                    session.pttUp()
                    ui { txMessage = "Microphone could not be opened - see log" }
                    return
                }
            } else {
                mic.stop()
                audio.muted = false
            }
            ui {
                this@RadioViewModel.transmitting = transmitting
                txMessage = when {
                    transmitting -> ""
                    reason == "app" || reason.contains("released") -> ""
                    else -> reason
                }
            }
        }

        override fun onRxAudio(pcm: FloatArray, samples: Int) = audio.write(pcm, samples)
    })

    init {
        audio.volume = volume
        session.setMicGainBoost(micGain)
        session.setTxLimits(limitsFor(txRegion))
        AppLog.addListener { line ->
            ui {
                logLines += line
                if (logLines.size > MAX_LOG_LINES) logLines.removeRange(0, logLines.size - MAX_LOG_LINES)
            }
        }
        refreshPorts()
    }

    fun refreshPorts() {
        ports = DesktopRadioSession.listPorts()
        if (selectedPort == null || ports.none { it.systemPath == selectedPort }) {
            selectedPort = ports.firstOrNull { it.likelyKv4p }?.systemPath ?: ports.firstOrNull()?.systemPath
        }
    }

    fun connect() {
        val port = selectedPort ?: return
        prefs.put(PREF_PORT, port)
        firmware = null
        audio.start()
        session.connect(port)
    }

    fun disconnect() = session.disconnect()

    private fun onConnected() {
        session.setSquelch(squelch)
        val saved = prefs.getFloat(PREF_FREQ, 0f)
        val current = rxFrequency
        if (saved > 0f && (current == null || current == 0f)) {
            tune(saved)
        }
    }

    /** Accepts input like "146.52", "146520" or "1465200", same as the Android app. */
    fun tune(input: String): Boolean {
        val parsed = input.trim().replace(',', '.').toFloatOrNull() ?: return false
        var freq = parsed
        while (freq > 1000f) freq /= 10f
        return tune(freq)
    }

    private fun tune(freq: Float): Boolean {
        val fw = firmware ?: return false
        if (freq < fw.minRadioFreq || freq > fw.maxRadioFreq) return false
        prefs.putFloat(PREF_FREQ, freq)
        session.tuneSimplex(freq)
        return true
    }

    fun updateSquelch(value: Int) {
        squelch = value
        prefs.putInt(PREF_SQUELCH, value)
        session.setSquelch(value)
    }

    fun updateVolume(value: Float) {
        volume = value
        audio.volume = value
        prefs.putFloat(PREF_VOLUME, value)
    }

    fun pttDown() {
        if (connection != ConnectionState.CONNECTED || transmitting) return
        txMessage = ""
        session.pttDown()
    }

    fun pttUp() = session.pttUp()

    fun updateMicGain(label: String) {
        micGain = label
        prefs.put(PREF_MIC_GAIN, label)
        session.setMicGainBoost(label)
    }

    fun updateTxRegion(region: String) {
        txRegion = region
        prefs.put(PREF_TX_REGION, region)
        session.setTxLimits(limitsFor(region))
    }

    val txRangeLabel: String
        get() {
            val l = limitsFor(txRegion)
            return if (firmware?.moduleType == Protocol.RfModuleType.RF_SA818_UHF) {
                "${l.min70cm.toInt()}-${l.max70cm.toInt()} MHz"
            } else {
                "${l.min2m.toInt()}-${l.max2m.toInt()} MHz"
            }
        }

    fun shutdown() {
        session.pttUp()
        mic.stop()
        session.close()
        audio.stop()
    }

    val bandLabel: String
        get() = when (firmware?.moduleType) {
            Protocol.RfModuleType.RF_SA818_UHF -> "70cm"
            Protocol.RfModuleType.RF_SA818_VHF -> "2m"
            else -> ""
        }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    companion object {
        private const val PREF_PORT = "port"
        private const val PREF_FREQ = "frequency"
        private const val PREF_SQUELCH = "squelch"
        private const val PREF_VOLUME = "volume"
        private const val PREF_MIC_GAIN = "micGain"
        private const val PREF_TX_REGION = "txRegion"
        private const val MAX_LOG_LINES = 400

        const val REGION_1 = "IARU Region 1 (Europe)"
        const val REGION_US = "US (Android app default)"
        val MIC_GAINS = listOf("None", "Low", "Med", "High")

        fun limitsFor(region: String): DesktopRadioSession.TxLimits =
            if (region == REGION_US) DesktopRadioSession.TxLimits.US else DesktopRadioSession.TxLimits.IARU_REGION_1

        fun formatFrequency(freq: Float?): String =
            if (freq == null || freq == 0f) "---.----" else String.format(Locale.US, "%.4f", freq)
    }
}
