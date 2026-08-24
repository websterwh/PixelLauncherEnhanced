# CLAUDE.md

Claude Code: this project's operational rules live in **AGENTS.md** and the
full technical context (architecture, bug history, reverted approaches,
device quirks) lives in **CONTEXT.md**. Read both before making changes.

## Quick reference

- **What**: Xposed module fork (PixelLauncherEnhanced) hooking Google Pixel
  Launcher (`com.google.android.apps.nexuslauncher`) via LSPosed.
- **Build**: `.\gradlew.bat assembleRelease` (signed via gitignored
  `keystore.properties`) or `assembleDebug`.
- **Install**: `adb install -r <apk>` then
  `adb shell am force-stop com.google.android.apps.nexuslauncher`.
- **Branch**: `layout-tweaks`, commits `vNN: description`.
- **Golden rules**: evidence before any reflection API guess; one change per
  round; mark temporary code `TEMPORARY (William, <date>)`; never commit
  `keystore.properties` / `local.properties` / `*.jks`.
- **Known device traps** (details in CONTEXT.md §5–6): LauncherProvider
  refuses same-process calls; PRAGMAs throw via `execSQL`; module log tags
  don't appear in `logcat -s` (bridge tag swallows them); force-stop can
  blank recents until reboot.
