package com.drdisagree.pixellauncherenhanced.ui.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.drdisagree.pixellauncherenhanced.R
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setBackgroundResource
import com.drdisagree.pixellauncherenhanced.ui.preferences.Utils.setFirstAndLastItemMargin
import com.google.android.material.button.MaterialButton

// William, Aug 2026: single preference row holding the Backup and Restore
// buttons side by side, instead of two separate PreferenceMenu rows - keeps
// the settings-backup feature as one visual block.
class BackupRestorePreference : Preference {

    private var mOnBackupClick: () -> Unit = {}
    private var mOnRestoreClick: () -> Unit = {}

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    constructor(context: Context) : super(context) {
        init()
    }

    private fun init() {
        isSelectable = false
        layoutResource = R.layout.custom_preference_backup_restore
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.findViewById<MaterialButton>(R.id.btn_backup)?.apply {
            isEnabled = this@BackupRestorePreference.isEnabled
            setOnClickListener { mOnBackupClick.invoke() }
        }

        holder.itemView.findViewById<MaterialButton>(R.id.btn_restore)?.apply {
            isEnabled = this@BackupRestorePreference.isEnabled
            setOnClickListener { mOnRestoreClick.invoke() }
        }

        setFirstAndLastItemMargin(holder)
        setBackgroundResource(holder)
    }

    fun setOnBackupClick(onClick: () -> Unit) {
        mOnBackupClick = onClick
    }

    fun setOnRestoreClick(onClick: () -> Unit) {
        mOnRestoreClick = onClick
    }
}
