package dev.antonix.deep.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.antonix.deep.model.ConnectionPhase
import dev.antonix.deep.model.PowerLevel
import dev.antonix.deep.model.ProgramKind
import dev.antonix.deep.ui.theme.Copper
import dev.antonix.deep.ui.theme.CopperDim
import dev.antonix.deep.ui.theme.Ember
import dev.antonix.deep.ui.theme.Mist
import dev.antonix.deep.ui.theme.MistDim
import dev.antonix.deep.ui.theme.Void
import dev.antonix.deep.ui.theme.VoidElevated

@Composable
fun DeepScreen(vm: DeepViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants.values.all { it }
        if (granted) vm.onPermissionsGranted() else vm.onPermissionsDenied()
    }

    LaunchedEffect(Unit) {
        launcher.launch(neededPermissions())
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Void)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when (state.phase) {
            ConnectionPhase.NoPermission -> CenterMessage(
                "Нужен Bluetooth",
                "Разреши доступ к Bluetooth — без него куб не найти.",
            )
            ConnectionPhase.Idle, ConnectionPhase.Scanning, ConnectionPhase.Connecting, ConnectionPhase.Failed ->
                ScanPane(state, onConnect = vm::connect)
            ConnectionPhase.Connected -> ConnectedPane(state, vm)
        }
    }
}

private fun neededPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

@Composable
private fun ScanPane(state: UiState, onConnect: (dev.antonix.deep.model.CubeInfo) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("DEEP", color = Copper, letterSpacing = 8.sp, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.phase) {
                ConnectionPhase.Connecting -> "Соединяюсь"
                ConnectionPhase.Failed -> "Нет связи"
                else -> "Ищу куб"
            },
            color = Color(0xFFF4F1EA),
            fontSize = 32.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(28.dp))
        PulseMark(active = state.phase != ConnectionPhase.Failed)
        Spacer(Modifier.height(20.dp))
        Text(
            "Дотронься до куба, если кольцо погасло.\nРеклама BLE короткая.",
            color = MistDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(28.dp))
        if (state.cubes.isEmpty()) {
            Text("Пока тишина", color = MistDim)
        } else {
            state.cubes.forEach { cube ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(VoidElevated)
                        .clickable { onConnect(cube) }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(cube.name, color = Color(0xFFF4F1EA), fontSize = 18.sp)
                        Text(cube.address, color = MistDim, fontSize = 12.sp)
                    }
                    Text("открыть", color = Copper, letterSpacing = 1.sp, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(state.message, color = Ember, fontSize = 13.sp)
    }
}

@Composable
private fun ConnectedPane(state: UiState, vm: DeepViewModel) {
    val cube = state.connected ?: return
    val running = state.status?.running == true
    val programs = ProgramKind.entries.filter { it.availableOn(cube.model) }
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 96.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("DEEP", color = Copper, letterSpacing = 6.sp, fontSize = 11.sp)
                    Text(cube.name.ifBlank { cube.model.title }, color = Color(0xFFF4F1EA), fontSize = 22.sp)
                }
                Text(
                    "сменить",
                    color = Mist,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.disconnect() }
                        .padding(8.dp),
                    fontSize = 13.sp,
                )
            }
            if (cube.firmware.isNotBlank()) {
                Text("прошивка ${cube.firmware}", color = MistDim, fontSize = 12.sp)
            }

            Spacer(Modifier.height(18.dp))
            StatusHero(state, onDoubleTap = vm::togglePlay)

            Spacer(Modifier.height(8.dp))
            Text(
                "Кнопка внизу или двойное касание карточки.",
                color = MistDim,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(18.dp))
            Text("ПРОГРАММА", color = MistDim, letterSpacing = 2.sp, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            programs.forEach { p ->
                val selected = state.selected == p
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selected) Color(0xFF1C1713) else VoidElevated)
                        .border(
                            1.dp,
                            if (selected) Copper.copy(alpha = 0.7f) else Color.Transparent,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { vm.select(p) }
                        .padding(16.dp),
                ) {
                    Text(p.title, color = if (selected) Copper else Color(0xFFF4F1EA), fontSize = 17.sp)
                    Text(p.subtitle, color = MistDim, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("ДЛИТЕЛЬНОСТЬ", color = MistDim, letterSpacing = 2.sp, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items((1..12).toList()) { h ->
                    val on = state.durationHours == h
                    Text(
                        "$h ч",
                        color = if (on) Void else Mist,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) Copper else VoidElevated)
                            .clickable { vm.setDurationHours(h) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("МОЩНОСТЬ", color = MistDim, letterSpacing = 2.sp, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PowerLevel.entries.forEach { level ->
                    val on = state.power == level
                    Text(
                        level.label,
                        color = if (on) Void else Mist,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (on) Copper else VoidElevated)
                            .clickable { vm.setPower(level) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ToggleChip(
                    icon = { Icon(Icons.Default.LightMode, null, tint = it, modifier = Modifier.size(18.dp)) },
                    label = "кольцо",
                    on = state.led,
                    onClick = { vm.setLed(!state.led) },
                )
                ToggleChip(
                    icon = { Icon(Icons.Default.Vibration, null, tint = it, modifier = Modifier.size(18.dp)) },
                    label = "вибро",
                    on = state.vibro,
                    onClick = { vm.setVibro(!state.vibro) },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (state.showLog) "скрыть лог" else "лог протокола",
                color = MistDim,
                modifier = Modifier.clickable { vm.toggleLog() },
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
            if (state.showLog) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C0C12))
                        .padding(12.dp),
                ) {
                    if (state.lastReaction.isNotBlank()) {
                        Text(state.lastReaction, color = CopperDim, fontSize = 11.sp)
                    }
                    state.log.takeLast(28).forEach {
                        Text(it, color = MistDim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.message, color = Ember, fontSize = 13.sp)
            }
        }

        PlayButton(
            running = running,
            busy = state.busy,
            onClick = vm::togglePlay,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun StatusHero(state: UiState, onDoubleTap: () -> Unit) {
    val status = state.status
    val running = status?.running == true
    val bg by animateColorAsState(
        if (running) Color(0xFF24180F) else VoidElevated,
        label = "hero",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(bg)
            .pointerInput(running) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .padding(20.dp),
    ) {
        Text(
            if (running) "идёт программа" else "тихо",
            color = if (running) Copper else MistDim,
            letterSpacing = 2.sp,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        val freq = status?.frequencyHz
        val programTitle = ProgramKind.entries
            .firstOrNull { it.number == status?.programNumber }
            ?.title
        Text(
            when {
                freq != null -> "$freq Гц"
                running -> programTitle ?: "сон"
                else -> "ожидание"
            },
            color = Color(0xFFF4F1EA),
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        if (status != null && status.totalSec > 0) {
            Text(
                "осталось ${formatDuration(status.remainingSec)} · ${formatDuration(status.totalSec)} всего",
                color = Mist,
                fontSize = 14.sp,
            )
        }
        val raw = status?.raw?.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
        if (raw != null) {
            Spacer(Modifier.height(8.dp))
            Text(raw, color = MistDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PlayButton(
    running: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (busy) CopperDim else if (running) Ember else Copper
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(color)
            .clickable(enabled = !busy, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = Void,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                busy && running -> "останавливаю…"
                busy -> "запускаю…"
                running -> "остановить"
                else -> "запустить"
            },
            color = Void,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ToggleChip(
    icon: @Composable (Color) -> Unit,
    label: String,
    on: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (on) Void else Mist
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) Copper else VoidElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(fg)
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp)
    }
}

@Composable
private fun PulseMark(active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val rot by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot",
    )
    Box(
        modifier = Modifier
            .size(120.dp)
            .rotate(if (active) rot else 0f)
            .background(
                Brush.linearGradient(listOf(Copper, Color(0xFF3A2A22), CopperDim)),
                RoundedCornerShape(28.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(Void, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.BluetoothSearching, null, tint = Copper, modifier = Modifier.size(36.dp))
        }
    }
}

@Composable
private fun CenterMessage(title: String, body: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color(0xFFF4F1EA), fontSize = 28.sp)
        Text(body, color = Mist)
    }
}

private fun formatDuration(sec: Int): String {
    if (sec <= 0) return "0 мин"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h} ч ${m} мин" else "${m} мин"
}
