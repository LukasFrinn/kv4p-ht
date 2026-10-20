package com.kv4p.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kv4p.desktop.RadioViewModel
import com.vagell.kv4pht.desktop.Memory
import com.vagell.kv4pht.radio.DesktopRadioSession.ConnectionState
import com.vagell.kv4pht.ui.ToneHelper
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/** Channel memories: group filter, list (click to tune), add/edit/delete, scan, CSV import/export. */
@Composable
fun MemoriesPanel(vm: RadioViewModel, modifier: Modifier) {
    var editing by remember { mutableStateOf<Memory?>(null) }
    var deleting by remember { mutableStateOf<Memory?>(null) }
    var importFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf("") }
    val connected = vm.connection == ConnectionState.CONNECTED

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            GroupPicker(vm)
            Spacer(Modifier.weight(1f))
            if (vm.scanning) {
                Button(onClick = { vm.stopScan() }) { Text("Stop scan") }
            } else {
                OutlinedButton(onClick = { vm.startScan() }, enabled = connected && vm.visibleMemories.isNotEmpty()) {
                    Text("Scan")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { editing = vm.newMemory() }) { Text("+ Add") }
            TextButton(onClick = {
                chooseFile("Import memories (CHIRP or RepeaterBook CSV)", FileDialog.LOAD, null)?.let { importFile = it }
            }) { Text("Import…") }
            TextButton(onClick = {
                chooseFile("Export memories (CHIRP CSV)", FileDialog.SAVE, "kv4p-memories.csv")?.let {
                    message = vm.exportMemories(it)
                }
            }, enabled = vm.memories.isNotEmpty()) { Text("Export…") }
        }
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = Kv4pColors.OverS9,
                modifier = Modifier.clickable { message = "" })
        }

        val list = vm.visibleMemories
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    "No memories yet.\nAdd one, or import a CHIRP / RepeaterBook CSV.",
                    color = Kv4pColors.MediumGray,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(list, key = { it.id }) { m ->
                    MemoryRow(
                        m,
                        active = m.id == vm.activeMemoryId,
                        scanning = vm.scanning,
                        enabled = connected,
                        onTune = { vm.tuneMemory(m) },
                        onEdit = { editing = m.copy() },
                        onDelete = { deleting = m },
                        onMove = { vm.moveMemory(m, it) },
                    )
                }
            }
        }
    }

    editing?.let { m ->
        MemoryDialog(vm, m, onDismiss = { editing = null })
    }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete memory?") },
            text = { Text("${m.name} (${m.frequency} MHz)") },
            confirmButton = { TextButton(onClick = { vm.deleteMemory(m); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    importFile?.let { file ->
        ImportDialog(file, vm.memoryGroups, onImport = { group ->
            message = vm.importMemories(file, group)
            importFile = null
        }, onDismiss = { importFile = null })
    }
}

@Composable
private fun GroupPicker(vm: RadioViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text("Group: ${vm.selectedGroup} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(RadioViewModel.ALL_GROUPS) + vm.memoryGroups).forEach { g ->
                DropdownMenuItem(text = { Text(g) }, onClick = { vm.selectGroup(g); open = false })
            }
        }
    }
}

@Composable
private fun MemoryRow(
    m: Memory,
    active: Boolean,
    scanning: Boolean,
    enabled: Boolean,
    onTune: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) Kv4pColors.AccentVeryFaint else Kv4pColors.PrimaryVeryFaint, RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onTune)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (active && scanning) "▶ " else "") + m.name,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(m.frequency, m.summary(), if (m.skipDuringScan) "skip" else "")
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Kv4pColors.PrimaryDeselected,
                maxLines = 1,
            )
        }
        TextButton(onClick = { onMove(-1) }, modifier = Modifier.width(36.dp)) { Text("↑") }
        TextButton(onClick = { onMove(1) }, modifier = Modifier.width(36.dp)) { Text("↓") }
        TextButton(onClick = onEdit, modifier = Modifier.width(44.dp)) { Text("Edit") }
        TextButton(onClick = onDelete, modifier = Modifier.width(36.dp)) { Text("✕", color = Kv4pColors.Accent) }
    }
}

@Composable
private fun MemoryDialog(vm: RadioViewModel, initial: Memory, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var freq by remember { mutableStateOf(initial.frequency) }
    var offset by remember { mutableStateOf(initial.offset) }
    var offsetKhz by remember { mutableStateOf(initial.offsetKhz.toString()) }
    var txTone by remember { mutableStateOf(initial.txTone) }
    var rxTone by remember { mutableStateOf(initial.rxTone) }
    var group by remember { mutableStateOf(initial.group) }
    var skip by remember { mutableStateOf(initial.skipDuringScan) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0) "Add memory" else "Edit memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.width(420.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(freq, { freq = it }, label = { Text("RX frequency (MHz)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Choice2("Offset", listOf("None", "+ (up)", "- (down)"), when (offset) {
                        Memory.OFFSET_UP -> "+ (up)"
                        Memory.OFFSET_DOWN -> "- (down)"
                        else -> "None"
                    }) {
                        offset = when (it) {
                            "+ (up)" -> Memory.OFFSET_UP
                            "- (down)" -> Memory.OFFSET_DOWN
                            else -> Memory.OFFSET_NONE
                        }
                    }
                    if (offset != Memory.OFFSET_NONE) {
                        OutlinedTextField(offsetKhz, { offsetKhz = it.filter(Char::isDigit).take(5) },
                            label = { Text("kHz") }, singleLine = true, modifier = Modifier.width(110.dp))
                    }
                }
                Row {
                    Choice2("TX tone", ToneHelper.VALID_TONE_STRINGS, txTone) { txTone = it }
                    Choice2("RX tone", ToneHelper.VALID_TONE_STRINGS, rxTone) { rxTone = it }
                }
                OutlinedTextField(group, { group = it.take(30) }, label = { Text("Group (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skip, { skip = it })
                    Text("Skip during scan", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotEmpty()) Text(error, color = Kv4pColors.Accent, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = initial.copy()
                m.name = name
                m.frequency = freq
                m.offset = offset
                m.offsetKhz = offsetKhz.toIntOrNull() ?: 600
                m.txTone = txTone
                m.rxTone = rxTone
                m.group = group
                m.skipDuringScan = skip
                val err = vm.saveMemory(m)
                if (err == null) onDismiss() else error = err
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(file: File, groups: List<String>, onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var group by remember { mutableStateOf(file.nameWithoutExtension.take(30)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import ${file.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "CHIRP files keep their own groups (Comment column). RepeaterBook files go into this group:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(group, { group = it.take(30) }, label = { Text("Group") }, singleLine = true)
                if (groups.isNotEmpty()) {
                    Text("Existing: " + groups.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = Kv4pColors.MediumGray)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(group) }) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice2(label: String, options: List<String>, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("$label: $value ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

/** Native file chooser (Finder on macOS, GTK on Linux). Returns null when cancelled. */
private fun chooseFile(title: String, mode: Int, suggestedName: String?): File? {
    val dialog = FileDialog(null as Frame?, title, mode)
    if (suggestedName != null) dialog.file = suggestedName
    if (mode == FileDialog.LOAD) dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".csv") }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(dir, name)
}
