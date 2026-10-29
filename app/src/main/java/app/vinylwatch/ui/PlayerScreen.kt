package app.vinylwatch.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import app.vinylwatch.audio.ScratchEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DEG_PER_SEC = 200f // 33⅓ об/мин = 200°/с

private fun ang(o: Offset): Float = Math.toDegrees(atan2(o.y.toDouble(), o.x.toDouble())).toFloat()
private fun wrap(d: Float): Float {
    var x = d
    while (x > 180f) x -= 360f
    while (x <= -180f) x += 360f
    return x
}

/**
 * animate = экран виден и не в ambient: только тогда крутим цикл кадров.
 * Частота обновления: 60 fps при скретче, ~30 fps при игре. На установившейся паузе ждём событие.
 */
@Composable
fun PlayerScreen(
    engine: ScratchEngine,
    title: String,
    loading: Boolean,
    error: String?,
    labelColor: Color,
    cover: ImageBitmap?,
    animate: Boolean,
    ambient: Boolean,
    ambientShift: Int,
    onSystemPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onBack: () -> Unit,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var playingUi by remember { mutableStateOf(false) }
    val backNow by rememberUpdatedState(onBack)
    val toggleNow by rememberUpdatedState(onTogglePlay)
    val systemNow by rememberUpdatedState(onSystemPlayer)

    // --- громкость: ползунок по верхней дуге у края экрана ---
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    var volFrac by remember { mutableFloatStateOf(0f) }
    var volTick by remember { mutableIntStateOf(0) }
    var volVisible by remember { mutableStateOf(false) }
    var lastIdx by remember { mutableIntStateOf(-1) }
    val setVolume: (Float) -> Unit = { f ->
        val fr = f.coerceIn(0f, 1f)
        volFrac = fr
        val idx = (fr * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt()
        if (idx != lastIdx) {
            lastIdx = idx
            try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) } catch (_: SecurityException) {}
        }
        volTick++
    }
    LaunchedEffect(volTick) {
        if (volTick == 0) return@LaunchedEffect
        volVisible = true
        delay(1200)
        volVisible = false
    }
    val titlePaint = remember {
        Paint().apply { isAntiAlias = true; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    }

    // --- название по дуге: короткое стоит по центру окна, длинное прокручивается (пауза 2 с, ход, пауза 1,5 с) ---
    val shownTitle = error ?: title
    var sidePx by remember { mutableIntStateOf(0) }
    var titleScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(shownTitle, sidePx, animate) {
        titleScroll = 0f
        if (!animate || sidePx == 0) return@LaunchedEffect
        val (len, maxLen) = titleMetrics(titlePaint, shownTitle, sidePx.toFloat())
        val overflow = len - maxLen
        if (overflow <= 0f) return@LaunchedEffect
        val speed = sidePx * 0.12f // px/с вдоль дуги
        while (true) {
            delay(2000)
            val t0 = SystemClock.uptimeMillis()
            while (true) {
                delay(33)
                val x = minOf(overflow, speed * (SystemClock.uptimeMillis() - t0) / 1000f)
                titleScroll = x
                if (x >= overflow) break
            }
            delay(1500)
            titleScroll = 0f
        }
    }

    BackHandler(onBack = onBack)

    // Угол диска = слышимая позиция * 200°/с. Состояние читается только в фазе рисования (без рекомпозиций).
    LaunchedEffect(engine, animate, ambientShift) {
        fun refresh() {
            angle = ((engine.positionSeconds * DEG_PER_SEC) % 360.0).toFloat()
            progress = engine.progress
            playingUi = engine.playing
        }
        engine.changes.collectLatest {
            refresh()
            if (!animate) return@collectLatest
            while (engine.isScratching || engine.playing || abs(engine.velocityView) > 0.001f) {
                if (!engine.isScratching) delay(25)
                withFrameNanos { }
                refresh()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .aspectRatio(1f)
                .onSizeChanged { sidePx = it.width }
                .pointerInput(engine, ambient) {
                    if (ambient) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val g = Geo(size.width.toFloat())
                        val p0 = down.position
                        val prog = engine.progress
                        val tip = g.armTip(prog)
                        val rear = g.armRearPoint(prog)
                        val fromC = (p0 - g.c).getDistance()

                        when {
                            // тонарм: перетащить иглу -> перемотка (на время переноса звук выключен)
                            engine.ready && distToSegment(p0, rear, tip) < g.w * 0.06f -> {
                                down.consume()
                                engine.muted = true
                                dragging = true
                                dragProgress = g.progressFor(p0)
                                try {
                                    drag(down.id) { ch ->
                                        dragProgress = g.progressFor(ch.position)
                                        ch.consume()
                                    }
                                    engine.seekToFraction(dragProgress)
                                } finally {
                                    engine.muted = false
                                    dragging = false
                                }
                            }
                            // этикетка = кнопка play/pause
                            fromC < g.labelR * 1.15f -> {
                                down.consume()
                                val up = waitForUpOrCancellation()
                                if (up != null) { up.consume(); toggleNow() }
                            }
                            // левая кромка: направление решает жест. Вертикально вдоль дуги = громкость,
                            // горизонтально вправо = назад к списку (так громкость не мешает свайпу «назад»)
                            isVolumeZone(p0, g) -> {
                                var total = Offset.Zero
                                var mode = 0 // 0 = ещё не решили, 1 = громкость, 2 = назад
                                val slop = viewConfiguration.touchSlop * 1.5f
                                drag(down.id) { ch ->
                                    total += ch.positionChange()
                                    if (mode == 0 && total.getDistance() > slop) {
                                        mode = if (total.x > abs(total.y) * 1.2f) 2 else 1
                                    }
                                    if (mode == 1) setVolume(fracOnArc(ch.position, g))
                                    if (mode != 0) ch.consume()
                                }
                                if (mode == 2 && total.x > g.w * 0.18f) backNow()
                            }
                            // пластинка: скретч
                            engine.ready && fromC < g.discR * 1.04f -> {
                                down.consume()
                                engine.beginScratch() // палец коснулся — пластинка остановлена
                                try {
                                    var prevA = ang(p0 - g.c)
                                    var prevT = down.uptimeMillis
                                    var v = 0f
                                    while (true) {
                                        val ev = withTimeoutOrNull(25) { awaitPointerEvent() }
                                        if (ev == null) { // палец держит, событий нет -> скорость 0
                                            v = 0f
                                            engine.setScratchVelocity(0f)
                                            continue
                                        }
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) { ch.consume(); break }
                                        val rel = ch.position - g.c
                                        val a = ang(rel)
                                        val dt = (ch.uptimeMillis - prevT).coerceIn(1L, 30L)
                                        // у самого центра угол шумит — игнорируем
                                        val da = if (rel.getDistance() < g.labelR * 0.8f) 0f else wrap(a - prevA)
                                        val inst = da / dt * 1000f / DEG_PER_SEC
                                        v = v * 0.4f + inst * 0.6f
                                        engine.setScratchVelocity(v.coerceIn(-10f, 10f))
                                        prevA = a
                                        prevT = ch.uptimeMillis
                                        ch.consume()
                                    }
                                } finally {
                                    engine.endScratch()
                                }
                            }
                            // вне пластинки: свайп вправо = назад к плейлисту
                            else -> {
                                var total = Offset.Zero
                                drag(down.id) { ch -> total += ch.positionChange() }
                                if (total.x > g.w * 0.18f && abs(total.y) < total.x) backNow()
                                // свайп влево с ПРАВОГО ободка — системный плеер часов (выбор наушников)
                                else if (total.x < -g.w * 0.18f && abs(total.y) < -total.x && isRightZone(p0, g)) systemNow()
                            }
                        }
                    }
                }
        ) {
            if (ambient) {
                // ambient: только тонкие серые контуры, смещение раз в минуту (защита от выгорания)
                Canvas(Modifier.fillMaxSize()) {
                    drawAmbient(Geo(size.width), progress, Offset(ambientShift.toFloat(), ambientShift.toFloat()))
                }
            } else {
                // вращающийся слой: этикетка/обложка + канавки
                Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
                    drawDisc(Geo(size.width), labelColor, cover)
                }
                // неподвижный блик поверх
                Canvas(Modifier.fillMaxSize()) { drawSheen(Geo(size.width), dimLabel = !playingUi) }
                // тонарм
                Canvas(Modifier.fillMaxSize()) {
                    drawArm(Geo(size.width), if (dragging) dragProgress else progress, dragging)
                }
                if (loading || error != null || !playingUi) {
                    Text(
                        text = when {
                            error != null -> "!"
                            loading -> "…"
                            else -> "▶"
                        },
                        fontSize = 16.sp,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // название по дуге внизу экрана (не обрезается углами круглого экрана)
            Canvas(Modifier.fillMaxSize()) {
                drawTitleArc(
                    Geo(size.width), shownTitle,
                    if (ambient) 0xFF777777.toInt() else 0xFFCCCCCC.toInt(), titlePaint, titleScroll,
                )
            }
            if (volVisible && !ambient) {
                Canvas(Modifier.fillMaxSize()) { drawVolume(Geo(size.width), volFrac) }
            }
        }
    }
}

private fun DrawScope.drawDisc(g: Geo, label: Color, cover: ImageBitmap?) {
    drawCircle(Color(0xFF111111), g.discR, g.c)
    var r = g.labelR * 1.25f
    while (r < g.discR * 0.97f) {
        drawCircle(Color.White.copy(alpha = 0.05f), r, g.c, style = Stroke(1f))
        r += g.discR * 0.045f
    }
    if (cover != null) {
        // обложка уже круглая; рисуем в размер этикетки, вращается вместе со слоем
        val d = (g.labelR * 2).roundToInt()
        drawImage(
            cover,
            dstOffset = IntOffset((g.c.x - g.labelR).roundToInt(), (g.c.y - g.labelR).roundToInt()),
            dstSize = IntSize(d, d),
            filterQuality = FilterQuality.Medium,
        )
        drawCircle(Color.Black.copy(alpha = 0.35f), g.labelR, g.c, style = Stroke(g.labelR * 0.04f))
        // засечка на ободке: по однотонной обложке вращение иначе не видно
        drawCircle(Color.White.copy(alpha = 0.9f), g.labelR * 0.06f, g.c + Offset(g.labelR * 0.88f, 0f))
    } else {
        drawCircle(label, g.labelR, g.c)
        drawArc(
            Color.Black.copy(alpha = 0.25f), 0f, 180f, true,
            topLeft = g.c - Offset(g.labelR, g.labelR), size = Size(g.labelR * 2, g.labelR * 2),
        )
        drawCircle(Color.White, g.labelR * 0.14f, g.c + Offset(g.labelR * 0.62f, 0f))
    }
    drawCircle(Color.Black, g.labelR * 0.07f, g.c) // шпиндель
    drawCircle(Color.White.copy(alpha = 0.15f), g.discR, g.c, style = Stroke(2f))
}

private fun DrawScope.drawSheen(g: Geo, dimLabel: Boolean) {
    val b = Brush.sweepGradient(
        0.00f to Color.Transparent, 0.08f to Color.White.copy(alpha = 0.10f), 0.16f to Color.Transparent,
        0.50f to Color.Transparent, 0.58f to Color.White.copy(alpha = 0.10f), 0.66f to Color.Transparent,
        1.00f to Color.Transparent,
        center = g.c,
    )
    drawCircle(b, g.discR, g.c)
    // на паузе затемняем этикетку, чтобы поверх читался значок ▶
    if (dimLabel) drawCircle(Color.Black.copy(alpha = 0.45f), g.labelR, g.c)
}

private fun DrawScope.drawArm(g: Geo, p: Float, active: Boolean) {
    val tip = g.armTip(p)
    val a = g.armAngle(p)
    val u = Offset(cos(a), sin(a))
    val rear = g.pivot - u * g.armRear
    drawCircle(Color(0xFF2A2A2A), g.w * 0.05f, g.pivot)
    drawCircle(Color(0xFF777777), g.w * 0.03f, g.pivot)
    drawLine(Color(0xFFD0D0D0), rear, tip, strokeWidth = g.w * 0.018f, cap = StrokeCap.Round)
    drawLine(Color(0xFFEEEEEE), tip - u * (g.w * 0.05f), tip, strokeWidth = g.w * 0.03f, cap = StrokeCap.Round)
    drawCircle(if (active) Color(0xFFFFC107) else Color(0xFFFF5252), g.w * 0.012f, tip)
}

private fun DrawScope.drawAmbient(g: Geo, p: Float, shift: Offset) {
    val gray = Color(0xFF7A7A7A)
    val a = g.armAngle(p)
    val u = Offset(cos(a), sin(a))
    drawCircle(gray, g.discR, g.c + shift, style = Stroke(2f))
    drawCircle(gray, g.labelR, g.c + shift, style = Stroke(2f))
    drawLine(gray, g.pivot + shift - u * g.armRear, g.armTip(p) + shift, strokeWidth = 3f, cap = StrokeCap.Round)
}

// Громкость — ползунок по ЛЕВОМУ ободку: от низа-слева (тихо) вверх (громко). Правый верх занят осью тонарма.
private const val VOL_START = 135f // углы экранные: 0° = справа, 90° = низ, 180° = лево, по часовой стрелке
private const val VOL_SWEEP = 90f
// Название внизу по дуге: по умолчанию СИММЕТРИЧНО низу (90°). Только если текст дошёл бы до дуги громкости слева (от 135°),
// он сдвигается вправо. Окно: от 127° (зазор до громкости) до 40° (чтобы не лезть на иглу); длиннее окна — прокрутка.
private const val TITLE_LEFT = 127f
private const val TITLE_RIGHT = 40f
private const val TITLE_CENTER = 90f

private fun deg360(p: Offset, g: Geo): Float {
    val a = ang(p - g.c)
    return if (a < 0f) a + 360f else a
}

private fun isVolumeZone(p: Offset, g: Geo): Boolean =
    (p - g.c).getDistance() >= g.discR * 1.04f && deg360(p, g) in VOL_START..(VOL_START + VOL_SWEEP)

private fun isRightZone(p: Offset, g: Geo): Boolean =
    (p - g.c).getDistance() >= g.discR * 1.04f && ang(p - g.c) in -50f..50f

private fun fracOnArc(p: Offset, g: Geo): Float = ((deg360(p, g) - VOL_START) / VOL_SWEEP).coerceIn(0f, 1f)

private fun DrawScope.drawVolume(g: Geo, frac: Float) {
    val r = g.w * 0.465f
    val tl = Offset(g.c.x - r, g.c.y - r)
    val sz = Size(2 * r, 2 * r)
    val st = Stroke(g.w * 0.014f, cap = StrokeCap.Round)
    drawArc(Color.White.copy(alpha = 0.3f), VOL_START, VOL_SWEEP, false, tl, sz, style = st)
    drawArc(Color.White, VOL_START, VOL_SWEEP * frac, false, tl, sz, style = st)
    val a = Math.toRadians((VOL_START + VOL_SWEEP * frac).toDouble())
    drawCircle(Color.White, g.w * 0.022f, Offset(g.c.x + r * Math.cos(a).toFloat(), g.c.y + r * Math.sin(a).toFloat()))
}

/** (длина текста, длина окна) в пикселях вдоль дуги — для решения, нужна ли прокрутка. */
private fun titleMetrics(paint: Paint, text: String, w: Float): Pair<Float, Float> {
    paint.textSize = w * 0.042f
    val rBase = w * 0.452f
    return paint.measureText(text) to rBase * Math.toRadians((TITLE_LEFT - TITLE_RIGHT).toDouble()).toFloat()
}

/**
 * Текст по дуге внизу (слева направо, верх букв — к центру). Каждый символ ставим вручную.
 * Влезает — стоит по центру окна; не влезает — сдвинут на scrollPx, края окна плавно гаснут.
 */
private fun DrawScope.drawTitleArc(g: Geo, text: String, color: Int, paint: Paint, scrollPx: Float) {
    val rBase = g.w * 0.452f
    paint.textSize = g.w * 0.042f
    paint.textAlign = Paint.Align.CENTER
    paint.color = color
    val baseAlpha = paint.alpha
    val maxLen = rBase * Math.toRadians((TITLE_LEFT - TITLE_RIGHT).toDouble()).toFloat()
    val len = paint.measureText(text)
    val scrolling = len > maxLen
    val fade = rBase * Math.toRadians(7.0).toFloat()
    // левый край текста от левого края окна: по центру низа (90°), но не левее окна — тогда прижимаем влево и текст едет вправо
    val centered = rBase * Math.toRadians((TITLE_LEFT - TITLE_CENTER).toDouble()).toFloat() - len / 2f
    val offset = if (scrolling) -scrollPx else maxOf(0f, centered)
    drawIntoCanvas { c ->
        val cv = c.nativeCanvas
        var adv = 0f
        for (i in text.indices) {
            val w = paint.measureText(text, i, i + 1)
            val s = offset + adv + w / 2f // центр символа вдоль дуги от левого края окна
            adv += w
            if (s < 0f || s > maxLen) continue
            var a = 1f
            if (scrolling) {
                val left = if (scrollPx > 0f) s / fade else 1f
                a = minOf(1f, left, (maxLen - s) / fade)
            }
            paint.alpha = (baseAlpha * a).toInt().coerceIn(0, 255)
            val thetaDeg = TITLE_LEFT - Math.toDegrees((s / rBase).toDouble()).toFloat()
            val th = Math.toRadians(thetaDeg.toDouble())
            val px = g.c.x + rBase * Math.cos(th).toFloat()
            val py = g.c.y + rBase * Math.sin(th).toFloat()
            cv.save()
            cv.rotate(thetaDeg - 90f, px, py)
            cv.drawText(text, i, i + 1, px, py, paint)
            cv.restore()
        }
    }
    paint.alpha = baseAlpha
}
