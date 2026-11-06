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
 * The three status cards at the top of the settings page: Xposed / Shizuku-Sui / Accessibility.
 *
 * Migrated from the original HomeFragment. Each card occupies one row and aligns with the
 * settings items; the accessibility card is now always shown (previously only when disabled).
 */
class StatusCards(
    private val fragment: Fragment,
    // Exposed read-only: SettingFragment uses it to detect whether the bound container is
    // still the original one, preventing the cards from being addView'd repeatedly.
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
        // Safeguard: on re-entry remove old cards first to avoid stacking duplicates in one container.
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

    /** Navigation callback for the accessibility card, injected by SettingFragment (reuses its registered launcher). */
    var onGoToAccessibilitySettings: (() -> Unit)? = null

    private fun addCard(): MaterialCardView {
        val binding = StatusCardItemBinding.inflate(
            LayoutInflater.from(fragment.requireContext()), container, false
        )
        container.addView(binding.root)
        return binding.root
    }

    /** Refresh the status display of the three cards. */
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
     * Accessibility card: always shown, only changes color/text with status.
     * In the original home page it was hidden (visibility=GONE) when enabled; after migration it stays.
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
     * Tapping a card only shows an intro dialog with description (matching the original permission page),
     * not a fleeting Snackbar. After the dialog dismisses we refresh status so the card color updates
     * immediately once Shizuku is activated.
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
                // Tapping also reconnects the binder, then the dialog shows intro + current status
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
                        // When already enabled, just show a hint; no navigation
                        setPositiveButton(fragment.getString(R.string.done)) { _, _ -> }
                    } else {
                        setPositiveButton(fragment.getString(R.string.go_to_start_accessibility)) { _, _ ->
                            onGoToAccessibilitySettings?.invoke()
                        }
                        setNegativeButton(fragment.getString(R.string.go_to_change_service_type)) { _, _ ->
                            // The home page was removed; now navigate to the "Keep-alive method" item in settings
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
