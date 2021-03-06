/*
 * Copyright (C) 2017-2019 Alexey Rochev <equeim@gmail.com>
 *
 * This file is part of Tremotesf.
 *
 * Tremotesf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Tremotesf is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.equeim.tremotesf

import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.AutoCompleteTextView
import android.widget.Checkable
import android.widget.ImageView
import android.widget.Toast

import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.onNavDestinationSelected

import com.google.android.material.textfield.TextInputLayout

import org.equeim.tremotesf.torrentslistfragment.DirectoriesViewAdapter
import org.equeim.tremotesf.torrentslistfragment.ServersViewAdapter
import org.equeim.tremotesf.torrentslistfragment.StatusFilterViewAdapter
import org.equeim.tremotesf.torrentslistfragment.TorrentsAdapter
import org.equeim.tremotesf.torrentslistfragment.TrackersViewAdapter
import org.equeim.tremotesf.utils.ArrayDropdownAdapter
import org.equeim.tremotesf.utils.Logger
import org.equeim.tremotesf.utils.Utils
import org.equeim.tremotesf.utils.findChildRecursively
import org.equeim.tremotesf.utils.hideKeyboard
import org.equeim.tremotesf.utils.setChildrenEnabled

import kotlinx.android.synthetic.main.navigation_activity.*
import kotlinx.android.synthetic.main.side_panel_header.view.*


class NavigationActivity : AppCompatActivity(R.layout.navigation_activity), Selector.ActionModeActivity, Logger {
    companion object {
        private val createdActivities = mutableListOf<NavigationActivity>()
        var activeActivity: NavigationActivity? = null
            private set

        fun recreateAllActivities() {
            for (activity in createdActivities) {
                activity.recreate()
            }
        }

        fun finishAllActivities() {
            for (activity in createdActivities) {
                activity.finish()
            }
            createdActivities.clear()
        }
    }

    override var actionMode: ActionMode? = null

    lateinit var navController: NavController
        private set
    lateinit var appBarConfiguration: AppBarConfiguration
        private set

    lateinit var drawerNavigationIcon: DrawerArrowDrawable
        private set
    lateinit var upNavigationIcon: DrawerArrowDrawable
        private set

    lateinit var sortView: AutoCompleteTextView
        private set
    lateinit var sortViewLayout: TextInputLayout
        private set
    lateinit var statusView: AutoCompleteTextView
        private set
    lateinit var trackersView: AutoCompleteTextView
        private set
    lateinit var directoriesView: AutoCompleteTextView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        info("NavigationActivity.onCreate(), intent=$intent")

        AppCompatDelegate.setDefaultNightMode(Settings.nightMode)
        setTheme(Settings.theme)

        super.onCreate(savedInstanceState)

        createdActivities.add(this)
        if (Settings.showPersistentNotification) {
            ContextCompat.startForegroundService(this, Intent(this, ForegroundService::class.java))
        }
        Rpc.error.observe(this) { error ->
            if (error == RpcError.ConnectionError) {
                Toast.makeText(this, Rpc.errorMessage, Toast.LENGTH_LONG).show()
            }
        }
        Rpc.connectOnce()

        drawerNavigationIcon = DrawerArrowDrawable(this)
        upNavigationIcon = DrawerArrowDrawable(this).apply { progress = 1.0f }

        navController = findNavController(R.id.nav_host)
        appBarConfiguration = AppBarConfiguration(navController.graph, drawer_layout)

        setupDrawer()

        navController.addOnDestinationChangedListener { _, destination, _ ->
            hideKeyboard()
            if (isTopLevelDestination(destination.id)) {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, GravityCompat.START)
            } else {
                drawer_layout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START)
            }
        }

        handleAddTorrentIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (activeActivity == null) {
            Rpc.cancelUpdateWorker()
            Rpc.nativeInstance.setUpdateDisabled(false)
        }
        activeActivity = this
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            if (activeActivity === this) {
                activeActivity = null

                if (!Settings.showPersistentNotification) {
                    Servers.save()
                    Rpc.nativeInstance.setUpdateDisabled(true)
                    Rpc.enqueueUpdateWorker()
                }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        info("NavigationActivity.onDestroy()")
        createdActivities.remove(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        info("NavigationActivity.onNewIntent(), intent=$intent")
        super.onNewIntent(intent)
        handleAddTorrentIntent(intent)
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onSupportActionModeStarted(mode: ActionMode) {
        super.onSupportActionModeStarted(mode)
        actionMode = mode
    }

    override fun onSupportActionModeFinished(mode: ActionMode) {
        super.onSupportActionModeFinished(mode)
        actionMode = null
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if ((supportFragmentManager.primaryNavigationFragment?.childFragmentManager?.primaryNavigationFragment as? NavigationFragment)?.showOverflowMenu() == true) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun setupDrawer() {
        drawer_layout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START)

        side_panel.setNavigationItemSelectedListener { menuItem ->
            return@setNavigationItemSelectedListener when (menuItem.itemId) {
                R.id.quit -> {
                    Utils.shutdownApp(this)
                    true
                }
                else -> menuItem.onNavDestinationSelected(navController)
            }
        }

        val sidePanelHeader = side_panel.getHeaderView(0)

        val serversView = sidePanelHeader.servers_view
        val serversViewAdapter = ServersViewAdapter(serversView)
        serversView.setAdapter(serversViewAdapter)
        serversView.setOnItemClickListener { _, _, position, _ ->
            serversViewAdapter.servers[position].let {
                if (it != Servers.currentServer.value) {
                    Servers.currentServer.value = it
                }
            }
        }
        Servers.servers.observe(this) { servers ->
            serversView.isEnabled = servers.isNotEmpty()
            serversViewAdapter.update()
        }

        sidePanelHeader.connection_settings_button.setOnClickListener {
            try {
                navController.navigate(R.id.action_torrentsListFragment_to_connectionSettingsFragment)
            } catch (ignore: IllegalArgumentException) {}
        }

        val listSettingsLayout = sidePanelHeader.list_settings_layout
        listSettingsLayout.setChildrenEnabled(Rpc.isConnected)
        Rpc.status.observe(this) { status ->
            when (status) {
                RpcStatus.Disconnected,
                RpcStatus.Connected -> {
                    listSettingsLayout.setChildrenEnabled(Rpc.isConnected)
                }
            }
        }

        sortView = sidePanelHeader.sort_view
        sortView.setAdapter(ArrayDropdownAdapter(resources.getStringArray(R.array.sort_spinner_items)))
        sortView.setText(sortView.adapter.getItem(Settings.torrentsSortMode.ordinal) as String)
        sortViewLayout = sidePanelHeader.sort_view_layout
        val startIconDrawable = sortViewLayout.startIconDrawable
        sortViewLayout.findChildRecursively { it is ImageView && it.drawable === startIconDrawable }?.let {
            (it as Checkable).isChecked = Settings.torrentsSortOrder == TorrentsAdapter.SortOrder.Descending
        }

        statusView = sidePanelHeader.status_view
        statusView.setAdapter(StatusFilterViewAdapter(this, statusView))

        trackersView = sidePanelHeader.trackers_view
        trackersView.setAdapter(TrackersViewAdapter(this, trackersView))

        directoriesView = sidePanelHeader.directories_view
        directoriesView.setAdapter(DirectoriesViewAdapter(this, directoriesView))
    }

    private fun handleAddTorrentIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val action = when (intent.scheme) {
                ContentResolver.SCHEME_FILE,
                ContentResolver.SCHEME_CONTENT -> R.id.action_global_addTorrentFileFragment
                AddTorrentLinkFragment.SCHEME_MAGNET -> R.id.action_global_addTorrentLinkFragment
                else -> return
            }
            val arguments = bundleOf(AddTorrentFragment.URI to intent.data!!.toString())
            if (isTaskRoot) {
                navController.navigate(action, arguments)
            } else {
                navController.navigate(action, arguments,
                                       NavOptions.Builder().setPopUpTo(R.id.nav_main, true).build())
            }
        }
    }

    // destinationId must not refer to NavGraph
    fun isTopLevelDestination(@IdRes destinationId: Int): Boolean {
        return appBarConfiguration.topLevelDestinations.contains(destinationId)
    }
}
