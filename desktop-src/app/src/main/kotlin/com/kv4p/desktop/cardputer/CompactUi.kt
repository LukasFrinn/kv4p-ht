package com.kv4p.desktop.cardputer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kv4p.desktop.RadioViewModel
import com.kv4p.desktop.ui.AprsSymbol
import com.kv4p.desktop.ui.Jost
import com.kv4p.desktop.ui.Kv4pColors
import com.kv4p.desktop.ui.MapConfig
import com.kv4p.desktop.ui.MapPreview
import com.kv4p.desktop.ui.hasAprsSymbol
import com.vagell.kv4pht.desktop.AprsController
import com.vagell.kv4pht.desktop.AprsController.Kind
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The 320x170 keyboard-only UI for the Cardputer Zero's built-in LCD. No focus or pointer:
 * every key goes through [onKey], which changes the state below; the composables only draw it.
 */
class CompactUi(private val vm: RadioViewModel, private val onExit: () -> Unit) {
    enum class Screen(val title: String) { RADIO("Radio"), MEMORIES("Memories"), APRS("APRS"), APRS_DETAIL("APRS"), HELP("Keys") }
    enum class Entry { FREQ, MSG_TO, MSG_TEXT }

    var screen by mutableStateOf(Screen.RADIO)
    var memorySel by mutableIntStateOf(0)
    var aprsSel by mutableIntStateOf(0)
    var mapZoom by mutableIntStateOf(MapConfig.ZOOM)
    var entry by mutableStateOf<Entry?>(null)
    var entryText by mutableStateOf("")
    var messageTo by mutableStateOf("")
    var flash by mutableStateOf("")
    /** Reconnect automatically; off after the user disconnects with C. */
    var autoConnect = true
    private var flashAt = 0L
    private var exitArmedAt = 0L
    private var pttHeld = false

    /** Newest first, as shown. */
    val aprsList: List<AprsController.Entry> get() = vm.aprsEntries.asReversed()

    private fun say(text: String) {
        flash = text
        flashAt = System.currentTimeMillis()
    }

    /** Clears an old message; called from the render loop. */
    fun tick() {
        if (flash.isNotEmpty() && System.currentTimeMillis() - flashAt > 3000) flash = ""
    }

    private enum class Nav { UP, DOWN, LEFT, RIGHT }

    /**
     * Arrow keys. The Cardputer Zero's arrows are Fn + F / Z / X / C (up, left, down, right), which
     * its keyboard driver may send as arrow keys or as Fn plus the letter. ; . , / (the original
     * Cardputer's arrows) also work while not typing.
     */
    private fun navOf(k: KeyPress): Nav? = when (k.code) {
        Keys.UP -> Nav.UP
        Keys.DOWN -> Nav.DOWN
        Keys.LEFT -> Nav.LEFT
        Keys.RIGHT -> Nav.RIGHT
        else -> if (k.fn) when (k.code) {
            Keys.F -> Nav.UP
            Keys.Z -> Nav.LEFT
            Keys.X -> Nav.DOWN
            Keys.C -> Nav.RIGHT
            else -> null
        } else if (entry == null) when (k.code) {
            Keys.SEMICOLON -> Nav.UP
            Keys.DOT -> Nav.DOWN
            Keys.COMMA -> Nav.LEFT
            Keys.SLASH -> Nav.RIGHT
            else -> null
        } else null
    }

    fun onKey(k: KeyPress) {
        // Space = push to talk while not typing; held as long as the key is down.
        if (k.code == Keys.SPACE && entry == null) {
            if (k.down && !pttHeld) {
                pttHeld = true
                vm.pttDown()
            } else if (!k.down && !k.repeat && pttHeld) {
                pttHeld = false
                vm.pttUp()
            }
            return
        }
        if (!k.pressed) return
        if (k.ctrl && (k.char == 'q' || k.char == 'Q')) return onExit()
        // Hardware volume keys (Fn + S / D on the Cardputer Zero), anywhere.
        if (k.code == Keys.VOLUME_UP || k.code == Keys.VOLUME_DOWN) {
            vm.updateVolumeSlider((vm.volumeSlider + if (k.code == Keys.VOLUME_UP) 0.05f else -0.05f).coerceIn(0f, 1f))
            return
        }
        val nav = navOf(k)
        if (entry != null) {
            // Fn + key is a function (arrow, volume...), not a letter to type.
            if (k.fn) return
            return typing(k)
        }
        if (k.code == Keys.TAB) return nextScreen()
        if (nav == null && k.fn) return
        when (screen) {
            Screen.RADIO -> radioKey(k, nav)
            Screen.MEMORIES -> memoriesKey(k, nav)
            Screen.APRS -> aprsKey(k, nav)
            Screen.APRS_DETAIL -> detailKey(k, nav)
            Screen.HELP -> if (k.code == Keys.ESC || k.code == Keys.ENTER) screen = Screen.RADIO
        }
    }

    private fun nextScreen() {
        screen = when (screen) {
            Screen.RADIO -> Screen.MEMORIES
            Screen.MEMORIES -> Screen.APRS
            else -> Screen.RADIO
        }
        if (screen == Screen.MEMORIES) {
            val i = vm.visibleMemories.indexOfFirst { it.id == vm.activeMemoryId }
            if (i >= 0) memorySel = i
        }
        if (screen == Screen.APRS) vm.clearAprsUnread()
    }

    private fun radioKey(k: KeyPress, nav: Nav?) {
        val connected = vm.connection == ConnectionState.CONNECTED
        when {
            nav == Nav.UP || nav == Nav.DOWN -> stepMemory(if (nav == Nav.UP) -1 else 1)
            nav == Nav.LEFT || nav == Nav.RIGHT -> {
                val f = vm.rxFrequency ?: return
                val next = f + if (nav == Nav.RIGHT) STEP_MHZ else -STEP_MHZ
                if (!vm.tune(String.format(Locale.US, "%.4f", next))) say("Out of this radio's range")
            }
            k.char?.isDigit() == true -> {
                entry = Entry.FREQ
                entryText = k.char.toString()
            }
            k.char == '[' || k.char == ']' -> vm.updateSquelch((vm.squelch + if (k.char == ']') 1 else -1).coerceIn(0, 8))
            k.char == '-' || k.char == '=' ->
                vm.updateVolumeSlider((vm.volumeSlider + if (k.char == '=') 0.05f else -0.05f).coerceIn(0f, 1f))
            k.char == 'p' -> vm.updateHighPower(!vm.highPower)
            k.char == 's' -> if (vm.scanning) vm.stopScan() else if (connected) vm.startScan()
            k.char == 'c' -> if (connected) {
                autoConnect = false
                vm.disconnect()
            } else {
                autoConnect = true
                vm.refreshPorts()
                vm.connect()
            }
            k.char == 'h' || k.char == '?' -> screen = Screen.HELP
            k.code == Keys.ESC -> {
                val now = System.currentTimeMillis()
                if (now - exitArmedAt < 2500) onExit() else {
                    exitArmedAt = now
                    say("Press Esc again to quit")
                }
            }
        }
    }

    private fun stepMemory(delta: Int) {
        val list = vm.visibleMemories
        if (list.isEmpty()) return say("No memories - add them in the desktop app")
        val i = list.indexOfFirst { it.id == vm.activeMemoryId }
        val next = if (i < 0) (if (delta > 0) 0 else list.lastIndex) else (i + delta).mod(list.size)
        vm.tuneMemory(list[next])
    }

    private fun memoriesKey(k: KeyPress, nav: Nav?) {
        val list = vm.visibleMemories
        when {
            nav == Nav.UP -> memorySel = (memorySel - 1).coerceAtLeast(0)
            nav == Nav.DOWN -> memorySel = (memorySel + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
            k.code == Keys.ENTER -> list.getOrNull(memorySel)?.let {
                vm.tuneMemory(it)
                screen = Screen.RADIO
            }
            k.char == 'g' -> {
                val groups = listOf(RadioViewModel.ALL_GROUPS) + vm.memoryGroups
                val i = groups.indexOfFirst { it.equals(vm.selectedGroup, ignoreCase = true) }
                vm.selectGroup(groups[(i + 1).mod(groups.size)])
                memorySel = 0
            }
            k.code == Keys.ESC -> screen = Screen.RADIO
        }
    }

    private fun aprsKey(k: KeyPress, nav: Nav?) {
        val list = aprsList
        vm.clearAprsUnread()
        when {
            nav == Nav.UP -> aprsSel = (aprsSel - 1).coerceAtLeast(0)
            nav == Nav.DOWN -> aprsSel = (aprsSel + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
            k.code == Keys.ENTER -> if (list.isNotEmpty()) screen = Screen.APRS_DETAIL
            k.char == 'm' -> startMessage(null)
            k.char == 'r' -> list.getOrNull(aprsSel)?.let { startMessage(it) }
            k.char == 'b' -> { vm.beaconNow(); say("Beacon sent") }
            k.char == 't' -> vm.tuneAprs()
            k.code == Keys.ESC -> screen = Screen.RADIO
        }
    }

    private fun detailKey(k: KeyPress, nav: Nav?) {
        val list = aprsList
        when {
            nav == Nav.UP -> aprsSel = (aprsSel - 1).coerceAtLeast(0)
            nav == Nav.DOWN -> aprsSel = (aprsSel + 1).coerceAtMost((list.size - 1).coerceAtLeast(0))
            k.char == '=' || k.char == '+' -> mapZoom = (mapZoom + 1).coerceAtMost(MapConfig.ZOOM + 2)
            k.char == '-' -> mapZoom = (mapZoom - 1).coerceAtLeast(MapConfig.OFFLINE_MIN_ZOOM)
            k.char == 'r' -> list.getOrNull(aprsSel)?.let { startMessage(it) }
            k.code == Keys.ESC || k.code == Keys.ENTER -> screen = Screen.APRS
        }
    }

    private fun startMessage(replyTo: AprsController.Entry?) {
        val to = replyTo?.let { if (it.outgoing) it.to else it.from }
        if (to != null && !to.startsWith("BLN")) {
            messageTo = to
            entry = Entry.MSG_TEXT
        } else {
            messageTo = ""
            entry = Entry.MSG_TO
        }
        entryText = ""
    }

    private fun typing(k: KeyPress) {
        when (k.code) {
            Keys.ESC -> { entry = null; entryText = ""; return }
            Keys.BACKSPACE, Keys.DELETE -> { entryText = entryText.dropLast(1); return }
            Keys.ENTER -> return finishEntry()
        }
        val c = k.char ?: return
        entryText = when (entry) {
            Entry.FREQ -> if (c.isDigit() || c == '.') (entryText + c).take(9) else entryText
            Entry.MSG_TO -> if (c.isLetterOrDigit() || c == '-') (entryText + c.uppercaseChar()).take(9) else entryText
            Entry.MSG_TEXT -> (entryText + c).take(67)
            null -> entryText
        }
    }

    private fun finishEntry() {
        when (entry) {
            Entry.FREQ -> {
                if (!vm.tune(entryText)) say("Can't tune $entryText")
                entry = null
            }
            Entry.MSG_TO -> {
                messageTo = entryText.trim()
                entryText = ""
                entry = Entry.MSG_TEXT
                return
            }
            Entry.MSG_TEXT -> {
                if (entryText.isBlank()) return
                // An empty "to" is a bulletin to everyone, like the desktop chat.
                val ok = vm.sendAprsMessage(messageTo.ifBlank { "BLN0" }, entryText)
                say(if (ok) "Sent" else vm.aprsStatus.ifEmpty { "Not sent" })
                entry = null
            }
            null -> Unit
        }
        entryText = ""
    }

    companion object {
        const val STEP_MHZ = 0.0125f
    }
}

// ---------------------------------------------------------------------------- drawing

private val Cream = Kv4pColors.Primary
private val Dim = Color(0xFF9A978A)
private val BarBg = Color(0xFF1C1C1A)
private val SelBg = Color(0xFF4A2A22)
private val Green = Color(0xFF6FBF73)
private val Red = Color(0xFFD9534F)
private val Key = Color(0xFFE0B860)

private fun style(size: TextUnit, color: Color = Cream, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Jost, fontSize = size, color = color, fontWeight = weight)

@Composable
private fun T(text: String, size: TextUnit = 12.sp, color: Color = Cream, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style(size, color, weight), maxLines = 1, overflow = TextOverflow.Clip)

@Composable
fun CompactApp(ui: CompactUi, vm: RadioViewModel) {
    Column(Modifier.fillMaxSize().background(Kv4pColors.Black)) {
        TopBar(ui, vm)
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (ui.screen) {
                CompactUi.Screen.RADIO -> RadioPage(ui, vm)
                CompactUi.Screen.MEMORIES -> MemoriesPage(ui, vm)
                CompactUi.Screen.APRS -> AprsPage(ui, vm)
                CompactUi.Screen.APRS_DETAIL -> AprsDetailPage(ui, vm)
                CompactUi.Screen.HELP -> HelpPage()
            }
        }
        HintBar(ui)
    }
}

@Composable
private fun TopBar(ui: CompactUi, vm: RadioViewModel) {
    val connected = vm.connection == ConnectionState.CONNECTED
    Row(
        Modifier.fillMaxWidth().height(15.dp).background(BarBg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(if (connected) Green else Red))
        T("kv4p", 11.sp, Dim)
        when {
            vm.transmitting -> T("TX", 11.sp, Red, FontWeight.Medium)
            !connected -> T(vm.connection.name.lowercase(), 11.sp, Dim)
            vm.squelched -> T("RX · sq", 11.sp, Dim)
            else -> T("RX", 11.sp, Green, FontWeight.Medium)
        }
        if (ui.screen != CompactUi.Screen.RADIO) vm.rxFrequency?.let { T(freqText(it), 11.sp, Dim) }
        Spacer(Modifier.weight(1f))
        if (vm.aprsUnread > 0 && ui.screen != CompactUi.Screen.APRS) T("APRS ${vm.aprsUnread} new", 11.sp, Key)
        T(ui.screen.title, 11.sp, Cream)
    }
}

@Composable
private fun HintBar(ui: CompactUi) {
    val hint = when {
        ui.flash.isNotEmpty() -> null
        ui.entry != null -> "Enter|ok  Del|erase  Esc|cancel"
        else -> when (ui.screen) {
            CompactUi.Screen.RADIO -> "Space|PTT  ↑↓|memory  ←→|step  0-9|freq  H|keys"
            CompactUi.Screen.MEMORIES -> "↑↓|select  Enter|tune  G|group  Tab|next"
            CompactUi.Screen.APRS -> "↑↓|select  Enter|map  R|reply  M|msg  B|beacon"
            CompactUi.Screen.APRS_DETAIL -> "↑↓|station  +/-|zoom  R|reply  Esc|back"
            CompactUi.Screen.HELP -> "Esc|back"
        }
    }
    Row(
        Modifier.fillMaxWidth().height(14.dp).background(BarBg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hint == null) {
            T(ui.flash, 11.sp, Key)
        } else {
            hint.split("  ").forEach { part ->
                val (k, what) = part.split('|')
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    T(k, 11.sp, Key)
                    T(what, 11.sp, Dim)
                }
            }
        }
    }
}

private fun freqText(f: Float): String {
    val s = String.format(Locale.US, "%.4f", f)
    return if (s.endsWith("0")) s.dropLast(1) else s
}

@Composable
private fun RadioPage(ui: CompactUi, vm: RadioViewModel) {
    val connected = vm.connection == ConnectionState.CONNECTED
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
        if (!connected) {
            T(
                when (vm.connection) {
                    ConnectionState.CONNECTING, ConnectionState.HANDSHAKE -> "Connecting…"
                    ConnectionState.DISCONNECTED -> "Not connected"
                    else -> vm.connection.name.lowercase().replace('_', ' ')
                },
                22.sp,
                Cream,
            )
            T(
                vm.ports.firstOrNull { it.likelyKv4p }?.systemPath ?: "No kv4p HT found - plug it in by USB",
                12.sp,
                Dim,
            )
            if (vm.connectionDetail.isNotEmpty()) T(vm.connectionDetail, 11.sp, Dim)
            Spacer(Modifier.height(6.dp))
            T("Connects by itself when the radio is plugged in", 11.sp, Dim)
            return
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (ui.entry == CompactUi.Entry.FREQ) {
                T(ui.entryText + "_", 40.sp, Key)
            } else {
                T(vm.rxFrequency?.let { freqText(it) } ?: "---", 40.sp, Cream)
            }
            Spacer(Modifier.width(4.dp))
            T("MHz", 12.sp, Dim, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            if (vm.transmitting) {
                Box(Modifier.background(Red).padding(horizontal = 6.dp)) { T("TX", 18.sp, Cream, FontWeight.Medium) }
            }
        }
        T(
            listOfNotNull(vm.activeMemory?.name?.ifBlank { null }, vm.bandLabel.ifEmpty { null }, if (vm.scanning) "scanning" else null)
                .joinToString(" · ").ifEmpty { "Simplex" },
            14.sp,
            if (vm.scanning) Key else Cream,
        )
        Spacer(Modifier.height(4.dp))
        SMeterBar(vm.sMeter)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Param("SQ", "${vm.squelch}")
            Param("VOL", "${(vm.volumeSlider * 100).toInt()}%")
            Param("PWR", if (vm.highPower) "high" else "low")
            if (!vm.txAllowed) T("RX only here", 12.sp, Dim)
        }
        if (vm.txMessage.isNotEmpty()) T(vm.txMessage, 11.sp, Red)
    }
}

@Composable
private fun RowScope.Param(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        T(label, 12.sp, Dim)
        T(value, 12.sp, Cream, FontWeight.Medium)
    }
}

@Composable
private fun SMeterBar(value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        T("S", 12.sp, Dim)
        Spacer(Modifier.width(2.dp))
        for (i in 1..9) {
            Box(
                Modifier.width(25.dp).height(9.dp).background(
                    when {
                        value < i -> Color(0xFF26261F)
                        i == 9 -> Kv4pColors.OverS9
                        else -> Cream
                    }
                )
            )
        }
        Spacer(Modifier.width(3.dp))
        T(if (value <= 0) "" else "S$value", 12.sp, Dim)
    }
}

@Composable
private fun MemoriesPage(ui: CompactUi, vm: RadioViewModel) {
    val list = vm.visibleMemories
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            T("Group: ${vm.selectedGroup}", 11.sp, Dim)
            Spacer(Modifier.weight(1f))
            T("${list.size}", 11.sp, Dim)
        }
        if (list.isEmpty()) {
            T("  No memories. Add or import them in the desktop app.", 12.sp, Dim)
            return
        }
        val rows = 7
        val first = (ui.memorySel - rows / 2).coerceIn(0, (list.size - rows).coerceAtLeast(0))
        list.drop(first).take(rows).forEachIndexed { i, m ->
            val sel = first + i == ui.memorySel
            Row(
                Modifier.fillMaxWidth().height(17.dp).background(if (sel) SelBg else Kv4pColors.Black).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                T(if (m.id == vm.activeMemoryId) "▶" else " ", 12.sp, Key, modifier = Modifier.width(9.dp))
                T(m.name.ifBlank { "-" }, 13.sp, Cream, modifier = Modifier.width(130.dp))
                T(m.frequency, 13.sp, Cream)
                val extra = listOfNotNull(
                    when (m.offset) { 1 -> "−"; 2 -> "+"; else -> null },
                    m.txTone.takeIf { it != "None" }?.let { "T$it" },
                ).joinToString(" ")
                T(extra, 11.sp, Dim)
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun distance(e: AprsController.Entry, me: DoubleArray?): String {
    val lat = e.lat ?: return ""
    val lon = e.lon ?: return ""
    me ?: return ""
    val km = AprsController.distanceKm(me[0], me[1], lat, lon)
    return if (km < 10) String.format(Locale.US, "%.1f km", km) else "${km.toInt()} km"
}

private fun body(e: AprsController.Entry): String = when {
    e.kind == Kind.MESSAGE -> (if (e.outgoing) "→ ${e.to}: " else if (e.toMe) "to you: " else "") + e.text.orEmpty() +
        if (e.outgoing && e.acked) " ✓" else ""
    else -> listOfNotNull(e.objectName?.let { "obj $it" }, e.weather?.takeIf { it.isNotBlank() }, e.text, e.comment)
        .joinToString(" · ").ifEmpty { e.kind.name.lowercase() }
}

@Composable
private fun AprsPage(ui: CompactUi, vm: RadioViewModel) {
    val list = ui.aprsList
    val me = vm.myPosition
    @Suppress("UNUSED_VARIABLE") val revision = vm.aprsRevision
    LaunchedEffect(list.size) { vm.clearAprsUnread() }
    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            T("  No packets yet.", 13.sp, Cream)
            T("  T tunes 144.800, then wait for beacons.", 12.sp, Dim)
        } else {
            val rows = if (ui.entry != null) 6 else 7
            val sel = ui.aprsSel.coerceIn(0, list.lastIndex)
            val first = (sel - rows / 2).coerceIn(0, (list.size - rows).coerceAtLeast(0))
            Column(Modifier.weight(1f)) {
                list.drop(first).take(rows).forEachIndexed { i, e ->
                    Row(
                        Modifier.fillMaxWidth().height(17.dp)
                            .background(if (first + i == sel) SelBg else Kv4pColors.Black)
                            .padding(horizontal = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(14.dp)) {
                            if (hasAprsSymbol(e.symbolTable, e.symbolCode)) AprsSymbol(e.symbolTable, e.symbolCode, 14.dp)
                        }
                        T(timeFormat.format(Instant.ofEpochMilli(e.timeMillis)), 11.sp, Dim, modifier = Modifier.width(30.dp))
                        T(e.from ?: "?", 12.sp, if (e.toMe) Key else Cream, FontWeight.Medium, Modifier.width(72.dp))
                        T(distance(e, me), 11.sp, Dim, modifier = Modifier.width(38.dp))
                        T(body(e), 12.sp, if (e.kind == Kind.MESSAGE) Cream else Dim)
                    }
                }
            }
        }
        if (ui.entry == CompactUi.Entry.MSG_TO || ui.entry == CompactUi.Entry.MSG_TEXT) MessageLine(ui)
    }
}

@Composable
private fun MessageLine(ui: CompactUi) {
    Row(
        Modifier.fillMaxWidth().height(17.dp).background(BarBg).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (ui.entry == CompactUi.Entry.MSG_TO) {
            T("To (empty = everyone):", 12.sp, Dim)
            T(ui.entryText + "_", 12.sp, Key)
        } else {
            T("${ui.messageTo.ifBlank { "everyone" }}:", 12.sp, Dim)
            // Keep the end of a long message in view.
            T((ui.entryText + "_").takeLast(42), 12.sp, Key)
        }
    }
}

@Composable
private fun AprsDetailPage(ui: CompactUi, vm: RadioViewModel) {
    val list = ui.aprsList
    val e = list.getOrNull(ui.aprsSel.coerceIn(0, (list.size - 1).coerceAtLeast(0))) ?: return
    @Suppress("UNUSED_VARIABLE") val revision = vm.aprsRevision
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val lat = e.lat
            val lon = e.lon
            if (lat != null && lon != null) {
                MapPreview(lat, lon, widthDp = 150, heightDp = 118, z = ui.mapZoom, onClick = null)
            } else {
                Box(Modifier.size(150.dp, 118.dp).background(BarBg), contentAlignment = Alignment.Center) {
                    T("no position", 12.sp, Dim)
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasAprsSymbol(e.symbolTable, e.symbolCode)) AprsSymbol(e.symbolTable, e.symbolCode, 16.dp)
                    T(e.from ?: "?", 15.sp, Cream, FontWeight.Medium)
                }
                T("${e.kind.name.lowercase()} · ${timeFormat.format(Instant.ofEpochMilli(e.timeMillis))}", 11.sp, Dim)
                distance(e, vm.myPosition).takeIf { it.isNotEmpty() }?.let { T(it, 12.sp, Cream) }
                if (lat != null && lon != null) {
                    T(String.format(Locale.US, "%.4f, %.4f", lat, lon) + if (e.positionFromEarlier) " *" else "", 11.sp, Dim)
                }
                e.relay?.let { T("via $it", 11.sp, Dim) }
                BasicText(
                    body(e),
                    Modifier.padding(top = 3.dp),
                    style(12.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                T("zoom ${ui.mapZoom}", 11.sp, Dim)
            }
        }
        if (ui.entry == CompactUi.Entry.MSG_TEXT) MessageLine(ui)
    }
}

@Composable
private fun HelpPage() {
    val keys = listOf(
        "Space" to "push to talk (hold)",
        "Tab" to "Radio → Memories → APRS",
        "↑ ↓  (Fn F / X)" to "memory / select",
        "← →  (Fn Z / C)" to "frequency step 12.5 kHz",
        "0-9 Enter" to "type a frequency",
        "[  ]" to "squelch   - =  (Fn S D) volume",
        "P  S  C" to "power, scan, connect",
        "Esc Esc / Ctrl+Q" to "quit",
    )
    Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
        keys.forEach { (k, what) ->
            Row(Modifier.height(15.dp), verticalAlignment = Alignment.CenterVertically) {
                T(k, 12.sp, Key, modifier = Modifier.width(110.dp))
                T(what, 12.sp, Cream)
            }
        }
        T("Settings, APRS station and offline map: desktop app on HDMI.", 11.sp, Dim)
    }
}
