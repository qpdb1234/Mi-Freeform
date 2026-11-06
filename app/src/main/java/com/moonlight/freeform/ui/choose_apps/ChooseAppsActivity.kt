package com.moonlight.freeform.ui.choose_apps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Filter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.moonlight.freeform.R
import com.moonlight.freeform.databinding.ActivityChooseAppsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.collections.ArrayList

/**
 * @author sunshine
 * @date 2021/1/31
 * App picker that shows which apps use freeform
 */
class ChooseAppsActivity : AppCompatActivity() {

    // In-app language switch: wrap base Context per setting (see LocaleHelper)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityChooseAppsBinding
    private lateinit var viewModel: ChooseAppsViewModel

    private lateinit var pm: PackageManager
    private val allAppsList = ArrayList<LauncherActivityInfo>()
    private var appsList: ArrayList<*>? = null

    private lateinit var userManager: UserManager
    private lateinit var launcherApps: LauncherApps

    // When select-all/deselect-all is triggered, the view needs refreshing
    private var needToUpdateView = false

    // Load the view only on the first observation, otherwise it would refresh too frequently
    private var firstObserver = true

    private var type = TYPE_FLOATING

    private var adapter: AppsRecyclerAdapter<*>? = null

    private val filter = AppNameFilter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooseAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)

        viewModel = ViewModelProvider(this)[ChooseAppsViewModel::class.java]

        pm = packageManager
        userManager = getSystemService(Context.USER_SERVICE) as UserManager
        launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

        // The list mode must be specified: 1 is freeform apps, 2 is bubble apps, 3 is compatibility apps
        type = intent.getIntExtra("type", TYPE_FLOATING)
        viewModel.type = type

        if (type == TYPE_FLOATING) {
            supportActionBar!!.title = getString(R.string.label_floating_apps)
            // Fetch the list of apps that use freeform from the database and put them in a table for display in the list
            viewModel.getAllApps().observe(this@ChooseAppsActivity) { list ->
                this@ChooseAppsActivity.appsList = list as ArrayList<*>
                showAppsList(type)
            }
        } else {
            supportActionBar!!.title = getString(R.string.label_notification_apps)
            viewModel.getAllNotificationApps().observe(this) { list ->
                //allAppsList.clear()
                this.appsList = list as ArrayList<*>
                showAppsList(type)
            }
        }

    }

    private fun showAppsList(type: Int) {
        if (firstObserver || needToUpdateView) {
            GlobalScope.launch(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN, null)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)

                if (allAppsList.isEmpty()) {
                    /**
                     * Get apps under all users (including the work profile)
                     */
                    userManager.userProfiles.forEach {
                        allAppsList.addAll(launcherApps.getActivityList(null, it))
                    }

                    allAppsList.sortWith { o1, o2 ->
                        Collator.getInstance().compare(
                            o1!!.applicationInfo.loadLabel(packageManager),
                            o2!!.applicationInfo.loadLabel(packageManager)
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.recyclerApps.layoutManager = LinearLayoutManager(this@ChooseAppsActivity)
                    adapter = AppsRecyclerAdapter(allAppsList, viewModel, appsList!!, type, this@ChooseAppsActivity)
                    binding.recyclerApps.adapter = adapter

                    firstObserver = false
                    needToUpdateView = false
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_choose_apps, menu)

        // The search box needs to be obtained here
        val searchItem = menu.findItem(R.id.app_bar_search)
        val searchView = searchItem?.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                //adapter?.filter?.filter(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                filter.filter(newText)
                return true
            }
        })

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
            }
            R.id.choose_all -> {
                needToUpdateView = true
                viewModel.insertAllApps(allAppsList, userManager)
                //allAppsList.clear()
            }
            R.id.choose_all_cancel -> {
                // Skip the update when the list is empty
                if (appsList != null && appsList!!.isNotEmpty()) {
                    needToUpdateView = true
                    viewModel.deleteAll()
                    //allAppsList.clear()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    inner class AppNameFilter : Filter() {
        override fun performFiltering(constraint: CharSequence): FilterResults {
            val results = FilterResults()
            var newAllAppsList =  ArrayList<LauncherActivityInfo>()

            if (constraint.isBlank()) {
                newAllAppsList = allAppsList
            } else {
                allAppsList.forEach {
                    if (it.label.contains(constraint, ignoreCase = true)) {
                        newAllAppsList.add(it)
                    }
                }
            }

            results.values = newAllAppsList
            results.count = newAllAppsList.size
            return results
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun publishResults(constraint: CharSequence, results: FilterResults) {
            adapter?.updateDate(results.values as ArrayList<LauncherActivityInfo>)
        }

    }

    companion object {
        const val TYPE_FLOATING = 1
        const val TYPE_NOTIFICATION = 2
    }
}