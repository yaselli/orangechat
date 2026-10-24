package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.components.crop.ImageCropDialog
import me.rerere.rikkahub.ui.context.LocalToaster
import com.dokar.sonner.ToastType
import java.io.File
import kotlin.uuid.Uuid

/** Cropping stays in Compose; the result is copied by the caller before its temporary file is removed. */
@Composable
internal fun useCropLauncher(
    onCroppedImageReady: (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
): (Uri) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val currentOnReady by rememberUpdatedState(onCroppedImageReady)
    val currentCleanup by rememberUpdatedState(onCleanup)
    var source by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun finish() {
        source = null
        currentCleanup?.invoke()
    }

    source?.let { uri ->
        ImageCropDialog(
            image = uri,
            onDismiss = { if (!saving) finish() },
            onConfirm = { bitmap ->
                if (saving) return@ImageCropDialog
                saving = true
                scope.launch {
                    val output = File(context.appTempFolder, "crop_${Uuid.random()}.png")
                    try {
                        withContext(Dispatchers.IO) {
                            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        }
                        currentOnReady(output.toUri())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        toaster.show("图片裁剪失败，请重试", type = ToastType.Error)
                    } finally {
                        output.delete()
                        saving = false
                        finish()
                    }
                }
            },
        )
    }
    return { uri -> if (!saving) source = uri }
}
