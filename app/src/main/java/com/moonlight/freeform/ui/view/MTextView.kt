package com.moonlight.freeform.ui.view

import android.content.Context
import android.util.AttributeSet

/**
 * @date 2022/8/23
 * @author sunshine0523
 * Always reports focus so the marquee scrolls automatically
 */
class MTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : androidx.appcompat.widget.AppCompatTextView(context, attrs) {

    override fun isFocused(): Boolean {
        return true
    }
}