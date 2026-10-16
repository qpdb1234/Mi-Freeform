package com.moonlight.freeform.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.databinding.StatusCardItemBinding
import com.moonlight.freeform.hook.utils.HookTest
import com.moonlight.freeform.utils.PermissionUtils
import rikka.sui.Sui

/**
 * 设置页顶部的三张状态卡片：Xposed / Shizuku-Sui / 无障碍。
 *
 * 从原 HomeFragment 迁移而来。三张卡片各占一行、与设置条目等高，
 * 其中无障碍卡片始终显示（原来仅在未开启时出现）。
 */
class StatusCards(
    private val fragment: Fragment,
    // 对外只读：SettingFragment 靠它判断“绑定的还是不是原来那个容器”，
    // 避免同一个容器被反复 addView 卡片。
    val container: ViewGroup
) : View.OnClickListener {

    private lateinit var sp: android.content.SharedPreferences
    private var xposedCard: MaterialCardView? = null
    private var shizukuCard: MaterialCardView? = null
    private var accessibilityCard: MaterialCardView? = null
    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        //防一手：重复进入时先把旧卡片清掉，避免同一个容器里叠出多套。
        container.removeAllViews()
        sp = fragment.requireContext()
            .getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)

        xposedCard = addCard().apply {
            id = R.id.status_card_xposed
            setOnClickListener(this@StatusCards)
        }
        shizukuCard = addCard().apply {
            id = R.id.status_card_shizuku
            setOnClickListener(this@StatusCards)
        }
        accessibilityCard = addCard().apply {
            id = R.id.status_card_accessibility
            setOnClickListener(this@StatusCards)
        }
    }

    /** 无障碍卡片的跳转回调，由 SettingFragment 注入（复用其已注册的 launcher）。 */
    var onGoToAccessibilitySettings: (() -> Unit)? = null

    private fun addCard(): MaterialCardView {
        val binding = StatusCardItemBinding.inflate(
            LayoutInflater.from(fragment.requireContext()), container, false
        )
        container.addView(binding.root)
        return binding.root
    }

    /** 刷新三张卡片的状态显示。 */
    fun refresh() {
        checkShizuku()
        checkXposed()
        refreshAccessibility()
    }

    private fun checkShizuku() {
        val card = shizukuCard ?: return
        val holder = card.tag as? Holder ?: bindHolder(card)
        val result = MiFreeform.me?.isRunning?.value == true
        if (result) {
            holder.bg.setBackgroundColor(contextColor(R.color.success_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_done)
            )
            holder.text.text = if (Sui.isSui())
                fragment.getString(R.string.sui_start_short)
            else fragment.getString(R.string.shizuku_start_short)
        } else {
            holder.bg.setBackgroundColor(contextColor(R.color.warn_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_error_white)
            )
            holder.text.text = fragment.getString(R.string.shizuku_no_start)
        }
    }

    private fun checkXposed() {
        val card = xposedCard ?: return
        val holder = card.tag as? Holder ?: bindHolder(card)
        if (HookTest.checkXposed()) {
            holder.bg.setBackgroundColor(contextColor(R.color.success_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_done)
            )
            holder.text.text = fragment.getString(R.string.xposed_start_short)
            holder.text.requestFocus()
        } else {
            holder.bg.setBackgroundColor(contextColor(R.color.warn_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_error_white)
            )
            holder.text.text = fragment.getString(R.string.xposed_no_start)
        }
    }

    /**
     * 无障碍卡片：始终显示，仅随状态变色/变文案。
     * 原首页里它在已开启时会被隐藏（visibility=GONE），迁移后保持常驻。
     */
    private fun refreshAccessibility() {
        val card = accessibilityCard ?: return
        val holder = card.tag as? Holder ?: bindHolder(card)
        val result = PermissionUtils.isAccessibilitySettingsOn(fragment.requireContext())
        if (result) {
            holder.bg.setBackgroundColor(contextColor(R.color.success_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_done)
            )
            holder.text.text = fragment.getString(R.string.accessibility_start)
        } else {
            holder.bg.setBackgroundColor(contextColor(R.color.warn_color))
            holder.icon.setImageDrawable(
                AppCompatResources.getDrawable(fragment.requireContext(), R.drawable.ic_error_white)
            )
            holder.text.text = fragment.getString(R.string.accessibility_no_start)
        }
    }

    private fun bindHolder(card: MaterialCardView): Holder {
        val holder = Holder(
            card.findViewById(R.id.info_card_bg),
            card.findViewById(R.id.info_card_icon),
            card.findViewById(R.id.info_card_text)
        )
        card.tag = holder
        return holder
    }

    private fun contextColor(resId: Int): Int = ContextCompat.getColor(fragment.requireContext(), resId)

    private class Holder(
        val bg: View,
        val icon: ImageView,
        val text: android.widget.TextView
    )

    /**
     * 点卡片只弹介绍与描述（与原来的权限页一致），不弹 Snackbar 那种一闪而过的小条。
     * 关闭对话框后再刷一次状态，便于激活完 Shizuku 直接看到卡片变色。
     */
    override fun onClick(v: View) {
        when (v.id) {
            R.id.status_card_xposed -> {
                showIntroDialog(
                    fragment.getString(R.string.xposed_card_title),
                    fragment.getString(R.string.xposed_permission_intro)
                )
            }
            R.id.status_card_shizuku -> {
                //点一下顺带重连 binder，然后把介绍和当前状态写在对话框里
                MiFreeform.me?.initShizuku()
                val running = MiFreeform.me?.isRunning?.value == true
                showIntroDialog(
                    fragment.getString(R.string.shizuku_card_title),
                    fragment.getString(
                        if (running) R.string.shizuku_card_message_running
                        else R.string.shizuku_card_message_not_running
                    )
                )
            }
            R.id.status_card_accessibility -> {
                val enabled = PermissionUtils.isAccessibilitySettingsOn(fragment.requireContext())
                MaterialAlertDialogBuilder(fragment.requireContext()).apply {
                    setTitle(fragment.getString(R.string.accessibility_card_title))
                    setMessage(fragment.getString(R.string.home_accessibility_warn_message))
                    if (enabled) {
                        // 已开启时只提示，不再跳转
                        setPositiveButton(fragment.getString(R.string.done)) { _, _ -> }
                    } else {
                        setPositiveButton(fragment.getString(R.string.go_to_start_accessibility)) { _, _ ->
                            onGoToAccessibilitySettings?.invoke()
                        }
                        setNegativeButton(fragment.getString(R.string.go_to_change_service_type)) { _, _ ->
                            // 首页已删除，改为跳到设置页的「保活方式」条目
                            (fragment as? SettingFragment)?.scrollToServiceType()
                        }
                    }
                    setOnDismissListener { refresh() }
                    create().show()
                }
            }
        }
    }

    private fun showIntroDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(fragment.requireContext()).apply {
            setTitle(title)
            setMessage(message)
            setPositiveButton(fragment.getString(R.string.done)) { _, _ -> }
            setOnDismissListener { refresh() }
            create().show()
        }
    }
}
