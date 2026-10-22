package com.kv4p.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kv4p.desktop.RadioViewModel
import com.vagell.kv4pht.desktop.AprsController
import com.vagell.kv4pht.desktop.AprsController.Kind
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/** APRS tab: received packets and chat on the left, station settings on the right. */
@Composable
fun AprsScreen(vm: RadioViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { vm.clearAprsUnread() }
    Row(modifier) {
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .border(1.dp, Kv4pColors.MediumGray, RoundedCornerShape(8.dp))
                .padding(12.dp),
        ) {
            AprsHeader(vm)
            Spacer(Modifier.height(8.dp))
            AprsList(vm, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ChatInput(vm)
        }
        Spacer(Modifier.width(12.dp))
        AprsSettings(vm, Modifier.width(300.dp).fillMaxHeight())
    }
}

@Composable
private fun AprsHeader(vm: RadioViewModel) {
    val connected = vm.connection == ConnectionState.CONNECTED
    val onAprs = vm.rxFrequency?.let { kotlin.math.abs(it - RadioViewModel.APRS_FREQ_REGION_1) < 0.001f } == true
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("APRS", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                !connected -> "Not connected"
                onAprs -> "Listening on ${RadioViewModel.formatFrequency(vm.rxFrequency)} MHz"
                else -> "Radio is on ${RadioViewModel.formatFrequency(vm.rxFrequency)} MHz"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Kv4pColors.PrimaryDeselected,
        )
        if (connected && !onAprs) {
            OutlinedButton(onClick = { vm.tuneAprs() }) { Text("Tune 144.800") }
        }
        Spacer(Modifier.weight(1f))
        Text("${vm.aprsEntries.size} packets", style = MaterialTheme.typography.bodySmall, color = Kv4pColors.MediumGray)
    }
    Text(
        "Tip: squelch 0 and all three filters off decode best - on the receiving radio too (kv4p FAQ).",
        style = MaterialTheme.typography.bodySmall,
        color = Kv4pColors.MediumGray,
    )
}

@Composable
private fun AprsList(vm: RadioViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.aprsEntries.size) {
        if (vm.aprsEntries.isNotEmpty()) listState.animateScrollToItem(vm.aprsEntries.lastIndex)
    }
    // Read the revision so rows recompose when an outgoing message gets acked.
    val revision = vm.aprsRevision
    val me = vm.myPosition
    if (vm.aprsEntries.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No packets yet.\nTune to 144.800 MHz and wait for stations to beacon.",
                color = Kv4pColors.MediumGray,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }
    WithScrollbar(listState, modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(vm.aprsEntries) { entry -> EntryRow(entry, me, revision) }
        }
    }
}

@Composable
private fun EntryRow(e: AprsController.Entry, me: DoubleArray?, @Suppress("UNUSED_PARAMETER") revision: Int) {
    val highlight = e.toMe || e.outgoing
    Column(
        Modifier.fillMaxWidth()
            .background(if (highlight) Kv4pColors.PrimaryVeryFaint else Kv4pColors.Black, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(timeFormat.format(Instant.ofEpochMilli(e.timeMillis)), style = MonoStyle.copy(fontSize = 12.sp), color = Kv4pColors.MediumGray)
            Text(kindLabel(e), style = MaterialTheme.typography.labelMedium, color = kindColor(e))
            Text(
                buildString {
                    append(e.from ?: "?")
                    if (e.to != null) append(" → ").append(if (e.to!!.startsWith("BLN")) "everyone (${e.to})" else e.to)
                    if (e.relay != null) append("  (via ").append(e.relay).append(')')
                },
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (e.kind == Kind.MESSAGE && e.outgoing && e.to?.startsWith("BLN") == false) {
                Text(
                    if (e.acked) "✓ delivered" else "sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (e.acked) androidx.compose.ui.graphics.Color(0xFF6FBF73) else Kv4pColors.MediumGray,
                )
            }
            Spacer(Modifier.weight(1f))
            distanceText(e, me)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Kv4pColors.PrimaryDeselected)
            }
        }
        val body = listOfNotNull(
            e.text,
            e.objectName?.let { "Object: $it" },
            e.weather?.takeIf { it.isNotBlank() },
            e.comment,
        ).joinToString(" · ")
        if (body.isNotBlank()) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Kv4pColors.Primary)
        }
    }
}

private fun kindLabel(e: AprsController.Entry): String = when (e.kind) {
    Kind.MESSAGE -> if (e.outgoing) "MSG OUT" else if (e.toMe) "MSG TO YOU" else "MSG"
    Kind.POSITION -> "POS"
    Kind.WEATHER -> "WX"
    Kind.OBJECT -> "OBJ"
    Kind.BEACON_SENT -> "BEACON OUT"
    Kind.ACK -> "ACK"
    Kind.OTHER -> "PKT"
}

private fun kindColor(e: AprsController.Entry) = when {
    e.toMe -> Kv4pColors.Accent
    e.outgoing -> Kv4pColors.OverS9
    else -> Kv4pColors.PrimaryDeselected
}

private fun distanceText(e: AprsController.Entry, me: DoubleArray?): String? {
    val lat = e.lat ?: return null
    val lon = e.lon ?: return null
    val pos = String.format(Locale.US, "%.4f, %.4f", lat, lon)
    if (me == null || e.outgoing) return pos
    val km = AprsController.distanceKm(me[0], me[1], lat, lon)
    return String.format(Locale.US, "%s · %.1f km", pos, km)
}

@Composable
private fun ChatInput(vm: RadioViewModel) {
    var to by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    val connected = vm.connection == ConnectionState.CONNECTED
    fun send() {
        if (vm.sendAprsMessage(to, text)) text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = to,
            onValueChange = { to = it.uppercase().take(9) },
            label = { Text("To (empty = everyone)") },
            singleLine = true,
            modifier = Modifier.width(170.dp).typingField(),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(67) }, // APRS message text limit
            label = { Text("Message") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).typingField(),
        )
        Button(onClick = { send() }, enabled = connected && text.isNotBlank()) { Text("Send") }
    }
    if (vm.aprsStatus.isNotEmpty()) {
        Text(vm.aprsStatus, color = Kv4pColors.Accent, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AprsSettings(vm: RadioViewModel, modifier: Modifier) {
    var callsign by remember { mutableStateOf(vm.aprsCallsign) }
    var position by remember { mutableStateOf(vm.aprsPosition) }
    var comment by remember { mutableStateOf(vm.aprsComment) }
    val positionOk = vm.parsePosition(position) != null
    val connected = vm.connection == ConnectionState.CONNECTED

    val scroll = rememberScrollState()
    WithScrollbar(scroll, modifier.border(1.dp, Kv4pColors.MediumGray, RoundedCornerShape(8.dp)).padding(start = 12.dp, top = 12.dp, bottom = 12.dp)) {
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Your station", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = callsign,
            onValueChange = {
                callsign = it.uppercase().take(9)
                vm.updateAprsSettings(callsign = callsign)
            },
            label = { Text("Callsign (e.g. OM0ABC-7)") },
            isError = callsign.isNotEmpty() && !vm.aprsCallsignValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().typingField(),
        )
        OutlinedTextField(
            value = position,
            onValueChange = {
                position = it
                vm.updateAprsSettings(position = it)
            },
            label = { Text("Position: locator (JN88ne) or lat, lon") },
            isError = position.isNotEmpty() && !positionOk,
            supportingText = {
                vm.myPosition?.let { Text(String.format(Locale.US, "%.4f, %.4f", it[0], it[1])) }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().typingField(),
        )
        OutlinedButton(
            onClick = { vm.usePcLocation { position = it } },
            enabled = !vm.locating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (vm.locating) "Locating…" else "Use this computer's location")
        }
        if (vm.locateStatus.isNotEmpty()) {
            Text(vm.locateStatus, style = MaterialTheme.typography.labelSmall, color = Kv4pColors.MediumGray)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.aprsApprox, onCheckedChange = { vm.updateAprsSettings(approx = it) })
            Text("Send approximate position (~1 km)", style = MaterialTheme.typography.bodySmall)
        }
        Picker("Symbol", vm.aprsSymbol, RadioViewModel.APRS_SYMBOLS.keys.toList()) { vm.updateAprsSettings(symbol = it) }
        OutlinedTextField(
            value = comment,
            onValueChange = {
                comment = it.take(40)
                vm.updateAprsSettings(comment = comment)
            },
            label = { Text("Beacon comment") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().typingField(),
        )
        Picker(
            "Auto beacon",
            if (vm.aprsBeaconMinutes == 0) "Off" else "every ${vm.aprsBeaconMinutes} min",
            RadioViewModel.APRS_BEACON_CHOICES.map { if (it == 0) "Off" else "every $it min" },
        ) { label -> vm.updateAprsSettings(beaconMinutes = label.filter { it.isDigit() }.toIntOrNull() ?: 0) }
        Button(onClick = { vm.beaconNow() }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
            Text("Send position beacon now")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.aprsDigipeat, onCheckedChange = { vm.updateAprsSettings(digipeat = it) })
            Column {
                Text("Digipeat (WIDE1-1 fill-in)", style = MaterialTheme.typography.bodySmall)
                Text("Repeats nearby packets, like the Android app", style = MaterialTheme.typography.labelSmall, color = Kv4pColors.MediumGray)
            }
        }
        Text(
            "Messages addressed to your callsign are acknowledged automatically.",
            style = MaterialTheme.typography.labelSmall,
            color = Kv4pColors.MediumGray,
        )
    }
    }
}

@Composable
private fun Picker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: $value") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onPick(option); open = false })
            }
        }
    }
}
