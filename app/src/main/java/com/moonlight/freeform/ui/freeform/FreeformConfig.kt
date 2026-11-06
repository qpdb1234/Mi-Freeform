package com.moonlight.freeform.ui.freeform

data class FreeformConfig(
    //Whether to use a custom config instead of configuring inside FreeformView
    var useCustomConfig: Boolean = false,
    //Package name of the running app
    var packageName: String,
    //Activity name of the running app
    var activityName: String,
    //userId to launch under
    var userId: Int,
    //Maximum overlay layer height
    var maxHeight: Int = 1,
    //DPI
    var freeformDpi: Int = 1,
    //Width-to-height ratio, default 9:16
    var widthHeightRatio: Float = 10f / 16f,
    //Use Shizuku/Sui to stop the freeform from escaping to fullscreen
    var useSuiRefuseToFullScreen: Boolean = false,
    //Change the scale ratio on screen rotation
    var changeDpi: Boolean = false,
    //Launch in compatibility mode
    @Deprecated("", ReplaceWith(""))
    var compatibleMode: Boolean = false,
    var rememberPosition: Boolean = false,
    //Remember the launch position
    var rememberX: Int = 0,
    var rememberY: Int = 0,
    //Action performed on double-tapping the center of the bottom bar (default: rotate the freeform)
    var bottomDoubleTapAction: TopBarAction = TopBarAction.ROTATE
) {

}