package dev.yakovsava.antiwhitelist.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.profileStore: DataStore<Preferences> by preferencesDataStore("profiles")
private val KEY_PROFILES  = stringPreferencesKey("profiles_json")
private val KEY_ACTIVE_ID = stringPreferencesKey("active_profile_id")

class ProfileRepository(private val ctx: Context) {

    val profilesFlow: Flow<List<VpnProfile>> = ctx.profileStore.data.map { p ->
        parseList(p[KEY_PROFILES])
    }

    val activeIdFlow: Flow<String?> = ctx.profileStore.data.map { p -> p[KEY_ACTIVE_ID] }

    suspend fun save(profile: VpnProfile) = ctx.profileStore.edit { p ->
        val list = parseList(p[KEY_PROFILES]).toMutableList()
        val idx  = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        p[KEY_PROFILES] = toJson(list)
    }

    suspend fun delete(id: String) = ctx.profileStore.edit { p ->
        p[KEY_PROFILES] = toJson(parseList(p[KEY_PROFILES]).filter { it.id != id })
        if (p[KEY_ACTIVE_ID] == id) p.remove(KEY_ACTIVE_ID)
    }

    suspend fun setActive(id: String?) = ctx.profileStore.edit { p ->
        if (id == null) p.remove(KEY_ACTIVE_ID) else p[KEY_ACTIVE_ID] = id
    }

    private fun parseList(json: String?): List<VpnProfile> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { VpnProfile.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    private fun toJson(list: List<VpnProfile>): String =
        JSONArray().also { arr -> list.forEach { arr.put(it.toJson()) } }.toString()
}
