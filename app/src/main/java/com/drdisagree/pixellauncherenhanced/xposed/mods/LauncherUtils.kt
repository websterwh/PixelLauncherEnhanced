package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.xposed.HookEntry.Companion.enqueueProxyCommand
import com.drdisagree.pixellauncherenhanced.xposed.HookRes.Companion.modRes
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callStaticMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getAnyField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hasMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector.resetCounter
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class LauncherUtils(context: Context) : ModPack(context) {

    override fun updatePrefs(vararg key: String) {}

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        ThemesClass = findClass("com.android.launcher3.util.Themes")
        GraphicsUtilsClass = findClass("com.android.launcher3.icons.GraphicsUtils")
        InvariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")
        BaseIconCacheClass = findClass("com.android.launcher3.icons.cache.BaseIconCache")
        QuickstepLauncherClass = findClass("com.android.launcher3.uioverrides.QuickstepLauncher")
        LauncherAppStateClass = findClass("com.android.launcher3.LauncherAppState")
        LauncherAppStateCompanionClass = findClass(
            $$"com.android.launcher3.LauncherAppState$Companion",
            suppressError = true
        )

        InvariantDeviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                invariantDeviceProfileInstance = param.thisObject
            }

        BaseIconCacheClass
            .hookConstructor()
            .runAfter { param ->
                mIconDb = param.thisObject.getAnyField("mIconDb", "iconDb")
                mCache = param.thisObject.getAnyField("mCache", "cache")
            }

        LauncherAppStateClass
            .hookConstructor()
            .runAfter { param ->
                mModel = param.thisObject.getAnyField("mModel", "model")
                if (invariantDeviceProfileInstance == null) {
                    invariantDeviceProfileInstance = param.thisObject.getAnyField(
                        "mInvariantDeviceProfile",
                        "invariantDeviceProfile"
                    )
                }
            }

        if (LauncherAppStateCompanionClass != null) {
            QuickstepLauncherClass
                .hookMethod("onCreate")
                .runAfter { param ->
                    if (invariantDeviceProfileInstance == null) {
                        invariantDeviceProfileInstance =
                            LauncherAppStateCompanionClass.callStaticMethod(
                                "getIDP",
                                param.thisObject
                            )
                    }
                }
        }
    }

    companion object {

        private var ThemesClass: Class<*>? = null
        private var GraphicsUtilsClass: Class<*>? = null
        private var InvariantDeviceProfileClass: Class<*>? = null
        private var BaseIconCacheClass: Class<*>? = null
        private var QuickstepLauncherClass: Class<*>? = null
        private var LauncherAppStateClass: Class<*>? = null
        private var LauncherAppStateCompanionClass: Class<*>? = null

        private var invariantDeviceProfileInstance: Any? = null
        private var mIconDb: Any? = null
        private var mCache: Any? = null
        private var mModel: Any? = null

        fun getAttrColor(context: Context, resID: Int): Int {
            return runCatching {
                ThemesClass.callStaticMethod(
                    "getAttrColor",
                    context,
                    resID
                )
            }.getOrElse {
                runCatching {
                    ThemesClass.callStaticMethod(
                        "getAttrColor",
                        resID,
                        context
                    )
                }.getOrElse {
                    runCatching {
                        GraphicsUtilsClass.callStaticMethod(
                            "getAttrColor",
                            context,
                            resID
                        )
                    }.getOrElse {
                        GraphicsUtilsClass.callStaticMethod(
                            "getAttrColor",
                            resID,
                            context
                        )
                    }
                }
            } as Int
        }

        // Root cause of the "restarts twice" bug (confirmed via the v17
        // RestartDump capture on William's device, Aug 2026): when PLE
        // reconnects after being reactivated (grant root -> Vector ->
        // reopen), several unrelated preference keys replay their
        // onChange in a burst - TopShadow, DarkStatusbar (x2),
        // HideStatusbar (x2) all fired within a ~600ms window in that
        // capture. The old guard only blocked a call within 500ms of the
        // LAST FIRE, not the last call, so two calls in that burst landed
        // >500ms apart and each independently toasted + killed the
        // launcher - a real double restart, not a logging artifact.
        //
        // Switched from a leading-edge "fire immediately, then ignore
        // calls for 500ms" guard to a trailing-edge one: every call
        // (re)schedules the actual restart for RESTART_DEBOUNCE_MS after
        // the LAST call, cancelling whatever was previously scheduled. A
        // burst of any size, spread over any gap shorter than the debounce
        // window, now collapses into exactly one restart.
        private var restartJob: kotlinx.coroutines.Job? = null
        private const val RESTART_DEBOUNCE_MS = 700L

        fun restartLauncher(context: Context) {
            restartJob?.cancel()
            restartJob = CoroutineScope(Dispatchers.IO).launch {
                delay(RESTART_DEBOUNCE_MS)

                resetCounter(context.packageName)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        modRes.getString(R.string.restarting_launcher),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                delay(1000)
                enqueueProxyCommand { proxy ->
                    proxy.runCommand("killall ${context.packageName}")
                }
            }
        }

        fun reloadLauncher(context: Context) {
            if (invariantDeviceProfileInstance.hasMethod("onConfigChanged", Context::class.java)) {
                invariantDeviceProfileInstance.callMethod("onConfigChanged", context)
            } else {
                invariantDeviceProfileInstance.callMethod("onConfigChanged")
            }
        }

        fun reloadIcons() {
            Handler(Looper.getMainLooper()).post {
                mCache.callMethod("clear")

                if (mIconDb.hasMethod("clear")) {
                    mIconDb.callMethod("clear")
                } else {
                    mIconDb.getField("mOpenHelper").also { mOpenHelper ->
                        mOpenHelper.callMethod(
                            "clearDB",
                            mOpenHelper.callMethod("getWritableDatabase")
                        )
                    }
                }

// forceReload() is the method that reloads the model, but its signature
                // is not stable across launcher builds:
                //  - William, Aug 2026: on his build the no-arg overload was
                //    GONE, and the no-arg call threw NoSuchMethodError from
                //    inside the hook - which took the whole launcher process
                //    down (FATAL EXCEPTION ... NoSuchMethodError:
                //    com.android.launcher3.LauncherModel#forceReload) right
                //    before it got SIG 9'd and auto-respawned - almost
                //    certainly the real cause of the "restarts twice" symptom.
                //  - Verified against the Android 17 launcher build
                //    (NexusLauncherRelease versionCode 907) via dexdump: the
                //    ONLY forceReload there is
                //    forceReload(String) -> CompletionStage, alongside
                //    reloadIfActive(String). So a bare callMethodSilently(
                //    "forceReload") is a silent no-op there - reloadIcons
                //    simply stops working.
                // Try the no-arg form first (older builds), then the
                // String-arg form that current builds expose.
                try {
                    mModel.callMethod("forceReload")
                } catch (_: Throwable) {
                    mModel.callMethod("forceReload", "reloadIcons")
                }
            }
        }
    }
}