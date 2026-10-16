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
 * 可点击数字输入的设置项滑条。
 *
 * 在 [SeekBarPreference] 基础上做两件事：
 * 1. 右侧的数值文字加一个灰色圆角底，让"数字"看起来像个可点的芯片；
 * 2. 点击数字弹出键盘输入框，按本条的 min/max 校验后写回，省得只能靠拖动一点点调。
 *
 * min/max 不再自己存一份，而是直接读父类的 [getMin] / [getMax]：设置页会按屏幕尺寸和
 * 把手大小实时重算这两个值，跳动的校验区间和拖动吸附跟着它们走才能保持一致。
 * app:seekStep 是拖动步进（转给父类的 seekBarIncrement，并在松手时做实际吸附），
 * 用于档位数多于轨道像素、中间某些值滑不到的场景。
 */
class NumericSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    // 拖动步进（app:seekStep），<=1 表示不吸附。
    private var stepIncrement = 1

    init {
        var step = 0
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.NumericSeekBarPreference)
            step = a.getInt(R.styleable.NumericSeekBarPreference_seekStep, 0)
            a.recycle()
        }
        // 档位比轨道像素还多时，单个数值只有 1px 的落点，像 Y 轴的 0（屏幕居中）基本滑不到。
        // seekBarIncrement 只能管住方向键，拖动吸附见 bindStepSnap。
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
        // 父容器 seekbar_frame 自带 ~16dp 的 paddingEnd，把整行内容右边界卡在 992，
        // 导致数值芯片比其它设置项的开关（右边缘 1036）靠左一截。清掉这个 paddingEnd，
        // 让芯片右边缘贴到行右边缘与开关对齐；滑条带 weight 会自动伸缩。
        (valueView.parent as? android.view.View)?.let { frame ->
            if (frame.paddingRight != 0) {
                frame.setPadding(frame.paddingLeft, frame.paddingTop, 0, frame.paddingBottom)
            }
        }
        // 加 16dp 左边距作为芯片与滑块之间的间隙（滑条会自动缩短让位）。
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
     * 松手后把值吸附到步进的整数倍。平台 SeekBar 的 increment 只作用于按键，拖动过程中
     * 根本不吸附，所以这里只挂一个不消费事件的触摸监听，等框架写完最终值再修正一次。
     */
    private fun bindStepSnap(holder: PreferenceViewHolder) {
        if (stepIncrement <= 1) return
        val seekBar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar ?: return
        seekBar.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                // post 一下：让框架先在 onStopTrackingTouch 里把拖动结束的值落库，再吸附
                v.post { snapToStep() }
            }
            false
        }
    }

    private fun snapToStep() {
        val snapped = ((value.toFloat() / stepIncrement).roundToInt() * stepIncrement)
            .coerceIn(min, max)
        if (snapped != value) {
            // 交给 SeekBarPreference 落库并刷新滑条与数字显示
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
                    // 按当前（可能是运行时重算过的）区间夹住，再交给 SeekBarPreference 落库
                    value = input.coerceIn(min, max)
                }
            }
            .show()
    }
}
