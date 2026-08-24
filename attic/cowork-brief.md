# Task: Fork PixelLauncherEnhanced, add "Dock columns" setting, build APK

## Goal
Add an independent "Dock columns" slider to the PixelLauncherEnhanced Xposed
module, so the number of icons shown in the Pixel Launcher's bottom dock
(hotseat) can be increased by 1 (or any amount) without being tied to the
homescreen grid column count. Then build a debug APK.

## Source repo
`https://github.com/Mahmud0808/PixelLauncherEnhanced` (Kotlin, Xposed module,
Gradle/AGP project, Kotlin toolchain 17, compileSdk 36, minSdk 26).

## Steps
1. Fork/clone the repo locally.
2. Apply the code changes below (already written and verified logically
   correct against the current `master` branch as of Aug 2026 — I already
   did this once in a sandbox, just couldn't finish an Android SDK build
   there).
3. Build with `./gradlew assembleDebug` (needs JDK 17+ and Android SDK
   compileSdk 36 / matching build-tools — accept SDK licenses if prompted).
4. Output APK lands in `app/build/outputs/apk/debug/`.
5. Give me the resulting APK file and a short diff summary when done.

## Background / why this works
The repo already hooks the exact Launcher3 field that controls dock icon
count (`numShownHotseatIcons` / `numHotseatIcons` / `numDatabaseHotseatIcons`
on `DeviceProfile` / `InvariantDeviceProfile`, plus `mHotseatProfile`). It's
just currently hardcoded to always mirror the "Homescreen columns" slider
value — there's no independent dock setting. The fix decouples them: add a
new `dockColumns` slider, and everywhere the code previously wrote
`homeScreenGridColumns` into a hotseat/dock field, write a new
`effectiveDockColumns` value instead (which uses the new dock slider if set,
otherwise falls back to the homescreen column count — so behavior for
existing users who never touch the new setting is unchanged).

## Exact changes (apply as a patch, or recreate by hand)

### 1. `app/src/main/java/com/drdisagree/pixellauncherenhanced/data/common/Constants.kt`
Add a new constant right after `DESKTOP_GRID_COLUMNS`:
```kotlin
const val DESKTOP_GRID_COLUMNS = "xposed_desktopgridcolumns"
const val DESKTOP_DOCK_COLUMNS = "xposed_desktopdockcolumns"
```

### 2. `app/src/main/res/values/strings.xml`
Add a new string near `homescreen_columns_title` / `homescreen_rows_title`:
```xml
<string name="homescreen_columns_title">Homescreen columns</string>
<string name="homescreen_rows_title">Homescreen rows</string>
<string name="dock_columns_title">Dock columns</string>
```

### 3. `app/src/main/res/xml/home_screen_mods.xml`
Add a new `SliderPreference` right after the existing "Homescreen rows"
slider block (which ends with `app:tickInterval="1" />`), still inside the
same `PreferenceCategory` (`xposed_home_screen_interface`):
```xml
<com.drdisagree.pixellauncherenhanced.ui.preferences.SliderPreference
    android:key="xposed_desktopdockcolumns"
    android:title="@string/dock_columns_title"
    app:defaultValue="0"
    app:iconSpaceReserved="false"
    app:maxVal="10"
    app:minVal="0"
    app:showDefaultIndicator="true"
    app:hideValueOnDefault="true"
    app:showResetButton="true"
    app:tickInterval="1" />
```

### 4. `app/src/main/java/com/drdisagree/pixellauncherenhanced/xposed/mods/GridOptions.kt`
Full set of edits:

**a) Add import:**
```kotlin
import com.drdisagree.pixellauncherenhanced.data.common.Constants.DESKTOP_DOCK_COLUMNS
```

**b) Add field + computed property**, right after the existing field
declarations at the top of the class:
```kotlin
private var homeScreenGridRows = 0
private var homeScreenGridColumns = 0
private var dockColumns = 0
private var appDrawerGridColumns = 0
private var appDrawerGridRowHeightMultiplier = 1f

// The dock (Hotseat) column count to apply. Falls back to the homescreen
// column count when no explicit dock value is set, to preserve the
// original coupled behavior. Returns 0 (no-op) when neither is set.
private val effectiveDockColumns: Int
    get() = if (dockColumns != 0) dockColumns else homeScreenGridColumns
```

**c) In `updatePrefs()`**, read the new pref and react to its key:
```kotlin
override fun updatePrefs(vararg key: String) {
    Xprefs.apply {
        homeScreenGridRows = getSliderInt(DESKTOP_GRID_ROWS, 0)
        homeScreenGridColumns = getSliderInt(DESKTOP_GRID_COLUMNS, 0)
        dockColumns = getSliderInt(DESKTOP_DOCK_COLUMNS, 0)
        appDrawerGridColumns = getSliderInt(APP_DRAWER_GRID_COLUMNS, 0)
        appDrawerGridRowHeightMultiplier =
            getSliderFloat(APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER, 10f) / 10f
    }

    when (key.firstOrNull()) {
        DESKTOP_GRID_ROWS,
        DESKTOP_GRID_COLUMNS,
        DESKTOP_DOCK_COLUMNS,
        APP_DRAWER_GRID_COLUMNS,
        APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER -> reloadLauncher(mContext)
    }
}
```

**d) In `handleLoadPackage()` → `hookDeviceProfile()` local function**,
replace every use of `homeScreenGridColumns` for hotseat/dock fields with
`effectiveDockColumns`:
```kotlin
fun Any.hookDeviceProfile() {
    val temp = getFieldSilently("iconSizePx") as? Int
    val mHotseatProfile = getFieldSilently("mHotseatProfile")
    val mDisplayOptionSpec = getFieldSilently("mDisplayOptionSpec")

    if (effectiveDockColumns != 0) {
        setFieldSilently("numShownHotseatIcons", effectiveDockColumns)
        mHotseatProfile?.setField("numShownIcons", effectiveDockColumns)
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
```

**e) The `initGrid` hook** — split the "before" and "after" blocks so dock
and homescreen columns are independent:
```kotlin
invariantDeviceProfileClass
    .hookMethod("initGrid")
    .runBefore { param ->
        if (effectiveDockColumns != 0 && param.args.size >= 3) {
            val displayOption = param.args[2]
            val closestProfile = displayOption.getField("grid")

            closestProfile.setField("numHotseatIcons", effectiveDockColumns)
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
        }
    }
```
(Previously this last block only had a single `if (homeScreenGridColumns != 0)`
that set both `numColumns` and `numShownHotseatIcons` together — split it
into two independent `if` blocks as shown.)

**f) The `invDistWeightedInterpolate` hook** — same split:
```kotlin
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
        if (appDrawerGridColumns != 0) {
            closestProfile.setFieldSilently("numAllAppsColumns", homeScreenGridColumns)
            closestProfile.setFieldSilently("numDatabaseAllAppsColumns", homeScreenGridColumns)
        }
    }
```
(Previously `numHotseatIcons`/`numDatabaseHotseatIcons` were set inside the
same `if (homeScreenGridColumns != 0)` block as `numColumns` — pull them out
into their own `if (effectiveDockColumns != 0)` block as shown.)

Everything else in `GridOptions.kt` (app-drawer row height multiplier hooks,
`AllAppsProfile` hook, etc.) is unchanged.

## After building
- Install the debug APK, enable the module + Pixel Launcher as a target in
  LSPosed, force-stop Pixel Launcher.
- New "Dock columns" slider will appear in Home Screen mods, right below
  "Homescreen rows". Leave at 0 for old behavior (dock follows homescreen
  columns). Set it to homescreen-columns + 1 to get one extra dock slot
  without changing the homescreen grid.
- Sanity check: confirm the dock actually shows the new column count and
  that homescreen columns/rows still work independently (this is the main
  regression risk from the refactor).

## Known unknowns to watch for
- Field names (`numShownHotseatIcons`, `numHotseatIcons`,
  `numDatabaseHotseatIcons`, `mHotseatProfile`) are internal Launcher3
  fields accessed via reflection/Xposed hooks — they're accurate as of the
  current PixelLauncherEnhanced master branch, but if upstream Launcher3
  renames them in a future Pixel Launcher update, the hooks could silently
  no-op. If the new slider does nothing after building, check these field
  names still exist in the target Pixel Launcher's DeviceProfile class via
  `getFieldSilently` logs.
