package com.drdisagree.pixellauncherenhanced.data.common

import com.drdisagree.pixellauncherenhanced.BuildConfig
import com.drdisagree.pixellauncherenhanced.xposed.utils.BootLoopProtector

object Constants {

    // Shared Preference File
    const val SHARED_PREFERENCES = "${BuildConfig.APPLICATION_ID}_preferences"

    // System packages
    const val FRAMEWORK_PACKAGE = "android"
    const val PIXEL_LAUNCHER_PACKAGE = "com.google.android.apps.nexuslauncher"
    const val LAUNCHER3_PACKAGE = "com.android.launcher3"

    // Preferences
    const val VIBRATE_UI = "vibrate_ui"
    const val XPOSED_HOOK_CHECK = "xposed_hook_check"
    const val ACTION_HOOK_CHECK_REQUEST = "${BuildConfig.APPLICATION_ID}.ACTION_HOOK_CHECK_REQUEST"
    const val ACTION_HOOK_CHECK_RESULT = "${BuildConfig.APPLICATION_ID}.ACTION_HOOK_CHECK_RESULT"
    const val ACTION_APP_LIST_UPDATED = "${BuildConfig.APPLICATION_ID}.ACTION_APP_LIST_UPDATED"
    const val FORCE_THEMED_ICONS = "xposed_forcethemedicons"
    const val APP_DRAWER_THEMED_ICONS = "xposed_appdrawerthemedicons"
    const val APP_DRAWER_BACKGROUND_OPACITY = "xposed_appdrawerbackgroundopacity"
    const val RECENTS_BACKGROUND_OPACITY = "xposed_recentsbackgroundopacity"
    const val DISABLE_RECENTS_LIVE_TILE = "xposed_disablerecentslivetile"
    const val DOUBLE_TAP_TO_SLEEP = "xposed_doubletaptosleep"
    const val ALLOW_WALLPAPER_ZOOMING = "xposed_allowwallpaperzooming"
    const val LAUNCHER_HIDE_STATUSBAR = "xposed_launcherhidestatusbar"
    const val LAUNCHER_HIDE_TOP_SHADOW = "xposed_launcherhidetopshadow"
    const val LAUNCHER_DARK_STATUSBAR = "xposed_launcherdarkstatusbar"
    const val LAUNCHER_DARK_PAGE_INDICATOR = "xposed_launcherdarkpageindicator"
    const val DESKTOP_ICON_LABELS = "xposed_desktopiconlabels"
    const val APP_DRAWER_ICON_LABELS = "xposed_appdrawericonlabels"
    const val HIDE_AT_A_GLANCE = "xposed_hideataglance"
    const val DESKTOP_SEARCH_BAR = "xposed_desktopsearchbar"
    const val DESKTOP_DOCK_SPACING = "xposed_desktopdockspacing"
    const val LAUNCHER_ICON_SIZE = "xposed_launchericonsize"
    const val LAUNCHER_TEXT_SIZE = "xposed_launchertextsize"
    const val RESTART_LAUNCHER = "xposed_restartlauncher"
    const val DEVELOPER_OPTIONS = "xposed_developeroptions"
    const val ENTRY_IN_LAUNCHER_SETTINGS = "xposed_entryinlaunchersettings"
    const val ENTRY_IN_OPTIONS_POPUP = "xposed_entryinoptionspopup"
    const val LOCK_LAYOUT = "xposed_locklayout"
    const val DRAWER_SEARCH_BAR = "xposed_drawersearchbar"
    const val RECENTS_CLEAR_ALL_BUTTON = "xposed_recentsclearallbutton"
    const val FIXED_RECENTS_BUTTONS_WIDTH = "xposed_fixedrecentsbuttonswidth"
    const val DESKTOP_GRID_ROWS = "xposed_desktopgridrows"
    const val DESKTOP_GRID_COLUMNS = "xposed_desktopgridcolumns"
    const val DESKTOP_DOCK_COLUMNS = "xposed_desktopdockcolumns"
    const val FOLDER_GRID_ROWS = "xposed_foldergridrows"
    const val FOLDER_GRID_COLUMNS = "xposed_foldergridcolumns"

    // William, Aug 2026: the independent rows/columns sliders above let him
    // dial in folder grid sizes beyond 4x4, but on-device testing (Aug 2026)
    // showed anything past 4x4 doesn't actually work (items just don't fill
    // the extra rows/columns) - likely a hard limit elsewhere in the
    // launcher. Replaced the two sliders with one on/off toggle for the one
    // size that's confirmed to work. FOLDER_GRID_ROWS/FOLDER_GRID_COLUMNS
    // above are no longer read from any preference screen but are left
    // defined since GridOptions.kt's hooks still key off their values
    // internally (now driven by this toggle instead of a slider).
    const val FOLDER_GRID_4X4_ENABLE = "xposed_foldergrid4x4"
    const val DESKTOP_ICON_HORIZONTAL_MARGIN = "xposed_desktopiconhorizontalmargin"
    const val DOCK_BOTTOM_SPACING = "xposed_dockbottomspacing"
    const val FOLDER_PREVIEW_ICON_COUNT = "xposed_folderpreviewiconcount"

    const val APP_DRAWER_GRID_COLUMNS = "xposed_appdrawergridcolumns"
    const val APP_DRAWER_GRID_ROW_HEIGHT_MULTIPLIER = "xposed_appdrawergridrowheightmultiplier"
    const val APP_BLOCK_LIST = "xposed_appblocklist"
    const val HIDE_APPS_FROM_APP_DRAWER = "xposed_hideappsfromappdrawer"
    const val SEARCH_HIDDEN_APPS = "xposed_searchhiddenapps"
    const val TOGGLE_HIDE_APPS_IN_OPTIONS_POPUP = "xposed_togglehideappsinoptionspopup"
    const val REMOVE_ICON_BADGE = "xposed_removeiconbadge"
    const val RECENTS_REMOVE_SCREENSHOT_BUTTON = "xposed_recentsremovescreenshotbutton"
    const val THEMED_ICON_CUSTOM_COLOR = "xposed_themediconcustomcolor"
    const val THEMED_ICON_CUSTOM_FG_COLOR_LIGHT = "xposed_themediconcustomfgcolorlight"
    const val THEMED_ICON_CUSTOM_BG_COLOR_LIGHT = "xposed_themediconcustombgcolorlight"
    const val THEMED_ICON_CUSTOM_FG_COLOR_DARK = "xposed_themediconcustomfgcolordark"
    const val THEMED_ICON_CUSTOM_BG_COLOR_DARK = "xposed_themediconcustombgcolordark"
    const val FOLDER_CUSTOM_COLOR_LIGHT = "xposed_foldercustomcolorlight"
    const val FOLDER_CUSTOM_COLOR_DARK = "xposed_foldercustomcolordark"
    const val DESKTOP_SEARCH_BAR_OPACITY = "xposed_desktopsearchbaropacity"
    const val FREEFORM_GESTURE = "xposed_startfreeformbygesture"
    const val FREEFORM_MODE = "xposed_freeform_mode"
    const val FREEFORM_GESTURE_PROGRESS = "xposed_startfreeformprogress"
    const val HIDE_GESTURE_PILL = "xposed_hidegesturepill"
    const val HIDE_NAVIGATION_SPACE = "xposed_hidenavigationspace"
    const val QUICK_LAUNCH = "xposed_quicklaunch"
    const val PREVENT_WALLPAPER_DIMMING_RESTART = "xposed_preventwallpaperdimmingrestart"
    const val BACKUP_RESTORE_SETTINGS = "xposed_backuprestoresettings"

    // William, Aug 2026: adds a real "Uninstall" entry to the long-press
    // popup, built from SecondaryDropTarget.getUninstallTarget()/
    // performUninstall() + SystemShortcut$AppInfo's mode=2 constructor (see
    // LauncherSettings.kt). UNINSTALL_IN_CONTEXT_MENU was the original
    // on/off SwitchPreference key - superseded by the 4-way
    // UNINSTALL_IN_CONTEXT_MENU_SCOPE ListPreference below (off/home
    // screen only/app drawer only/both) and no longer read anywhere, kept
    // only so an old stored Boolean under this key doesn't collide with
    // the new String-valued key.
    const val UNINSTALL_IN_CONTEXT_MENU = "xposed_uninstallincontextmenu"
    const val UNINSTALL_IN_CONTEXT_MENU_SCOPE = "xposed_uninstallincontextmenu_scope"

    val PREF_UPDATE_EXCLUSIONS = listOf(
        BootLoopProtector.LOAD_TIME_KEY_KEY,
        BootLoopProtector.PACKAGE_STRIKE_KEY_KEY,
    )
}