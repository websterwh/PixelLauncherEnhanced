package com.drdisagree.pixellauncherenhanced.xposed.mods

import android.content.Context
import android.os.Build
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_COLUMNS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_GRID_ROWS
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_GRID_4X4_ENABLE
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_PREVIEW_ICON_COUNT
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class GridOptions (context: Context) : ModPack(context) {

    private var homeScreenGridRows = 0
    private var homeScreenGridColumns = 0
    private var dockColumns = 0
    private var folderGridRows = 0
    private var folderGridColumns = 0
    private var appDrawerGridColumns = 0
    private var appDrawerGridRowHeightMultiplier = 1f

    // The dock (Hotseat) column count to apply. Falls back to the homescreen
    // column count when no explicit dock value is set, to preserve the
    // original coupled behavior. Returns 0 (no-op) when neither is set.
    private val effectiveDockColumns: Int
        get() = if (dockColumns != 0) dockColumns else homeScreenGridColumns

    private var folderPreviewIconCount = 0

    override fun updatePrefs(vararg key: String) {
        Xprefs.apply {
            homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
            homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
            dockColumns = getSliderInt(DESKTOP_DOCK_COLUMNS, 0)
            // William, Aug 2026: the old independent rows/columns sliders
            // let him request grids bigger than 4x4, but that turned out to
            // not actually work on-device (confirmed via testing) - so this
            // is now a single on/off toggle for the one size that does.
            val folderGrid4x4Enabled = getBoolean(FOLDER_GRID_4X4_ENABLE, false)
            folderGridRows = if (folderGrid4x4Enabled) 4 else 0
            folderGridColumns = if (folderGrid4x4Enabled) 4 else 0
            folderPreviewIconCount = getSliderInt(FOLDER_PREVIEW_ICON_COUNT, 0)
            appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
            appDrawerGridRowHeightMultiplier =
                getSliderFloat(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10f) / 10f
        }

        when (key.firstOrNull()) {
            DESKTOP_GRID_ROWS,
            DESKTOP_GRID_COLUMNS,
            DESKTOP_DOCK_COLUMNS,
            FOLDER_GRID_4X4_ENABLE,
            FOLDER_PREVIEW_ICON_COUNT,
            APP_DRAWER_GRID_COLUMNS,
            APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER -> reloadLauncher(mContext)
        }
    }

    override fun handleLoadPackage(loadPackageParam: XC_LoadPackage.LoadPackageParam) {
        val deviceProfileClass = findClass("com.android.launcher3.DeviceProfile")
        val deviceProfileBuilderClass = findClass($$"com.android.launcher3.DeviceProfile$Builder")
        val invariantDeviceProfileClass = findClass("com.android.launcher3.InvariantDeviceProfile")

        fun Any.hookDeviceProfile() {
            val temp = getFieldSilently("iconSizePx") as? Int
            val mHotseatProfile = getFieldSilently("mHotseatProfile")
            val mDisplayOptionSpec = getFieldSilently("mDisplayOptionSpec")

            if (effectiveDockColumns != 0) {
                setFieldSilently("numShownHotseatIcons", effectiveDockColumns)
                mHotseatProfile?.setField("numShownIcons", effectiveDockColumns)
            }

            // numFolderRows/numFolderColumns are declared `final` on DeviceProfile,
            // so this is a best-effort fallback for any code path that reads them
            // straight off DeviceProfile without going through the "grid"/GridOption
            // object patched below (which is where the value normally ought to come
            // from, before folder cell sizes are computed from it).
            if (folderGridRows != 0) {
                setFieldSilently("numFolderRows", folderGridRows)
            }
            if (folderGridColumns != 0) {
                setFieldSilently("numFolderColumns", folderGridColumns)
            }

            if (appDrawerGridColumns != 0) {
                setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)

                if (temp == null) {
                    val mAllAppsProfile = getField("mAllAppsProfile")
                    mAllAppsProfile.setFieldSilently("numShownAllAppsColumns", appDrawerGridColumns)
                }
            }

            mDisplayOptionSpec?.apply {
                if (effectiveDockColumns != 0) {
                    setField("numShownHotseatIcons", effectiveDockColumns)
                }
                if (appDrawerGridColumns != 0) {
                    setField("numAllAppsColumns", appDrawerGridColumns)
                }
            }
        }

        deviceProfileClass
            .hookConstructor()
            .runAfter { param ->
                param.thisObject.hookDeviceProfile()
            }

        deviceProfileBuilderClass
            .hookMethod("build")
            .runAfter { param ->
                param.result.hookDeviceProfile()
            }

        invariantDeviceProfileClass
            .hookMethod("initGrid")
            .runBefore { param ->
                if (param.args.size >= 3) {
                    val displayOption = param.args[2]
                    val closestProfile = displayOption.getField("grid")

                    if (effectiveDockColumns != 0) {
                        closestProfile.setField("numHotseatIcons", effectiveDockColumns)
                    }
                    if (folderGridRows != 0) {
                        closestProfile.setFieldSilently("numFolderRows", folderGridRows)
                    }
                    if (folderGridColumns != 0) {
                        closestProfile.setFieldSilently("numFolderColumns", folderGridColumns)
                    }
                }
            }
            .runAfter { param ->
                param.thisObject.apply {
                    if (homeScreenGridRows != 0) {
                        setField("numRows", homeScreenGridRows)
                    }
                    if (homeScreenGridColumns != 0) {
                        setField("numColumns", homeScreenGridColumns)
                    }
                    if (effectiveDockColumns != 0) {
                        setField("numShownHotseatIcons", effectiveDockColumns)
                    }
                    if (folderGridRows != 0) {
                        setFieldSilently("numFolderRows", folderGridRows)
                    }
                    if (folderGridColumns != 0) {
                        setFieldSilently("numFolderColumns", folderGridColumns)
                    }
                }
            }

        invariantDeviceProfileClass
            .hookMethod("invDistWeightedInterpolate")
            .runAfter { param ->
                val displayOption = param.result
                val closestProfile = displayOption.getField("grid")

                if (homeScreenGridRows != 0) {
                    closestProfile.setFieldSilently("numRows", homeScreenGridRows)
                }
                if (homeScreenGridColumns != 0) {
                    closestProfile.setFieldSilently("numColumns", homeScreenGridColumns)
                }
                if (effectiveDockColumns != 0) {
                    closestProfile.setFieldSilently("numHotseatIcons", effectiveDockColumns)
                    closestProfile.setFieldSilently("numDatabaseHotseatIcons", effectiveDockColumns)
                }
                if (folderGridRows != 0) {
                    closestProfile.setFieldSilently("numFolderRows", folderGridRows)
                }
                if (folderGridColumns != 0) {
                    closestProfile.setFieldSilently("numFolderColumns", folderGridColumns)
                }
                if (appDrawerGridColumns != 0) {
                    closestProfile.setFieldSilently("numAllAppsColumns", homeScreenGridColumns)
                    closestProfile.setFieldSilently("numDatabaseAllAppsColumns", homeScreenGridColumns)
                }
            }

        deviceProfileClass
            .hookMethod(
                "updateIconSize",
                "autoResizeAllAppsCells"
            )
            .suppressError()
            .runAfter { param ->
                if (appDrawerGridRowHeightMultiplier == 1f) return@runAfter

                val allAppsCellHeightPx =
                    param.thisObject.getFieldSilently("allAppsCellHeightPx") as? Int
                val allAppsIconDrawablePaddingPx = 0

                if (allAppsCellHeightPx != null) {
                    param.thisObject.setField(
                        "allAppsCellHeightPx",
                        (allAppsCellHeightPx * appDrawerGridRowHeightMultiplier).roundToInt()
                    )
                    param.thisObject.setField(
                        "allAppsIconDrawablePaddingPx",
                        allAppsIconDrawablePaddingPx
                    )
                }
            }

        val allAppsProfileClass = findClass(
            "com.android.launcher3.deviceprofile.AllAppsProfile",
            suppressError = Build.VERSION.SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM
        )

        allAppsProfileClass
            .hookConstructor()
            .runAfter { param ->
                if (appDrawerGridRowHeightMultiplier == 1f) return@runAfter

                val cellHeightPx = param.thisObject.getField("cellHeightPx") as Int
                val iconDrawablePaddingPx = 0

                param.thisObject.setField(
                    "cellHeightPx",
                    (cellHeightPx * appDrawerGridRowHeightMultiplier).roundToInt()
                )
                param.thisObject.setField(
                    "iconDrawablePaddingPx",
                    iconDrawablePaddingPx
                )
            }

        // Folder preview icon count (the icons visible on a *closed* folder).
        // AOSP hardcodes this to MAX_NUM_ITEMS_IN_PREVIEW = 4 as a compile-time
        // constant, which is inlined at every call site - so overriding the
        // constant's field value directly has no effect on already-compiled
        // callers. Instead this overrides the *results* of the two methods
        // that actually decide (a) which items make it into the preview and
        // (b) where each preview icon is positioned, via runAfter + result
        // mutation (this toolkit's .replace{} always returns null regardless
        // of the callback, so it can't be used for value-returning methods).
        //
        // Known gaps (left as-is, not overridden): drag-and-drop reordering
        // animations inside FolderIcon still reference the hardcoded 4, as
        // does the accessibility title and folder pages beyond the first.
        val folderGridOrganizerClass = findClass("com.android.launcher3.folder.FolderGridOrganizer")
        val clippedFolderIconLayoutRuleClass =
            findClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule")
        val previewItemManagerClass =
            findClass("com.android.launcher3.folder.PreviewItemManager")

        // Folder grid rows/columns (the OPEN folder's own grid, distinct from
        // the closed preview handled below). AOSP's FolderGridOrganizer only
        // uses numFolderRows/numFolderColumns as a CAP - calculateGridSize()
        // derives the actual on-screen rows/cols from the live item count via
        // ceil(sqrt(n)), so a folder with fewer items than the cap renders
        // identically no matter what the cap is set to; this is why the
        // sliders looked like they did nothing for ordinary folders.
        //
        // v14 tried overriding the public getCountX()/getCountY() getters,
        // but a v15 diagnostic class dump (confirmed via logcat on William's
        // device, Aug 2026) showed R8 has fully inlined those getters out of
        // FolderGridOrganizer on this build - they don't exist as methods at
        // all anymore. The backing fields mCountX/mCountY (both plain int)
        // are still there, though, and since a trivial inlined getter means
        // every caller now reads the field directly instead of calling a
        // method, writing the fields is actually MORE robust than hooking a
        // getter would have been - it works whether or not the getter
        // survived compilation. setContentSize(int) is what (re)computes
        // mCountX/mCountY via calculateGridSize() any time a folder's
        // content changes (including on open).
        //
        // William, Aug 2026: the sliders are meant to be a CAP, not a forced
        // size - a folder with fewer items than the cap should keep
        // shrinking to fit its actual contents (AOSP's own calculateGridSize
        // already does this correctly via the live item count, right before
        // this hook runs). The old code force-set mCountX/mCountY to the
        // full configured max unconditionally, which stomped that natural
        // sizing and made every folder open at the max grid size regardless
        // of how many apps it actually held. Fix: read back what AOSP's own
        // call just computed (the "needed" size for this folder's real item
        // count) and only clamp it DOWN if it exceeds the configured cap -
        // never force it up.
        // William, Aug 2026: the v29 min()-clamp fix ("only shrink AOSP's
        // own natural mCountX/mCountY, never force it up") turned out wrong
        // - the FolderSizeDebug log (v30, on-device) showed AOSP's own
        // natural value never exceeds 3x3 no matter the item count (18 items
        // still came back 3x3, just spread across more pages), so it has its
        // own fixed cap unrelated to our numFolderRows/numFolderColumns
        // overrides, and min(natural, configured) could only ever shrink,
        // never reach a configured cap above 3. So instead of trusting
        // AOSP's natural value at all, compute the needed size ourselves
        // straight from the real item count (ceil(sqrt(n)) for columns, then
        // ceil(n / cols) for rows - the same algorithm the old comment
        // above already documented as AOSP's own), and clamp THAT to the
        // configured cap. This grows folders up to the configured max when
        // they have enough items, and shrinks them for small folders,
        // instead of only ever capping down from whatever AOSP happened to
        // pick.
        // William, Aug 2026: found the real reason folders still split at 9
        // items per page no matter what setContentSize wrote - the v32
        // FolderGridOrganizer dump (confirmed via logcat) showed it has
        // mMaxCountX/mMaxCountY/mMaxItemsPerPage fields, entirely separate
        // from the mCountX/mCountY this hook already controls. mCountX/
        // mCountY just size the visible grid for whatever items already
        // landed on a page; mMaxItemsPerPage is what actually decides how
        // many items GO on a page before the rest spill to the next one -
        // and that's presumably still stuck at the same fixed default (3x3)
        // that mCountX/mCountY used to be stuck at, unrelated to our
        // numFolderRows/numFolderColumns overrides. setFolderInfo(FolderInfo)
        // is the only other method on this class and the obvious place
        // those three fields get established, so override them there.
        folderGridOrganizerClass
            .hookMethod("setFolderInfo")
            .runAfter { param ->
                val newMaxCols = if (folderGridColumns != 0) {
                    folderGridColumns
                } else {
                    param.thisObject.getFieldSilently("mMaxCountX") as? Int
                }
                val newMaxRows = if (folderGridRows != 0) {
                    folderGridRows
                } else {
                    param.thisObject.getFieldSilently("mMaxCountY") as? Int
                }

                if (newMaxCols != null) param.thisObject.setFieldSilently("mMaxCountX", newMaxCols)
                if (newMaxRows != null) param.thisObject.setFieldSilently("mMaxCountY", newMaxRows)
                if (newMaxCols != null && newMaxRows != null) {
                    param.thisObject.setFieldSilently("mMaxItemsPerPage", newMaxCols * newMaxRows)
                }
            }

        folderGridOrganizerClass
            .hookMethod("setContentSize")
            .runAfter { param ->
                val itemCount = param.args.getOrNull(0) as? Int
                if (itemCount == null || itemCount <= 0) return@runAfter

                val currentCols = param.thisObject.getFieldSilently("mCountX") as? Int
                val currentRows = param.thisObject.getFieldSilently("mCountY") as? Int

                val newCols = if (folderGridColumns != 0) {
                    val needed = ceil(sqrt(itemCount.toDouble())).toInt().coerceAtLeast(1)
                    min(needed, folderGridColumns)
                } else {
                    currentCols
                }

                val newRows = if (folderGridRows != 0) {
                    val effectiveCols = (newCols ?: currentCols ?: 1).coerceAtLeast(1)
                    val needed = ceil(itemCount.toDouble() / effectiveCols).toInt().coerceAtLeast(1)
                    min(needed, folderGridRows)
                } else {
                    currentRows
                }

                if (newCols != null) param.thisObject.setFieldSilently("mCountX", newCols)
                if (newRows != null) param.thisObject.setFieldSilently("mCountY", newRows)
            }

        folderGridOrganizerClass
            .hookMethod("previewItemsForPage")
            .runAfter { param ->
                val page = param.args.getOrNull(0) as? Int ?: return@runAfter
                val contents = param.args.getOrNull(1) as? List<*> ?: return@runAfter

                // William, Sep 2026: this is native AOSP big-folder behavior
                // (FolderGridOrganizer.mDisplayingUpperLeftQuadrant flips
                // true for ANY folder with >4 items, stock or not), and it's
                // the REAL cause of the "stray icon left after closing from
                // page >= 1" bug - confirmed via logcat with the getGridPosition
                // page-guard above already in place and correctly bailing
                // (animatedPage=1 detected, no override applied), yet the
                // icon still stuck around. Root cause: AOSP's own
                // isItemInPreview(page, indexOnPage), once
                // mDisplayingUpperLeftQuadrant is true, treats every PAGE's
                // own upper-left 2x2 as "in preview" - not just page 0's.
                // FolderAnimationSpringBuilderManager.createAnimatorSet
                // skips the close-direction alpha-fade spring for any icon
                // in that per-page list, so a lone page-2 icon (trivially
                // "upper-left" of its own page) never fades out - it just
                // gets its translation/scale reset to identity at animation
                // end while staying fully opaque, which is the stray icon.
                // Our closed-icon preview is a FIXED page-0-derived grid
                // (repositionPreviewItemAsGrid) - it never reflects
                // "whichever page you're on" the way AOSP's own multi-page
                // preview concept assumes, so that concept must be
                // disabled entirely for us: report empty for every page
                // other than 0 once a folder is in "big folder" territory
                // (>4 items total), so every non-zero-page icon gets the
                // normal fade-out on open/close, matching a folder that
                // simply has no preview items on that page.
                if (contents.size > 4 && page != 0) {
                    param.result = ArrayList<Any?>()
                    return@runAfter
                }

                if (folderPreviewIconCount == 0) return@runAfter
                if (page != 0) return@runAfter // only override the closed-icon preview (page 0)

                val count = min(folderPreviewIconCount, contents.size)
                param.result = ArrayList(contents.subList(0, count))
            }

        // AOSP's own circular layout is hand-tuned for at most 4 items; stretching
        // it further just spreads items evenly around a full ring instead of a
        // folder-preview-like arrangement. Once there are more than 4 items, lay
        // them out as a small square grid instead (ceil(sqrt(n)) columns, filled
        // row-major), replacing every item's position so the whole preview is one
        // consistent layout rather than a mix of AOSP's diamond for the first 4
        // and something else for the rest.
        //
        // IMPORTANT: everything below keys off curNumItems - the ACTUAL item
        // count of the specific folder being drawn - never off the global
        // folderPreviewIconCount setting (which is only the user's configured
        // *maximum*). A single ClippedFolderIconLayoutRule class is shared by
        // every folder, so a rule instance for a 2-item folder and one for a
        // 9-item folder both pass through the exact same hooks; guarding on
        // the global setting instead of the real per-folder count was the bug
        // that shrank small (2-4 item) folders too.

        // William, Aug 2026 (final build): the TEMP sliders are gone - these
        // stock-anchored constants are the permanently baked-in tuning.
        // Derivation (from decompiled source): PreviewItemManager
        // .computePreviewDrawingParams() sets mBaselineIconScale =
        // previewSize / iconSize (previewSize is exactly our
        // "availableSpace"), and ClippedFolderIconLayoutRule.scaleForItem()
        // returns 0.44 * mBaselineIconScale for a 4-icon preview - so stock
        // always draws each of its 4 preview icons at exactly
        // 0.44 * availableSpace px. Our iconFootprint works out to
        // (availableSpace / max(cols, rows)) * spacingFactor * sizeFactor -
        // treating stock's 4-icon case as an (effective) 2x2 grid and
        // solving for the product that reproduces stock's
        // 0.44 * availableSpace gives spacingFactor * sizeFactor = 0.88.
        // Split as sizeFactor=1.0 (icon fully fills its cell) and
        // spacingFactor=0.88 (the rest of the work) - matching how William
        // read the knobs ("icon size" = fill-fraction, "spacing" =
        // cluster tightness); his hand-tuned 100%/85% landed within ~3% of
        // the derived 88%.
        val DEFAULT_SPACING_FACTOR = 0.88f
        val DEFAULT_SIZE_FACTOR = 1.0f
        val DEFAULT_GAP_DP = 2

        // How close together the cells sit (cluster tightness). Lowering
        // this pulls the whole cluster toward the folder's center.
        fun spacingFactorFor(curNumItems: Int): Float = DEFAULT_SPACING_FACTOR

        // How much of each cell's pitch the icon itself fills, independent
        // of spacing.
        fun sizeFactorFor(curNumItems: Int): Float = DEFAULT_SIZE_FACTOR

        // cols/rows: grid shape for this item count.
        // pitch: distance between adjacent cell centers. Unlike a fixed cell
        // size, pitch itself shrinks as spacingFactorFor() drops below 1 - so
        // lowering the spacing slider pulls icons closer together, instead
        // of just shrinking icons in place while they stay pinned near the
        // folder's edge.
        // William, Aug 2026: cols/rows here MUST land on the exact same
        // numbers as FolderGridOrganizer.mCountX/mCountY (overridden by the
        // setContentSize hook below using this identical
        // ceil(sqrt(n))-then-cap-at-folderGridColumns/Rows formula) - because
        // FolderAnimationSpringBuilderManager.createAnimatorSet() (confirmed
        // via the decompiled source, William Aug 2026) reads mCountX directly
        // from a FolderGridOrganizer to compute each icon's open/close
        // animation destination (layoutRule.getGridPosition(index/mCountX,
        // index%mCountX, ...)), completely independent of this function. This
        // was previously UNCAPPED (always ceil(sqrt(n)), ignoring
        // folderGridColumns/Rows), so e.g. an 18-item folder with the 4x4
        // toggle on drew its closed preview at 5 cols (ceil(sqrt(18))) while
        // the animation moved icons toward a 4-col grid (capped mCountX) -
        // that column-count disagreement is a real, confirmed contributor to
        // the open/close "flash": icons animate into a 4-wide arrangement,
        // then the next real draw() snaps them into a 5-wide one. Capping
        // here the same way removes that specific mismatch. (A residual,
        // smaller flash may still remain since getGridPosition's own
        // internal per-cell pitch formula - AOSP's, unrelated to our
        // spacing/size sliders - still isn't identical to this function's;
        // that would need hooking getGridPosition itself, not yet done.)
        fun gridPitch(curNumItems: Int, availableSpace: Float): Triple<Int, Int, Float> {
            val neededCols = ceil(sqrt(curNumItems.toDouble())).toInt().coerceAtLeast(1)
            val cols = if (folderGridColumns != 0) {
                min(neededCols, folderGridColumns)
            } else {
                neededCols
            }
            val neededRows = ceil(curNumItems.toDouble() / cols).toInt().coerceAtLeast(1)
            val rows = if (folderGridRows != 0) {
                min(neededRows, folderGridRows)
            } else {
                neededRows
            }
            val baseCellSize = availableSpace / max(cols, rows)
            val pitch = baseCellSize * spacingFactorFor(curNumItems)
            return Triple(cols, rows, pitch)
        }

        // Remembers the actual item count last seen for a given rule
        // instance, keyed by the rule object itself. scaleForItem() (hooked
        // further down) is called by FolderAnimationManager without a
        // trustworthy way to read the real per-folder count from its own
        // args, so it looks up the value recorded here by
        // computePreviewItemDrawingParams() instead - which always receives
        // the true curNumItems for whichever folder is currently being drawn.
        val lastCurNumItemsByRule = java.util.WeakHashMap<Any, Int>()

        // William, Aug 2026, v49: companion cache to lastCurNumItemsByRule
        // above, holding the folder's TOTAL content count per rule instance.
        // The v48 FlashDebug capture confirmed why this is needed:
        // createAnimatorSet walks page icons as getGridPosition(i/mCountX,
        // i%mCountX) where mCountX is sized from TOTAL contents (Social: 11
        // items -> 4 cols), while this file's other math keys off the PREVIEW
        // count (min(9, ...) -> 3 cols). Reconstructing the reading-order
        // index with the 3-wide stride against the caller's 4-wide walk
        // produced duplicated indices (0,1,2,3,3,4,5,6,6,7,8) - two pairs of
        // icons flew to identical destinations during close, which is the
        // visible "order goes wrong then fixes" glitch. The getGridPosition
        // hook below rebuilds the caller's column count from this cached
        // total instead. Populated in the fallback computePreviewItemDrawingParams
        // hook (the only place that has both the rule and its owning
        // FolderIcon's mInfo).
        val lastTotalContentsByRule = java.util.WeakHashMap<Any, Int>()

        // William, Sep 2026: fixes "stray icon left on the preview" when a
        // folder bigger than the AOSP default is closed (or reopened) from
        // page >= 1 without returning to page 0 first. Root cause confirmed
        // from decompiled source (FolderAnimationSpringBuilderManager
        // .createAnimatorSet / FolderGridOrganizer.isItemInPreview): once a
        // folder has >4 items, isItemInPreview treats EVERY page's own
        // upper-left 2x2 as "in preview" (mDisplayingUpperLeftQuadrant),
        // and createAnimatorSet calls getGridPosition(i/mCountX, i%mCountX)
        // with i being the icon's index WITHIN THE CURRENT PAGE - not a
        // global reading-order index. Our getGridPosition hook (below)
        // always treated that (row, col) as global preview coordinates,
        // which only happens to be correct for page 0 (where page-relative
        // index == global index). For page >= 1 it silently maps that
        // page's icons onto page-0's preview slots, producing bogus
        // destinations for whichever of those icons AOSP's own quadrant
        // rule decided not to fade out - which is what shows up as a stray
        // icon sitting in/near the closed preview. Cache keyed by the
        // layout rule (same rule instance getGridPosition receives as
        // thisObject) so that hook can bail out and leave AOSP's own
        // per-page math untouched whenever the page isn't 0. Populated in
        // the createAnimatorSet hook below, mirroring how AOSP itself picks
        // the page (getCurrentPage() when opening, getDestinationPage()
        // when closing - see decompiled source).
        val lastAnimatedPageByRule = java.util.WeakHashMap<Any, Int>()

        // William, Aug 2026 (final build): flat 2dp gap between preview
        // icons, baked in - purely a taste/spread choice William tuned on
        // device (stock's implied value is 0, but 2dp is what he settled
        // on through every round of testing).
        fun effectiveGapPx(): Float = mContext.toPx(DEFAULT_GAP_DP).toFloat()

        // Cols/rows-based placement, used by both the "grid" shape (square,
        // cols = ceil(sqrt(n))) and the "list" shape (fixed 2 columns).
        // gapPx is added ONLY here, on top of the size/spacing-driven pitch,
        // so it purely spreads cells further apart without touching icon
        // draw size (iconFootprint, and therefore scale, stays whatever the
        // size/spacing sliders already computed it as - unaffected by gap).
        fun gridPosition(
            index: Int,
            curNumItems: Int,
            availableSpace: Float,
            iconFootprint: Float,
            gapPx: Float,
            isRtl: Boolean,
            forcedCols: Int?
        ): Pair<Float, Float> {
            val (defaultCols, defaultRows, basePitch) = gridPitch(curNumItems, availableSpace)
            val cols = forcedCols ?: defaultCols
            val rows = forcedCols?.let { ceil(curNumItems.toDouble() / it).toInt().coerceAtLeast(1) }
                ?: defaultRows
            // "list" shape has no natural pitch of its own (it isn't a
            // square grid) - base it on the icon's own footprint instead, so
            // icons sit edge-to-edge by default (gap=0) and spread apart as
            // the gap slider increases.
            val pitch = if (forcedCols != null) iconFootprint + gapPx else basePitch + gapPx

            val row = index / cols
            val rawCol = index % cols
            val col = if (isRtl) cols - 1 - rawCol else rawCol

            val gridWidth = cols * pitch
            val gridHeight = rows * pitch
            val offsetX = (availableSpace - gridWidth) / 2f
            val offsetY = (availableSpace - gridHeight) / 2f

            val transX = offsetX + col * pitch + (pitch - iconFootprint) / 2f
            val transY = offsetY + row * pitch + (pitch - iconFootprint) / 2f
            return transX to transY
        }

        // William, Aug 2026 (final build): the experimental shapes (circle/
        // list/honeycomb/flower) are gone - the square grid is the one and
        // only closed-preview arrangement, hardcoded. History of the shapes
        // that were tried and removed here lives in the v51-v54 commits if
        // it's ever needed again.
        fun previewPosition(
            index: Int,
            curNumItems: Int,
            availableSpace: Float,
            iconFootprint: Float,
            gapPx: Float,
            isRtl: Boolean
        ): Pair<Float, Float> =
            gridPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl, forcedCols = null)

        // Icon draw size: the square-grid cell's pitch times the size
        // factor - shared by the draw path and the scaleForItem animation
        // hook below so the two can never disagree.
        fun iconFootprintFor(
            curNumItems: Int,
            availableSpace: Float,
            gapPx: Float,
            basePitch: Float
        ): Float {
            return basePitch * sizeFactorFor(curNumItems)
        }

        fun repositionPreviewItemAsGrid(
            rule: Any,
            index: Int,
            curNumItems: Int,
            resultParams: Any?
        ) {
            lastCurNumItemsByRule[rule] = curNumItems
            if (index < 0 || index >= curNumItems || curNumItems <= 4) return
            // This function always runs our own stock-tuned grid math
            // instead of ever falling back to AOSP's own math, which isn't
            // built for curNumItems>4.

            val availableSpace = rule.getFieldSilently("mAvailableSpace") as? Float
            val iconSize = rule.getFieldSilently("mIconSize") as? Float
            val isRtl = rule.getFieldSilently("mIsRtl") as? Boolean ?: false
            // availableSpace/iconSize can be 0 very early (before this rule's
            // owning FolderIcon has been measured) - bail rather than divide
            // by zero and hand AOSP a NaN transX/transY/scale.
            if (availableSpace == null || iconSize == null ||
                availableSpace <= 0f || iconSize <= 0f
            ) return

            // iconFootprint (and therefore scale) is always computed from the
            // default square-grid pitch, regardless of which shape is
            // selected - so switching shapes only changes the ARRANGEMENT,
            // not how big the icons themselves are. Keeps scaleForItem()
            // below (which has no notion of "shape") consistent with
            // whatever this function actually draws.
            val (_, _, basePitch) = gridPitch(curNumItems, availableSpace)
            val gapPx = effectiveGapPx()
            val iconFootprint = iconFootprintFor(curNumItems, availableSpace, gapPx, basePitch)
            val scale = iconFootprint / iconSize

            val (transX, transY) =
                previewPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl)

            resultParams?.callMethodSilently("update", transX, transY, scale)
        }

        // Primary path (AOSP master): the rule class owns the method directly.
        clippedFolderIconLayoutRuleClass
            ?.hookMethod("computePreviewItemDrawingParams")
            ?.runAfter { param ->
                val index = param.args.getOrNull(0) as? Int ?: return@runAfter
                val curNumItems = param.args.getOrNull(1) as? Int ?: return@runAfter
                if (index < 0) return@runAfter // workspace destroy/create animation, not a grid slot
                repositionPreviewItemAsGrid(param.thisObject, index, curNumItems, param.result)
            }

        // Fallback path: on some builds the rule's method body gets inlined into
        // its only caller, so ClippedFolderIconLayoutRule no longer has a method
        // by this name - hook PreviewItemManager's copy instead and reach the
        // rule's fields via PreviewItemManager.mIcon.mPreviewLayoutRule.
        previewItemManagerClass
            ?.hookMethod("computePreviewItemDrawingParams")
            ?.runAfter { param ->
                val index = param.args.getOrNull(0) as? Int ?: return@runAfter
                val curNumItems = param.args.getOrNull(1) as? Int ?: return@runAfter

                val rule = param.thisObject
                    .getFieldSilently("mIcon")
                    ?.getFieldSilently("mPreviewLayoutRule")
                    ?: return@runAfter
                lastCurNumItemsByRule[rule] = curNumItems
                // William, Aug 2026, v49: cache the folder's TOTAL content
                // count for the getGridPosition stride fix (see the
                // lastTotalContentsByRule comment above). mInfo.contents is a
                // plain public ArrayList on FolderInfo, so its size is the
                // same number AOSP's own setContentSize sizing uses.
                val contentsList = param.thisObject
                    .getFieldSilently("mIcon")
                    ?.getFieldSilently("mInfo")
                    ?.getFieldSilently("contents") as? List<*>
                val totalContents = contentsList?.size
                if (totalContents != null && totalContents > 0) {
                    lastTotalContentsByRule[rule] = totalContents
                }
                if (index < 0) return@runAfter // workspace destroy/create animation, not a grid slot
                if (curNumItems <= 4) return@runAfter
                repositionPreviewItemAsGrid(rule, index, curNumItems, param.result)
            }

        // Fixes the open/close "flash": FolderAnimationManager.getAnimator()
        // (which drives the open/close reveal animation) separately calls
        // ClippedFolderIconLayoutRule.scaleForItem() to work out how large the
        // closed-folder preview icons are, and uses that to size the initial
        // (closed) vs final (open) state of the whole folder content scale
        // animation. We never overrode that method - only the per-item
        // position/scale used to actually draw the preview - so for large
        // previews it still returned AOSP's circular-preview scale, which no
        // longer matched what repositionPreviewItemAsGrid() draws above. That
        // mismatch is what showed up as a brief sliver of the old layout
        // peeking out at the folder's border during open/close. Hook it so it
        // agrees with the same grid math the preview itself uses - using the
        // real per-folder count cached above, not the global setting.
        clippedFolderIconLayoutRuleClass
            ?.hookMethod("scaleForItem")
            ?.runAfter { param ->
                val curNumItems = lastCurNumItemsByRule[param.thisObject] ?: return@runAfter
                if (curNumItems <= 4) return@runAfter

                val availableSpace = param.thisObject.getFieldSilently("mAvailableSpace") as? Float
                val iconSize = param.thisObject.getFieldSilently("mIconSize") as? Float
                if (availableSpace == null || iconSize == null ||
                    availableSpace <= 0f || iconSize <= 0f
                ) return@runAfter

                val (_, _, pitch) = gridPitch(curNumItems, availableSpace)
                val gapPx = effectiveGapPx()
                val iconFootprint = iconFootprintFor(curNumItems, availableSpace, gapPx, pitch)
                val result = iconFootprint / iconSize
                param.result = result
            }

        // Real fix (William, Aug 2026) for the open/close animation itself
        // still not matching the closed-preview arrangement. Confirmed via
        // the decompiled source (FolderAnimationSpringBuilderManager.java)
        // that the animation NEVER goes through
        // computePreviewItemDrawingParams/repositionPreviewItemAsGrid at
        // all - it computes each icon's shrink/grow destination directly
        // via layoutRule.getGridPosition(row, col, fArr), using AOSP's own
        // corner-interpolation formula (see getPosition() in
        // ClippedFolderIconLayoutRule.java), completely independent of
        // whatever shape/spacing/size we've customized. The gridPitch()
        // column cap fix (earlier round) only fixed the two sides
        // disagreeing on the *column count*; the per-cell pitch and
        // arrangement formula itself remained AOSP's own, unrelated math -
        // which is why the flash was still visible after that fix.
        //
        // getGridPosition(row, col, fArr) is called (per the decompiled
        // createAnimatorSet) as row=index/mCountX, col=index%mCountX, where
        // mCountX is FolderGridOrganizer's column count - the SAME field
        // our setContentSize hook sets using this exact gridPitch() cols
        // value. So as long as the curNumItems cached from the most recent
        // real draw (lastCurNumItemsByRule) still matches the folder's
        // actual content (true whenever the animation isn't racing a
        // simultaneous add/remove), reconstructing
        // index = row * gridPitch(curNumItems,...).cols + col recovers the
        // exact same reading-order index computePreviewItemDrawingParams
        // used, letting us hand the animation the SAME transX/transY our
        // own shape math already draws with - instead of AOSP's unrelated
        // formula. If mCountX and our recomputed cols ever disagree (e.g.
        // stale cache), the reconstructed index falls outside
        // [0, curNumItems) and this bails, leaving AOSP's own (still
        // possibly-mismatched) position rather than drawing somewhere
        // wrong.
        clippedFolderIconLayoutRuleClass
            ?.hookMethod("getGridPosition")
            ?.runAfter { param ->
                val curNumItems = lastCurNumItemsByRule[param.thisObject] ?: return@runAfter
                if (curNumItems <= 4) return@runAfter

                // William, Sep 2026: bail out for open/close animations that
                // aren't walking page 0 - see lastAnimatedPageByRule above.
                // Our (row, col) -> global-index reconstruction below only
                // holds when the caller's row/col are page-0/global preview
                // coordinates; for page >= 1 they're page-relative, and
                // reinterpreting them as global coordinates is exactly what
                // produced the stray-icon bug. Leaving fArr untouched here
                // means AOSP's own (unmodified) per-page math drives those
                // icons instead - same as if this hook never ran.
                val animatedPage = lastAnimatedPageByRule[param.thisObject]
                if (animatedPage != null && animatedPage != 0) return@runAfter

                val row = param.args.getOrNull(0) as? Int ?: return@runAfter
                val col = param.args.getOrNull(1) as? Int ?: return@runAfter
                val fArr = param.args.getOrNull(2) as? FloatArray ?: return@runAfter

                val availableSpace = param.thisObject.getFieldSilently("mAvailableSpace") as? Float
                val iconSize = param.thisObject.getFieldSilently("mIconSize") as? Float
                val isRtl = param.thisObject.getFieldSilently("mIsRtl") as? Boolean ?: false
                if (availableSpace == null || iconSize == null ||
                    availableSpace <= 0f || iconSize <= 0f
                ) return@runAfter

                val (previewCols, _, basePitch) = gridPitch(curNumItems, availableSpace)
                if (previewCols <= 0) return@runAfter
                // William, Aug 2026, v49: reconstruct the reading-order index
                // using the CALLER'S column count, not this file's preview-count
                // one. createAnimatorSet calls getGridPosition(row=i/mCountX,
                // col=i%mCountX) with mCountX taken from a FolderGridOrganizer
                // sized by TOTAL contents - a value this file's own
                // setContentSize hook fully controls (ceil(sqrt(total)) capped
                // at folderGridColumns). The v48 FlashDebug capture proved the
                // mismatch: Social walks its 11 page icons 4-wide while this
                // hook reconstructed with gridPitch(9).cols = 3, producing
                // duplicated indices (3 and 6 twice each) - two pairs of icons
                // flew to identical destinations mid-close, i.e. the visible
                // "order goes wrong then fixes" glitch. Replicate the exact
                // setContentSize formula here against the cached total so both
                // sides agree; fall back to the preview-derived count when no
                // total has been seen yet for this rule.
                val totalContents = lastTotalContentsByRule[param.thisObject]
                val callerCols = if (totalContents != null && totalContents > 0) {
                    val needed = ceil(sqrt(totalContents.toDouble())).toInt().coerceAtLeast(1)
                    if (folderGridColumns != 0) min(needed, folderGridColumns) else needed
                } else {
                    previewCols
                }
                // William, Aug 2026, v50: page icons beyond the preview cap
                // used to hit this bail and KEEP AOSP's raw extrapolated
                // coordinates - which extend up to ~2x beyond availableSpace,
                // so those icons visibly swung OUTSIDE the closed folder's
                // area during close (William: "the tiniest sliver of something
                // outside the folder that shows for half a second"; the v49
                // log showed 54 such bails, and adding an 8th app to a 7-app
                // folder resurrected the glitch by creating exactly one).
                // Clamp instead: out-of-preview icons converge onto the last
                // preview slot, staying inside the folder for the morph.
                val indexInBounds = row * callerCols + col
                val index = indexInBounds.coerceIn(0, curNumItems - 1)

                val gapPx = effectiveGapPx()
                val iconFootprint = iconFootprintFor(curNumItems, availableSpace, gapPx, basePitch)
                val (transX, transY) =
                    previewPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl)

                fArr[0] = transX
                fArr[1] = transY
            }

        // Close-animation "glitch" fix (William, Aug 2026), history:
        // Round 1 (v-something) suppressed the close-direction VIEW_ALPHA
        // fade that AOSP plays for every page icon NOT in the preview list,
        // and round 2 zeroed the per-icon stagger delay on the remaining
        // icon-view springs. Neither cured William's "order goes wrong then
        // fixes itself" close glitch. The v48/v49 FlashDebug captures proved
        // why: the REAL cause was the getGridPosition stride mismatch fixed
        // in v49 - icons were being handed duplicated/scrambled destinations,
        // so the closing morph visibly scrambled regardless of what the
        // springs played. With destinations now correct, round 1's
        // suppression is REVERTED (v50): AOSP's own design fades non-preview
        // icons out during close so they don't pile up or escape the folder
        // bounds, and its original "icons vanish then snap back" symptom was
        // an artifact of those same wrong destinations, not of the fade.
        // Round 2's stagger-zeroing stays, so everything on a closing icon
        // view (alpha included now) animates in lockstep.
        val bubbleTextViewClass = findClass(
            "com.android.launcher3.BubbleTextView",
            suppressError = true
        )
        val appPairIconClass = findClass(
            "com.android.launcher3.apppairs.AppPairIcon",
            suppressError = true
        )
        val folderSpringAnimatorSetFactoryClass = findClass(
            $$"com.android.launcher3.folder.FolderSpringAnimatorSet$Factory",
            suppressError = true
        )

        // William, Aug 2026, round 2: William confirmed the close glitch
        // ("app order is different for a second") is STILL there after
        // round 1's alpha-fade suppression alone. Root cause of the
        // remainder: the position/scale springs for EVERY per-icon view
        // (translateX, translateY, scale - not just the alpha fade) also
        // use this same staggered per-icon delay (iconDelay, confirmed via
        // decompiled source to spread up to size*5 ms apart based on
        // reading-order position - e.g. up to 80ms across a 16-item page).
        // So even with the extra fade gone, icons still started MOVING
        // toward their closed position at different times - some already
        // snapped into place while others hadn't budged yet, which reads
        // as "the order looks different for a second." Fix: zero the delay
        // argument for every closing-direction per-icon spring (not just
        // the alpha one), so every icon on the page animates in lockstep
        // instead of staggered by reading-order position. This still only
        // touches CLOSING + a per-icon view target - opening's stagger and
        // every non-icon animation (footer/name/scrim/workspace/hotseat)
        // are untouched, matching the same minimal-behavior-change
        // reasoning as round 1.
        folderSpringAnimatorSetFactoryClass
            ?.hookMethod("playSpringAnimation")
            ?.runBefore { param ->
                // playSpringAnimation(context, animatorSet, isOpening, delay,
                // stiffness, damping, fromValue, toValue, minVisibleChange,
                // floatProperty, target) - confirmed via decompiled source.
                val isOpening = param.args.getOrNull(2) as? Boolean ?: return@runBefore
                if (isOpening) return@runBefore // only the close direction glitched

                val target = param.args.getOrNull(10) ?: return@runBefore
                val isIconView = bubbleTextViewClass?.isInstance(target) == true ||
                    appPairIconClass?.isInstance(target) == true
                if (!isIconView) return@runBefore

                // Every per-icon view spring on close (alpha now included -
                // round 1's suppression was reverted in v50, see the comment
                // block above): keep playing it, just remove its stagger by
                // zeroing the delay so all icons animate in lockstep.
                val delay = param.args.getOrNull(3) as? Int
                if (delay != null && delay != 0) {
                    param.args[3] = 0
                }
            }

        // Real fix (William, Aug 2026, confirmed on-device) for "only ~4
        // icons animate, then the real 9 pop in a second later": the spring
        // animation builder (FolderAnimationSpringBuilderManager
        // .createAnimatorSet) reads PreviewItemManager.mCurrentPageParams
        // raw - and that list is EMPTY at animation-build time for both
        // open and close (v23 capture: mFirstPageParams.size=9 correct,
        // mCurrentPageParams.size=0). Ordinary drawing tolerates the empty
        // list (falls back to mFirstPageParams somewhere), which is why the
        // closed icon and fully-opened folder render correctly - but the
        // animator builder has no such fallback, so it built its per-icon
        // springs from an empty/short list. Fix: if mCurrentPageParams is
        // empty and mFirstPageParams isn't, copy the latter's contents into
        // the former before the animator set is built. A copy (new
        // ArrayList), not the same list reference, so later mutation of one
        // doesn't alias the other.
        val folderAnimationSpringBuilderManagerClass = findClass(
            "com.android.launcher3.folder.FolderAnimationSpringBuilderManager",
            suppressError = true
        )
        folderAnimationSpringBuilderManagerClass
            ?.hookMethod("createAnimatorSet")
            ?.runBefore { param ->
                val folder = param.thisObject.getFieldSilently("folder")
                val folderIcon = folder?.getFieldSilently("mFolderIcon")
                val previewItemManager = folderIcon?.getFieldSilently("mPreviewItemManager")
                val currentPageParams =
                    previewItemManager?.getFieldSilently("mCurrentPageParams") as? ArrayList<Any?>
                val firstPageParams =
                    previewItemManager?.getFieldSilently("mFirstPageParams") as? ArrayList<Any?>

                if (currentPageParams != null && currentPageParams.isEmpty() &&
                    !firstPageParams.isNullOrEmpty()
                ) {
                    previewItemManager.setFieldSilently(
                        "mCurrentPageParams",
                        ArrayList(firstPageParams)
                    )
                }

                // William, Sep 2026: record which page this open/close
                // animation is actually walking, matching AOSP's own choice
                // (decompiled createAnimatorSet: currentPage2 = isOpening ?
                // mContent.getCurrentPage() : mContent.getDestinationPage(),
                // no RTL adjustment - that's a separate flipped variable
                // used only for the reveal-rect math). See
                // lastAnimatedPageByRule above for why getGridPosition needs
                // this.
                val isOpening = param.args.getOrNull(0) as? Boolean
                val layoutRule = folderIcon?.callMethodSilently("getLayoutRule")
                val mContent = folder?.getFieldSilently("mContent")
                if (isOpening != null && layoutRule != null && mContent != null) {
                    val page = mContent.callMethodSilently(
                        if (isOpening) "getCurrentPage" else "getDestinationPage"
                    ) as? Int
                    if (page != null) {
                        lastAnimatedPageByRule[layoutRule] = page
                    }
                }
            }
    }
}