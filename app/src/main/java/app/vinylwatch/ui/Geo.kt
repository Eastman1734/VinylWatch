package app.vinylwatch.ui

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Вся геометрия экрана в долях стороны квадрата w (круглый экран 480x480). */
class Geo(val w: Float) {
    val c = Offset(w / 2f, w / 2f)
    val discR = 0.38f * w
    val labelR = 0.40f * discR

    val pivot = Offset(0.80f * w, 0.20f * w)
    val armLen = 0.55f * w
    val armRear = 0.07f * w

    private val phi0 = atan2(c.y - pivot.y, c.x - pivot.x) // направление от оси тонарма к центру диска
    private val d = hypot(c.x - pivot.x, c.y - pivot.y)

    // угол отклонения руки от линии "ось -> центр", при котором игла на радиусе r (теорема косинусов)
    private fun delta(r: Float): Float =
        acos(((armLen * armLen + d * d - r * r) / (2f * armLen * d)).coerceIn(-1f, 1f))

    private val deltaOut = delta(0.92f * discR) // начало трека (внешняя канавка)
    private val deltaIn = delta(0.48f * discR)  // конец трека (у этикетки)

    fun armAngle(progress: Float): Float = phi0 - (deltaOut + (deltaIn - deltaOut) * progress)

    fun armTip(progress: Float): Offset {
        val a = armAngle(progress)
        return Offset(pivot.x + armLen * cos(a), pivot.y + armLen * sin(a))
    }

    fun armRearPoint(progress: Float): Offset {
        val a = armAngle(progress)
        return Offset(pivot.x - armRear * cos(a), pivot.y - armRear * sin(a))
    }

    /** Палец -> прогресс трека (0..1) по углу руки вокруг оси. */
    fun progressFor(finger: Offset): Float {
        val theta = atan2(finger.y - pivot.y, finger.x - pivot.x)
        var dl = phi0 - theta
        val twoPi = (2 * PI).toFloat()
        while (dl > PI) dl -= twoPi
        while (dl < -PI) dl += twoPi
        return ((deltaOut - dl) / (deltaOut - deltaIn)).coerceIn(0f, 1f)
    }
}

fun distToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - Offset(a.x + ab.x * t, a.y + ab.y * t)).getDistance()
}
