package com.liskovsoft.appupdatechecker2.core;

import android.net.Uri;

public interface AppVersionCheckerListener {
	/**
	 * NEWTUBE(update-flow): the whole parsed manifest (it used to be only the changelog newer than the
	 * installed version, flattened), so the update screen can show every version's notes separately.
	 */
	void onManifestReceived(UpdateManifest manifest, Uri[] downloadUris);
	void onCheckError(Exception e);
	void processDownloadUrls(Uri[] downloadUrls);
}
