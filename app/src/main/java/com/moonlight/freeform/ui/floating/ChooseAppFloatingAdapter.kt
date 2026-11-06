package com.moonlight.freeform.ui.floating

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.moonlight.freeform.R
import com.moonlight.freeform.ui.floating_apps_sort.FloatingAppsSortActivity
import com.moonlight.freeform.ui.choose_apps.ChooseAppsActivity
import com.moonlight.freeform.room.FreeFormAppsEntity
import com.moonlight.freeform.ui.freeform.*
import java.lang.reflect.Method

/**
 * @author sunshine
 * @date 2021/3/7
 * Sidebar adapter for app selection
 *
 * resolveInfoList / userHandleMap / itemDataMap are all prepared on a background thread by the caller
 * (ChooseAppFloatingView) and passed in: these are respectively the full PackageManager queryIntentActivities,
 * a binder call, and per-row app info + icon decode + app name. Previously the first two ran in the constructor
 * and onCreateViewHolder, and the last in onBindViewHolder, all squeezed between "opening the panel and drawing it"
 * (measured first-frame measure+layout was 481ms).
 */
class ChooseAppFloatingAdapter(
    private val context: Context,
    private val apps: List<FreeFormAppsEntity>?,
    private val resolveInfoList: List<ResolveInfo>,
    private val userHandleMap: Map<Int, UserHandle>,
    private val itemDataMap: Map<String, FloatingItemData>,
    private val callback: ChooseAppFloatingView.ClickListener,
    private val allAppsCallback: ChooseAppFloatingView.ClickListener
) : RecyclerView.Adapter<ChooseAppFloatingAdapter.ViewHolder>() {

    private val miniFreeformSize = FreeformHelper.getMiniFreeformStackSet().size()
    private val launcherApps: LauncherApps =
        context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val runningText = context.getString(R.string.running)

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = itemView.findViewById(R.id.imageView_icon)
        val click: View = itemView.findViewById(R.id.view_click)
        val appName: TextView = itemView.findViewById(R.id.textView_appName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Having every ViewHolder call userManager.userProfiles once via binder causes frame drops while scrolling;
        // userHandleMap is already a constructor parameter, so nothing needs to be done here.
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_floating,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int {
        // Updated 2021.07.25: added display of minimized apps
        return apps?.size?.plus(2)!! + miniFreeformSize
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Optimized 2021.07.15: improved the app-picker UI style, added app name display, moved all apps to the top, added edit apps
        when (position) {
            0 -> {
                holder.icon.setImageResource(R.drawable.ic_all)
                holder.appName.text = context.getString(R.string.all_apps)
                holder.click.setOnClickListener {
                    allAppsCallback.onClick()
                }
            }

            in 1 .. miniFreeformSize -> {
                val stackConfig = FreeformHelper.getMiniFreeformStackSet().get(position - 1).config
                val cached = itemDataMap[floatingItemKey(stackConfig.packageName, stackConfig.userId)]
                if (cached != null) {
                    holder.icon.setImageDrawable(cached.icon)
                    holder.appName.text = "${cached.label}\n$runningText"
                    holder.click.setOnClickListener {
                        FreeformHelper.getMiniFreeformStackSet().get(position - 1).fromBackstage()
                        callback.onClick()
                    }
                } else {
                    try {
                        val appInfo = applicationInfo(stackConfig.packageName, stackConfig.userId)
                        loadIconWithGlide(holder, appInfo)
                        holder.appName.text = "${getLabel(appInfo, stackConfig.userId)}\n$runningText"
                        holder.click.setOnClickListener {
                            FreeformHelper.getMiniFreeformStackSet().get(position - 1).fromBackstage()
                            callback.onClick()
                        }
                    } catch (e: PackageManager.NameNotFoundException) {

                    }
                }
            }

            apps!!.size + 1 + miniFreeformSize -> {
                holder.icon.setImageResource(R.drawable.ic_add)
                holder.appName.text = context.getString(R.string.edit_apps)
                holder.click.setOnClickListener {
                    context.startActivity(Intent(context, ChooseAppsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    callback.onClick()
                }
            }
            else -> {
                val entity = apps[position - 1 - miniFreeformSize]
                val packageName = entity.packageName
                val cached = itemDataMap[floatingItemKey(packageName, entity.userId)]
                if (cached != null) {
                    holder.icon.setImageDrawable(cached.icon)
                    holder.appName.text = cached.label
                } else {
                    // Not prewarmed (e.g. the panel was opened right after installing an app); fall back to the original on-demand lookup
                    try {
                        val appInfo = applicationInfo(packageName, entity.userId)
                        loadIconWithGlide(holder, appInfo)
                        holder.appName.text = getLabel(appInfo, entity.userId)
                    } catch (e: PackageManager.NameNotFoundException) {}
                }
                holder.click.setOnClickListener {
                    // When the app already has a freeform, destroy the old instance first to avoid two windows or an orphan floating ball
                    FreeformHelper.destroyExistingFreeform(packageName, entity.userId)

                    FreeformView(
                        FreeformConfig(
                            packageName = packageName,
                            activityName = cached?.activityName ?: findLaunchActivity(packageName),
                            userId = entity.userId
                        ),
                        context
                    )
                    callback.onClick()
                }
                // Long-press to enter the sorting screen
                holder.click.setOnLongClickListener {
                    context.startActivity(Intent(context, FloatingAppsSortActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    callback.onClick()
                    true
                }
            }
        }

    }

    /** Only runs on cache miss: request app info from LauncherApps once (a binder call). */
    @Throws(PackageManager.NameNotFoundException::class)
    private fun applicationInfo(packageName: String, userId: Int): ApplicationInfo {
        val userHandle = userHandleMap[userId] ?: userHandleMap[0]!!
        return launcherApps.getApplicationInfo(packageName, 0, userHandle)
    }

    private fun loadIconWithGlide(holder: ViewHolder, appInfo: ApplicationInfo) {
        Glide.with(context)
            .load(appInfo.loadIcon(context.packageManager))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(holder.icon)
    }

    /** Same as before: when a package has multiple launchable Activities, take the last one. */
    private fun findLaunchActivity(packageName: String): String {
        var activityName = ""
        resolveInfoList.forEach {
            if (it.activityInfo.applicationInfo.packageName == packageName) {
                activityName = it.activityInfo.name
            }
        }
        return activityName
    }

    private fun getLabel(applicationInfo: ApplicationInfo, userId: Int): CharSequence {
        return if (userId == 0) {
            context.packageManager.getApplicationLabel(applicationInfo)
        } else {
            "${context.packageManager.getApplicationLabel(applicationInfo)}-${context.getString(R.string.fenshen)}${userId}"
        }
    }

    private fun getActivityOptions(): ActivityOptions? {
        val options = ActivityOptions.makeBasic()
        val freeFormStackId = 5
        try {
            val method: Method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            method.invoke(options, freeFormStackId)
        } catch (e: Exception) {

        }
        return options
    }
}

/**
 * What one row of the panel displays: icon, app name, and the launch Activity.
 * All three can be computed ahead of time on a background thread (binder + icon decode), so binding only does a
 * table lookup and the first frame no longer blocks the main thread.
 */
class FloatingItemData(
    val icon: Drawable?,
    val label: CharSequence,
    val activityName: String
)

/** Cache key: the same package under different users (clones) occupies two rows in the panel. */
fun floatingItemKey(packageName: String, userId: Int): String = "$packageName#$userId"