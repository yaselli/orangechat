package me.rerere.rikkahub.ui.components.music

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.music.*

@Composable
internal fun MusicLoginQr(key: String, status: String, platform: String, image: String) {
    val context = LocalContext.current
    val bitmap =
        remember(key, platform, image) {
            if (platform == "qq") {
                runCatching {
                    require(image.startsWith("data:image/png;base64,") && image.length <= 700000)
                    val bytes = java.util.Base64.getDecoder().decode(image.substringAfter(','))
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth in 1..1024 && options.outHeight in 1..1024)
                    requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
                    .getOrNull()
            } else {
                val login =
                    Uri.Builder()
                        .scheme("https")
                        .authority("music.163.com")
                        .appendPath("login")
                        .appendQueryParameter("codekey", key)
                        .build()
                        .toString()
                val matrix = QRCodeWriter().encode(login, BarcodeFormat.QR_CODE, 400, 400)
                Bitmap.createBitmap(400, 400, Bitmap.Config.RGB_565).apply {
                    val pixels =
                        IntArray(400 * 400) { n ->
                            if (matrix[n % 400, n / 400]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        }
                    setPixels(pixels, 0, 400, 0, 0, 400, 400)
                }
            }
        }
    if (bitmap == null) {
        Text("二维码无法显示，请重新生成", color = MaterialTheme.colorScheme.error)
        return
    }
    val scope = rememberCoroutineScope()
    var saveError by remember { mutableStateOf<String?>(null) }
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri
            ->
            uri?.let {
                scope.launch {
                    saveError =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                requireNotNull(context.contentResolver.openOutputStream(uri)).use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                                .exceptionOrNull()
                                ?.let { "保存失败，请重试" }
                        }
                }
            }
        }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = "${musicPlatformName(platform)}登录二维码",
        modifier = Modifier.size(220.dp),
    )
    Text(status)
    saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    TextButton(
        onClick = {
            if (Build.VERSION.SDK_INT >= 29)
                scope.launch {
                    saveError =
                        withContext(Dispatchers.IO) {
                            runCatching { saveMusicQrToGallery(context, bitmap) }
                                .exceptionOrNull()
                                ?.let { "保存失败，请重试" }
                        }
                }
            else save.launch("$platform-login-qr.png")
        }
    ) {
        Text("保存二维码")
    }
}

@androidx.annotation.RequiresApi(29)
private fun saveMusicQrToGallery(context: Context, bitmap: Bitmap) {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "music-login-${System.currentTimeMillis()}.png",
            )
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WatermelonChat")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        requireNotNull(resolver.openOutputStream(uri)).use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
