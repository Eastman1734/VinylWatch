package app.vinylwatch

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Плеер живёт на уровне процесса, а не Activity: музыка играет, пока Activity закрыта. */
class VinylApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val deck: Deck by lazy { Deck(this, scope) }
}
