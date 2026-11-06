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
 * Freeform screenshot: save the TextureView's current frame to the system screenshots directory.
 *
 * Why TextureView.getBitmap() instead of MediaProjection:
 * MediaProjection pops a system authorization dialog every time, which is unacceptable UX;
 * while the TextureView itself holds the Surface content of the freeform's virtual display, so
 * getBitmap() grabs the current frame directly with no extra permission at all.
 *
 * The save path goes through MediaStore.Images: on Android 10+ use RELATIVE_PATH to write into
 * Pictures/Screenshots, and on Android 9 and below write the file straight into the public directory.
 */
internal object ScreenshotHelper {

    private const val TAG = "ScreenshotHelper"

    /**
     * @param textureView the freeform's content view
     * @param packageName the package running inside the freeform, used only to name the file
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
            // If a row was inserted but the write failed, delete the empty row so the gallery never shows a 0-byte image.
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
