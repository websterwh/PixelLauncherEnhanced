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
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_PREVIEW_ICON_GAP_TEMP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_PREVIEW_ICON_SIZE_TEMP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_PREVIEW_ICON_SPACING_TEMP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.FOLDER_PREVIEW_SHAPE_TEMP
import com.drdisagree.pixellauncherenhanced.data.common.Constants.TEMP_MASTER_ENABLE
import com.drdisagree.pixellauncherenhanced.xposed.ModPack
import com.drdisagree.pixellauncherenhanced.xposed.mods.LauncherUtils.Companion.reloadLauncher
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.Helpers.toPx
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.XposedHook.Companion.findClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.callMethodSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.dumpClass
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.getStaticFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookConstructor
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.hookMethod
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.log
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setField
import com.drdisagree.pixellauncherenhanced.xposed.mods.toolkit.setFieldSilently
import com.drdisagree.pixellauncherenhanced.xposed.utils.XPrefs.Xprefs
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
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

    // TEMPORARY (William, Aug 2026): see FOLDER_PREVIEW_ICON_SIZE_TEMP /
    // FOLDER_PREVIEW_ICON_SPACING_TEMP in Constants.kt for removal
    // instructions. Both stored as percents so they're clean integer
    // sliders; divided down to 0.xx fractions here. Only applied to folders
    // with >= SCALE_SLIDER_MIN_ITEMS (7) preview items - see
    // scaleFactorFor()/sizeFactorFor() in handleLoadPackage().
    //
    // folderPreviewIconSpacingScale controls the grid pitch (how close
    // together the icons/cells sit - lower pulls the whole cluster toward
    // the center). folderPreviewIconSizeScale controls how much of each
    // cell's pitch the icon itself fills (independent of spacing, so the
    // icon can be made bigger/smaller without changing how tightly packed
    // the cluster is).
    // William, Aug 2026: defaults anchored to the real stock 4-icon preview
    // scale, derived from decompiled source rather than picked by feel.
    // PreviewItemManager.computePreviewDrawingParams() sets
    // mBaselineIconScale = previewSize / iconSize (previewSize is exactly
    // our "availableSpace"), and ClippedFolderIconLayoutRule.scaleForItem()
    // returns 0.44 * mBaselineIconScale for a 4-icon preview - so stock
    // always draws each of its 4 preview icons at exactly
    // 0.44 * availableSpace px, independent of icon size. Our own icon size
    // (iconFootprint) works out to
    // (availableSpace / max(cols, rows)) * spacingFactor * sizeFactor - so
    // treating stock's 4-icon case as an (effective) 2x2 grid
    // (max(cols,rows)=2) and solving for the product that reproduces
    // stock's 0.44 * availableSpace gives spacingFactor * sizeFactor =
    // 0.44 * 2 = 0.88. Split as sizeFactor=1.0 (icon fully fills its cell,
    // no extra shrink) and spacingFactor=0.88 (does the rest of the work),
    // matching how the sliders already read to William: "icon size" as a
    // fill-fraction knob, "spacing" as the cluster-tightness knob. William's
    // own hand-tuned values (100%/85%) landed within ~3% of this derived
    // 88% - close enough that this is really just a mathematical
    // confirmation of what he already found by eye.
    private var folderPreviewIconSpacingScale = 0.88f
    private var folderPreviewIconSizeScale = 1.0f

    // TEMPORARY (William, Aug 2026): third TEMP slider - an absolute dp gap
    // added purely to the DISTANCE between preview icons, independent of the
    // size (%) and spacing (%) sliders above, which both also indirectly
    // affect icon draw size. See gapPx usage in repositionPreviewItemAsGrid.
    // Default 2dp matches William's tuned value - purely a taste/spread
    // choice, not part of the stock-matching math above (which implies 0).
    private var folderPreviewIconGapTemp = 2

    // TEMPORARY (William, Aug 2026): which arrangement to use for >4-item
    // folder previews. "grid" is the original square-grid behavior; the
    // others are alternate shapes so William can compare them. See
    // positionForShape() in handleLoadPackage().
    private var folderPreviewShapeTemp = "grid"

    // TEMP tab master toggle (William, Aug 2026): when off, the whole custom
    // preview-repositioning system is skipped entirely (not just reset to
    // default slider values) so the folder falls back to AOSP's own,
    // un-adjusted preview layout - lets William A/B compare his adjustments
    // against stock instead of just seeing the sliders' "default" numbers,
    // which still went through our custom grid/shape math.
    private var tempModsEnabled = true

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
            tempModsEnabled = getBoolean(TEMP_MASTER_ENABLE, true)
            folderPreviewIconSpacingScale = getSliderInt(FOLDER_PREVIEW_ICON_SPACING_TEMP, 88) / 100f
            folderPreviewIconSizeScale = getSliderInt(FOLDER_PREVIEW_ICON_SIZE_TEMP, 100) / 100f
            folderPreviewIconGapTemp = getSliderInt(FOLDER_PREVIEW_ICON_GAP_TEMP, 2)
            folderPreviewShapeTemp = getListString(FOLDER_PREVIEW_SHAPE_TEMP, "grid") ?: "grid"
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
            FOLDER_PREVIEW_ICON_SPACING_TEMP,
            FOLDER_PREVIEW_ICON_SIZE_TEMP,
            FOLDER_PREVIEW_ICON_GAP_TEMP,
            FOLDER_PREVIEW_SHAPE_TEMP,
            TEMP_MASTER_ENABLE,
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

        // TEMPORARY (William, Aug 2026): diagnostic-only. v31's setContentSize
        // fix writes the right mCountX/mCountY (confirmed via FolderSizeDebug
        // logs - e.g. 4x4 for an 18-item folder), but William reports the
        // actual folder view still splits at 9 items per page no matter what
        // mCountX/mCountY end up as - meaning something ELSE decides the
        // items-per-page split, earlier than or separately from
        // setContentSize, and mCountX/mCountY only affect how many empty
        // grid slots get drawn on a page that's already been given its items.
        // Dumping FolderGridOrganizer's full declared surface (paced, same
        // flooding problem as the UninstallDebug dump) to find whatever
        // computes/caps the per-page item count. Remove this block once the
        // real pagination method is found and hooked.
        folderGridOrganizerClass?.let { clazz ->
            Thread {
                Thread.sleep(4500) // after the UninstallDebug dump's pacing window
                log("FolderSizeDebug", "Dumping FolderGridOrganizer (paced)...")
                log("FolderSizeDebug", "  extends: ${clazz.superclass?.name}")

                val ctors = clazz.declaredConstructors
                log("FolderSizeDebug", "  Constructors (${ctors.size}):")
                for (c in ctors) {
                    log("FolderSizeDebug", "    (${c.parameterTypes.joinToString { it.typeName }})")
                    Thread.sleep(15)
                }

                val declaredMethods = clazz.declaredMethods
                log("FolderSizeDebug", "  Declared methods (${declaredMethods.size}):")
                for (m in declaredMethods) {
                    log(
                        "FolderSizeDebug",
                        "    ${m.name}(${m.parameterTypes.joinToString { it.typeName }}): ${m.returnType.typeName}"
                    )
                    Thread.sleep(15)
                }

                val fields = clazz.declaredFields
                log("FolderSizeDebug", "  Declared fields (${fields.size}):")
                for (f in fields) {
                    log("FolderSizeDebug", "    ${f.name}: ${f.type.typeName}")
                    Thread.sleep(15)
                }

                log("FolderSizeDebug", "Done dumping FolderGridOrganizer.")
            }.start()
        }

        // TEMPORARY (William, Aug 2026): diagnostic-only. The PreviewItemManager
        // dump (confirmed via logcat, Aug 2026) found a zero-arg
        // recomputePreviewDrawingParams() method plus a mIcon: FolderIcon
        // field - i.e. this class can force-refresh the closed folder
        // icon's preview, which fits the working theory that the close
        // animation is morphing from a stale/default layout because
        // nothing re-computed it before the animation grabbed its start
        // state (see the fuller theory where scaleForItem is hooked,
        // below). Before wiring that in as a real fix, this logs whether
        // AOSP itself ever calls recomputePreviewDrawingParams() around an
        // open/close cycle - if it never fires, that confirms the preview
        // genuinely goes stale and calling it ourselves before close is the
        // right move; if it already fires and the flash still happens, the
        // bug is elsewhere. Remove this hook once diagnosed.
        previewItemManagerClass
            .hookMethod("recomputePreviewDrawingParams")
            .runBefore { log("FlashDebug", "PreviewItemManager.recomputePreviewDrawingParams() called") }

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

                log(
                    "FolderSizeDebug",
                    "setFolderInfo() -> wrote mMaxCountX=$newMaxCols mMaxCountY=$newMaxRows " +
                        "mMaxItemsPerPage=${if (newMaxCols != null && newMaxRows != null) newMaxCols * newMaxRows else "?"}"
                )
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

                log(
                    "FolderSizeDebug",
                    "setContentSize(itemCount=$itemCount) AOSP natural=$currentCols x $currentRows " +
                        "configured=$folderGridColumns x $folderGridRows -> wrote $newCols x $newRows"
                )
            }

        folderGridOrganizerClass
            .hookMethod("previewItemsForPage")
            .runAfter { param ->
                if (folderPreviewIconCount == 0) return@runAfter
                val page = param.args.getOrNull(0) as? Int ?: return@runAfter
                if (page != 0) return@runAfter // only override the closed-icon preview (page 0)

                val contents = param.args.getOrNull(1) as? List<*> ?: return@runAfter
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

        // Only folders with at least this many preview items are affected by
        // the two user-adjustable sliders below. Folders with 5-6 items still
        // get the grid layout (AOSP's circular math still can't handle them),
        // just packed at fixed default tightness/size instead.
        val SCALE_SLIDER_MIN_ITEMS = 7
        // William, Aug 2026: matches the new stock-anchored slider defaults
        // (see folderPreviewIconSpacingScale/folderPreviewIconSizeScale)
        // rather than the arbitrary previous 0.82/0.9.
        val DEFAULT_SPACING_FACTOR = 0.88f
        val DEFAULT_SIZE_FACTOR = 1.0f
        // William, Aug 2026: matches the gap slider's own default value (2dp)
        // - see the effectiveGapPx() fix below for why this exists.
        val DEFAULT_GAP_DP = 2

        // How close together the cells sit (cluster tightness). Lowering
        // this pulls the whole cluster toward the folder's center.
        //
        // William, Aug 2026: TEMP master toggle off now falls back to
        // DEFAULT_SPACING_FACTOR/DEFAULT_SIZE_FACTOR (our own tuned
        // defaults) rather than skipping this whole custom system and
        // letting AOSP's OWN untouched math run for curNumItems>4. AOSP's
        // native formula (getPosition()/scaleForItem() in the decompiled
        // ClippedFolderIconLayoutRule.java) was never built for more than 4
        // preview icons - scaleForItem() returns the SAME 0.44x scale for
        // any count >=4, so at e.g. 9 items it draws each icon exactly as
        // large as it would for 4, spread around a ring whose radius also
        // doesn't grow to compensate - a severely overlapping, "everything
        // crammed with no visible gaps" mess is exactly what "sets it to
        // like 0" was describing. Falling back to our own DEFAULT-tuned
        // grid instead of raw AOSP is what actually gives a sane look with
        // the toggle off.
        fun spacingFactorFor(curNumItems: Int): Float = when {
            !tempModsEnabled -> DEFAULT_SPACING_FACTOR
            curNumItems >= SCALE_SLIDER_MIN_ITEMS -> folderPreviewIconSpacingScale
            else -> DEFAULT_SPACING_FACTOR
        }

        // How much of each cell's pitch the icon itself fills, independent
        // of spacing - so icon size can be tuned without changing how
        // tightly packed the cluster is.
        fun sizeFactorFor(curNumItems: Int): Float = when {
            !tempModsEnabled -> DEFAULT_SIZE_FACTOR
            curNumItems >= SCALE_SLIDER_MIN_ITEMS -> folderPreviewIconSizeScale
            else -> DEFAULT_SIZE_FACTOR
        }

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

        // William, Aug 2026: TEMP master toggle off previously fell back to a
        // hardcoded 0dp gap here - but the gap SLIDER's own default is 2dp
        // (see folderPreviewIconGapTemp/temp_mods.xml), not 0. So even with
        // every slider left at its default value, toggle-ON used 2dp while
        // toggle-OFF forced 0dp - a real, confirmed (William: "with it on
        // and settings at default it should be the same as off... doesn't
        // make sense") residual mismatch, on top of the already-fixed
        // spacingFactorFor/sizeFactorFor defaults. Falls back to
        // DEFAULT_GAP_DP instead of 0, matching that pattern exactly.
        fun effectiveGapPx(): Float =
            mContext.toPx(if (tempModsEnabled) folderPreviewIconGapTemp else DEFAULT_GAP_DP).toFloat()

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

        // Evenly spaced around a ring instead of a square grid. Radius grows
        // with the spacing slider (same role it plays for the grid shape)
        // plus the flat gap, capped so icons stay within availableSpace.
        fun circlePosition(
            index: Int,
            curNumItems: Int,
            availableSpace: Float,
            iconFootprint: Float,
            gapPx: Float,
            isRtl: Boolean
        ): Pair<Float, Float> {
            val center = availableSpace / 2f
            val maxRadius = ((availableSpace - iconFootprint) / 2f).coerceAtLeast(0f)
            val radius = (maxRadius * spacingFactorFor(curNumItems) + gapPx).coerceAtMost(maxRadius)
            val direction = if (isRtl) -1.0 else 1.0
            val angle = direction * (2.0 * PI * index / curNumItems) - PI / 2.0
            val x = center + (radius * cos(angle)).toFloat() - iconFootprint / 2f
            val y = center + (radius * sin(angle)).toFloat() - iconFootprint / 2f
            return x to y
        }

        // Concentric hex rings around a filled center cell (ring 0 = 1 cell,
        // ring k = 6k cells; cumulative through ring k = 1+3k(k+1)) - a
        // "honeycomb/flower" cluster instead of a square grid or single
        // ring. William, Aug 2026: requested after "circle" (a single even
        // ring) turned out not to be what he meant by an octagon/hexagon
        // arrangement - this is a closer match to an actual honeycomb look.
        // Items within a ring are still evenly spaced by angle (not true
        // axial hex-cell placement) - simpler, and visually reads the same
        // for a folder preview's purposes.
        // William, Aug 2026, v2 - two fixes from real-device testing of the
        // first version:
        // 1. Reading order was "center first, then spiral clockwise from
        //    the top" - mathematically consistent (verified index-by-index
        //    against William's own on-device reports) but not what he
        //    expects: app order should read top row left-to-right, then
        //    center, then bottom row left-to-right - the same order he'd
        //    read a real row of icons in. Fixed by generating every
        //    candidate slot's (x, y) same as before, then SORTING those
        //    slots by (y, then x) before assigning reading-order indices to
        //    them - top-to-bottom, left-to-right, regardless of which ring
        //    a slot belongs to.
        // 2. Above 7 items (i.e. once a 2nd ring is needed), the ring
        //    pitch was sized as availableSpace/(2*maxRing+1) - shrinking
        //    every ring's spacing just because a further-out ring existed,
        //    even one barely populated (e.g. maxRing jumps to 2 the moment
        //    curNumItems is 8, for just a single 2nd-ring icon) - while
        //    iconFootprint (icon draw size) came from the unrelated
        //    square-grid pitch and didn't shrink to match, so icons ended
        //    up closer together than they are wide: the "glitched stacked"
        //    look. Fixed by sizing ring spacing directly off iconFootprint
        //    + gapPx instead (same idea "list" shape already uses) so
        //    adjacent rings can never be closer than the icons themselves.
        // Number of concentric hex rings needed to hold curNumItems (ring 0 =
        // 1 center cell, ring k adds 6k cells). Shared by honeycombPosition
        // (arrangement) and iconFootprintFor (sizing) so the two can never
        // disagree on how many rings a given item count needs.
        fun honeycombRingsNeeded(curNumItems: Int): Int {
            var maxRing = 0
            var cumulative = 1
            while (cumulative < curNumItems) {
                maxRing++
                cumulative += 6 * maxRing
            }
            return maxRing
        }

        // William, Aug 2026, v4: reverted v3's single-ring "daisy" layout -
        // William confirmed it doesn't read as honeycomb ("try and keep it
        // a honeycomb shape"), so this goes back to true concentric hex
        // rings (fixed 6 cells in ring 1, 12 in ring 2, etc. - the actual
        // honeycomb/flower structure) for the ARRANGEMENT. What's kept from
        // v3 is only the sizing fix in honeycombIconFootprint below, which
        // is what actually solved "renders outside the folder" - shrinking
        // icons enough that ring 2 (needed at 8+ items) still fits inside
        // availableSpace, rather than reusing the unrelated square-grid
        // icon size. So: real honeycomb shape, correctly bounded size.
        // William, Aug 2026, v5: fixed slot SELECTION above 7 items (the v48
        // HoneycombDebug log confirmed this exactly). The old code generated
        // ALL slots for maxRing rings, sorted every one of them by (y, x),
        // then handed reading-order indices to the first curNumItems slots in
        // sort order. Once a 2nd ring exists (curNumItems >= 8), ring-2's top
        // cells sit at very negative y and dominate the head of the sort - so
        // the center cell (y=0) and everything below it never got filled:
        // Social's 9-item preview drew at (relative to center) y offsets
        // [-2p, -1.73p x2, -1.73p/-p/-p, -0.5p x2, 0-far-left] with the true
        // center empty and the whole bottom half unused, matching William's
        // "clustered upper-left" screenshot. Fix: SELECT slots ring-by-ring
        // inner-first (ring 0 center, then each ring's cells clockwise from
        // 12 o'clock) until curNumItems slots are chosen, and only THEN sort
        // just those chosen slots by (round(y), x) for reading-order index
        // assignment. For <= 7 items the chosen set is identical to before
        // (rings 0+1 fill exactly 7) so the confirmed-good case is unchanged;
        // above 7 you get complete inner rings plus a partially-filled outer
        // ring - a centered staggered honeycomb that 9 icons fit in.
        fun honeycombPosition(
            index: Int,
            curNumItems: Int,
            availableSpace: Float,
            iconFootprint: Float,
            gapPx: Float,
            isRtl: Boolean
        ): Pair<Float, Float> {
            val maxRing = honeycombRingsNeeded(curNumItems)

            val cellPitch = iconFootprint + gapPx
            val direction = if (isRtl) -1.0 else 1.0

            data class Slot(val x: Float, val y: Float)

            val chosen = ArrayList<Slot>(curNumItems)
            if (curNumItems > 0) {
                chosen.add(Slot(0f, 0f)) // ring 0: center
            }
            var remaining = curNumItems - chosen.size
            var ring = 1
            while (remaining > 0 && ring <= maxRing) {
                val cellsInRing = 6 * ring
                val radius = ring * cellPitch
                val take = min(remaining, cellsInRing)
                for (pos in 0 until take) {
                    val angle = direction * (2.0 * PI * pos / cellsInRing) - PI / 2.0
                    chosen.add(
                        Slot((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
                    )
                }
                remaining -= take
                ring++
            }

            // Round y before sorting so slots meant to be in the same row
            // don't get split apart by float rounding noise.
            //
            // William, Aug 2026, v50: one refinement from on-device testing of
            // v49 ("the last two icons that show, bottom middle and bottom
            // right, appear to be flipped"). Strict (y, x) ordering breaks for
            // STAGGERED bottoms: with 9 chosen slots the bottom band is
            // ring-1's lower pair at y=+0.5*pitch plus ring-1's bottom cell at
            // y=+1.0*pitch sitting horizontally BETWEEN them - visually one
            // row reading left/middle/right, but strict y puts the middle
            // cell LAST, swapping the final two icons. Fix: group slots into
            // exact-y bands as before, then if the LAST band is a singleton
            // hanging less than 0.6*cellPitch below the band above it, merge
            // it into that band and re-sort by x. The 7-item case is
            // untouched (its last band is a PAIR, not a singleton), and the
            // top edge keeps strict order (William confirmed the 9-icon top
            // reads correctly as-is).
            val strictOrder = chosen.sortedWith(compareBy({ (it.y * 100f).roundToInt() }, { it.x }))
            val bands = LinkedHashMap<Int, MutableList<Slot>>()
            for (slot in strictOrder) {
                bands.getOrPut((slot.y * 100f).roundToInt()) { mutableListOf() }.add(slot)
            }
            if (bands.size >= 2) {
                val bandKeys = bands.keys.toList()
                val lastBand = bands[bandKeys[bandKeys.size - 1]]!!
                val prevBand = bands[bandKeys[bandKeys.size - 2]]!!
                val dropBelowPrev = lastBand[0].y - (prevBand.minOf { it.y })
                if (lastBand.size == 1 && dropBelowPrev in 0f..(0.6f * cellPitch)) {
                    prevBand.add(lastBand[0])
                    prevBand.sortBy { it.x }
                    bands.remove(bandKeys[bandKeys.size - 1])
                }
            }
            val sorted = bands.values.flatten()
            val slot = sorted.getOrNull(index) ?: sorted.last()

            val center = availableSpace / 2f
            val halfIcon = iconFootprint / 2f
            return (center + slot.x - halfIcon) to (center + slot.y - halfIcon)
        }

        // Single place both the closed-preview draw path
        // (repositionPreviewItemAsGrid) and the open/close animation path
        // (the getGridPosition hook below) pick a shape from, so the two
        // can never drift out of sync with each other again the way the
        // animation used to silently ignore custom shapes entirely.
        //
        // William, Aug 2026: TEMP master toggle off keeps whatever shape is
        // selected (only spacingFactorFor/sizeFactorFor/effectiveGapPx fall
        // back to defaults) - it previously forced "grid" here regardless
        // of the chosen shape, so toggling off while Honeycomb (or Circle/
        // List) was selected silently switched the arrangement itself,
        // which read as "spacing/sizing changed" even though the shape
        // change was the real difference.
        fun previewPosition(
            index: Int,
            curNumItems: Int,
            availableSpace: Float,
            iconFootprint: Float,
            gapPx: Float,
            isRtl: Boolean
        ): Pair<Float, Float> = when (folderPreviewShapeTemp) {
            "circle" -> circlePosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl)
            "list" -> gridPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl, forcedCols = 2)
            "honeycomb" -> honeycombPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl)
            else -> gridPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl, forcedCols = null)
        }

        // William, Aug 2026, v4: back to true concentric hex rings for the
        // SHAPE (see honeycombPosition above), but this sizing fix from v3
        // is kept - it's what actually solved "renders outside the folder"
        // above 7 items. Every other shape (grid/circle/list) draws its
        // icons at the square-grid's own footprint (basePitch *
        // sizeFactorFor) - fine for them, since their arrangement's own
        // extent is already bounded by availableSpace. Honeycomb's ring
        // layout is NOT bounded the same way: reusing the square-grid
        // footprint meant ring 2 (needed once curNumItems >= 8) could have
        // a radius (2 * (iconFootprint + gapPx)) far exceeding
        // availableSpace/2, pushing those icons outside the folder
        // entirely. Fix: solve for the largest iconFootprint such that the
        // OUTERMOST ring's icon edge still lands inside availableSpace -
        // maxRing * (iconFootprint + gapPx) + iconFootprint / 2 <=
        // availableSpace / 2 - then take whichever is SMALLER of that and
        // the ordinary square-grid footprint, so the already-confirmed
        // -working single-ring case (<=7 items) is completely unchanged
        // (the square-grid footprint is already the tighter bound there) -
        // only the 2-plus-ring case (8+ items) actually shrinks, exactly as
        // much as needed to stay inside the folder and no more.
        fun honeycombIconFootprint(curNumItems: Int, availableSpace: Float, gapPx: Float): Float {
            val maxRing = honeycombRingsNeeded(curNumItems)
            if (maxRing < 2) return Float.MAX_VALUE // no honeycomb-specific cap needed yet

            return ((availableSpace / 2f - maxRing * gapPx) / (maxRing + 0.5f))
                .coerceAtLeast(0f)
        }

        fun iconFootprintFor(
            curNumItems: Int,
            availableSpace: Float,
            gapPx: Float,
            basePitch: Float
        ): Float {
            val gridFootprint = basePitch * sizeFactorFor(curNumItems)
            if (folderPreviewShapeTemp != "honeycomb") return gridFootprint

            val maxFootprint = honeycombIconFootprint(curNumItems, availableSpace, gapPx)
            return min(gridFootprint, maxFootprint * sizeFactorFor(curNumItems))
        }

        fun repositionPreviewItemAsGrid(
            rule: Any,
            index: Int,
            curNumItems: Int,
            resultParams: Any?
        ) {
            lastCurNumItemsByRule[rule] = curNumItems
            if (index < 0 || index >= curNumItems || curNumItems <= 4) return
            // TEMP master toggle off no longer bails out here - see the
            // !tempModsEnabled branches inside spacingFactorFor/
            // sizeFactorFor/previewPosition above. This function always
            // runs our own grid math now (default-tuned when the toggle is
            // off, user-tuned when it's on) instead of ever falling back to
            // AOSP's own math, which isn't built for curNumItems>4.

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
                if (curNumItems > 4) {
                    log("FlashDebug", "computePreviewItemDrawingParams[rule] index=$index curNumItems=$curNumItems")
                }
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
                if (curNumItems > 4) {
                    log("FlashDebug", "computePreviewItemDrawingParams[fallback] index=$index curNumItems=$curNumItems")
                }
                if (index < 0) return@runAfter // workspace destroy/create animation, not a grid slot
                if (curNumItems <= 4) return@runAfter
                // TEMPORARY (William, Aug 2026): HoneycombDebug diagnostics for
                // Bug 1 ("Social" folder honeycomb renders clustered in the
                // upper-left above 7 items). For every grid-slot call this
                // prints index, curNumItems, what AOSP's own method body just
                // computed into the result params BEFORE our override runs
                // (for index >= 4 that should be AOSP's corner-stacking point,
                // i.e. all three values identical), and what
                // repositionPreviewItemAsGrid left behind AFTER - so the log
                // directly shows (a) the folder's real curNumItems, (b) whether
                // any grid slot is skipped or silently not overridden (post ==
                // pre means repositionPreviewItemAsGrid bailed early for that
                // index, e.g. null/zero mAvailableSpace). Capture with:
                //   adb logcat -d -s HoneycombDebug:V > log.txt
                // Remove this whole block once Bug 1 is confirmed fixed.
                val preOverrideX = param.result?.getFieldSilently("transX")
                val preOverrideY = param.result?.getFieldSilently("transY")
                val preOverrideScale = param.result?.getFieldSilently("scale")
                repositionPreviewItemAsGrid(rule, index, curNumItems, param.result)
                log(
                    "HoneycombDebug",
                    "computePreview[index=$index curNumItems=$curNumItems] " +
                        "aospPre=(x=$preOverrideX, y=$preOverrideY, scale=$preOverrideScale) -> " +
                        "oursPost=(x=${param.result?.getFieldSilently("transX")}, " +
                        "y=${param.result?.getFieldSilently("transY")}, " +
                        "scale=${param.result?.getFieldSilently("scale")})"
                )
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
                // TEMP master toggle off no longer bails out here - see
                // spacingFactorFor/sizeFactorFor's !tempModsEnabled branch.

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
                log("FlashDebug", "scaleForItem -> curNumItems=$curNumItems pitch=$pitch scale=$result")
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
                // TEMP master toggle off no longer bails out here either -
                // see spacingFactorFor/sizeFactorFor/previewPosition's
                // !tempModsEnabled branches.
                val curNumItems = lastCurNumItemsByRule[param.thisObject] ?: return@runAfter
                if (curNumItems <= 4) return@runAfter

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
                if (indexInBounds != index) {
                    log(
                        "FlashDebug",
                        "getGridPosition: index=$indexInBounds out of range for " +
                            "curNumItems=$curNumItems (row=$row col=$col callerCols=$callerCols) - " +
                            "clamping to $index"
                    )
                }

                val gapPx = effectiveGapPx()
                val iconFootprint = iconFootprintFor(curNumItems, availableSpace, gapPx, basePitch)
                val (transX, transY) =
                    previewPosition(index, curNumItems, availableSpace, iconFootprint, gapPx, isRtl)

                val aospX = fArr[0]
                val aospY = fArr[1]
                fArr[0] = transX
                fArr[1] = transY
                log(
                    "FlashDebug",
                    "getGridPosition(row=$row, col=$col) curNumItems=$curNumItems " +
                        "callerCols=$callerCols previewCols=$previewCols index=$index -> " +
                        "aosp=($aospX, $aospY) ours=($transX, $transY)"
                )
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

        // TEMPORARY (William, Aug 2026) - diagnostic logging for the open/close
        // flash investigation only. Every line this block adds is tagged
        // "FlashDebug" so it's easy to find and delete once the flash is
        // confirmed fixed (delete this whole block, and the two log() calls
        // added above in the computePreviewItemDrawingParams hooks and the
        // scaleForItem hook). To capture a timeline:
        //   adb shell am force-stop com.google.android.apps.nexuslauncher
        //   adb logcat | findstr FlashDebug
        // then open, then close, one folder with 7+ preview icons.
        val folderClass = findClass(
            "com.android.launcher3.folder.Folder",
            suppressError = true
        )
        folderClass
            ?.hookMethod("animateOpen")
            ?.runBefore { log("FlashDebug", "Folder.animateOpen() start") }
        folderClass
            ?.hookMethod("closeComplete")
            ?.runBefore { log("FlashDebug", "Folder.closeComplete() start") }

        // animateClosed() is confirmed "Method not found" on Folder in this
        // build (a v15 diagnostic class dump showed animateOpen/closeComplete
        // both still exist, but no animateClosed at all - it's gone, not just
        // renamed-and-still-1-arg). The dump's closest candidate by shape is
        // handleClose(boolean) - unverified whether it plays the same role,
        // so this is still just a log marker, not a functional hook, until
        // the FlashDebug timeline confirms it fires at the right point in
        // the close sequence.
        folderClass
            ?.hookMethod("handleClose")
            ?.runBefore { log("FlashDebug", "Folder.handleClose() start") }

        // TEMPORARY (William, Aug 2026): diagnostic-only. The v15 dump also
        // showed FolderAnimationManager - the class our whole open/close
        // flash theory (scaleForItem -> getAnimator) was built on - doesn't
        // exist on this device at all. Folder.getFolderAnimationManager()
        // actually returns a com.android.launcher3.folder.
        // FolderAnimationSpringBuilderManager instead (presumably this
        // Pixel Launcher version switched the folder open/close animation
        // to spring physics instead of a single Animator). This dumps that
        // real class once so its actual method surface is known before
        // attempting a real fix for the flash. Remove this whole block once
        // that's done.
        var folderAnimationManagerDumped = false
        folderClass
            ?.hookMethod("getFolderAnimationManager")
            ?.runAfter { param ->
                if (folderAnimationManagerDumped) return@runAfter
                folderAnimationManagerDumped = true
                log("FlashDebug", "getFolderAnimationManager() returned ${param.result?.javaClass?.name}")
                param.result?.javaClass.dumpClass()
            }

        // TEMPORARY (William, Aug 2026): diagnostic-only. The v16 dump of
        // FolderAnimationSpringBuilderManager (confirmed via logcat) showed
        // exactly one non-Object method: createAnimatorSet(boolean) ->
        // AnimatorSet. That's the real thing driving the open/close visual -
        // this logs what it actually built (whether isOpening, and what each
        // child Animator's target is) so the flash mechanism can be
        // understood before attempting a real fix, instead of guessing. The
        // scaleForItem values feeding into it are already confirmed correct
        // (see the scaleForItem log above), so if the flash is still visible
        // the mismatch has to be somewhere in here - e.g. animating a
        // snapshot/drawable captured before our repositioning ran, rather
        // than the live preview. Remove this whole block once diagnosed.
        val folderAnimationSpringBuilderManagerClass = findClass(
            "com.android.launcher3.folder.FolderAnimationSpringBuilderManager",
            suppressError = true
        )
        folderAnimationSpringBuilderManagerClass
            ?.hookMethod("createAnimatorSet")
            ?.runAfter { param ->
                val isOpening = param.args.getOrNull(0)
                val animatorSet = param.result as? android.animation.AnimatorSet
                // TEMPORARY (William, Aug 2026): added startDelay/duration to
                // this existing log line. v24 fixed the empty-mCurrentPageParams
                // issue (confirmed: the mid-animation frame now shows icons
                // still in the correct grid layout, shrinking, instead of the
                // old 4-icon diamond), but William still sees 4 icons appear
                // first and the rest "catch up" about a second later on open.
                // getStartDelay()/getDuration() are standard Animator methods -
                // no field-name guessing needed - and will show directly
                // whether some children are scheduled to start much later than
                // others, which would explain a staggered per-icon entrance
                // tuned for AOSP's max-4 assumption. Remove once diagnosed.
                val children = animatorSet?.childAnimations?.joinToString(", ") { anim ->
                    val target = (anim as? android.animation.ObjectAnimator)?.target
                    "${anim.javaClass.simpleName}(target=${target?.javaClass?.name}, " +
                        "propertyName=${(anim as? android.animation.ObjectAnimator)?.propertyName}, " +
                        "startDelay=${anim.startDelay}, duration=${anim.duration})"
                } ?: "null"
                log(
                    "FlashDebug",
                    "createAnimatorSet(isOpening=$isOpening) childCount=" +
                        "${animatorSet?.childAnimations?.size} totalDuration=${animatorSet?.duration} " +
                        "children=[$children]"
                )
            }

        // TEMPORARY (William, Aug 2026): diagnostic-only. The childCount from
        // the hook above (~50-51) didn't visibly change between two separate
        // opens of the same 9-item folder, which doesn't tell us whether it
        // scales with item count at all - and those animators are all
        // untargeted ValueAnimators (invisible closures), so we can't tell
        // from the outside whether they're driving per-icon positions or
        // something item-count-independent (e.g. the background blob shape).
        // PreviewItemManager (already confirmed via dump: fields mNumOfPrevItems,
        // mCurrentPageParams, mFirstPageParams) is the object that actually
        // knows how many preview items exist - if mNumOfPrevItems is stuck at
        // 4 right when the animator set is built, that's the real smoking gun
        // for "why only 4 icons animate." Reached via the already-verified
        // chain FolderAnimationSpringBuilderManager.folder (Folder) ->
        // Folder.mFolderIcon (FolderIcon) -> FolderIcon.mPreviewItemManager
        // (PreviewItemManager) - mFolderIcon/mPreviewItemManager are standard
        // AOSP field names; mPreviewItemManager is the same field the
        // existing computePreviewItemDrawingParams[fallback] hook above
        // already reaches successfully via mIcon.mPreviewLayoutRule (mIcon is
        // PreviewItemManager's back-reference to FolderIcon - same object,
        // opposite direction). Remove this whole hook once diagnosed.
        // Candidate real fix (William, Aug 2026), replacing the previous
        // log-only version of this hook. The v23 capture confirmed
        // mFirstPageParams.size=9 (correct) but mCurrentPageParams.size=0
        // (empty) right when createAnimatorSet() is about to build - for
        // BOTH the open and close animation. Ordinary drawing evidently
        // tolerates an empty mCurrentPageParams (falls back to
        // mFirstPageParams somewhere, e.g. in drawParams()), which is why
        // the closed icon and the fully-opened folder both render
        // correctly - but the spring animation builder likely reads the raw
        // mCurrentPageParams field directly with no such fallback, so it's
        // building its per-icon springs from an empty/short list. That
        // matches the symptom exactly: only ~4 icons animate, then the real
        // 9 "pop in" once a real draw() pass runs after the animation ends.
        // Fix: if mCurrentPageParams is empty and mFirstPageParams isn't,
        // copy mFirstPageParams's contents into mCurrentPageParams before
        // the animator set is built. A copy (new ArrayList), not the same
        // list reference, so later mutation of one doesn't alias the other.
        // Logging is kept so this same capture also shows whether the copy
        // took effect. Remove the logging once this is confirmed fixed;
        // keep the copy itself if it is.
        folderAnimationSpringBuilderManagerClass
            ?.hookMethod("createAnimatorSet")
            ?.runBefore { param ->
                val folder = param.thisObject.getFieldSilently("folder")
                val folderIcon = folder?.getFieldSilently("mFolderIcon")
                val previewItemManager = folderIcon?.getFieldSilently("mPreviewItemManager")
                val numOfPrevItems = previewItemManager?.getFieldSilently("mNumOfPrevItems")
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

                val currentPageParamsAfter =
                    previewItemManager?.getFieldSilently("mCurrentPageParams") as? ArrayList<*>
                log(
                    "FlashDebug",
                    "createAnimatorSet about to build: previewItemManager=" +
                        "${previewItemManager != null} mNumOfPrevItems=$numOfPrevItems " +
                        "mCurrentPageParams.size(before)=${currentPageParams?.size} " +
                        "mCurrentPageParams.size(after)=${currentPageParamsAfter?.size} " +
                        "mFirstPageParams.size=${firstPageParams?.size}"
                )
            }

        // TEMPORARY (William, Aug 2026): diagnostic-only. The v25 capture
        // showed the animation itself finishes in well under a second
        // (createAnimatorSet's own animators top out around 400-450ms,
        // matching the animateOpen()->createAnimatorSet and handleClose()->
        // closeComplete() timestamps), so the ~1-second "shows 4, then
        // corrects to 9" William still sees on CLOSE must be happening AFTER
        // closeComplete() - in however the closed folder icon gets redrawn
        // afterward, which nothing so far has been watching. draw() is a
        // confirmed real method on PreviewItemManager (from the v16 class
        // dump). This logs every actual draw call's item-count snapshot, so
        // we can see when a draw with the correct count finally happens
        // after close, and what (if anything) precedes it. Remove once
        // diagnosed.
        previewItemManagerClass
            ?.hookMethod("draw")
            ?.runBefore { param ->
                val currentPageParams =
                    param.thisObject.getFieldSilently("mCurrentPageParams") as? ArrayList<*>
                val firstPageParams =
                    param.thisObject.getFieldSilently("mFirstPageParams") as? ArrayList<*>
                val numOfPrevItems = param.thisObject.getFieldSilently("mNumOfPrevItems")
                log(
                    "FlashDebug",
                    "PreviewItemManager.draw() mNumOfPrevItems=$numOfPrevItems " +
                        "mCurrentPageParams.size=${currentPageParams?.size} " +
                        "mFirstPageParams.size=${firstPageParams?.size}"
                )
            }
    }
}