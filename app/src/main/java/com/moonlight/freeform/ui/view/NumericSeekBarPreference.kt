package com.moonlight.freeform.ui.view

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moonlight.freeform.R
import kotlin.math.roundToInt

/**
 * A settings seek bar whose numeric value can be tapped for direct input.
 *
 * On top of [SeekBarPreference] it does two things:
 * 1. Adds a gray rounded background to the value text on the right, making the "number" look like a tappable chip;
 * 2. Tapping the number pops a keyboard input box, validated against this item's min/max before writing back, so you don't have to fine-tune only by dragging.
 *
 * min/max are no longer stored separately but read directly from the parent's [getMin] / [getMax]: the settings page
 * recomputes these two values live based on screen size and handle size, so the validation range and drag snapping must follow them to stay consistent.
 * app:seekStep is the drag step (forwarded to the parent's seekBarIncrement, with actual snapping on release),
 * useful when there are more steps than track pixels and some intermediate values can't be reached by sliding.
 */
class NumericSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    // Drag step (app:seekStep); <=1 means no snapping.
    private var stepIncrement = 1

    init {
        var step = 0
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.NumericSeekBarPreference)
            step = a.getInt(R.styleable.NumericSeekBarPreference_seekStep, 0)
            a.recycle()
        }
        // When there are more steps than track pixels, a single value has only a 1px landing point, so values like Y-axis 0 (screen center) are basically unreachable.
        // seekBarIncrement only governs the arrow keys; drag snapping is handled in bindStepSnap.
        if (step > 1) {
            stepIncrement = step
            seekBarIncrement = step
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        bindStepSnap(holder)
        val valueView = holder.findViewById(androidx.preference.R.id.seekbar_value) ?: return
        valueView.setBackgroundResource(R.drawable.bg_seekbar_value)
        val density = context.resources.displayMetrics.density
        val hPad = (8 * density).toInt()
        val vPad = (2 * density).toInt()
        (valueView as? TextView)?.apply {
            setPadding(hPad, vPad, hPad, vPad)
            gravity = Gravity.CENTER
        }
        // The parent container seekbar_frame has ~16dp paddingEnd, which pins the whole row's right edge at 992,
        // making the value chip sit left of the switches in other settings items (right edge 1036). Clear this paddingEnd
        // so the chip's right edge meets the row's right edge and aligns with the switch; the seek bar has weight and shrinks automatically.
        (valueView.parent as? android.view.View)?.let { frame ->
            if (frame.paddingRight != 0) {
                frame.setPadding(frame.paddingLeft, frame.paddingTop, 0, frame.paddingBottom)
            }
        }
        // Add a 16dp start margin as the gap between the chip and the thumb (the seek bar shortens automatically to yield).
        (valueView.layoutParams as? LinearLayout.LayoutParams)?.let { vlp ->
            val gap = (16 * density).toInt()
            if (vlp.marginStart < gap) {
                vlp.marginStart = gap
                valueView.layoutParams = vlp
            }
        }
        valueView.isClickable = true
        valueView.setOnClickListener { showInputDialog() }
    }

    /**
     * After release, snap the value to a multiple of the step. The platform SeekBar's increment only affects key presses;
     * it doesn't snap during dragging at all, so here we only attach a touch listener that consumes no events and corrects once after the framework writes the final value.
     */
    private fun bindStepSnap(holder: PreferenceViewHolder) {
        if (stepIncrement <= 1) return
        val seekBar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar ?: return
        seekBar.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                // post once: let the framework first persist the drag-end value in onStopTrackingTouch, then snap
                v.post { snapToStep() }
            }
            false
        }
    }

    private fun snapToStep() {
        val snapped = ((value.toFloat() / stepIncrement).roundToInt() * stepIncrement)
            .coerceIn(min, max)
        if (snapped != value) {
            // Let SeekBarPreference persist it and refresh the seek bar and number display
            value = snapped
        }
    }

    private fun showInputDialog() {
        val density = context.resources.displayMetrics.density
        val edit = AppCompatEditText(context).apply {
            inputType = if (min < 0) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            setText(value.toString())
            setSelection(text?.length ?: 0)
        }
        val container = FrameLayout(context).apply {
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done) { _, _ ->
                val input = edit.text?.toString()?.toIntOrNull()
                if (input != null) {
                    // Clamp to the current (possibly runtime-recomputed) range, then let SeekBarPreference persist it
                    value = input.coerceIn(min, max)
                }
            }
            .show()
    }
}
