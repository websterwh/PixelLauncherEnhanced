package com.drdisagree.pixellauncherenhanced.ui.fragments

import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.base.ControlledPreferenceFragmentCompat

// TEMPORARY (William, Aug 2026): whole fragment. See TEMP_MASTER_ENABLE /
// FOLDER_PREVIEW_ICON_SIZE_TEMP / FOLDER_PREVIEW_ICON_SPACING_TEMP in
// Constants.kt for removal instructions.
class TempMods : ControlledPreferenceFragmentCompat() {

    override val title: String
        get() = getString(R.string.fragment_temp_title)

    override val backButtonEnabled: Boolean
        get() = true

    override val layoutResource: Int
        get() = R.xml.temp_mods

    override val hasMenu: Boolean
        get() = false

    override val themeResource: Int
        get() = R.style.PrefsThemeCollapsingToolbar
}
