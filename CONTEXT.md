# PLEnhanced Fork — Project Context

Everything an agent or human needs to continue this project from scratch.
Read this before touching anything. Companion docs: `AGENTS.md` (rules of
engagement), `CLAUDE.md` (pointer file for Claude Code).

Last updated: Sep 27, 2026. Current release: **v1.2.0 (versionCode 6)**,
branch `layout-tweaks`. Built against Android 17 / Pixel Launcher 907 as of
round v56 — see §4a for the field map, and read it before touching any hook.

---

## 1. What this is

William's fork of [PixelLauncherEnhanced](https://github.com/DrDisagree/PixelLauncherEnhanced)
(PLE) — an Xposed/LSPosed module that hooks Google's Pixel Launcher
(`com.google.android.apps.nexuslauncher`, APK `NexusLauncherRelease` in
`/system_ext/priv-app/`). The fork's work:

1. **Folder closed-preview layout** — fixed for >4 icons and locked to a
   stock-matched square grid (all experimental shapes removed).
2. **Open/close animation fixes** — correct icon destinations/order, no
   sliver outside the folder, no "only 4 icons animate" pop-in.
3. **Working Import/Export** — the launcher's hidden dev-options Import
   button is dead in stock; this fork makes it fully work, including
   widgets.

Everything else in PLE (dock columns, drawer columns, 4x4 toggle, preview
count, etc.) is upstream functionality and still works.

## 2. Environment

- **Device**: rooted Pixel, 4x6 home grid, launcher db is
  `launcher_4_by_6.db`. Connected via adb throughout development.
- **Module host**: LSPosed. In logcat the module's output appears under a
  bridge tag (e.g. `VectorLegacyBridge`), NOT under the tag passed to the
  toolkit `log(tag, msg)` — the tag ends up in the message text as
  `[PLEnhanced] <tag> => ...`. **To capture module logs: `adb logcat -d >
  full.txt` then `findstr "SomeTag" full.txt`.** Filtering with
  `logcat -s SomeTag` yields nothing.
- **Decompiled launcher source** was the evidence base for every hook
  (jadx on the pulled `NexusLauncherRelease.apk`). Class/method names in
  this launcher build are NOT obfuscated.

### Build

```
cd PixelLauncherEnhanced
.\gradlew.bat assembleRelease     # signed release (R8 minified, ~2.6 MB)
.\gradlew.bat assembleDebug       # debug build (~10 MB)
```

- Release signing reads `keystore.properties` (repo root, **gitignored**)
  → keystore at
  `C:\Users\William\Documents\projects\android\.KEYS\PixelLauncherEnhanced\PLEnhanced.jks`
  (alias `pleenhanced`, RSA-4096, ~30y). **That folder must be backed up** —
  lose it and no future APK updates over this signature.
- Without `keystore.properties` the build falls back to debug signing
  (CI-style). Debug- and release-signed builds cannot install over each
  other; switching requires `adb uninstall` (module settings are lost —
  folder-preview tuning is hardcoded so nothing important resets).
- Install + restart:
  ```
  adb install -r "<apk>"
  adb shell am force-stop com.google.android.apps.nexuslauncher
  ```
  Note: `force-stop` can leave the recents/app-switcher half-dead (blank
  task previews and names, dead dropdown). A reboot fixes it. This was
  mistaken for a module bug once — it isn't.

### Deliverable convention

Current APK kept at the workspace root:
`PLEnhanced-v1.2.0-android17-release-signed.apk` (signed with the release
keystore, cert `e6026394…`, so it installs over previous builds).
Historical per-round APKs/patches were deleted; every round is reproducible
from git history (branch `layout-tweaks`, commit messages `vNN: description`).

## 3. Architecture

- Entry: `xposed/HookEntry.kt` → `EntryList.kt` lists `ModPack` classes per
  package. Folder-preview + import/export mods: `xposed/mods/GridOptions.kt`
  (~900 lines) and `xposed/mods/ImportExportMod.kt`.
- Toolkit (`xposed/mods/toolkit/`): `findClass`, `hookMethod(...).runBefore/
  runAfter`, `getFieldSilently`, `callMethodSilently`, `getStaticFieldSilently`,
  `log`. `hookMethod("name")` hooks ALL overloads by name.
- Root access from inside the launcher process:
  `HookEntry.enqueueProxyCommand { proxy -> proxy.runCommand("...") }`
  (libsu root service; used for the post-import `killall`).
- Prefs: `Xprefs` (remote preferences). Constants in
  `data/common/Constants.kt`. Settings UI: `res/xml/*.xml` + fragments.

### Folder preview (GridOptions.kt) — final design

For `curNumItems > 4` (else stock AOSP path):

- **Grid only.** cols = `ceil(sqrt(n))` capped by the 4x4 toggle's
  `folderGridColumns` (same cap applied to the open folder via the
  `setContentSize` hook), rows analogous.
- **Stock-anchored constants** (the "claude math"): stock draws its 4-icon
  preview at `0.44 * availableSpace` per icon; solving our
  `footprint = (availableSpace / max(cols,rows)) * spacing * size` for the
  4-icon-as-2x2 case gives `spacing * size = 0.88`. Baked as
  `DEFAULT_SPACING_FACTOR = 0.88`, `DEFAULT_SIZE_FACTOR = 1.0`,
  `DEFAULT_GAP_DP = 2` (flat gap, William's taste; stock implies 0).
- Hook set:
  - `computePreviewItemDrawingParams` (rule class + PreviewItemManager
    fallback path — some builds inline the rule's method) →
    `repositionPreviewItemAsGrid` writes transX/transY/scale.
  - `scaleForItem` → animation scale agrees with the draw path.
  - `getGridPosition` (ClippedFolderIconLayoutRule) → open/close animation
    destinations use OUR position math (see Bug 2 below).
  - `playSpringAnimation` (FolderSpringAnimatorSet$Factory) → zero the
    per-icon stagger delay on CLOSE so all icons move in lockstep.
  - `createAnimatorSet` (FolderAnimationSpringBuilderManager) runBefore →
    if `PreviewItemManager.mCurrentPageParams` is empty, copy
    `mFirstPageParams` into it (see Bug 4).
- Caches: `lastCurNumItemsByRule` / `lastTotalContentsByRule`
  (WeakHashMaps keyed by the rule object) let the animation hooks know the
  real preview/total counts.

### Import/Export (ImportExportMod.kt)

- **Export**: stock launcher's own button — works, untouched. Produces XML:
  `<workspace rows columns>` with `<autoinstall>` (apps; hotseat items use
  `rank` instead of x/y/screen), nested `<folder titleText>` elements, and
  `<appwidget ... className packageName>` for widgets. Real sample preserved
  at `attic/launcher_grid_export.xml`.
- **Import** (stock is a dead stub — zero writes after picking a file):
  1. GATE: hook `android.app.Instrumentation.execStartActivity` — any
     `ACTION_OPEN_DOCUMENT` start in the launcher process raises a one-shot
     `importPending` flag.
  2. CATCH: hook BOTH `Activity.onActivityResult` and androidx
     `ActivityResultRegistry.dispatchResult` — whichever fires first
     consumes the flag and takes the URI (covers classic and
     ActivityResultLauncher delivery; the stock callback is a stub).
  3. PARSE: `XmlPullParser` directly — deliberately NOT Google's
     `AutoInstallsLayout` (coupled to APK-resource lookup; dies with
     `XmlPullParserException: Missing attribute screen`). Whole file parsed
     before any DB write.
  4. WRITE: **direct SQLiteDatabase** — largest `launcher*.db` in
     `applicationInfo.dataDir/databases` (name varies by grid). One
     transaction: `DELETE FROM favorites` (wipe & replace) → insert rows
     (folder `_id` remapping, hotseat slot in `screen`+`cellX`, rank for
     folder children) → commit → close. Intent strings rebuilt as
     `#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=pkg/cls;end`
     with `$` → `%24`.
  5. WIDGETS: allocate a real AppWidgetId via `AppWidgetHost(context,
     hostId)` (host id read from `com.android.launcher3.LauncherAppWidgetHost.APPWIDGET_HOST_ID`,
     fallback 1024), bind with
     `AppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)` —
     NOTE the argument order (id first) — then write the row with
     `appWidgetId` + `appWidgetProvider` (`ComponentName.flattenToString()`).
     Works because we run inside the launcher process, which holds
     `BIND_APPWIDGET`. Uninstalled providers → skipped + counted.
  6. Toast result → root-proxy `killall` so `LoaderTask` re-reads fresh.

## 4. Bug history (root causes → fixes)

| # | Symptom | Root cause | Fix |
|---|---------|-----------|-----|
| 1 | Folder preview broken >4 icons | AOSP hardcodes max 4 preview icons; its own math (0.44x scale, corner stacking) can't do more | Custom grid math in `repositionPreviewItemAsGrid` |
| 2 | Icon sizes/spacing wrong at 7+ | Arbitrary factors (0.82/0.9) not anchored to anything | Derived 0.88/1.0 from stock's 0.44x preview scale (decompiled `PreviewItemManager` + `ClippedFolderIconLayoutRule.scaleForItem`) |
| 3 | Open/close "flash" (layout changes mid-morph) | Preview used uncapped `ceil(sqrt(n))` cols while the open folder's `mCountX` was capped at 4 | Cap both sides identically (`gridPitch` + `setContentSize` hook) |
| 4 | "Only 4 icons animate, rest pop in ~1s later" | `PreviewItemManager.mCurrentPageParams` is EMPTY at animation-build time (v23 capture: `mFirstPageParams.size=9`, `mCurrentPageParams.size=0`); the spring builder reads it raw with no fallback | `createAnimatorSet` runBefore hook copies `mFirstPageParams` → `mCurrentPageParams` (a copy, not an alias) |
| 5 | Close animation: wrong order/count for ~0.5s, then corrects | `createAnimatorSet` walks page icons as `getGridPosition(i/mCountX, i%mCountX)` with `mCountX` sized from TOTAL contents (11 → 4 cols) while reconstruction used the PREVIEW count (9 → 3 cols) → duplicated indices (0,1,2,3,3,4,5,6,6,7,8) | `getGridPosition` hook rebuilds the caller's column count from the cached total (`lastTotalContentsByRule`), replicating the exact `setContentSize` formula |
| 6 | "Sliver of something outside the folder" during close | Page icons beyond the preview cap kept AOSP's raw extrapolated coords (~2x outside `availableSpace`) — 54 such events in the v49 log | Clamp out-of-range indices onto the last preview slot |
| 7 | Honeycomb preview clustered upper-left >7 items (original Bug 1) | Slot selection took the sorted-first-N of ALL ring slots; ring-2's top cells dominated the (y,x) sort so the center/bottom never filled | (Moot — rings removed. Historical fix: ring-first selection, then sort chosen slots only.) |
| 8 | Stock Import button does nothing | `onActivityResult`/ActivityResultLauncher callback is a stub; picked URI goes nowhere (proven by before/after file-timestamp snapshot: zero writes) | ImportExportMod (above) |
| 9 | Toast "Restarting launcherâ€¦" garbage | UTF-8 ellipsis double-encoded in `strings.xml` (also `search_hint`) | ASCII `...` |
| 10 | Stray icon left in closed preview when closing (or reopening) a >4-item folder from page ≥ 1 (e.g. a full 4x4 page 0 plus a page 1) | `FolderGridOrganizer.previewItemsForPage`: once a folder has >4 items, AOSP's `mDisplayingUpperLeftQuadrant` flips true and `isItemInPreview` treats EVERY page's own upper-left 2x2 as "in preview" — not just page 0's — so `createAnimatorSet` skips the close-direction alpha-fade for a lone page-N icon; it snaps to identity transform while staying opaque | `previewItemsForPage` hook reports an empty preview list for any page != 0 once `contents.size > 4` (our closed preview is a fixed page-0-derived grid, so no page has "its own" preview items); paired with the pre-existing `getGridPosition` page-guard (bails out of custom position math for non-zero pages, added first but insufficient alone) |
| 11 | Android 17 (SDK 37) update killed the layout patches: hide desktop search bar, dock column count, desktop/dock spacing | Pixel Launcher 907 dropped the `m` prefix from every `DeviceProfile` field, removed the 3-arg `Hotseat` ctor, split `initGrid` to 1 arg, dropped `invDistWeightedInterpolate`, and moved `numShownHotseatIcons`/`numFolderRows` onto new carrier objects | v56: merged upstream `getAnyField` compat work, then re-pointed the remaining hooks at the objects that actually carry the values now (see §4a) |
| 12 | Home-screen "desktop icon horizontal margin" does nothing; William reported it as "spacing from one side instead of from the center out" | v56 moved the patch onto `DeviceProfile.workspacePadding`, but on launcher 907 that Rect lives on `WorkspaceProfile` under a de-prefixed name — both lookups miss and a TEMPORARY probe logged `workspacePadding=null` on a real launcher start. Even if it had resolved it would have been wrong: `Workspace.setPadding()` only shifts the page origin, it never narrows the grid | First working shape (v57) put the delta on each page `CellLayout`'s own padding — logcat showed pages measuring `padL=32/padR=32`, and `CellLayout.onMeasure()` feeds that into `calculateCellWidth(measuredWidth - padL - padR, borderSpace, countX)`. Superseded by #13 once drag/rotation turned out to reset it. |
| 13 | Icon margin "messed up" after moving an app on the home screen, and again after a rotate-and-return | v57 set each page CellLayout's padding once (WeakHashMap), but `Workspace.setInsets()` re-applies `workspaceProfile.cellLayoutPaddingPx` to EVERY page on every insets dispatch (`Workspace.java` → `Workspace$$ExternalSyntheticLambda5` case 0, `cellLayout.setPadding(rect.left, ...)`), and that dispatch fires both on rotation and when the drag drop-target bar shows — silently overwriting the margin, with the guard then blocking re-application | v58 mutates `workspaceProfile.cellLayoutPaddingPx` itself (the source the launcher re-reads every dispatch) once per DeviceProfile: no per-view state, every future `setInsets()` re-applies the margin by itself. Verified on device: column positions pixel-identical after a long-press icon drag and after a portrait→landscape→portrait round trip |
| 14 | "Auto-rotate keeps getting turned back on after I turn it off" | Not the module: PLE has no `Settings.System` writes anywhere, the launcher only *reads* the flag (`SettingsCache.ROTATION_SETTING_URI` → `RecentsOrientedState.updateAutoRotateSetting`), and no other LSPosed module is scoped to the launcher or SystemUI. It's Android 12+'s rotation-suggestion flow — `Settings.Secure.num_rotation_suggestions_accepted` on the device is non-zero, i.e. suggestions have been accepted, and accepting one re-enables auto-rotate | No module change. This is stock Android behavior: rotating the device with auto-rotate off raises the "rotate screen?" prompt, and dismissing/accepting it flips the flag back on. |
| 15 | Uninstall entry gone from the long-press context menu | The Aug 2026 build hand-made a `SystemShortcut.AppInfo` via its package-private 6-arg constructor with `mode=2` and stashed the ComponentName in `mSplitA11yInfo`. On 907 that constructor is gone — `AppInfo` has a hardcoded `public final int f6867c = 1` and only a 5-arg ctor, so the reflective lookup returned null and the hook bailed before adding anything. (`mSplitA11yInfo` is also no longer a ComponentName: `onClick`'s default branch casts it to a `UserHandle` for the private-space Play Store intent.) | v59 calls the launcher's own factory: `SystemShortcut.UNINSTALL_APP` is now `new UninstallAppFactory(false)` — `mPrivateSpaceOnly=false`, so it no longer bails for normal users and only returns null when `SecondaryDropTarget.getUninstallTarget()` says the app isn't uninstallable. `UninstallApp.onClick` → `performUninstall()`. `QuickstepLauncher` only wires up `UNINSTALL_PRIVATE_APP`/`PRIVATE_PROFILE_INSTALL`, so there's no duplicate-entry risk. Deletes ~60 lines of reflection into private ctors/fields. Verified on device: entry present, tap raises the system "Uninstall Discord?" dialog |
| 16 | After a launcher restart, the far-right dock app is replaced by the Pixel app-suggestion chip | **Wrong diagnosis, corrected by #17.** v59 neutralized `fillGapsWithPrediction()` on the theory that it was racing the hotseat bind. It wasn't: the 5th dock row had been deleted from the DB (#17), so the empty slot it found was genuinely empty and the chip was simply the only thing filling it. Disabling the filler just made the hole visible. v60 restores the filler — it behaves correctly once the row exists. |
| 17 | Dock slot past the loaded item count never appears | **Wrong diagnosis, corrected here.** v60 originally blamed `numDatabaseHotseatIcons` being derived inside `initGrid` before the module's `runAfter` patch landed. That derivation-ordering problem is real and the `runBefore` hook is kept, but it is **not** what caused the missing icon: `DeviceGridState.mNumHotseat` only round-trips through prefs for a grid-change comparison, it does not gate the hotseat query. The actual cause, found by pulling `launcher_4_by_6.db` off the device, was that the row was **deleted from the database** (`_id 287`, Camera, container -101, screen 4) — the icon was never a load failure. The prediction chip was simply the first thing to fill the hole. Re-added via UI drag; it then survived restarts and rotation with the filler active. Lesson: check the DB before theorizing about load counts — `AGENTS.md` rule 1 (evidence before reflection) applies to "the icon doesn't appear", and the cheapest evidence was a SQL query. |
| 18 | Home screen doesn't rotate even with auto-rotate on | `RotationHelper.notifyChange()` resolves the activity orientation request from three fields, and on this build its default state resolves to `SCREEN_ORIENTATION_PORTRAIT`: `mStateHandlerRequest==2` or `mCurrentStateRequest==2` → 14 (drag), `mCurrentStateRequest==1` → -1 (rotation allowed), `mIgnoreAutoRotateSettings`/`mForceAllowRotationForTesting` → -1, otherwise → **5 (portrait)**. `Launcher.onCreate()` constructs the helper with both request fields at 0 (`Launcher.java` ~1403-1407), so the home screen falls straight through to 5 and calls `setRequestedOrientation(PORTRAIT)`. Nothing on this build ever sets the fields that yield -1. Proof: the launcher's own `b/475447538` log printed `[mStateHandlerRequest=0, mCurrentStateRequest=0, mLastActivityFlags=5]` while the Settings app rotated normally on identical device state. | v60 hooks `RotationHelper.notifyChange()` runBefore and, only in the neutral "nothing requested" state (`!mIsFixedLandscape && state==0 && current==0`), sets `mCurrentStateRequest = 1` so the launcher asks for unspecified orientation. Drag (state 2) and fixed-landscape are left alone, so orientation locking during a drag still works. Verified: `mLastActivityFlags` goes 5 → -1, and a locked-landscape capture comes back 2992x1344 instead of 1344x2992 |


## 4a. Android 17 launcher (versionCode 907) field map

Evidence: `adb pull /system_ext/priv-app/NexusLauncherRelease.apk` then
`$ANDROID_HOME/build-tools/37.0.0/dexdump -d classes.dex`. No jadx needed —
class/method/field signatures are all in the dexdump listing. Two of these
were confirmed independently by the module's own on-device boot error dump
(`NoSuchMethodError: Hotseat(Context, AttributeSet, int)#exact`,
`Method not found: invDistWeightedInterpolate`).

| Old (gone) | New (use this) |
|---|---|
| `DeviceProfile.mHotseatProfile` | `DeviceProfile.hotseatProfile` |
| `DeviceProfile.mWorkspaceProfile` | `DeviceProfile.workspaceProfile` |
| `DeviceProfile.mDisplayOptionSpec` | `DeviceProfile.displayOptionSpec` |
| `DeviceProfile.mAllAppsProfile` | `DeviceProfile.allAppsProfile` |
| `DeviceProfile.mFolderProfile` | `DeviceProfile.folderProfile` |
| `DeviceProfile.mInvariantDeviceProfile` | `DeviceProfile.inv` |
| `Hotseat(Context, AttributeSet, int)` | `Hotseat(Context)` / `Hotseat(Context, AttributeSet)` |
| `DeviceProfile.numShownHotseatIcons` | `DisplayOptionSpec.numShownHotseatIcons` |
| `DeviceProfile.numFolderRows/Columns` | `FolderProfile.numRows/numColumns` (Int) — the `InvariantDeviceProfile` copies are now `int[]` |
| `DisplayOptionSpec.grid` + `GridOption.numHotseatIcons` | gone entirely; the per-profile objects are read directly |
| `InvariantDeviceProfile.initGrid(a,b,c)` | `initGrid(String)` |
| `InvariantDeviceProfile.invDistWeightedInterpolate` | gone; profile is built in the `DeviceProfile` ctor / `Builder.build()` |
| `Launcher.getDeviceProfile()` / `Launcher.deviceProfileRef` | gone; capture the `DeviceProfile` from its constructor |
| `LauncherModel.forceReload()` | `forceReload(String) -> CompletionStage` |
| `IDP.numShownHotseatIcons` | `IDP.displayOptionSpec.numShownHotseatIcons` |

Unchanged and still valid: `Hotseat.mQsb`, `Hotseat.setInsets`,
`DeviceProfile.getHotseatLayoutPadding`, `HotseatProfile.{numShownIcons,
barSizePx, barBottomSpacePx}`, `WorkspaceProfile.workspacePadding`, the
`qsb_widget_height` dimen, `IDP.{numRows, numColumns, numDatabaseHotseatIcons}`.

**Rule that came out of this**: a `getFieldSilently()` that misses is
invisible — no error dump, no log, the setting just quietly does nothing.
Field renames are the failure mode that will not announce itself.

## 5. Things tried and REVERTED (learned the hard way)

- **Close-animation alpha-fade suppression** (early round): suppressing
  AOSP's close-direction `VIEW_ALPHA` fade did nothing for the real bug and
  changed stock behavior → **reverted**; destinations were the real problem
  (Bug 5). The stagger-delay zeroing from round 2 stayed.
- **Honeycomb as concentric hex rings** (v2–v50, renamed "flower" in v51):
  worked at 7 (center + 6) but 8+ needed a second ring that shrank icons
  hard ("at 9 it's like it's scaling the size for way more apps") → split
  to its own option (v51), then **merged back and deleted** (v53/v55).
- **Honeycomb as staggered rows** (v51): equal-width rows with only the
  middle row nudged — William: "thats not honeycomb thats more like a
  cheveron" → replaced in v52.
- **Tapered hexagon pile** (v52): correct-looking but "not what I'd like" —
  William wanted the flower@7 look at 9 → v53 merged flower + honeycomb
  into a center + single adaptive ring (radius = gridPitch/(2·sin(π/m)),
  full-size icons, 4-item folders included).
- **ALL shapes** (circle/list/honeycomb/flower) + **TEMP settings page**
  (master toggle, size/spacing/gap sliders, shape dropdown): removed in
  v55 — William: "the grid shape will be implemented into the app as the
  default and only shape. i don't like them." Values baked in as constants.
  The v54 circle slot-matching (ring slots assigned by open-folder angular
  position to shorten animation travel) died with circle.
- **LauncherProvider-based import writes**: provider throws
  `"same process should call model directly"` for in-process callers —
  provider path is external-callers-only on this build → direct SQLite.
- **`PRAGMA busy_timeout` via `execSQL`**: throws
  `"Queries can be performed using SQLiteDatabase query or rawQuery methods
  only"` (PRAGMAs return a row) → removed; Android applies its own default
  busy timeout.
- **`LauncherSettings$Favorites.CONTENT_URI` reflection**: comes up empty on
  this build (and the authority is NOT the AOSP default — it's
  `<packageName>.settings`, confirmed via `pm dump`) → authority now
  discovered from the package manifest (kept as documentation; the current
  code writes via direct SQLite and doesn't need the provider at all).
- **Diagnostic logging** (FlashDebug/HoneycombDebug/FolderSizeDebug/
  LauncherModelDump, class dumps): all stripped in v55 final.
- **v55 cleanup mishap** (kept for honesty): while stripping
  `LauncherModelDump` from LauncherUtils the dump block was accidentally
  duplicated, then removed; also a bogus `toolkit.enqueueProxyCommand`
  import was briefly added. Build caught nothing (it was valid syntax) —
  caught in review before commit.

## 6. Known limitations

- **Widget configs reset** on import (per-widget settings live in the
  widget's own app, keyed to the old widget id — cannot survive XML).
- **Widget size options** are not pushed at bind time (no
  `OPTION_APPWIDGET_*` bundle) — most widgets sort themselves out on first
  layout; a wrong-sized one fixes via long-press resize. Can be added from
  DeviceProfile cell dims if William ever wants it.
- **Residual minor close-flash** is still theoretically possible: the
  animation's per-cell pitch inside `getGridPosition` is AOSP's own formula,
  not ours (we only override destinations for icons the builder walks).
  Never observed as a problem after Bug 5's fix.
- **~200ms blank-then-populate delay** on the closed icon when closing a
  multi-page (>4-item) folder from page ≥ 1 (William noticed this right
  after Bug 10's fix and asked about it). A/B tested via on-device screen
  recording + frame extraction: the same delay is present in `c74861d`
  (the commit BEFORE Bug 10's `previewItemsForPage` fix), so it's not a
  regression from that fix. Matches `PreviewItemManager`'s own
  `mShouldSlideInFirstPage`/`mCurrentPageItemsTransX` mechanism (decompiled
  source) — a stock AOSP transition for "sliding" the closed icon back to
  its page-0 preview when closed from a different page. William chose to
  leave it as-is (stock-matched behavior, not a bug).
- **Drag-reorder animations + accessibility** still reference AOSP's
  hardcoded 4-icon preview (pre-existing upstream gap, left as-is).
- Import is **wipe & replace** by design (William's choice).

## 7. Conventions (see AGENTS.md for the full rules)

- Code comments narrating decisions use `William, Aug 2026` + round/version.
- Historical "why" comments are deliberately verbose — they encode
  on-device evidence so nobody re-litigates solved bugs.
- Commit style: `vNN: description` on branch `layout-tweaks`.
- Evidence first: never guess a reflection class/method/field — verify via
  decompile, runtime dump, or logcat capture first.
