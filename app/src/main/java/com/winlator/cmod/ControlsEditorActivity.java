package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.ui.UiStrings;
import com.winlator.cmod.ui.inputcontrols.ControlsEditorController;
import com.winlator.cmod.ui.toast.WinToast;

/**
 * Full-screen controls editor, opened from the Input Controls screen. All the editing logic lives
 * in ControlsEditorController, which the in-game editor (XServerDisplayActivity) uses as well.
 */
public class ControlsEditorActivity extends AppCompatActivity {
    private ControlsEditorController controller;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        AppUtils.hideSystemUI(this);
        setContentView(R.layout.controls_editor_activity);

        int profileId = getIntent().getIntExtra("profile_id", 0);
        ControlsProfile profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        if (profile == null) {
            Log.e("ControlsEditor", "Profile not found for id=" + profileId);
            WinToast.show(this, UiStrings.get(R.string.ui_no_profile_selected), Toast.LENGTH_SHORT);
            finish();
            return;
        }

        FrameLayout container = findViewById(R.id.FLContainer);
        // No Done button: this screen exits with the system Back, as it always did.
        controller = new ControlsEditorController(this, container, profile, false, null);
    }

    @Override
    protected void onPause() {
        if (controller != null) controller.flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (controller != null) controller.showMixWarningIfNeeded();
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.release();
        super.onDestroy();
    }

    // Every way out (system back, the editor's own close/save) ends in finish(), so the return
    // motion is applied here: the reverse of the shared-axis enter used to open this screen.
    // The result carries the profile the editor ended on (it can switch profiles in place), so
    // the in-game sidebar can keep that layout active instead of dropping to Disabled.
    @Override
    public void finish() {
        Intent result = new Intent();
        result.putExtra("profile_id", controller != null ? controller.getProfileId() : -1);
        setResult(Activity.RESULT_OK, result);
        super.finish();
        overridePendingTransition(R.anim.shared_axis_pop_enter, R.anim.shared_axis_pop_exit);
    }
}
