package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.content.Intent;
import android.os.Bundle;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;

public class SplashActivity extends MotherActivity implements SplashView {
    private static final String TAG = SplashActivity.class.getSimpleName();
    private Intent mNewIntent;
    private SplashPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                "splash onCreate data=" + (getIntent() != null ? getIntent().getDataString() : null));
        mNewIntent = getIntent();

        if (restorePictureInPictureIfLauncherIntent(mNewIntent)) {
            finish();
            return;
        }

        // (v927) فتح البرنامج من أيقونة اللانشر = صفحة الميديا (Joyboy MainActivity) مش يوتيوب. يوتيوب بيتفتح من الشريط
        // السفلي / اللينكات (Intent من غير MAIN+LAUNCHER) فمش متأثرين.
        if (openJoyboyFromLauncher(mNewIntent)) {
            finish();
            overridePendingTransition(0, 0);
            return;
        }

        mPresenter = SplashPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        //finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                "splash onNewIntent data=" + (intent != null ? intent.getDataString() : null));
        mNewIntent = intent;

        if (restorePictureInPictureIfLauncherIntent(intent)) {
            finish();
            return;
        }

        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public Intent getNewIntent() {
        return mNewIntent;
    }

    @Override
    public void finishView() {
        try {
            finish();
        } catch (NullPointerException e) {
            // NullPointerException: Attempt to invoke virtual method 'void com.android.server.wm.DisplayContent.moveStack(com.android.server.wm.TaskStack, boolean)'
            e.printStackTrace();
        }
    }

    private boolean openJoyboyFromLauncher(Intent intent) {
        try {
            if (intent == null || !isTaskRoot() || !Intent.ACTION_MAIN.equals(intent.getAction())
                    || !intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.getData() != null) {
                return false;
            }
            Intent main = new Intent();
            main.setClassName(getPackageName(), "com.tttt.subtitler.MainActivity");
            if (main.resolveActivity(getPackageManager()) == null) {
                return false;
            }
            main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (intent.getExtras() != null) {
                main.putExtras(intent.getExtras());
            }
            startActivity(main);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean restorePictureInPictureIfLauncherIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || !(getApplication() instanceof MainApplication)) {
            return false;
        }
        return ((MainApplication) getApplication()).restorePictureInPictureFromLauncher(this);
    }
}
