package com.kv4p.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vagell.kv4pht.desktop.AprsController
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
    var txRegion by mutableStateOf(prefs.get(PREF_TX_REGION, REGION_1).let { if (it == REGION_US) it else REGION_1 })
        private set
    var highPower by mutableStateOf(prefs.getBoolean(PREF_HIGH_POWER, true))
        private set

    // ------------------------------------------------------------------ APRS

    /** Received and sent APRS entries, oldest first. */
    val aprsEntries = mutableStateListOf<AprsController.Entry>()
    /** Bumped when an entry changes in place (e.g. a sent message got acked). */
    var aprsRevision by mutableStateOf(0)
        private set
    var aprsCallsign by mutableStateOf(prefs.get(PREF_APRS_CALL, ""))
        private set
    var aprsPosition by mutableStateOf(prefs.get(PREF_APRS_POS, ""))
        private set
    var aprsSymbol by mutableStateOf(prefs.get(PREF_APRS_SYMBOL, "House"))
        private set
    var aprsApprox by mutableStateOf(prefs.getBoolean(PREF_APRS_APPROX, false))
        private set
    var aprsDigipeat by mutableStateOf(prefs.getBoolean(PREF_APRS_DIGI, false))
        private set
    var aprsBeaconMinutes by mutableStateOf(prefs.getInt(PREF_APRS_BEACON_MIN, 0))
        private set
    var aprsComment by mutableStateOf(prefs.get(PREF_APRS_COMMENT, "kv4p HT desktop"))
        private set
    var aprsStatus by mutableStateOf("")
        private set
    var aprsUnread by mutableStateOf(0)
        private set

    private val aprs: AprsController = AprsController(object : AprsController.Sink {
        override fun sendAx25(frameWithoutCrc: ByteArray): Boolean = session.sendAx25(frameWithoutCrc)

        override fun onEntry(entry: AprsController.Entry) = ui {
            aprsEntries += entry
            if (aprsEntries.size > MAX_APRS_ENTRIES) aprsEntries.removeRange(0, aprsEntries.size - MAX_APRS_ENTRIES)
            if (!entry.outgoing) aprsUnread++
        }

        override fun onEntryUpdated(entry: AprsController.Entry) = ui { aprsRevision++ }
    })
    private val beaconTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kv4p-beacon").apply { isDaemon = true }
    }
    private var beaconTask: java.util.concurrent.ScheduledFuture<*>? = null

    private val mic: MicInput = MicInput(
        onFrame = { frame -> session.sendTxAudio(frame) },
        onLevel = { level -> ui { micLevel = level } },
    )
    val logLines = mutableStateListOf<String>()

    private val session: DesktopRadioSession = DesktopRadioSession(object : DesktopRadioSession.Listener {
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

        override fun onAx25Packet(packet: ByteArray) = aprs.handleAx25(packet)
    })

    init {
        audio.volume = volume
        session.setMicGainBoost(micGain)
        session.setTxLimits(limitsFor(txRegion))
        applyAprsSettings()
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
        session.setHighPower(highPower)
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

    fun updateHighPower(high: Boolean) {
        highPower = high
        prefs.putBoolean(PREF_HIGH_POWER, high)
        session.setHighPower(high)
    }

    /** Volume slider position 0..1: 0.5 = unity gain, 1.0 = +12 dB (quadratic, feels even). */
    val volumeSlider: Float
        get() = kotlin.math.sqrt(volume / AudioOutput.MAX_VOLUME)

    fun updateVolumeSlider(position: Float) =
        updateVolume(AudioOutput.MAX_VOLUME * position * position)

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

    // ------------------------------------------------------------------ APRS actions

    /** Accepts a Maidenhead locator (JN88ne) or "lat, lon" in decimal degrees. */
    fun parsePosition(text: String): DoubleArray? {
        AprsController.locatorToLatLon(text)?.let { return it }
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) doubleArrayOf(lat, lon) else null
    }

    val myPosition: DoubleArray? get() = parsePosition(aprsPosition)

    fun updateAprsSettings(
        callsign: String = aprsCallsign,
        position: String = aprsPosition,
        symbol: String = aprsSymbol,
        approx: Boolean = aprsApprox,
        digipeat: Boolean = aprsDigipeat,
        beaconMinutes: Int = aprsBeaconMinutes,
        comment: String = aprsComment,
    ) {
        aprsCallsign = callsign.trim().uppercase()
        aprsPosition = position
        aprsSymbol = symbol
        aprsApprox = approx
        aprsDigipeat = digipeat
        aprsBeaconMinutes = beaconMinutes
        aprsComment = comment
        prefs.put(PREF_APRS_CALL, aprsCallsign)
        prefs.put(PREF_APRS_POS, position)
        prefs.put(PREF_APRS_SYMBOL, symbol)
        prefs.putBoolean(PREF_APRS_APPROX, approx)
        prefs.putBoolean(PREF_APRS_DIGI, digipeat)
        prefs.putInt(PREF_APRS_BEACON_MIN, beaconMinutes)
        prefs.put(PREF_APRS_COMMENT, comment)
        applyAprsSettings()
    }

    private fun applyAprsSettings() {
        aprs.setCallsign(aprsCallsign)
        aprs.setDigipeat(aprsDigipeat)
        aprs.setApproximatePosition(aprsApprox)
        aprs.setSymbolCode(APRS_SYMBOLS[aprsSymbol] ?: '-')
        val pos = myPosition
        aprs.setMyPosition(pos?.get(0), pos?.get(1))
        beaconTask?.cancel(false)
        beaconTask = null
        if (aprsBeaconMinutes > 0) {
            val period = aprsBeaconMinutes.toLong()
            beaconTask = beaconTimer.scheduleAtFixedRate({
                if (connection == ConnectionState.CONNECTED && !transmitting) beaconNow()
            }, period, period, java.util.concurrent.TimeUnit.MINUTES)
        }
    }

    /** Callsign check: AX.25 allows 1-6 letters/digits plus an optional -SSID (0-15). */
    val aprsCallsignValid: Boolean
        get() = Regex("^[A-Z0-9]{1,6}(-([0-9]|1[0-5]))?$").matches(aprsCallsign)

    fun sendAprsMessage(to: String, text: String): Boolean {
        if (text.isBlank()) return false
        if (!aprsCallsignValid) {
            aprsStatus = "Set a valid callsign first (max 6 characters + optional -SSID)"
            return false
        }
        val sent = aprs.sendMessage(to, text.trim())
        aprsStatus = if (sent == null) "Not sent - check connection and TX band" else ""
        return sent != null
    }

    fun beaconNow() {
        val ok = aprsCallsignValid && myPosition != null && aprs.sendPositionBeacon(
            if (aprsComment.isBlank()) null else " " + aprsComment.trim()
        ) != null
        ui {
            aprsStatus = when {
                !aprsCallsignValid -> "Set a valid callsign first"
                myPosition == null -> "Set your position (locator like JN88ne, or lat, lon)"
                !ok -> "Beacon not sent - check connection and TX band"
                else -> ""
            }
        }
    }

    fun clearAprsUnread() {
        aprsUnread = 0
    }

    fun tuneAprs() = tune(APRS_FREQ_REGION_1)

    fun shutdown() {
        beaconTimer.shutdownNow()
        aprs.close()
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
        private const val PREF_HIGH_POWER = "highPower"
        private const val PREF_APRS_CALL = "aprsCallsign"
        private const val PREF_APRS_POS = "aprsPosition"
        private const val PREF_APRS_SYMBOL = "aprsSymbol"
        private const val PREF_APRS_APPROX = "aprsApprox"
        private const val PREF_APRS_DIGI = "aprsDigipeat"
        private const val PREF_APRS_BEACON_MIN = "aprsBeaconMinutes"
        private const val PREF_APRS_COMMENT = "aprsComment"
        private const val MAX_APRS_ENTRIES = 500

        /** European APRS frequency (IARU Region 1). */
        const val APRS_FREQ_REGION_1 = 144.800f
        val APRS_BEACON_CHOICES = listOf(0, 5, 10, 30, 60)

        /** Primary-table APRS symbols, same set as the Android app plus a house for fixed stations. */
        val APRS_SYMBOLS = linkedMapOf(
            "House" to '-', "Person" to '[', "Phone" to '$', "Car" to '>', "Jeep" to 'j',
            "Truck" to 'k', "Van" to 'v', "Motorcycle" to '<', "Bicycle" to 'b', "RV" to 'R',
            "Sailboat" to 'Y', "Motorboat" to 's',
        )
        private const val MAX_LOG_LINES = 400

        const val REGION_1 = "IARU R1 (EU)"
        const val REGION_US = "US"
        val MIC_GAINS = listOf("None", "Low", "Med", "High")

        fun limitsFor(region: String): DesktopRadioSession.TxLimits =
            if (region == REGION_US) DesktopRadioSession.TxLimits.US else DesktopRadioSession.TxLimits.IARU_REGION_1

        fun formatFrequency(freq: Float?): String =
            if (freq == null || freq == 0f) "---.----" else String.format(Locale.US, "%.4f", freq)
    }
}
