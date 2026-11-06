package com.moonlight.freeform.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.moonlight.freeform.R

/**
 * Wraps the three status cards into a RecyclerView header item.
 *
 * Why not addView: the PreferenceFragmentCompat list is a RecyclerView, and directly addView-ing a
 * child View not created by the adapter crashes during layout
 * ('Scrapped or attached views may not be recycled' / IndexOutOfBoundsException).
 * ConcatAdapter is the officially supported way to compose multiple adapters; the header scrolls with
 * the list and does not conflict with PreferenceGroupAdapter's internal state.
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
        // The card container calls back here on every bind (the header item is recycled when it scrolls off and rebound when it scrolls back,
        // and the same Holder still holds the original container).
        // Whether to rebuild the cards is decided by the caller based on container identity, see SettingFragment:
        // it used to rebuild unconditionally, so repeated scrolling piled up a heap of duplicate cards at the top.
        onReady(holder.itemView as ViewGroup)
    }

    override fun getItemCount(): Int = 1

    /** Attach itself before the Preference's adapter, forming "cards + settings list". */
    fun wrap(preferenceAdapter: RecyclerView.Adapter<*>?): RecyclerView.Adapter<*> {
        if (preferenceAdapter == null) return this
        return ConcatAdapter(this, preferenceAdapter)
    }

    companion object {
        private const val TYPE_CARDS = 1
    }
}
