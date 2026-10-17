package com.winlator.cmod.ui.library

import com.winlator.cmod.R
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.winlator.cmod.container.SaveProfiles
import com.winlator.cmod.container.Shortcut
import com.winlator.cmod.ui.ThemedAlertHost
import com.winlator.cmod.ui.toast.WinToast

// After a shortcut is removed: offer to delete its save folder in Winlator/saves too. Only for a
// game's own profile that no other shortcut (a copy in another container) still uses; the shared
// _Common profile is never offered. Keeping the saves is the default (Cancel).
object SaveProfileCleanup {
    @JvmStatic
    fun offerDelete(activity: AppCompatActivity, shortcut: Shortcut) {
        if (!SaveProfiles.isOwn(shortcut)) return
        Thread {
            val dir = SaveProfiles.findProfileDir(shortcut) ?: return@Thread
            if (SaveProfiles.isUsedByOtherShortcut(activity, shortcut)) return@Thread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                ThemedAlertHost.confirm(
                    activity,
                    activity.getString(R.string.ui_delete_saves_title),
                    activity.getString(R.string.ui_delete_saves_message, shortcut.name, SaveProfiles.displayPath(dir)),
                    activity.getString(R.string.ui_delete),
                    Runnable {
                        Thread {
                            val ok = SaveProfiles.deleteProfile(shortcut)
                            activity.runOnUiThread {
                                WinToast.show(activity, if (ok) activity.getString(R.string.ui_saves_deleted) else activity.getString(R.string.ui_saves_delete_failed), Toast.LENGTH_SHORT)
                            }
                        }.start()
                    },
                    true
                )
            }
        }.start()
    }
}
