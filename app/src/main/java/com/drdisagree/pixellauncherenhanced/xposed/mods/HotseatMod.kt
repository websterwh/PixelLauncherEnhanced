package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_SPACING
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_ICON_HORIZONTAL_MARGIN
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_SEARCH_BAR_OPACITY
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DOCK_BOTTOM_SPACING
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.restartLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.ResourceHookManager
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class HotseatMod(context: Context) : ModPack(context) {

    private var hideDesktopSearchBar = false
    private var desktopDockSpacing = -1
    private var desktopSearchBarOpacity = 100
    private var desktopIconHorizontalMargin = 0
    private var dockBottomSpacing = 0
    private var mQuickSearchBar: View? = null

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            hideDesktopSearchBar = getBoolean(DESKTOP_SEARCH_BAR, false)
            desktopDockSpacing = getSliderInt(DESKTOP_DOCK_SPACING, -1)
            desktopSearchBarOpacity = getSliderInt(DESKTOP_SEARCH_BAR_OPACITY, 100)
            desktopIconHorizontalMargin = getSliderInt(DESKTOP_ICON_HORIZONTAL_MARGIN, 0)
            dockBottomSpacing = getSliderInt(DOCK_BOTTOM_SPACING, 0)
        }

        when (key.firstOrNull()) {
            DESKTOP_SEARCH_BAR -> {
                triggerSearchBarVisibility()
                restartLauncher(mContext)
            }

            DESKTOP_DOCK_SPACING,
            DESKTOP_ICON_HORIZONTAL_MARGIN,
            DOCK_BOTTOM_SPACING -> restartLauncher(mContext)

            DESKTOP_SEARCH_BAR_OPACITY -> updateSearchBarOpacity()
        }
    }

    override fun handleLoadPackage(loadPackageParam: LoadPackageParam) {
        val hotseatClass = findClass("com.android.launcher3.Hotseat")
        val workspaceClass = findClass("com.android.launcher3.Workspace")
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")
        val deviceProfileBuilderClass = findClass($$"com.android.launcher3.DeviceProfile$Builder")

        // Both the DeviceProfile constructor hook and the Builder.build() hook
        // below call hookDockBottomSpacing() - and Builder.build() itself
        // constructs a DeviceProfile internally, which the constructor hook
        // also catches (hookConstructor() with no explicit signature hooks
        // every overload). Without a guard, a single DeviceProfile ends up
        // adjusted twice (once per hook site), stacking hotseatBarSizePx /
        // hotseatBarBottomSpacePx deltas and making DOCK_BOTTOM_SPACING look
        // broken or wildly off. Track which instances were already adjusted
        // so each one only gets the delta applied once.
        val dockBottomSpacingAdjusted =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

        // Moves the dock itself up/down from the bottom of the screen. This is
        // distinct from DESKTOP_DOCK_SPACING above, which only changes the gap
        // between the workspace icons and the (unmoved) dock.
        //
        // A v15 diagnostic class dump (confirmed via logcat on William's
        // device, Aug 2026) showed DeviceProfile has been split into several
        // sub-profile objects on this build (mHotseatProfile, mWorkspaceProfile,
        // mAllAppsProfile, etc.) - it never carries hotseatBarSizePx/
        // hotseatBarBottomSpacePx itself. The dock's own sub-object,
        // mHotseatProfile (runtime class com.android.launcher3.deviceprofile.
        // HotseatProfile), carries the same two values under shorter names -
        // barSizePx and barBottomSpacePx - since the "hotseat" prefix is
        // redundant once they're already inside a class called HotseatProfile.
        // Each (owner, fieldName) pair below is tried in order so this also
        // keeps working if a future/older build still uses the old shape.
        fun Any.hookDockBottomSpacing() {
            val alreadyAdjusted = !dockBottomSpacingAdjusted.add(this)

            if (dockBottomSpacing != 0 && !alreadyAdjusted) {
                val deltaPx = mContext.toPx(dockBottomSpacing)
                val hotseatProfile = getFieldSilently("mHotseatProfile")

                fun adjustField(fieldName: String) {
                    val candidates = listOfNotNull(
                        hotseatProfile?.let { it to fieldName },
                        this to fieldName
                    )
                    for ((owner, name) in candidates) {
                        val current = owner.getFieldSilently(name) as? Int ?: continue
                        owner.setFieldSilently(name, (current + deltaPx).coerceAtLeast(0))
                        return
                    }
                }

                adjustField("barSizePx")
                adjustField("barBottomSpacePx")
                // Old/AOSP-source field names, kept as a fallback for builds
                // where the HotseatProfile split above doesn't apply.
                adjustField("hotseatBarSizePx")
                adjustField("hotseatBarBottomSpacePx")
            }

            // Mutated here (once, on the DeviceProfile-owned Rect) rather than
            // only on the Workspace view's own padding, so the Hotseat/dock -
            // which independently reads this same workspacePadding field when
            // computing its own horizontal position via getHotseatLayoutPadding()
            // - picks up the same margin the homescreen icons use, instead of
            // only the homescreen grid shifting.
            if (desktopIconHorizontalMargin != 0 && !alreadyAdjusted) {
                val horizontalDeltaPx = mContext.toPx(desktopIconHorizontalMargin)
                val padding = getFieldSilently("workspacePadding") as? Rect
                    ?: (getFieldSilently("mWorkspaceProfile")
                        ?.getFieldSilently("workspacePadding") as? Rect)

                if (padding != null) {
                    padding.left += horizontalDeltaPx
                    padding.right += horizontalDeltaPx
                }
            }
        }

        deviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.hookDockBottomSpacing()
            }

        deviceProfileBuilderClass
            .hookMethod("build")
            .runAfter { param ->
                param.result.hookDockBottomSpacing()
            }

        // getHotseatLayoutPadding() is what actually decides the dock/QSB's
        // horizontal position at draw time. On scalable-grid profiles (which
        // is what this launcher build uses) it does NOT read workspacePadding
        // at all - it derives left/right padding from availableWidthPx and
        // hotseatQsbWidth instead, so mutating workspacePadding in
        // hookDockBottomSpacing() above has no effect on the dock for those
        // profiles. Hook the method itself and shift its result the same
        // amount, so the dock follows the icon margin regardless of which
        // internal branch (scalable grid, taskbar, vertical bar, or plain)
        // the device profile takes.
        deviceProfileClass
            .hookMethod("getHotseatLayoutPadding")
            .runAfter { param ->
                if (desktopIconHorizontalMargin == 0) return@runAfter
                val horizontalDeltaPx = mContext.toPx(desktopIconHorizontalMargin)
                val padding = param.result as? Rect ?: return@runAfter
                padding.left += horizontalDeltaPx
                padding.right += horizontalDeltaPx
            }

        hotseatClass
            .hookConstructor()
            .parameters(
                Context::class.java,
                AttributeSet::class.java,
                Int::class.javaPrimitiveType
            )
            .runAfter { param ->
                mQuickSearchBar = param.thisObject.getField("mQsb") as View
                triggerSearchBarVisibility()
                updateSearchBarOpacity()
            }

        hotseatClass
            .hookMethod("setInsets")
            .runAfter { param ->
                mQuickSearchBar = param.thisObject.getField("mQsb") as View
                triggerSearchBarVisibility()
                updateSearchBarOpacity()
            }

        workspaceClass
            .hookMethod("setInsets")
            .runAfter { param ->
                val mLauncher = param.thisObject.getField("mLauncher")
                val grid = mLauncher.callMethodSilently("getDeviceProfile")
                    ?: mLauncher
                        .getField("deviceProfileRef")
                        .getField("value")
                val padding = grid.getFieldSilently("workspacePadding") as? Rect
                    ?: grid
                        .getField("mWorkspaceProfile")
                        .getField("workspacePadding") as Rect
                val workspace = param.thisObject as View

                // padding.left/right already reflect DESKTOP_ICON_HORIZONTAL_MARGIN
                // here - it's applied once, directly on this same Rect field, in
                // hookDockBottomSpacing() above (so the dock picks up the same
                // margin too). Only the bottom value still needs adjusting here.
                workspace.setPadding(
                    padding.left,
                    padding.top,
                    padding.right,
                    if (desktopDockSpacing == -1) padding.bottom
                    else mContext.toPx(desktopDockSpacing + 20)
                )
            }

        ResourceHookManager
            .hookDimen()
            .whenCondition { hideDesktopSearchBar }
            .forPackageName(loadPackageParam.packageName)
            .addResource("qsb_widget_height") { 0 }
            .apply()
    }

    private fun triggerSearchBarVisibility() {
        mQuickSearchBar?.visibility = if (hideDesktopSearchBar) View.GONE else View.VISIBLE
    }

    private fun updateSearchBarOpacity() {
        mQuickSearchBar?.background?.alpha = desktopSearchBarOpacity * 255 / 100
    }
}