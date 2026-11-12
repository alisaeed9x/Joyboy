package com.newtube.mobile.downloads;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.liskovsoft.smartyoutubetv2.tv.R;

/** MEETUBE(download-folder): an invisible host for the system folder picker, so any screen can open it. */
public final class DownloadFolderActivity extends Activity {
    private static final int REQUEST_TREE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            return; // the picker is already up
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(pick, REQUEST_TREE);
        } catch (ActivityNotFoundException e) {
            com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_download_folder_failed);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri tree = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (tree != null) {
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                DownloadFolder.set(this, tree);
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, getString(R.string.mobile_download_folder_set, DownloadFolder.label(this)));
            } catch (SecurityException e) {
                com.newtube.mobile.ui.common.MobileSnackbar.show(this, R.string.mobile_download_folder_failed);
            }
        }
        finish();
    }
}
