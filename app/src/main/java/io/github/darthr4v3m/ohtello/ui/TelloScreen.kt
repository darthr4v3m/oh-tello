package io.github.darthr4v3m.ohtello.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.darthr4v3m.ohtello.tello.CommandLogEntry
import io.github.darthr4v3m.ohtello.tello.ConnectionState
import io.github.darthr4v3m.ohtello.tello.protocol.MoveDirection
import io.github.darthr4v3m.ohtello.tello.protocol.TelloState
import io.github.darthr4v3m.ohtello.tello.protocol.TurnDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TelloScreen(
    modifier: Modifier = Modifier,
    viewModel: TelloViewModel = viewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val telemetryFresh by viewModel.telemetryFresh.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val stepCm by viewModel.stepCm.collectAsStateWithLifecycle()
    val turnDegrees by viewModel.turnDegrees.collectAsStateWithLifecycle()
    val showKeepAlives by viewModel.showKeepAlives.collectAsStateWithLifecycle()
    val emergencyArmed by viewModel.emergencyArmed.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    val canFly = connected && !busy

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(connection = connection, telemetryFresh = telemetryFresh)

        ConnectionCard(
            connection = connection,
            busy = busy,
            onConnect = viewModel::connect,
            onDisconnect = viewModel::disconnect,
        )

        TelemetryCard(
            state = state,
            telemetryFresh = telemetryFresh,
            connected = connected,
            onRefreshBattery = viewModel::queryBattery,
        )

        FlightCard(
            canFly = canFly,
            connected = connected,
            emergencyArmed = emergencyArmed,
            onTakeoff = viewModel::takeoff,
            onLand = viewModel::land,
            onEmergency = viewModel::emergency,
        )

        MovementCard(
            canFly = canFly,
            stepCm = stepCm,
            turnDegrees = turnDegrees,
            onStepChange = viewModel::setStepCm,
            onTurnChange = viewModel::setTurnDegrees,
            onMove = viewModel::move,
            onTurn = viewModel::turn,
        )

        ConsoleCard(
            entries = log,
            showKeepAlives = showKeepAlives,
            onShowKeepAlivesChange = viewModel::setShowKeepAlives,
            onClear = viewModel::clearLog,
        )
    }
}

@Composable
private fun Header(connection: ConnectionState, telemetryFresh: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Oh-Tello",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        StatusChip(connection = connection, telemetryFresh = telemetryFresh)
    }
}

@Composable
private fun StatusChip(connection: ConnectionState, telemetryFresh: Boolean) {
    val (label, color) = when {
        connection is ConnectionState.Connected && telemetryFresh ->
            "connected" to MaterialTheme.colorScheme.secondary

        connection is ConnectionState.Connected ->
            "no telemetry" to MaterialTheme.colorScheme.error

        connection is ConnectionState.Connecting ->
            "connecting" to MaterialTheme.colorScheme.primary

        connection is ConnectionState.Failed ->
            "failed" to MaterialTheme.colorScheme.error

        else -> "offline" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = color.copy(alpha = 0.16f),
        contentColor = color,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ConnectionCard(
    connection: ConnectionState,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    SectionCard {
        Text(
            text = "Join the drone's TELLO-XXXXXX Wi-Fi network in Android settings, " +
                "then connect. Ignore the \"no internet\" warning and stay on it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (connection is ConnectionState.Failed) {
            Text(
                text = connection.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConnect,
                enabled = connection !is ConnectionState.Connected &&
                    connection !is ConnectionState.Connecting,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (busy && connection is ConnectionState.Connecting) "Connecting…" else "Connect")
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = connection !is ConnectionState.Disconnected,
                modifier = Modifier.weight(1f),
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun TelemetryCard(
    state: TelloState?,
    telemetryFresh: Boolean,
    connected: Boolean,
    onRefreshBattery: () -> Unit,
) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Telemetry", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefreshBattery, enabled = connected) {
                Text("battery?")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Readout("battery", state?.batteryPercent?.let { "$it%" })
            Readout("height", state?.heightCm?.let { "$it cm" })
            Readout("tof", state?.timeOfFlightCm?.let { "$it cm" })
            Readout("flight", state?.flightTimeSeconds?.let { "$it s" })
        }

        val batteryPercent = state?.batteryPercent
        if (batteryPercent != null && batteryPercent <= LOW_BATTERY_PERCENT) {
            Text(
                text = "Battery at $batteryPercent% — land it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (!telemetryFresh) {
            Text(
                text = "No state packet in the last 2 seconds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        state?.raw?.let { raw ->
            Text(
                text = raw,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Readout(label: String, value: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FlightCard(
    canFly: Boolean,
    connected: Boolean,
    emergencyArmed: Boolean,
    onTakeoff: () -> Unit,
    onLand: () -> Unit,
    onEmergency: () -> Unit,
) {
    SectionCard {
        Text("Flight", style = MaterialTheme.typography.titleMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onTakeoff,
                enabled = canFly,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Text("TAKE OFF")
            }
            Button(
                onClick = onLand,
                // Deliberately not gated on `busy`: landing has to work while
                // another command is still in flight.
                enabled = connected,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text("LAND")
            }
        }

        OutlinedButton(
            onClick = onEmergency,
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(
                if (emergencyArmed) {
                    "TAP AGAIN TO CUT MOTORS"
                } else {
                    "Emergency stop"
                },
            )
        }
    }
}

@Composable
private fun MovementCard(
    canFly: Boolean,
    stepCm: Int,
    turnDegrees: Int,
    onStepChange: (Int) -> Unit,
    onTurnChange: (Int) -> Unit,
    onMove: (MoveDirection) -> Unit,
    onTurn: (TurnDirection) -> Unit,
) {
    SectionCard {
        Text("Move", style = MaterialTheme.typography.titleMedium)

        OptionRow(
            options = TelloViewModel.STEP_OPTIONS_CM,
            selected = stepCm,
            format = { "$it cm" },
            onSelect = onStepChange,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            PadButton("▲\nforward", canFly, Modifier.weight(1f)) { onMove(MoveDirection.FORWARD) }
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("◀\nleft", canFly, Modifier.weight(1f)) { onMove(MoveDirection.LEFT) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$stepCm cm",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PadButton("▶\nright", canFly, Modifier.weight(1f)) { onMove(MoveDirection.RIGHT) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.weight(1f))
            PadButton("▼\nback", canFly, Modifier.weight(1f)) { onMove(MoveDirection.BACK) }
            Spacer(Modifier.weight(1f))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text("Altitude", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("▲\nup", canFly, Modifier.weight(1f)) { onMove(MoveDirection.UP) }
            PadButton("▼\ndown", canFly, Modifier.weight(1f)) { onMove(MoveDirection.DOWN) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Text("Yaw", style = MaterialTheme.typography.titleMedium)
        OptionRow(
            options = TelloViewModel.TURN_OPTIONS_DEGREES,
            selected = turnDegrees,
            format = { "$it°" },
            onSelect = onTurnChange,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadButton("↺\nccw", canFly, Modifier.weight(1f)) {
                onTurn(TurnDirection.COUNTER_CLOCKWISE)
            }
            PadButton("↻\ncw", canFly, Modifier.weight(1f)) {
                onTurn(TurnDirection.CLOCKWISE)
            }
        }
    }
}

@Composable
private fun PadButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        Text(
            text = label,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun OptionRow(
    options: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            OutlinedButton(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                colors = if (isSelected) {
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    ButtonDefaults.outlinedButtonColors()
                },
                contentPadding = PaddingValues(4.dp),
            ) {
                Text(format(option), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ConsoleCard(
    entries: List<CommandLogEntry>,
    showKeepAlives: Boolean,
    onShowKeepAlivesChange: (Boolean) -> Unit,
    onClear: () -> Unit,
) {
    val visible = remember(entries, showKeepAlives) {
        if (showKeepAlives) {
            entries
        } else {
            entries.filterNot { it.kind == CommandLogEntry.Kind.KEEPALIVE }
        }
    }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LaunchedEffect(visible.lastOrNull()?.id) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Console", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "keepalives",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Switch(checked = showKeepAlives, onCheckedChange = onShowKeepAlivesChange)
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }

        // Fixed height so this LazyColumn can live inside the screen's own
        // vertical scroll without fighting it for measurement.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
        ) {
            if (visible.isEmpty()) {
                Text(
                    text = "Nothing sent yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visible, key = { it.id }) { entry ->
                    Text(
                        text = "${timeFormat.format(Date(entry.timestampMillis))}  ${entry.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = entry.kind.color(),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandLogEntry.Kind.color(): Color = when (this) {
    CommandLogEntry.Kind.SENT -> MaterialTheme.colorScheme.primary
    CommandLogEntry.Kind.RECEIVED -> MaterialTheme.colorScheme.onSurface
    CommandLogEntry.Kind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    CommandLogEntry.Kind.ERROR -> MaterialTheme.colorScheme.error
    CommandLogEntry.Kind.KEEPALIVE -> MaterialTheme.colorScheme.outline
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

private const val LOW_BATTERY_PERCENT = 20
