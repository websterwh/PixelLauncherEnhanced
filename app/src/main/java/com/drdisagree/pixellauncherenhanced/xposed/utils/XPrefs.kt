package com.drdisagree.pixellauncherenhanced.xposed.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.crossbowffs.remotepreferences.RemotePreferences
import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.data.common.Constants.PREF_UPDATE_EXCLUSIONS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.SHARED_PREFERENCES
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log

object XPrefs {

    @SuppressLint("StaticFieldLeak")
    lateinit var Xprefs: ExtendedRemotePreferences
    private val listener = OnSharedPreferenceChangeListener { _: SharedPreferences?, key: String? ->
        loadEverything(key)
    }

    val XprefsIsInitialized: Boolean
        get() = ::Xprefs.isInitialized

    fun init(context: Context) {
        Xprefs = ExtendedRemotePreferences(
            context,
            BuildConfig.APPLICATION_ID,
            SHARED_PREFERENCES,
            true
        )

        // v20's no-op-replay filter (see lastKnownValues below) only
        // compared against values seen so far THIS PROCESS - but every
        // restart-worthy "change" kills and respawns the launcher process,
        // which wipes that cache right along with it. So the very next
        // settings-page-visit burst always looked brand new again, and the
        // cache could never actually break the loop (confirmed via the v20
        // device log, William, Aug 2026: 4 restarts, one per screen
        // visited, despite 23 in-process replays correctly getting
        // filtered). Seeding the cache from the CURRENT on-disk state right
        // here, before the listener is even registered, means a burst
        // replaying values that were already true before this process
        // started gets recognized as a no-op on the very first burst too -
        // not just the second time within one process - which is what
        // actually breaks the restart-every-visit loop.
        lastKnownValues.putAll(runCatching { Xprefs.all }.getOrDefault(emptyMap()))

        (Xprefs as RemotePreferences).registerOnSharedPreferenceChangeListener(listener)
    }

    // The only reliable way to tell a real edit from a replayed no-op is to
    // compare against the last value actually seen for that key and skip
    // work entirely when it hasn't changed. See the seeding comment in
    // init() above for why this alone wasn't enough on its own.
    private val lastKnownValues = mutableMapOf<String, Any?>()

    private fun loadEverything(vararg key: String?) {
        if (key.isEmpty() ||
            key[0].isNullOrEmpty() ||
            PREF_UPDATE_EXCLUSIONS.any { exclusion -> key[0]?.equals(exclusion) == true }
        ) return

        val changedKey = key[0]!!
        val newValue = runCatching { Xprefs.all[changedKey] }.getOrNull()
        val alreadySeen = lastKnownValues.containsKey(changedKey)
        val previousValue = lastKnownValues[changedKey]
        lastKnownValues[changedKey] = newValue

        if (alreadySeen && newValue == previousValue) {
            return
        }

        HookEntry.runningMods.forEach { thisMod ->
            try {
                thisMod.updatePrefs(*key.filterNotNull().toTypedArray())
            } catch (throwable: Throwable) {
                log(this@XPrefs, "${thisMod.javaClass.simpleName} -> " + throwable)
            }
        }
    }
}
