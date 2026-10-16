package com.moonlight.freeform.ui.freeform

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.TextureView
import android.widget.Toast
import com.moonlight.freeform.R
import java.io.OutputStream

/**
 * 小窗截屏：把 TextureView 当前帧保存到系统截图目录。
 *
 * 之所以用 TextureView.getBitmap() 而不是 MediaProjection：
 * MediaProjection 需要每次弹系统授权框，体验上无法接受；
 * 而 TextureView 本身持有小窗虚拟屏的 Surface 内容，
 * getBitmap() 直接拿到当前帧，不需要任何额外权限。
 *
 * 保存路径走 MediaStore.Images，Android 10+ 用 RELATIVE_PATH
 * 写入 Pictures/Screenshots，Android 9 及以下直接写公共目录文件。
 */
internal object ScreenshotHelper {

    private const val TAG = "ScreenshotHelper"

    /**
     * @param textureView 小窗的画面控件
     * @param packageName 小窗内运行的包名，仅用于给文件命名
     */
    fun capture(context: Context, textureView: TextureView, packageName: String) {
        try {
            val bitmap = textureView.bitmap ?: run {
                toast(context, R.string.screenshot_failed)
                return
            }
            val name = "Freeform_${sanitize(packageName)}_${System.currentTimeMillis()}.png"
            val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, name, bitmap)
            } else {
                saveViaFile(name, bitmap)
            }
            toast(
                context,
                if (saved) R.string.screenshot_saved else R.string.screenshot_failed
            )
        } catch (e: Throwable) {
            Log.w(TAG, "screenshot_failed", e)
            toast(context, R.string.screenshot_failed)
        }
    }

    private fun sanitize(packageName: String): String =
        packageName.replace(Regex("[^A-Za-z0-9_]"), "_").take(24).ifEmpty { "app" }

    private fun saveViaMediaStore(context: Context, name: String, bitmap: Bitmap): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Screenshots")
        }
        var uri: Uri? = null
        var out: OutputStream? = null
        return try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            out = resolver.openOutputStream(uri) ?: return false
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            true
        } catch (e: Throwable) {
            // 插入了记录但写入失败时删掉空记录，避免图库里出现 0 字节的图。
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        } finally {
            runCatching { out?.close() }
        }
    }

    private fun saveViaFile(name: String, bitmap: Bitmap): Boolean {
        val dir = Environment.getExternalStoragePublicDirectory(
            "${Environment.DIRECTORY_PICTURES}/Screenshots"
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = java.io.File(dir, name)
        return try {
            java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun toast(context: Context, resId: Int) {
        runCatching { Toast.makeText(context, resId, Toast.LENGTH_SHORT).show() }
    }
}
