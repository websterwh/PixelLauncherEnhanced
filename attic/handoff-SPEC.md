# PixelLauncherEnhanced fork — folder preview/animation bug handoff

## Who this is for

You're picking up work from another Claude session that made real progress but got stuck on two
specific, still-broken bugs after several rounds of source-grounded but ultimately unconfirmed
fixes. The owner (William) asked to stop guessing in code and instead get a full write-up + the
relevant source so a fresh, more capable pass can actually nail it. Everything below is either
(a) confirmed via decompiled AOSP/Pixel Launcher source, (b) confirmed via on-device testing and
William's direct feedback, or (c) explicitly marked as an open hypothesis that was **not**
confirmed. Don't treat (c) as fact.

## Project context

- Fork of `Mahmud0808/PixelLauncherEnhanced`, an Xposed/LSPosed module for Pixel Launcher
  (`com.google.android.apps.nexuslauncher`), built for William's personal device only. Fork-only,
  no upstream PR intended.
- Kotlin, hooks installed via a small in-repo reflection/hooking toolkit (see
  `source/kotlin/toolkit/XposedHook.kt`) wrapping standard Xposed APIs
  (`de.robv.android.xposed.*`).
- The file almost all of this work lives in: `source/kotlin/mods/GridOptions.kt` (included, this
  is the CURRENT state after the last round of edits — i.e. honeycomb reverted to true concentric
  hex rings, close-animation hooks from two rounds of attempts still in place).
- Build is **debug variant only**: `./gradlew assembleDebug -q` from the repo root produces
  `app/build/outputs/apk/debug/PLEnhanced v1.1.0-debug.apk`.
- Decompiled Pixel Launcher source (JADX output, from William's actual installed launcher APK) is
  included under `source/decompiled/` for the classes this work touches. This is **real source
  from the actual build running on William's device**, not upstream AOSP — upstream launcher3
  source will NOT reliably match; always prefer what's in `source/decompiled/` over anything
  known from general Android/AOSP training knowledge. Where decompiled method bodies show as
  "Method not decompiled" bytecode dumps (JADX couldn't produce clean Java), the bytecode dump
  itself is still included and has been hand-read/verified where it mattered (see
  `FolderGridOrganizer.setContentSize` below).

## Standing rules this project has been run under (please keep following these)

1. **Before making any code change involving an uncertain/reflection-based API, say exactly what
   log capture is needed and wait for results — don't guess blindly.** This rule got violated
   implicitly by shipping two rounds of unconfirmed animation-glitch fixes in a row without a
   working log capture in between; that's part of why you're being handed this instead of another
   blind attempt. Use it strictly from here.
2. Keep code style close to upstream/existing conventions in the file (see the extensive existing
   comments in `GridOptions.kt` — every non-obvious change has a `William, Aug 2026: ...` comment
   explaining what changed and why; keep doing that).
3. Build **debug** only.
4. **Never test on-device yourself** — you don't have device access. Always give William exact
   `adb` commands and wait for his results/logs/screenshots.
5. Deliver every round as both the built debug APK and a `git diff`-style patch file.
6. Any temporary diagnostic logging must be tagged `TEMPORARY (William, Aug 2026): ...` with
   removal instructions, and use a distinct logcat tag (existing convention: `"FlashDebug"` for
   the folder open/close animation investigation, `"FolderSizeDebug"` for grid-sizing
   investigation — both still present in `GridOptions.kt`, both still active).
7. Git workflow used so far: single feature branch, `git add -A` (of just the relevant files) +
   `git diff --cached > patch-name.patch` + `git commit`. No PR — fork stays local/private to
   William.

## adb log capture — corrected method (important)

The first log-capture attempt this session came back as a **0-byte empty file**. Root cause:
piping `adb logcat | findstr TAG` on Windows and then hitting Ctrl+C to stop it is unreliable —
output frequently doesn't flush to the redirected file before the process dies, especially for a
short capture window. **Use dump mode instead, which needs no interrupt:**

```
adb logcat -c
```
(perform the on-device action you want captured)
```
adb logcat -d -s FlashDebug:V FolderSizeDebug:V > log.txt
```

`-d` dumps the current buffer and exits immediately — no live streaming, no Ctrl+C, no flush race.
Ask William to run this and confirm the resulting file is non-empty (`> 0 bytes`) before relying
on it. If you add new temporary log tags, add them to the `-s` filter list the same way
(`-s TagA:V TagB:V TagC:V`).

---

## Bug 1: Honeycomb preview shape — broken scaling/positioning above 7 items

### What "honeycomb" is supposed to be

A custom (non-AOSP) closed-folder preview arrangement: concentric hex rings around a filled
center icon — ring 0 = 1 center cell, ring k adds `6k` cells (ring 1 = 6, ring 2 = 12, ...). This
is `GridOptions.kt`'s `honeycombPosition()` + `honeycombIconFootprint()` /
`iconFootprintFor()` functions. It's one of four selectable preview shapes (`grid` / `circle` /
`list` / `honeycomb`, see `FOLDER_PREVIEW_SHAPE_TEMP` setting, `res/xml/temp_mods.xml` and
`res/values/arrays.xml`).

William's own reading-order convention for the 7-item (1 ring) case, confirmed correct and must
be preserved: "left to right top to bottom kinda like a clock — 11 o'clock/top-left is 1,
12 o'clock/top-middle is 2, 1 o'clock/top-right is 3, center is 4, 7 o'clock/bottom-left is 5,
6 o'clock/bottom-middle is 6, 5 o'clock/bottom-right is 7."

### History of attempts (chronological, all in `GridOptions.kt`'s current file — read the
`William, Aug 2026, v2/v3/v4` comments around `honeycombPosition`/`iconFootprintFor` for the full
paper trail)

1. **v1**: index-assigned "center first, then clockwise spiral from top." Math was verified
   correct by hand against two real examples, but reading order didn't match William's
   expectation.
2. **v2**: fixed reading order by generating all slot `(x,y)` positions with the ring geometry,
   then sorting by `(round(y), x)` before assigning index → slot (top-to-bottom, left-to-right).
   **William confirmed this fixed ordering for exactly 7 items** ("honeycomb ordering seems
   better with the preview set at 7"). Also changed ring pitch to
   `cellPitch = iconFootprint + gapPx` (was `availableSpace/(2*maxRing+1)`), which stopped
   same-ring icons overlapping.
3. Bug found: **above 7 items** (i.e. once curNumItems >= 8, needing a 2nd ring), icons rendered
   **outside the folder's visible bounds**. Root cause: `iconFootprint` (icon draw size) was
   still computed via the totally unrelated square-grid formula
   (`gridPitch()` → `ceil(sqrt(n))` cols/rows → `basePitch * sizeFactorFor(n)`), not shrunk to
   account for the 2nd ring's larger radius (`2 * cellPitch`), so `2*cellPitch` could exceed
   `availableSpace/2`.
4. **v3 (reverted, see below)**: tried a "single-ring daisy" approach — instead of a true 2nd
   ring, kept ALL non-center icons on one ring and just grew the number of evenly-spaced outer
   cells (7, 8, 9... outer icons for 8, 9, 10... total items), solving for icon size in closed
   form to maximize it. This DID fix the out-of-bounds problem and gave noticeably bigger icons,
   but **William rejected the look**: "the new honeycomb isn't really like honeycomb... try and
   keep it a honeycomb shape... doesn't have to be a perfect honeycomb but some sort of staggered
   honeycomb-like thing that 9 app icons can fit in."
5. **v4 (current state in the included file)**: reverted the arrangement back to true concentric
   hex rings (v2's geometry), but **kept** a sizing fix (`honeycombIconFootprint()`) that shrinks
   `iconFootprint` only when `maxRing >= 2`, solving for the largest icon size such that the
   outermost ring's icon edge still lands inside `availableSpace`:
   ```
   maxRing * (iconFootprint + gapPx) + iconFootprint / 2 <= availableSpace / 2
   =>  iconFootprint <= (availableSpace/2 - maxRing*gapPx) / (maxRing + 0.5)
   ```
   then takes `min(that, ordinary square-grid footprint)` so the confirmed-good ≤7-item case is
   byte-for-byte unchanged (the square-grid footprint is already the tighter bound there).

### Current (still broken) symptom — v4, with a real screenshot

William tested v4 on a folder called "Social" and sent a screenshot (included:
`source/screenshots/social_folder_honeycomb_broken.png`). Visually: **7 icons** (reddit,
facebook, instagram, messenger, steam, a phone/dialer icon, whatsapp), all clustered into the
**upper-left** portion of the closed-folder circle, quite small, with a large empty area in the
lower-right of the circle. Not evenly arranged around the center at all — looks broken/off-center,
not merely "smaller than ideal."

**Confirmed facts about this specific repro** (from William directly):
- His "closed folder preview icon count" setting (`FOLDER_PREVIEW_ICON_COUNT` /
  `folderPreviewIconCount` in `GridOptions.kt`) is set to **9**.
- The "Social" folder itself was not explicitly counted by William, but the screenshot shows
  exactly 7 visible icons. **This is a genuinely open question** (see below) — is this folder's
  real content count 7 (meaning `min(9, 7) = 7`, i.e. the single-ring case, which per the v4 logic
  should be COMPLETELY UNCHANGED from the already-confirmed-good ≤7 behavior), or is something
  else causing only 7 of a larger set to render?
- Separately, William confirmed a different, much bigger folder (used for the animation-glitch
  repro, see Bug 2) has **18 apps total, 16 of which are on the first page** because 4x4 folder
  grid mode is enabled (`FOLDER_GRID_4X4_ENABLE`). Do not confuse this folder with "Social" — they
  are different folders with different item counts. It is not confirmed whether "Social" is 7, 9,
  or some other count.

### Why the "confirmed good at 7" case might not actually be untouched — read this carefully

The previous session's reasoning ("v4's sizing fix only kicks in when `maxRing >= 2`, so the
`curNumItems == 7` case is byte-identical to the earlier confirmed-good state") assumed
`curNumItems` as seen by `GridOptions.kt`'s hooks equals the actual number of icons drawn. **This
assumption was never directly verified against the real decompiled source for this exact call
path**, and reading `PreviewItemManager.java` (included, freshly re-staged this round after two
earlier staging attempts silently failed — see the "flaky staging" note below) surfaced a
detail that was NOT previously accounted for:

`PreviewItemManager.computePreviewItemDrawingParams(int i10, int i11, PreviewItemDrawingParams previewItemDrawingParams)`
(this is the METHOD ACTUALLY HOOKED — see "which hook is live" below) has, in AOSP's own
unmodified body, this branch:

```java
float fScaleForItem = clippedFolderIconLayoutRule.scaleForItem(i11, 0);
if (i10 == -2) {
    clippedFolderIconLayoutRule.getGridPosition(0, 2, fArr);
} else if (i10 == -3) {
    clippedFolderIconLayoutRule.getGridPosition(1, 2, fArr);
} else if (i10 >= 4) {
    // EVERY index >= 4 gets the EXACT SAME position - bottom-right-ish corner:
    float f12 = (clippedFolderIconLayoutRule.mAvailableSpace / 2.0f)
              - ((clippedFolderIconLayoutRule.mIconSize * fScaleForItem) / 2.0f);
    fArr[1] = f12;
    fArr[0] = f12;
} else {
    clippedFolderIconLayoutRule.getPosition(i10, i11, fArr); // only for index 0..3
}
```

i.e. **AOSP's own code only has real per-item positions for index 0-3** (via `getPosition`,
tuned for exactly 3 or 4 items — see `ClippedFolderIconLayoutRule.java`). For index >= 4, AOSP
just stacks every such icon at the identical corner point — the entire point being that those
icons are invisible/clipped in AOSP's real 4-icon-max preview, since AOSP never expected more
than 4 preview icons to exist.

`GridOptions.kt`'s hook is a `runAfter` on this exact method, and it's supposed to **completely
overwrite** whatever AOSP just computed (including this corner-stacking) via
`resultParams?.callMethodSilently("update", transX, transY, scale)` inside
`repositionPreviewItemAsGrid()`. In theory this should make the AOSP special-case irrelevant. But
this interaction was **never explicitly logged/verified** — it's entirely possible there's an
edge case (a specific index, a specific curNumItems, an early-return path in
`repositionPreviewItemAsGrid` or the hook itself) where our override doesn't actually fire or gets
silently skipped for some indices but not others, which would exactly explain "some icons land in
the right honeycomb ring position, others end up stacked together" — matching the screenshot's
"tight cluster in one region" look far better than a pure size/geometry bug would.

**This is the most promising lead for Bug 1 and was not chased down before this handoff.**
Suggested next step: add temporary logging (tag `HoneycombDebug` or reuse `FlashDebug`) inside
the fallback `computePreviewItemDrawingParams` hook in `GridOptions.kt` that prints, for every
call: `index`, `curNumItems`, the AOSP-computed `fArr[0]/fArr[1]` BEFORE `repositionPreviewItemAsGrid`
overwrites them, and the final `transX/transY/scale` AFTER — for one open+close of the "Social"
folder with honeycomb selected. That will directly show: (a) the real `curNumItems` for this
folder, (b) whether AOSP's corner-stacking value is visible before our override, and (c) whether
every index actually gets overridden or some get skipped.

### "Which hook is live" — a subtlety that's easy to get wrong

`GridOptions.kt` registers **two** hooks for `computePreviewItemDrawingParams`:

1. **"Primary" path**, on `ClippedFolderIconLayoutRule` (the class name matching where the method
   theoretically lives per its own internal helper methods `getPosition`/`getGridPosition`/
   `scaleForItem`).
2. **"Fallback" path**, on `PreviewItemManager` (reached via `mIcon.mPreviewLayoutRule` to get
   back to the `ClippedFolderIconLayoutRule` instance for `lastCurNumItemsByRule` bookkeeping).

**Confirmed** (this was established in an earlier round and re-confirmed by directly reading
`ClippedFolderIconLayoutRule.java`, included, full source): on William's actual build,
`computePreviewItemDrawingParams` does **not exist** on `ClippedFolderIconLayoutRule` at all — the
method only exists on `PreviewItemManager` (confirmed above, full source included). So the
"primary" hook silently finds nothing (logs "Method not found", not fatal, just a no-op) and
**only the fallback hook (on `PreviewItemManager`) is actually active**. Don't waste time on the
primary path; all real debugging should focus on the fallback hook and `PreviewItemManager.java`'s
real source.

### Other relevant real-source facts (all confirmed via the included decompiled files)

- `FolderGridOrganizer.previewItemsForPage(int page, List contents)` (full source included,
  `FolderGridOrganizer.java`) — AOSP's own version selects items via `isItemInPreview()`, which is
  NOT simple reading order: for page 0, `i11 < 4` (first 4 by iteration order); for other pages,
  `i11 % mCountX < 2 && i11/mCountX < 2` (upper-left 2x2 quadrant of that page's grid). It also
  **hard-caps at exactly 4 items** (`if (arrayList.size() == 4) break;`). `GridOptions.kt` hooks
  this method and, when `folderPreviewIconCount != 0` and `page == 0`, completely replaces the
  result with `contents.subList(0, min(folderPreviewIconCount, contents.size))` — i.e. straight
  reading-order truncation, ignoring AOSP's own quadrant-selection logic. This override is what
  lets more than 4 preview icons show at all. Verify this override is actually the one used for
  the "Social" folder repro (vs. some path that still goes through AOSP's own 4-cap logic).
- `PreviewItemManager.buildParamsForPage(int page, ArrayList arrayList, boolean animate)` (full
  source included) is the real caller of both `previewItemsForPage` and
  `computePreviewItemDrawingParams`. It resizes `arrayList` (`mFirstPageParams` for page 0) to
  match `previewItemsForPage(...)`'s (overridden) size, then loops calling
  `computePreviewItemDrawingParams(i, size, ...)` where `size = page==0 ? previewItems.size() : 4`.
  So for page 0, `curNumItems` passed into our hook SHOULD equal our overridden preview count.
  This needs to be confirmed with real numbers via logging, not assumed.
- `FolderGridOrganizer.setContentSize(int)` — JADX failed to decompile this cleanly (shown as raw
  smali/bytecode in the included file, with a comment "Code decompiled incorrectly"). A previous
  round hand-traced this bytecode and confirmed it computes `mCountX`/`mCountY` via essentially
  `ceil(sqrt(n))`-style logic bounded by `mMaxCountX`/`mMaxCountY`. This is a DIFFERENT sizing
  concern (the OPEN folder's own grid) from the closed-preview honeycomb math above — don't
  conflate the two.

### A note on flaky file staging

Earlier in this project, `FolderGridOrganizer.java` and `FolderAnimationSpringBuilderManager.java`
intermittently failed to stage from William's machine (the staging tool reported success but the
file never actually appeared). The workaround that worked reliably was running `cat` on the file
directly via a remote shell tool instead of the file-staging tool. If you hit the same "reports
success but file doesn't exist" symptom again, use that same `cat`-based fallback rather than
retrying the staging call.

---

## Bug 2: Folder close-animation "glitch" — icons show wrong order/count for about half a second

### Symptom (William's own words, verbatim from two separate reports)

> "when the folder closes it has a small glitch like before and the order goes to
> fb,mesng,inst,voice,whatapp,steam,messages then it fixes to
> fb,mesng,inst,red,voice,whatap,stea,tg,messages after half a second"

(This is an 18-app folder, 4x4 grid enabled, 16 items on the first page — see "Confirmed facts"
above. "red" and "tg" specifically are the ones that appear to be missing/wrong during the
glitch window before it corrects itself.)

After two rounds of fixes (below), latest status: **"the animation glitch is still there"** — no
further detail on whether it looks different than before, since the log capture that would show
that came back empty (0 bytes — see "adb log capture" section above for the fix). **Get a working
log capture before attempting a third fix.**

### Real source: what `FolderAnimationSpringBuilderManager.createAnimatorSet(boolean isOpening)`
actually does (full source included, `FolderAnimationSpringBuilderManager.java`, ~355 lines —
read the whole thing, it's dense but this summary hits the load-bearing parts)

This is the method that builds the ENTIRE open/close `AnimatorSet` for a folder. Key mechanics:

1. It builds a fresh `FolderGridOrganizer`, calls `previewItemsForPage(currentPage, ...)` to get
   `itemsInPreview` (capped at 4 by AOSP's own logic — see Bug 1's notes; NOTE this call also
   goes through `GridOptions.kt`'s override hook, so `itemsInPreview` here is realistically
   William's configured preview count, not AOSP's real 4-cap, for page 0 at least).
2. For every icon on the CURRENT PAGE (`arrayList`, sized by `mMaxItemsPerPage`, e.g. 16 for a
   4x4 folder — this is NOT the same list as `itemsInPreview`), it builds an `IconAnimationData`
   with:
   - `iconDelay`: starts at `isOpening ? 0 : (pageItemCount * 5)` and steps by `±5` per icon in
     reading order. **For closing, this means the FIRST icon (reading-order index 0) gets the
     LARGEST delay (`pageItemCount * 5`, e.g. 80ms for 16 items) and the LAST icon gets the
     smallest (`5`ms)** — i.e. closing animates last-icon-first. This is AOSP's own native
     design, not something this fork introduced.
   - `xDistance`/`yDistance`: computed via `layoutRule.getGridPosition(i/mCountX, i%mCountX, fArr)`
     for `size2 > 4 || page != 0` (i.e. exactly the case this fork's `getGridPosition` hook
     targets — the position math itself was already fixed and **confirmed working** by William:
     "the animation is looking very good with both honeycomb and grid. bro great job." Do not
     re-litigate the position math — it's correct.
3. For each `IconAnimationData`, it calls
   `FolderSpringAnimatorSet$Factory.playSpringAnimation(...)` (full source included, tiny file —
   just wraps a `SpringAnimationBuilder`) **multiple times per icon**, once per animated property:
   - `getFloatingViewTextAlpha()` (a text-label alpha, always, every icon)
   - **`VIEW_ALPHA` targeting the icon view itself, but ONLY `if (!itemsInPreview.contains(icon))`**
     — i.e. only for icons NOT in the (4-or-configured-count) preview list. Animates 1.0 → 0.0 on
     close. This is the "extra fade" mechanism.
   - `VIEW_TRANSLATE_X`, `VIEW_TRANSLATE_Y`, scale — always, every icon.
4. **Regardless of which sub-animators actually ran**, an `AnimatorListenerAdapter` is added (once
   per icon) to the shared `AnimatorSet`. Its `onAnimationEnd` unconditionally resets
   `translationX/Y = 0`, `scaleX/Y = 1`, and (if `!itemsInPreview.contains(icon)`) `alpha = 1`.
   Its `onAnimationStart` only forces the "from" state **when `isOpening`** — for closing, nothing
   forces an initial state, icons just animate from wherever they currently are.

### Attempts so far (both in the current `GridOptions.kt`, both still present — do not just
re-add them, they're already there and confirmed insufficient)

**Round 1**: hooked `FolderSpringAnimatorSet$Factory.playSpringAnimation` (a static method — see
its tiny full source, included), and for calls where `isOpening == false` AND the `FloatProperty`
argument `===` `LauncherAnimUtils.VIEW_ALPHA` (fetched via reflection,
`getStaticFieldSilently("VIEW_ALPHA")`) AND the target argument is an instance of
`BubbleTextView`/`AppPairIcon` (i.e. specifically the "extra fade for non-preview icons on close"
call from point 3 above) — set `param.result = null` to skip the call entirely (the method is
`void`, so this is a legitimate no-op skip; this exact skip-via-`param.result=null` pattern is
already used elsewhere in the codebase, e.g. `TopShadow.kt`'s `onViewAttachedToWindow` hook).
Reasoning: this fade-to-invisible-then-instant-snap-back (via the unconditional
`onAnimationEnd` alpha reset) for icons beyond the preview cap, staggered per-icon via the delay,
looked like a plausible cause for "icons disappear/reappear out of order."

**Round 2**: William reported the glitch was still there after round 1. Expanded the same hook:
for closing + icon-view target, if it's NOT the alpha property, **zero the `delay` argument**
(`param.args[3] = 0`) instead of skipping — reasoning being that the position/scale animations for
EVERY icon (not just the alpha-faded ones) also carry this same staggered delay, so even with the
fade suppressed, icons still started MOVING toward their closed position at different times.

**Current code state**: both of these are combined into one hook on `playSpringAnimation` in
`GridOptions.kt` (search for `"Close-animation \"glitch\" fix"` and
`"William, Aug 2026, round 2"` comments). William has not yet confirmed or denied whether this
made any visible difference — only that the glitch is "still there." **We genuinely don't know
if round 2 helped, hurt, or did nothing** — get a real log before changing this again.

### What a good log capture should tell you

With the corrected `adb logcat -d -s FlashDebug:V > log.txt` method (see top of this doc), have
William reproduce on the 18-app/16-per-page 4x4 folder specifically (this is the repro case that
glitches; smaller single-page folders reportedly animate smoothly). The existing `FlashDebug` logs
already print, per call: `playSpringAnimation: suppressing close-direction icon alpha fade for
<class>` (round 1's skip firing) — if this line is ABSENT from the close-direction capture, the
hook isn't matching for some reason (e.g. `VIEW_ALPHA` static field lookup failed, or the target
class check is wrong) and that's the actual bug, not the underlying theory. Also check
`createAnimatorSet(isOpening=...) childCount=... totalDuration=... children=[...]` (already
logged) for the actual list of child animators and their `startDelay`/`duration` on a close, to
see directly whether the round-2 delay-zeroing is taking effect (all per-icon animators should
show `startDelay=0` after the fix, if it's working).

If, after a real log capture, the round 1+2 hooks are confirmed firing correctly and the glitch
STILL visually happens, the mechanism theory (staggered delay + extra alpha fade) may simply be
wrong or incomplete, and it's worth re-reading `FolderIcon.java`'s `mFolderAnimator`/redraw-timing
logic (included) and/or capturing `PreviewItemManager.draw()`'s per-frame `mCurrentPageParams`/
`mFirstPageParams` sizes across the close transition (there's already a `FlashDebug`-tagged hook
on `PreviewItemManager.draw()` for exactly this, currently still in the file) to see if the
REAL cause is a stale/incomplete `mCurrentPageParams` list being drawn mid-transition rather than
anything animation-timing related.

---

## File manifest (everything included alongside this spec)

```
source/
  kotlin/
    mods/GridOptions.kt          — the fork's own code; ALL custom folder-preview/animation logic
    Constants.kt                 — SharedPreferences key names (FOLDER_PREVIEW_SHAPE_TEMP, etc.)
    toolkit/XposedHook.kt        — the hooking toolkit (hookMethod/.runBefore{}/.runAfter{}, get/setField, etc.)
  res/
    xml/temp_mods.xml            — settings UI for the TEMP folder-preview tuning screen
    values/arrays.xml            — shape/scope dropdown option lists
  decompiled/
    folder/
      ClippedFolderIconLayoutRule.java         — AOSP's own (unmodified) preview layout math
      FolderGridOrganizer.java                 — page/grid sizing, previewItemsForPage, setContentSize
      PreviewItemManager.java                  — REAL owner of computePreviewItemDrawingParams (see Bug 1)
      FolderAnimationSpringBuilderManager.java — builds the whole open/close AnimatorSet (see Bug 2)
      FolderSpringAnimatorSet$Factory.java     — the hooked static method (see Bug 2)
      FolderIcon.java                          — ties FolderGridOrganizer/PreviewItemManager/LayoutRule together
      FolderOpenCloseAnimationListener.java    — animation listener AOSP attaches on open/close
      FolderPreviewItemAnim.java               — a DIFFERENT (non-spring) per-item animator AOSP also uses
      PreviewBackground.java                   — closed-icon background circle sizing (previewSize, etc.)
  screenshots/
    social_folder_honeycomb_broken.png         — William's screenshot of the current honeycomb bug (Bug 1)
```

All decompiled `.java` files are real JADX output from William's actual installed Pixel Launcher
build (retraceme hash `8bcbbc35bb13161dedaa519f339f298517b051dcbf8e2046701ae935fa579434`, visible
in each file's header comment) — treat them as ground truth over general AOSP/launcher3 knowledge,
which will not reliably match this specific OEM build.

## What NOT to do

- Don't guess another honeycomb geometry blind — get the `HoneycombDebug`/`FlashDebug` log for the
  "Social" folder first (see Bug 1's suggested next step) and confirm the real `curNumItems`
  before changing anything.
- Don't re-attempt the close-animation fix without a working log capture confirming whether round
  1/2's hooks are even firing as intended.
- Don't touch the `getGridPosition` position-matching fix or the honeycomb ordering (sort-by-y-
  then-x) — both are explicitly confirmed working by William and are not part of either open bug.
- Don't re-introduce the "single-ring daisy" honeycomb shape — explicitly rejected by William, he
  wants true hex-ring geometry kept even if it means smaller icons at high counts.
