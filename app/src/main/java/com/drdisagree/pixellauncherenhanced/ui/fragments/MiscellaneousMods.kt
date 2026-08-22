package com.drdisagree.pixellauncherenhanced.ui.fragments

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.data.common.Constants.BACKUP_RESTORE_SETTINGS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_GESTURE_PILL
import com.drdisagree.pixellauncherenhanced.data.common.Constants.HIDE_NAVIGATION_SPACE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.RESTART_LAUNCHER
import com.drdisagree.pixellauncherenhanced.data.config.RPrefs
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat
import com.drdisagree.pixellauncherenhanced.ui.preferences.BackupRestorePreference
import com.drdisagree.pixellauncherenhanced.ui.preferences.SwitchPreference
import com.drdisagree.pixellauncherenhanced.utils.LauncherUtils.restartLauncher
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MiscellaneousMods : ControlledPreferenceFragmentCompat() {

    // Backup/restore (William, Aug 2026): dumps/restores the whole
    // RPrefs-backed SharedPreferences file as a JSON document the user picks
    // a location for via the system file picker (SAF), rather than a fixed
    // internal path - so it survives an uninstall and can be moved between
    // devices. Each entry is tagged with its original type ("boolean",
    // "int", "long", "float", "string", "stringSet") rather than relying on
    // org.json's implicit numeric typing, since SharedPreferences enforces
    // exact type matches on read (e.g. a value saved as Int throws if later
    // read via getFloat) and org.json alone can't reliably tell Int/Long/
    // Float apart on the way back in.
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { exportSettingsTo(it) } }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importSettingsFrom(it) } }

    override val title: String
        get() = getString(R.string.fragment_miscellaneous_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.miscellaneous_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<SwitchPreference>(HIDE_GESTURE_PILL)?.onPreferenceChangeListener =
            Preference.OnPreferenceChangeListener { preference, newValue ->
                if (newValue == false && RPrefs.getBoolean(HIDE_NAVIGATION_SPACE)) {
                    RPrefs.putBoolean(HIDE_NAVIGATION_SPACE, false)
                    findPreference<SwitchPreference>(HIDE_NAVIGATION_SPACE)?.isChecked = false
                }
                true
            }

        findPreference<Preference>(RESTART_LAUNCHER)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener {
                context?.restartLauncher()
                true
            }

        findPreference<BackupRestorePreference>(BACKUP_RESTORE_SETTINGS)?.apply {
            setOnBackupClick {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                exportLauncher.launch("PLEnhanced_backup_$timestamp.json")
            }
            setOnRestoreClick {
                importLauncher.launch(arrayOf("application/json"))
            }
        }
    }

    private fun buildBackupJson(): JSONObject {
        val root = JSONObject()

        RPrefs.getAll().forEach { (key, value) ->
            val entry = JSONObject()

            when (value) {
                is Boolean -> {
                    entry.put("type", "boolean")
                    entry.put("value", value)
                }

                is Int -> {
                    entry.put("type", "int")
                    entry.put("value", value)
                }

                is Long -> {
                    entry.put("type", "long")
                    entry.put("value", value)
                }

                is Float -> {
                    entry.put("type", "float")
                    entry.put("value", value.toDouble())
                }

                is String -> {
                    entry.put("type", "string")
                    entry.put("value", value)
                }

                is Set<*> -> {
                    entry.put("type", "stringSet")
                    entry.put("value", JSONArray(value.map { it.toString() }))
                }

                else -> return@forEach
            }

            root.put(key, entry)
        }

        return root
    }

    private fun applyBackupJson(root: JSONObject) {
        val keys = root.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val entry = root.optJSONObject(key) ?: continue

            when (entry.optString("type")) {
                "boolean" -> RPrefs.putBoolean(key, entry.optBoolean("value"))
                "int" -> RPrefs.putInt(key, entry.optInt("value"))
                "long" -> RPrefs.putLong(key, entry.optLong("value"))
                "float" -> RPrefs.putFloat(key, entry.optDouble("value").toFloat())
                "string" -> RPrefs.putString(key, entry.optString("value"))
                "stringSet" -> {
                    val array = entry.optJSONArray("value") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        set.add(array.getString(i))
                    }
                    RPrefs.putStringSet(key, set)
                }
            }
        }
    }

    private fun exportSettingsTo(uri: Uri) {
        val success = runCatching {
            context?.contentResolver?.openOutputStream(uri)?.use { out ->
                out.write(buildBackupJson().toString(2).toByteArray())
            }
        }.isSuccess

        Toast.makeText(
            context,
            getString(if (success) R.string.backup_settings_success else R.string.backup_settings_failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun importSettingsFrom(uri: Uri) {
        val success = runCatching {
            val text = context?.contentResolver?.openInputStream(uri)
                ?.use { it.bufferedReader().readText() }
                ?: return@runCatching false
            applyBackupJson(JSONObject(text))
            true
        }.getOrDefault(false)

        Toast.makeText(
            context,
            getString(if (success) R.string.restore_settings_success else R.string.restore_settings_failed),
            Toast.LENGTH_SHORT
        ).show()

        if (success) {
            context?.restartLauncher()
        }
    }
}