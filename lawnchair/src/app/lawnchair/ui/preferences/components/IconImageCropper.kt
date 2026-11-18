package app.lawnchair.ui.preferences.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.launcher3.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "IconImageCropper"
private const val MAX_DECODE_PX = 2048
private const val MAX_ZOOM = 8f

/** Fraction of the shorter side of the preview that the square frame takes. */
private const val FRAME_FRACTION = 0.8f

/**
 * Full-screen editor for a picked image: drag and pinch to frame a square, then [onCrop] gets that
 * square scaled to [outputPx]. Images that can't be read call [onCancel].
 */
@Composable
fun IconImageCropper(
    uri: Uri,
    outputPx: Int,
    onCrop: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    val currentOnCancel by rememberUpdatedState(onCancel)
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { decode(context, uri) }
        if (bitmap == null) currentOnCancel()
    }
    // User zoom on top of the scale where the image just covers the frame, and pan in preview pixels.
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var pan by remember(uri) { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.safeDrawingPadding()) {
                Text(
                    text = stringResource(R.string.icon_image_crop_hint),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = bitmap
                    if (image == null) {
                        CircularProgressIndicator()
                    } else {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { viewSize = it }
                                .pointerInput(image) {
                                    detectTransformGestures { _, panChange, zoomChange, _ ->
                                        zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                        // Clamp here so the stored pan never leaves the frame uncovered.
                                        pan = CropLayout(size.toSize(), image, zoom, pan + panChange).pan
                                    }
                                },
                        ) {
                            val layout = CropLayout(size, image, zoom, pan)
                            drawImage(
                                image = image.asImageBitmap(),
                                dstOffset = IntOffset(layout.imageLeft.roundToInt(), layout.imageTop.roundToInt()),
                                dstSize = IntSize(
                                    (image.width * layout.scale).roundToInt(),
                                    (image.height * layout.scale).roundToInt(),
                                ),
                            )
                            val frame = Path().apply {
                                addRoundRect(
                                    RoundRect(
                                        left = layout.frameLeft,
                                        top = layout.frameTop,
                                        right = layout.frameLeft + layout.frame,
                                        bottom = layout.frameTop + layout.frame,
                                        cornerRadius = CornerRadius(layout.frame * 0.12f),
                                    ),
                                )
                            }
                            clipPath(frame, clipOp = ClipOp.Difference) {
                                drawRect(Color.Black.copy(alpha = 0.6f))
                            }
                            drawPath(frame, Color.White, style = Stroke(width = 2.dp.toPx()))
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    Button(
                        enabled = bitmap != null && viewSize != IntSize.Zero,
                        onClick = {
                            val image = bitmap ?: return@Button
                            val size = Size(viewSize.width.toFloat(), viewSize.height.toFloat())
                            onCrop(CropLayout(size, image, zoom, pan).crop(image, outputPx))
                        },
                    ) {
                        Text(stringResource(R.string.icon_image_use))
                    }
                }
            }
        }
    }
}

/**
 * Where the image and the square frame sit in a preview of [view] size. The image is centred, scaled
 * to cover the frame times [zoom], and moved by [requestedPan] only as far as keeps the frame covered.
 */
internal class CropLayout(view: Size, image: Bitmap, zoom: Float, requestedPan: Offset) {
    val frame = min(view.width, view.height) * FRAME_FRACTION
    val frameLeft = (view.width - frame) / 2
    val frameTop = (view.height - frame) / 2
    val scale = frame / min(image.width, image.height) * zoom
    private val maxPanX = (image.width * scale - frame) / 2
    private val maxPanY = (image.height * scale - frame) / 2
    val pan = Offset(requestedPan.x.coerceIn(-maxPanX, maxPanX), requestedPan.y.coerceIn(-maxPanY, maxPanY))
    val imageLeft = (view.width - image.width * scale) / 2 + pan.x
    val imageTop = (view.height - image.height * scale) / 2 + pan.y

    /** The part of [image] under the frame, as an [outputPx] square. */
    fun crop(image: Bitmap, outputPx: Int): Bitmap {
        val side = (frame / scale).roundToInt().coerceIn(1, min(image.width, image.height))
        val left = ((frameLeft - imageLeft) / scale).roundToInt().coerceIn(0, image.width - side)
        val top = ((frameTop - imageTop) / scale).roundToInt().coerceIn(0, image.height - side)
        val square = Bitmap.createBitmap(image, left, top, side, side)
        return Bitmap.createScaledBitmap(square, outputPx, outputPx, true)
    }
}

private fun decode(context: Context, uri: Uri): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = max(info.size.width, info.size.height)
        if (longest > MAX_DECODE_PX) decoder.setTargetSampleSize(longest / MAX_DECODE_PX)
    }
} catch (e: Exception) {
    Log.w(TAG, "Could not read the picked image", e)
    null
}
