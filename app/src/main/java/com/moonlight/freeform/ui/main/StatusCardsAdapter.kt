package com.moonlight.freeform.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moonlight.freeform.R

/**
 * 把三张状态卡片包成 RecyclerView 的头部 item。
 *
 * 为什么不用 addView：PreferenceFragmentCompat 的列表是 RecyclerView，
 * 直接 addView 非 adapter 创建的子 View 会在 layout 时崩溃
 * （'Scrapped or attached views may not be recycled' / IndexOutOfBoundsException）。
 * ConcatAdapter 是官方支持的多 adapter 组合方式，头部会随列表一起滚动，
 * 且不与 PreferenceGroupAdapter 的内部状态冲突。
 */
class StatusCardsAdapter(
    private val fragment: Fragment,
    private val onReady: (ViewGroup) -> Unit
) : RecyclerView.Adapter<StatusCardsAdapter.Holder>() {

    class Holder(itemView: ViewGroup) : RecyclerView.ViewHolder(itemView)

    override fun getItemViewType(position: Int): Int = TYPE_CARDS

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.status_cards, parent, false) as ViewGroup
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // 卡片容器每次绑定时都会回调这里（头部 item 滚出屏幕会被回收、滚回来会重新绑定，
        // 同一个 Holder 的容器还是原来那个）。
        // 是否重建卡片由调用方按容器身份判断，见 SettingFragment：
        // 以前是无条件重建，反复滚动后顶部就堆出一大堆重复卡片。
        onReady(holder.itemView as ViewGroup)
    }

    override fun getItemCount(): Int = 1

    /** 把自身接到 Preference 的 adapter 之前，组成「卡片 + 设置列表」。 */
    fun wrap(preferenceAdapter: RecyclerView.Adapter<*>?): RecyclerView.Adapter<*> {
        if (preferenceAdapter == null) return this
        return ConcatAdapter(this, preferenceAdapter)
    }

    companion object {
        private const val TYPE_CARDS = 1
    }
}
