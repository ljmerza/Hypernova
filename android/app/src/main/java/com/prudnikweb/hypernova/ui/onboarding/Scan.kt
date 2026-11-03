package com.prudnikweb.hypernova.ui.onboarding

import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A camera frame's brightness to the QR code in it (docs/DECISIONS.md, "Scan in the app": ZXing's
 * decoder, all on this phone). Inverted as well, for a code in a terminal's light-on-dark colours,
 * and with the rows closed up, for a terminal whose line spacing leaves stripes through its half
 * blocks, where a code doesn't read as it is. Plain Java underneath, so a test reads the daemon's
 * own code with exactly this.
 */
object QrFrames {
    private val HINTS = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")

    /** How many rows either way a stripe is closed over: 2 is a stripe up to 4 or so pixels high in the frame. */
    private const val CLOSE_ROWS = 2

    /**
     * The text of a QR code in [luma] (one byte a pixel, [rowStride] apart), or null. Says what it read
     * only to its caller. [stripes]: try with the rows closed up too, which takes two more copies of the frame.
     */
    fun read(luma: ByteArray, rowStride: Int, width: Int, height: Int, stripes: Boolean = true): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        val plain = decode(source) ?: decode(source.invert())
        if (plain != null || !stripes) return plain
        return decode(PlanarYUVLuminanceSource(closed(luma, rowStride, width, height, darkest = true), width, height, 0, 0, width, height, false))
            ?: decode(PlanarYUVLuminanceSource(closed(luma, rowStride, width, height, darkest = false), width, height, 0, 0, width, height, false).invert())
    }

    /** Each pixel as the darkest (or lightest) of the rows around it, so a thin stripe across the modules goes. */
    private fun closed(luma: ByteArray, rowStride: Int, width: Int, height: Int, darkest: Boolean): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val from = maxOf(0, y - CLOSE_ROWS)
            val to = minOf(height - 1, y + CLOSE_ROWS)
            for (x in 0 until width) {
                var best = luma[y * rowStride + x].toInt() and 0xff
                for (r in from..to) {
                    val v = luma[r * rowStride + x].toInt() and 0xff
                    if (if (darkest) v < best else v > best) best = v
                }
                out[y * width + x] = best.toByte()
            }
        }
        return out
    }

    private fun decode(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), HINTS).text
    } catch (_: Exception) {
        // Not a code, or not read this time: the next frame is along in a moment.
        null
    }
}

/** What Scan asks of the phone; a screen test stands in for it, as there's no camera under Robolectric. */
class ScanCamera(
    val present: (Context) -> Boolean,
    val viewfinder: @Composable (modifier: Modifier, onCode: (String) -> Unit, onFailed: () -> Unit) -> Unit,
)

val PhoneCamera = ScanCamera(
    present = { it.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) },
    viewfinder = { modifier, onCode, onFailed -> CameraViewfinder(modifier, onCode, onFailed) },
)

/**
 * The camera, reading: the first code it recognises goes to [onCode], whatever it says, with no tap
 * (a link Hypernova can't use is refused on the pairing screen, in its own words). Back, Cancel or
 * Paste leave it. The camera stops with this screen, by way of the viewfinder leaving composition.
 */
@Composable
fun ScanScreen(camera: ScanCamera, onCode: (String) -> Unit, onPaste: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var failed by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(HN.bg).pointerBlock()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        ) {
            HText(stringResource(R.string.scan_title), style = Type.display, color = HN.ink)
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) HText(stringResource(R.string.scan_failed), Modifier.padding(24.dp), style = Type.body, color = HN.inkMuted)
                else camera.viewfinder(Modifier.fillMaxSize(), onCode) { failed = true }
            }
            Spacer(Modifier.height(16.dp))
            if (!failed) HText(stringResource(R.string.scan_hint), style = Type.body, color = HN.inkMuted)
            Spacer(Modifier.weight(1f))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(stringResource(R.string.onb_paste), onPaste, Modifier.fillMaxWidth(), kind = if (failed) PillKind.Primary else PillKind.Outline, height = 48.dp)
                Pill(stringResource(R.string.cancel), onClose, Modifier.fillMaxWidth(), kind = PillKind.Ghost, height = 48.dp)
            }
        }
    }
}

/**
 * The back camera (a front one on a phone with only that) showing here, and each frame read in
 * memory, one at a time, the newest. No frame is saved or sent: there is no capture here at all.
 * It runs only while the app is on screen (the lifecycle) and this is composed.
 */
@Composable
private fun CameraViewfinder(modifier: Modifier, onCode: (String) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val code by rememberUpdatedState(onCode)
    val failed by rememberUpdatedState(onFailed)
    val view = remember {
        PreviewView(context).apply {
            // A TextureView: the rounded corners clip it, as they couldn't a SurfaceView.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    AndroidView({ view }, modifier)
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val reading = Executors.newSingleThreadExecutor()
        val gone = AtomicBoolean(false)
        val read = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // The default, 640×480, leaves too few pixels a module for a code across a room's width.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        var frame = ByteArray(0)
        var frames = 0
        analysis.setAnalyzer(reading) { image: ImageProxy ->
            image.use {
                if (gone.get() || read.get()) return@use
                val plane = it.planes[0]
                val size = plane.rowStride * it.height
                if (frame.size < size) frame = ByteArray(size)
                val bytes = plane.buffer
                bytes.rewind()
                bytes.get(frame, 0, minOf(bytes.remaining(), size))
                // The striped look is rare, and costly in memory on a phone: every third frame is plenty.
                val text = QrFrames.read(frame, plane.rowStride, it.width, it.height, stripes = frames++ % 3 == 0) ?: return@use
                if (read.compareAndSet(false, true)) main.execute { if (!gone.get()) code(text) }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener(
            {
                if (gone.get()) return@addListener
                try {
                    val p = future.get()
                    val selector = if (p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    p.bindToLifecycle(owner, selector, preview, analysis)
                    provider = p
                } catch (_: Exception) {
                    // Nothing to bind, in use by another app, or the permission gone: said on the screen.
                    failed()
                }
            },
            main,
        )
        onDispose {
            gone.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            reading.shutdown()
        }
    }
}
