package com.kv4p.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kv4p.desktop.AppLog
import com.kv4p.desktop.RadioViewModel
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState

/**
 * Main window. Laid out for 1280x720 (ClockworkPi uConsole) and up:
 * connection bar on top, radio face on the left, log on the right.
 */
@Composable
fun MainScreen(vm: RadioViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            ConnectionBar(vm)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxSize()) {
                RadioFace(vm, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                LogPanel(vm, Modifier.width(420.dp).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ConnectionBar(vm: RadioViewModel) {
    var menuOpen by remember { mutableStateOf(false) }
    val connected = vm.connection == ConnectionState.CONNECTED
    val busy = vm.connection == ConnectionState.CONNECTING || vm.connection == ConnectionState.HANDSHAKE

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { vm.refreshPorts(); menuOpen = true }, enabled = !connected && !busy) {
                Text(vm.selectedPort ?: "No serial port found", maxLines = 1)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (vm.ports.isEmpty()) {
                    DropdownMenuItem(text = { Text("No serial ports - is the radio plugged in?") }, onClick = { menuOpen = false })
                }
                vm.ports.forEach { port ->
                    DropdownMenuItem(
                        text = { Text((if (port.likelyKv4p) "★ " else "") + port.toString()) },
                        onClick = { vm.selectedPort = port.systemPath; menuOpen = false },
                    )
                }
            }
        }
        if (connected || busy) {
            Button(onClick = vm::disconnect) { Text("Disconnect") }
        } else {
            Button(onClick = vm::connect, enabled = vm.selectedPort != null) { Text("Connect") }
        }
        Spacer(Modifier.width(8.dp))
        StatusDot(vm.connection)
        Text(
            text = statusText(vm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

private fun statusText(vm: RadioViewModel): String {
    val label = when (vm.connection) {
        ConnectionState.DISCONNECTED -> "Not connected"
        ConnectionState.CONNECTING -> "Opening port…"
        ConnectionState.HANDSHAKE -> "Waiting for the radio…"
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.OUTDATED_FIRMWARE -> "Firmware update needed"
        ConnectionState.NO_RESPONSE -> "No response"
        ConnectionState.RADIO_MODULE_NOT_FOUND -> "Radio module not found"
        ConnectionState.ERROR -> "Error"
    }
    return if (vm.connectionDetail.isBlank()) label else "$label - ${vm.connectionDetail}"
}

@Composable
private fun StatusDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF6FBF73)
        ConnectionState.CONNECTING, ConnectionState.HANDSHAKE -> Kv4pColors.OverS9
        ConnectionState.DISCONNECTED -> Kv4pColors.MediumGray
        else -> Kv4pColors.Accent
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun RadioFace(vm: RadioViewModel, modifier: Modifier) {
    val connected = vm.connection == ConnectionState.CONNECTED
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf(false) }

    Column(
        modifier
            .border(1.dp, Kv4pColors.MediumGray, RoundedCornerShape(8.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Simplex", style = MaterialTheme.typography.titleMedium, color = Kv4pColors.PrimaryDeselected)

        Row(verticalAlignment = Alignment.Bottom) {
            if (editing) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; inputError = false },
                    singleLine = true,
                    isError = inputError,
                    label = { Text("Frequency (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (vm.tune(input)) editing = false else inputError = true
                    }),
                    textStyle = MaterialTheme.typography.displayMedium,
                    modifier = Modifier.width(360.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Button(onClick = { if (vm.tune(input)) editing = false else inputError = true }) { Text("Tune") }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            } else {
                Text(
                    text = RadioViewModel.formatFrequency(vm.rxFrequency),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Light,
                    fontFamily = Jost,
                    color = if (connected) Kv4pColors.Primary else Kv4pColors.MediumGray,
                    modifier = Modifier.clickable(enabled = connected) {
                        input = RadioViewModel.formatFrequency(vm.rxFrequency).takeIf { vm.rxFrequency != null } ?: ""
                        editing = true
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "MHz  ${vm.bandLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Kv4pColors.PrimaryDeselected,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
        }
        if (inputError) {
            val fw = vm.firmware
            Text(
                if (fw != null) "Enter a frequency between ${fw.minRadioFreq} and ${fw.maxRadioFreq} MHz" else "Not connected",
                color = Kv4pColors.Accent,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SMeter(if (connected) vm.sMeter else 0)
        Text(
            text = when {
                !connected -> ""
                vm.txActive -> "TX"
                vm.squelched -> "RX · squelched"
                else -> "RX · receiving"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (vm.txActive) Kv4pColors.Accent else Kv4pColors.PrimaryDeselected,
        )

        LabeledSlider("Squelch", "${vm.squelch}", vm.squelch.toFloat(), 0f..8f, steps = 7, enabled = connected) {
            vm.updateSquelch(it.toInt())
        }
        LabeledSlider("Volume", "${(vm.volume * 100).toInt()}%", vm.volume, 0f..1f, steps = 0, enabled = true) {
            vm.updateVolume(it)
        }

        Spacer(Modifier.weight(1f))
        vm.firmware?.let { fw ->
            Text(
                "Firmware v${fw.ver} · ${vm.bandLabel} module · ${fw.minRadioFreq}-${fw.maxRadioFreq} MHz" +
                    (if (fw.isHasPhysPtt) " · PTT button" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = Kv4pColors.PrimaryDeselected,
            )
        }
        Text(
            "Transmit (PTT) comes in the next step - this build is receive-only.",
            style = MaterialTheme.typography.bodySmall,
            color = Kv4pColors.MediumGray,
        )
    }
}

/** S1-S9, as computed by the released Android app (Protocol.calculateSMeter9Value). */
@Composable
private fun SMeter(value: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("S", style = MaterialTheme.typography.labelLarge, color = Kv4pColors.PrimaryDeselected)
        Spacer(Modifier.width(4.dp))
        for (i in 1..9) {
            val color = when {
                value < i -> Kv4pColors.PrimaryVeryFaint
                i == 9 -> Kv4pColors.OverS9
                else -> Kv4pColors.Primary
            }
            Box(Modifier.width(30.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (value <= 0) "" else "S$value",
            style = MaterialTheme.typography.labelLarge,
            color = Kv4pColors.PrimaryDeselected,
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(80.dp), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.width(320.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(valueText, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LogPanel(vm: RadioViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(vm.logLines.size) {
        if (vm.logLines.isNotEmpty()) listState.scrollToItem(vm.logLines.lastIndex)
    }
    Column(modifier.border(1.dp, Kv4pColors.MediumGray, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(vm.logLines.joinToString("\n"))) }) {
                Text("Copy")
            }
        }
        Text(
            AppLog.file.absolutePath,
            style = MaterialTheme.typography.bodySmall,
            color = Kv4pColors.MediumGray,
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(vm.logLines) { line ->
                Text(
                    line,
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = when {
                        line.contains(" E/") -> Kv4pColors.Accent
                        line.contains(" W/") -> Kv4pColors.OverS9
                        else -> Kv4pColors.PrimaryDeselected
                    },
                )
            }
        }
    }
}
