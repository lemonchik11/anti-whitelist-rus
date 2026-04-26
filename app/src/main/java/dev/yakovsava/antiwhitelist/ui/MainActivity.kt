package dev.yakovsava.antiwhitelist.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import dev.yakovsava.antiwhitelist.R
import dev.yakovsava.antiwhitelist.data.VpnProfile
import dev.yakovsava.antiwhitelist.data.WireGuardConfParser
import dev.yakovsava.antiwhitelist.databinding.ActivityMainBinding
import dev.yakovsava.antiwhitelist.service.GoodTurnVpnService.VpnState
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: ProfilesViewModel by viewModels()
    private lateinit var adapter: ProfilesAdapter

    // Which profile is pending VPN permission
    private var pendingProfileId: String? = null

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pendingProfileId?.let { vm.connect(it) }
        } else {
            Snackbar.make(b.root, "VPN permission denied", Snackbar.LENGTH_LONG).show()
        }
        pendingProfileId = null
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Open file picker for .conf import
    private val confImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val stream = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            val fileName = uri.lastPathSegment?.removeSuffix(".conf") ?: "Профиль"
            val profile = WireGuardConfParser.parse(stream, fileName)
            openProfileEdit(profile, isNew = true)
        } catch (e: Exception) {
            Snackbar.make(b.root, "Ошибка импорта: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val json = r.data?.getStringExtra(ProfileEditActivity.RESULT_PROFILE_JSON) ?: return@registerForActivityResult
            val profile = VpnProfile.fromJson(org.json.JSONObject(json))
            vm.saveProfile(profile)
            Snackbar.make(b.root, "Профиль сохранён: ${profile.name}", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        requestNotifPermission()
        setupRecycler()
        setupFab()
        observeVm()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_import_conf -> { confImportLauncher.launch("*/*"); true }
        R.id.menu_clear_log   -> { vm.clearLogs(); true }
        R.id.menu_new_profile -> { openProfileEdit(VpnProfile(), isNew = true); true }
        else                  -> super.onOptionsItemSelected(item)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = ProfilesAdapter(
            onConnect    = { p -> requestConnect(p.id) },
            onDisconnect = { _ -> vm.disconnect() },
            onEdit       = { p -> openProfileEdit(p, isNew = false) },
            onDelete     = { p -> confirmDelete(p) },
        )
        b.rvProfiles.adapter = adapter
    }

    private fun setupFab() {
        b.fab.setOnClickListener { openProfileEdit(VpnProfile(), isNew = true) }
    }

    private fun observeVm() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Profiles list
                launch {
                    vm.profiles.collect { profiles ->
                        val active = vm.activeProfileId
                        adapter.submitList(profiles.map {
                            ProfilesAdapter.ProfileItem(it, it.id == active && vm.isRunning)
                        })
                        b.tvEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                // VPN state
                launch {
                    vm.vpnState.collect { state ->
                        updateStatusBar(state)
                        // Refresh adapter to update active indicator
                        val profiles = vm.profiles.value
                        val active = vm.activeProfileId
                        adapter.submitList(profiles.map {
                            ProfilesAdapter.ProfileItem(it, it.id == active && vm.isRunning)
                        })
                    }
                }
                // Log
                launch {
                    vm.logs.collect { lines ->
                        b.tvLog.text = lines.takeLast(200).joinToString("\n")
                        b.scrollLog.post { b.scrollLog.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateStatusBar(state: VpnState) {
        val (text, colorRes) = when (state) {
            is VpnState.Idle     -> "Отключено"       to R.color.status_idle
            is VpnState.Starting -> "Подключение..."   to R.color.status_idle
            is VpnState.Ready    -> "✓ ${state.profileName}" to R.color.status_ok
            is VpnState.Error    -> "⚠ ${state.msg}"  to R.color.status_error
            is VpnState.Stopping -> "Отключение..."    to R.color.status_idle
        }
        b.tvStatus.text = text
        b.tvStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        b.progressBar.visibility = if (state is VpnState.Starting) View.VISIBLE else View.GONE
    }

    private fun requestConnect(profileId: String) {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            pendingProfileId = profileId
            vpnLauncher.launch(vpnIntent)
        } else {
            vm.connect(profileId)
        }
    }

    private fun openProfileEdit(profile: VpnProfile, isNew: Boolean) {
        val intent = Intent(this, ProfileEditActivity::class.java).apply {
            putExtra(ProfileEditActivity.EXTRA_PROFILE_JSON, profile.toJson().toString())
            putExtra(ProfileEditActivity.EXTRA_IS_NEW, isNew)
        }
        editLauncher.launch(intent)
    }

    private fun confirmDelete(p: VpnProfile) {
        AlertDialog.Builder(this)
            .setTitle("Удалить профиль?")
            .setMessage("«${p.name}» будет удалён безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                if (vm.activeProfileId == p.id && vm.isRunning) vm.disconnect()
                vm.deleteProfile(p.id)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
