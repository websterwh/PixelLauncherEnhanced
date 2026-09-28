# AGENTS.md — Rules of engagement for AI agents

Fork of PixelLauncherEnhanced (Xposed module for Google Pixel Launcher).
**Read `CONTEXT.md` first** — it has the full architecture, bug history,
reverted approaches, and device quirks. This file is the operational
checklist.

## Build / install / test

```
.\gradlew.bat assembleRelease        # signed (keystore.properties), R8 minified
.\gradlew.bat assembleDebug          # fallback/debug
adb install -r "C:\Users\William\Documents\projects\android\PLEFork\PLEnhanced-v1.2.0-release-signed.apk"
adb shell am force-stop com.google.android.apps.nexuslauncher
```

- Release signing needs `keystore.properties` (repo root, gitignored) →
  keystore in `C:\Users\William\Documents\projects\android\.KEYS\PixelLauncherEnhanced\`.
  NEVER commit keystore.properties, local.properties, or any .jks.
- Signature changes require uninstall → module settings reset. Don't flip
  between debug/release signing casually.
- After installing, ALWAYS `force-stop` the launcher. Warn the user that
  force-stop can leave recents blank until reboot (known launcher quirk,
  not a module bug).

## Hard rules (learned from 55 rounds of iteration)

1. **Evidence before reflection.** Never guess a class, method, field, or
   signature on the launcher. Verify by decompiling
   (`NexusLauncherRelease.apk` — class names are unobfuscated), a runtime
   class dump, or a logcat capture. Every guessed API that shipped without
   evidence cost a full round.
2. **Log capture gotcha.** The toolkit `log(tag, msg)` writes the tag into
   the MESSAGE text (`[PLEnhanced] <tag> => ...`) under LSPosed's bridge
   tag. Capture with `adb logcat -d > full.txt` + `findstr "<tag>" full.txt`.
   `logcat -s <tag>` returns nothing.
3. **One change per round.** Ship → William tests on device → feedback
   drives the next round. Don't stack speculative changes.
4. **Mark temporary work.** Anything diagnostic or experimental gets a
   `TEMPORARY (William, <date>)` comment with removal instructions, and is
   actually removed once confirmed. v55 final stripped ~800 lines of
   accumulated diagnostics — don't let it rebuild.
5. **Comment the WHY with the evidence.** Decisions that cost device
   testing get a comment citing what was observed (see GridOptions.kt /
   ImportExportMod.kt for the style). Prefix user-driven decisions with
   `William, <date>`.
6. **Don't touch what the user called perfect.** The 3x3/9-icon grid
   preview sizing is signed off; the stock Export button works; leave them
   alone unless asked.
7. **Commit every round** on `layout-tweaks` (`vNN: description`). The
   git history IS the backup — workspace artifacts (APKs/patches) get
   cleaned, history doesn't.

## Device facts (Pixel, 4x6 grid)

- Launcher db: `launcher_4_by_6.db` (name varies by grid — code discovers
  the largest `launcher*.db`).
- LauncherProvider authority: `com.google.android.apps.nexuslauncher.settings`
  (NOT the AOSP default). Provider throws
  `"same process should call model directly"` for in-process callers —
  write DB rows via direct `SQLiteDatabase` instead.
- `SQLiteDatabase.execSQL("PRAGMA ...")` throws (PRAGMAs return rows).
- `AppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)` — id
  FIRST. Widget host id: `LauncherAppWidgetHost.APPWIDGET_HOST_ID` (1024).
- `LauncherSettings$Favorites.CONTENT_URI` reflection fails on this build.
- Module prefs live in DEVICE-PROTECTED storage, not the app's data dir:
  `/data/user_de/0/com.drdisagree.pixellauncherenhanced/shared_prefs/com.drdisagree.pixellauncherenhanced_preferences.xml`
  (`/data/data/com.drdisagree.pixellauncherenhanced/shared_prefs/` is EMPTY —
  don't waste time looking there). Name comes from
  `Constants.SHARED_PREFERENCES` = `${applicationId}_preferences`.

## Current state (Sep 27, 2026)

- v1.2.0 release-signed (cert `e6026394…`), installed and working on device.
- Import (apps/folders/widgets) confirmed working end-to-end by William.
- Folder preview: grid-only, stock-anchored constants, animation fixes in.
- Built against **Android 17 / Pixel Launcher 907** as of v56. Before touching
  any hook, read the old→new field map in `CONTEXT.md` §4a — a missed
  `getFieldSilently` fails silently, so renames are the failure mode that
  never announces itself.
- 8 commits ahead of `fork/layout-tweaks`, unpushed. Backup branch:
  `backup/pre-merge-20260927`.
- No open bugs. Feature requests go through William first.
