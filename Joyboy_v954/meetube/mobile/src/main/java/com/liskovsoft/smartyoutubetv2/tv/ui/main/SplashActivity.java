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
        // (v939) ضغطة أيقونة اللانشر — باردة أو دافية — لازم تروح لواجهة Joyboy، وعمرها ما تقع على تصفّح يوتيوب
        if (isLauncherMain(mNewIntent)) {
            if (openJoyboyFromLauncher(mNewIntent)) {
                finish();
                overridePendingTransition(0, 0);
                return;
            }
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

        // JOYBOY(v932): على التلفزيون ضغطة الأيقونة تاني = لانشر التلفزيون مش صفحة MeeTube
        // JOYBOY(v939): نفس منطق onCreate — قرار التلفزيون هنا كمان بيعتمد على تفضيل ui_mode مش الهاردوير بس
        if (openJoyboyFromLauncher(intent)) {
            finish();
            overridePendingTransition(0, 0);
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

    /**
     * JOYBOY(v932): على التلفزيون الحقيقي أيقونة البرنامج تفتح لانشر التلفزيون (TvActivity) مش MainActivity بتاعة الموبايل
     * ولا صفحة يوتيوب (v927 كان بيحوّل لـMainActivity على كل الأجهزة، فالتلفزيون كان بيفتح على شكل الموبايل / يوتيوب).
     */
    private boolean isRealTv() {
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                return true;
            }
            return getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * JOYBOY(v939): «تلفزيون» هنا = هاردوير التلفزيون أو تفضيل ui_mode=tv بتاع Joyboy (نفس تعريف DeviceUi.isTv
     * في :app — :mobile مايقدرش ينده DeviceUi فبنقرا نفس ملف التفضيلات "p" ونفس المفتاح بالاسم).
     * من غير ده أي TCL/ستيك مابيبلّغش LEANBACK كان بيقع على يوتيوب، والموبايل في وضع TV كان بيتجاهل تمامًا.
     */
    private boolean isTvMode() {
        if (isRealTv()) {
            return true;
        }
        try {
            String m = getSharedPreferences("p", MODE_PRIVATE).getString("ui_mode", "auto");
            return "tv".equals(m);
        } catch (Throwable e) {
            return false;
        }
    }

    /** JOYBOY(v939): ضغطة أيقونة لانشر حقيقية (MAIN + LAUNCHER/LEANBACK + من غير data) — دي اللي لازم تروح لـJoyboy مش للـpresenter. */
    private boolean isLauncherMain(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && (intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                        || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))
                && intent.getData() == null;
    }

    private boolean openJoyboyFromLauncher(Intent intent) {
        try {
            boolean tv = isTvMode();
            boolean launcher = intent != null && (intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                    || (tv && intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)));
            if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                    || !launcher || intent.getData() != null) {
                return false;
            }
            Intent main = new Intent();
            main.setClassName(getPackageName(), tv ? "com.tttt.subtitler.TvActivity" : "com.tttt.subtitler.MainActivity");
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
