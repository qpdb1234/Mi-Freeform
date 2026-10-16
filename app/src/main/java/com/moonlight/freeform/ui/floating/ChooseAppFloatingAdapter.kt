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
 * 选择应用侧边栏适配器
 *
 * resolveInfoList / userHandleMap / itemDataMap 都由调用方（ChooseAppFloatingView）在后台线程取好后传进来：
 * 这几样分别是 PackageManager 全量 queryIntentActivities、一次 binder 调用，以及每一行的
 * 应用信息 + 图标解码 + 应用名。以前前两个放在构造器与 onCreateViewHolder 里，最后一个
 * 放在 onBindViewHolder 里，全都压在「点开面板到面板画出来」之间（实测首帧 measure+layout 481ms）。
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
        // 每个 ViewHolder 都去 userManager.userProfiles 走一次 binder，滚动时会掉帧；
        // userHandleMap 已经在构造参数里了，这里不需要再做任何事。
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(
                R.layout.item_floating,
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int {
        //2021.07.25更新，新增展示最小化的应用
        return apps?.size?.plus(2)!! + miniFreeformSize
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        //2021.07.15优化了选择应用界面的样式，增加了应用名称显示，所有应用移动到了最上面，新增编辑应用
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
                    // 没预热到（例如刚装好应用就点开面板），退回原来的现查现用
                    try {
                        val appInfo = applicationInfo(packageName, entity.userId)
                        loadIconWithGlide(holder, appInfo)
                        holder.appName.text = getLabel(appInfo, entity.userId)
                    } catch (e: PackageManager.NameNotFoundException) {}
                }
                holder.click.setOnClickListener {
                    // 同一应用已有小窗时先清掉旧实例，避免出现两个小窗或孤儿悬浮球
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
                //长按进入排序界面
                holder.click.setOnLongClickListener {
                    context.startActivity(Intent(context, FloatingAppsSortActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    callback.onClick()
                    true
                }
            }
        }

    }

    /** 缓存没命中时才走：向 LauncherApps 要一次应用信息（binder 调用）。 */
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

    /** 与原来一致：同一个包有多个可启动 Activity 时取最后一个。 */
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
 * 面板一行要显示的内容：图标、应用名、启动用的 Activity。
 * 这三样都能先在后台线程算好（binder + 图标解码），绑定时只查表，首帧不再卡主线程。
 */
class FloatingItemData(
    val icon: Drawable?,
    val label: CharSequence,
    val activityName: String
)

/** 缓存键：同一包名在不同用户（分身）下是面板里的两行。 */
fun floatingItemKey(packageName: String, userId: Int): String = "$packageName#$userId"