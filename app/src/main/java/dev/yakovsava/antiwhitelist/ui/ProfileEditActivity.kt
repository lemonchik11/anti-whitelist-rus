package dev.yakovsava.antiwhitelist.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.yakovsava.antiwhitelist.R
import dev.yakovsava.antiwhitelist.data.LinkType
import dev.yakovsava.antiwhitelist.data.VpnProfile
import dev.yakovsava.antiwhitelist.data.WireGuardConfParser
import dev.yakovsava.antiwhitelist.databinding.ActivityProfileEditBinding

class ProfileEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_JSON  = "profile_json"
        const val EXTRA_IS_NEW        = "is_new"
        const val RESULT_PROFILE_JSON = "result_profile_json"
    }

    private lateinit var b: ActivityProfileEditBinding
    private val vm: ProfilesViewModel by viewModels()
    private lateinit var profile: VpnProfile

    private val confImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val stream = contentResolver.openInputStream(uri) ?: return@registerForActivityResult
            val name   = uri.lastPathSegment?.removeSuffix(".conf") ?: b.etName.text.toString()
            val parsed = WireGuardConfParser.parse(stream, name)
            // Merge parsed .conf into current profile (keep id, linkType, callLink etc.)
            profile = profile.copy(
                name           = parsed.name,
                wgPrivateKey   = parsed.wgPrivateKey,
                wgAddress      = parsed.wgAddress,
                wgDns          = parsed.wgDns,
                wgMtu          = parsed.wgMtu,
                wgServerPubKey = parsed.wgServerPubKey,
                wgPresharedKey = parsed.wgPresharedKey,
                wgAllowedIps   = parsed.wgAllowedIps,
                wgKeepalive    = parsed.wgKeepalive,
                wgRealEndpoint = parsed.wgRealEndpoint,
            )
            populateFields()
            Snackbar.make(b.root, "Конфиг загружен: ${parsed.name}", Snackbar.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Snackbar.make(b.root, "Ошибка: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val json    = intent.getStringExtra(EXTRA_PROFILE_JSON) ?: "{}"
        val isNew   = intent.getBooleanExtra(EXTRA_IS_NEW, true)
        profile     = VpnProfile.fromJson(org.json.JSONObject(json))
        title       = if (isNew) "Новый профиль" else "Редактировать"

        setupSpinner()
        populateFields()
        setupButtons()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_edit_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home     -> { finish(); true }
        R.id.menu_save        -> { trySave(); true }
        R.id.menu_import_conf -> { confImportLauncher.launch("*/*"); true }
        R.id.menu_gen_keys    -> { generateKeys(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupSpinner() {
        b.spinnerLinkType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            arrayOf("ВК Звонки", "Яндекс Телемост")
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun populateFields() {
        b.etName.setText(profile.name)
        b.spinnerLinkType.setSelection(if (profile.linkType == LinkType.VK) 0 else 1)
        b.etCallLink.setText(profile.callLink)
        b.etPrivKey.setText(profile.wgPrivateKey)
        b.etServerPk.setText(profile.wgServerPubKey)
        b.etPsk.setText(profile.wgPresharedKey)
        b.etAddress.setText(profile.wgAddress)
        b.etDns.setText(profile.wgDns)
        b.etMtu.setText(profile.wgMtu.toString())
        b.etAllowedIps.setText(profile.wgAllowedIps)
        b.etKeepalive.setText(profile.wgKeepalive.toString())
        b.etRealEndpoint.setText(profile.wgRealEndpoint)
        b.etGoodTurnHost.setText(profile.goodTurnServerHost)
        b.etGoodTurnPort.setText(profile.goodTurnServerPort.toString())
        b.etTurnOverride.setText(profile.turnOverride)
        b.etConnCount.setText(if (profile.connCount > 0) profile.connCount.toString() else "")
        b.switchWgEnabled.isChecked  = profile.wgEnabled
        b.switchUdp.isChecked        = profile.useUdp
        b.switchNoDtls.isChecked     = profile.noDtls
        b.switchStartOnBoot.isChecked = profile.startOnBoot
    }

    private fun setupButtons() {
        b.btnSave.setOnClickListener { trySave() }
        b.btnCancel.setOnClickListener { finish() }
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private fun trySave() {
        val name = b.etName.text.toString().trim()
        if (name.isEmpty()) { b.etName.error = "Обязательное поле"; return }

        val callLink = b.etCallLink.text.toString().trim()
        if (callLink.isEmpty()) { b.etCallLink.error = "Обязательное поле"; return }

        val updated = profile.copy(
            name           = name,
            linkType       = if (b.spinnerLinkType.selectedItemPosition == 0) LinkType.VK else LinkType.YANDEX,
            callLink       = callLink,
            wgPrivateKey   = b.etPrivKey.text.toString().trim(),
            wgServerPubKey = b.etServerPk.text.toString().trim(),
            wgPresharedKey = b.etPsk.text.toString().trim(),
            wgAddress      = b.etAddress.text.toString().trim().ifEmpty { "10.8.0.2/32" },
            wgDns          = b.etDns.text.toString().trim().ifEmpty { "1.1.1.1" },
            wgMtu          = b.etMtu.text.toString().toIntOrNull() ?: 1280,
            wgAllowedIps   = b.etAllowedIps.text.toString().trim().ifEmpty { "0.0.0.0/0, ::/0" },
            wgKeepalive    = b.etKeepalive.text.toString().toIntOrNull() ?: 25,
            wgRealEndpoint = b.etRealEndpoint.text.toString().trim(),
            goodTurnServerHost = b.etGoodTurnHost.text.toString().trim(),
            goodTurnServerPort = b.etGoodTurnPort.text.toString().toIntOrNull() ?: 56000,
            turnOverride   = b.etTurnOverride.text.toString().trim(),
            connCount      = b.etConnCount.text.toString().toIntOrNull() ?: 0,
            wgEnabled      = b.switchWgEnabled.isChecked,
            useUdp         = b.switchUdp.isChecked,
            noDtls         = b.switchNoDtls.isChecked,
            startOnBoot    = b.switchStartOnBoot.isChecked,
        )

        setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_PROFILE_JSON, updated.toJson().toString()))
        finish()
    }

    private fun generateKeys() {
        val (priv, pub) = vm.generateKeyPair()
        MaterialAlertDialogBuilder(this)
            .setTitle("Новая пара ключей")
            .setMessage("Приватный ключ установлен.\n\nPublic key (добавить на сервер через wg-easy):\n\n$pub")
            .setPositiveButton("Применить") { _, _ ->
                b.etPrivKey.setText(priv)
                Snackbar.make(b.root, "Ключи установлены", Snackbar.LENGTH_SHORT).show()
            }
            .setNeutralButton("Отмена", null)
            .show()
    }
}
