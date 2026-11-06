package com.moonlight.freeform.ui.freeform

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.moonlight.freeform.app.MiFreeform
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * @date 2022/8/22
 * @author sunshine0523
 * Freeform helper class
 */
object FreeformHelper {
    //Default freeform height
    const val PORTRAIT_HEIGHT = 1200
    const val LANDSCAPE_HEIGHT = 800

    //Default freeform DPI
    const val PORTRAIT_DPI = 500
    const val LANDSCAPE_DPI = 500

    //The freeforms currently shown
    private val freeformStackSet = StackSet()
    //The freeforms suspended in the background
    private val miniFreeformStackSet = StackSet()

    //Records package name + userId for faster lookups
    private val freeformPackageSet = HashSet<String>()
    private val miniFreeformPackageSet = HashSet<String>()

    fun getDefaultHeight(context: Context): Int {
        return getDefaultHeight(context, (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY))
    }

    fun getDefaultHeight(context: Context, defaultDisplay: Display): Int {
        return getDefaultHeight(context, defaultDisplay, 10f / 16f)
    }

    /**
     * @param defaultDisplay the default display, used to read the screen orientation
     * @param widthHeightRadio the freeform's width-to-height ratio
     */
    fun getDefaultHeight(context: Context, defaultDisplay: Display, widthHeightRadio: Float): Int {
        val screenWidth = min(context.resources.displayMetrics.heightPixels, context.resources.displayMetrics.widthPixels)
        val rotation = defaultDisplay.rotation
        return if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) (screenWidth / widthHeightRadio).roundToInt() else screenWidth
    }

    fun getDefaultDpi(context: Context): Int {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val orientation = context.resources.configuration.orientation
        val freeformHeight = getDefaultHeight(context)
        val screenDpi = context.resources.displayMetrics.densityDpi
        val screenHeight = context.resources.displayMetrics.heightPixels
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            (screenHeight * sp.getInt("freeform_landscape_dpi", 75) / 100.0f / freeformHeight * screenDpi).roundToInt()
        } else {
            //This 0.75 was found empirically; it's the DPI value that simply looks right
            (screenHeight * sp.getInt("freeform_dpi", 75) / 100.0f / freeformHeight * screenDpi).roundToInt()
        }
    }

    fun getScreenDpi(context: Context): Int {
        return context.resources.displayMetrics.densityDpi
    }

    /**
     * @param rotation whether the screen orientation is portrait
     * @see Surface.ROTATION_0
     * @return orientation
     */
    fun screenIsPortrait(rotation: Int): Boolean {
        return rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
    }

    /**
     * Whether this freeform is on the topmost, i.e. operable, layer
     */
    fun isShowingFirst(freeformView: FreeformViewAbs): Boolean {
        return freeformStackSet.peek() == freeformView
    }

    fun addFreeformToSet(freeformView: FreeformViewAbs) {
        freeformStackSet.push(freeformView)
        freeformPackageSet.add("${freeformView.config.packageName}/${freeformView.config.userId}")
    }

    fun removeFreeformFromSet(freeformView: FreeformViewAbs) {
        freeformStackSet.remove(freeformView)
        freeformPackageSet.remove("${freeformView.config.packageName}/${freeformView.config.userId}")
    }

    fun addMiniFreeformToSet(freeformView: FreeformViewAbs) {
        miniFreeformStackSet.push(freeformView)
        miniFreeformPackageSet.add("${freeformView.config.packageName}/${freeformView.config.userId}")
    }

    fun removeMiniFreeformFromSet(freeformView: FreeformViewAbs) {
        miniFreeformStackSet.remove(freeformView)
        miniFreeformPackageSet.remove("${freeformView.config.packageName}/${freeformView.config.userId}")
    }

    fun getFreeformStackSet(): StackSet {
        return freeformStackSet
    }

    fun getMiniFreeformStackSet(): StackSet {
        return miniFreeformStackSet
    }

    //Check whether the freeform to be launched is already running in a freeform
    fun isAppInFreeform(packageName: String, userId: Int): Boolean {
        return freeformPackageSet.contains("$packageName/$userId")
    }
    //Check whether the freeform to be launched is already running in the background in a freeform
    fun isAppInMiniFreeform(packageName: String, userId: Int): Boolean {
        return miniFreeformPackageSet.contains("$packageName/$userId")
    }

    /**
     * Fully destroy an app's previous freeform (foreground freeform + background-suspended freeform).
     *
     * Used before "the same app launches a freeform again": the old instance may be showing in the foreground,
     * or may already be collapsed into the sidebar or turned into a floating ball; both must be cleaned up,
     * otherwise you get two freeforms, or an orphan ball that neither responds to taps nor shows any content.
     */
    fun destroyExistingFreeform(packageName: String, userId: Int) {
        if (isAppInFreeform(packageName, userId)) {
            getFreeformStackSet().getByPackageName(packageName, userId)?.destroyFreeform()
        }
        if (isAppInMiniFreeform(packageName, userId)) {
            getMiniFreeformStackSet().getByPackageName(packageName, userId)?.destroyFreeform()
        }
    }
}