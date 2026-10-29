package app.vinylwatch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Открыть СИСТЕМНЫЙ плеер часов (где кнопка наушников/выхода звука).
 * Публичного intent'а для него нет, поэтому два уровня:
 *  1) COMPONENT — «пакет/класс» системного плеера, если задан;
 *  2) запасной — настройки Bluetooth (подключить наушники).
 * Узнать COMPONENT: открой системный плеер на часах и выполни
 *   adb shell dumpsys activity activities | Select-String topResumedActivity
 * В строке будет вид  com.xxx.yyy/.ZzzActivity  — его и впиши ниже.
 */
object SystemPlayer {
    const val COMPONENT = "com.samsung.android.mediacontroller/.view.control.MediaControlActivity"

    fun open(ctx: Context) {
        val tries = ArrayList<Intent>()
        ComponentName.unflattenFromString(COMPONENT)?.let { tries += Intent().setComponent(it) }
        tries += Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        for (i in tries) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                // нет такой активности / нет доступа — пробуем следующий вариант
            }
        }
    }
}
