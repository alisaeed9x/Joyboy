# Vanilla TV Launcher
Launcher لـ Android TV / Google TV (TCL). الحالة والخطة: `CLAUDE.md` و`docs/project-continuity/`.
البناء: ادفع على GitHub → Actions → artifact `vanilla-tv-<branch>-debug`. dependency واحدة: media3-exoplayer 1.10.1.
تحقق سريع بدون Gradle: `kotlinc -cp android.jar app/src/main/java/com/vanilla/tvlauncher/*.kt dev-tests/media3-stubs/*.kt -d out` + `dev-tests/BackRouterTest.kt`.
