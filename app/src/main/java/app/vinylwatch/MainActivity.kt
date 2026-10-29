package app.vinylwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.MaterialTheme
import app.vinylwatch.ui.PlayerScreen
import app.vinylwatch.ui.PlaylistScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    private var ambientTick by mutableIntStateOf(0)
    private var resumed by mutableStateOf(false)
    private var openPlayerTick by mutableIntStateOf(0) // растёт, когда нас открыли значком на циферблате/уведомлением

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) { ambient = true }
        override fun onExitAmbient() { ambient = false }
        override fun onUpdateAmbient() { ambientTick++ } // раз в минуту: сдвигаем картинку против выгорания
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        if (intent?.getBooleanExtra(EXTRA_PLAYER, false) == true) openPlayerTick++
        setContent {
            MaterialTheme {
                App(
                    ambient, ambientTick, resumed, openPlayerTick,
                    onExit = { finish() }, // музыка продолжает играть в сервисе
                    onSystemPlayer = { SystemPlayer.open(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PLAYER, false)) openPlayerTick++
    }

    override fun onResume() { super.onResume(); resumed = true }
    override fun onPause() { resumed = false; super.onPause() }

    companion object {
        const val EXTRA_PLAYER = "open_player"
    }
}

@Composable
private fun App(
    ambient: Boolean,
    ambientTick: Int,
    resumed: Boolean,
    openPlayerTick: Int,
    onExit: () -> Unit,
    onSystemPlayer: () -> Unit,
) {
    val ctx = LocalContext.current
    val deck = remember { (ctx.applicationContext as VinylApp).deck }

    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ctx.checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(audioPerm)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
    }

    var tick by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val uiScope = rememberCoroutineScope()
    var showPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(openPlayerTick) {
        if (openPlayerTick > 0 && deck.engine != null && deck.current != null) showPlayer = true
    }
    LaunchedEffect(granted, tick) {
        deck.tracks = withContext(Dispatchers.IO) { Library.scan(ctx, granted) }
    }

    val engine = deck.engine
    val cur = deck.current
    var cover by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(cur, deck.coverVersion) {
        cover = null
        cover = cur?.let { withContext(Dispatchers.IO) { Covers.load(ctx, it) } }
    }

    if (showPlayer && engine != null && cur != null) {
        key(engine) {
            PlayerScreen(
                engine = engine,
                title = cur.title,
                loading = deck.loading,
                error = deck.error,
                labelColor = Color.hsv(cur.title.hashCode().mod(360).toFloat(), 0.55f, 0.85f),
                cover = cover,
                animate = resumed && !ambient,
                ambient = ambient,
                ambientShift = ((ambientTick % 3) - 1) * 3,
                onSystemPlayer = onSystemPlayer,
                onTogglePlay = { deck.togglePlay() },
                onBack = { showPlayer = false },
            )
        }
    } else {
        PlaylistScreen(
            tracks = deck.tracks,
            current = cur,
            needsPermission = !granted,
            onRequestPermission = { launcher.launch(arrayOf(audioPerm)) },
            onRefresh = {
                if (!refreshing) uiScope.launch {
                    refreshing = true
                    try {
                        if (granted) Library.requestIndex(ctx.applicationContext)
                        tick++
                    } finally { refreshing = false }
                }
            },
            coverStatus = deck.coverStatus,
            coverRunning = deck.coverRunning,
            onFetchCovers = { deck.fetchCovers() },
            onOpenPlayer = if (engine != null && cur != null) ({ showPlayer = true }) else null,
            onExit = onExit,
            onPick = { deck.open(it); showPlayer = true },
        )
    }
}
