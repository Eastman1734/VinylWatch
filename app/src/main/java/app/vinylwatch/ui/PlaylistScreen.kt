package app.vinylwatch.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import app.vinylwatch.Track

/**
 * Список и пластинка — две «страницы» одной оси: со списка свайп влево (или строка «Сейчас играет») ведёт к пластинке,
 * с пластинки свайп вправо — обратно к списку. Со списка свайп вправо — выход из приложения (музыка продолжает играть).
 * onOpenPlayer == null, если пока нечего открывать.
 */
@Composable
fun PlaylistScreen(
    tracks: List<Track>,
    current: Track?,
    needsPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    coverStatus: String?,
    coverRunning: Boolean,
    onFetchCovers: () -> Unit,
    onOpenPlayer: (() -> Unit)?,
    onExit: () -> Unit,
    onPick: (Track) -> Unit,
) {
    val open by rememberUpdatedState(onOpenPlayer)
    val exit by rememberUpdatedState(onExit)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        // вправо (как «назад») — выйти из приложения; влево — к пластинке, если есть что открывать
                        val w = size.width
                        if (total > w * 0.18f) exit() else if (total < -w * 0.18f) open?.invoke()
                    },
                    onDragCancel = { total = 0f },
                    onHorizontalDrag = { _, dx -> total += dx },
                )
            }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("Vinyl", fontSize = 14.sp) }
            if (current != null && onOpenPlayer != null) {
                item {
                    Chip(
                        onClick = onOpenPlayer,
                        label = { Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = { Text("Сейчас играет →", maxLines = 1) },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (needsPermission) {
                item {
                    Chip(
                        onClick = onRequestPermission,
                        label = { Text("Доступ к музыке", maxLines = 1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (tracks.isEmpty()) {
                item {
                    Text(
                        "Нет треков.\nПоложи файлы в /sdcard/Music и нажми «Обновить»",
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
            items(tracks, key = { it.uri.toString() }) { t ->
                Chip(
                    onClick = { onPick(t) },
                    label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = t.artist?.let { a -> { Text(a, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    colors = if (t == current) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onFetchCovers,
                    label = { Text(if (coverRunning) "Остановить поиск" else "Докачать обложки", maxLines = 1) },
                    secondaryLabel = coverStatus?.let { s -> { Text(s, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = onRefresh,
                    label = { Text("Обновить", maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
