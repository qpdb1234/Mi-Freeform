package com.moonlight.freeform.ui.recent

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.moonlight.freeform.R
import com.moonlight.freeform.ui.freeform.FreeformHelper

class RecentAdapter : RecyclerView.Adapter<RecentAdapter.ViewHolder>() {

    private lateinit var context: Context

    private val count: Int

    init {
        count = FreeformHelper.getFreeformStackSet().size() + FreeformHelper.getMiniFreeformStackSet().size()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        context = parent.context
        return ViewHolder(LayoutInflater.from(context).inflate(R.layout.item_recent, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {

    }

    override fun getItemCount(): Int {
        return count
    }
}