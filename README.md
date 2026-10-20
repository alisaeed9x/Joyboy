# Subtitler

**An all-in-one Android media app for AI-powered video translation, video playback, music, lyrics, browsing, and media tools.**

Subtitler brings several everyday media workflows together in one Android application: watch videos with AI-assisted subtitles, browse the web, discover and play YouTube content, listen to local music with lyrics, and run useful media-processing tasks without constantly switching apps.

> **Project status:** Under active development. Features can vary by version, Android device, media source, network access, and configured AI services. This README describes capabilities found in the supplied project source; it does not claim that every online source or device is supported.

## Highlights

### 🎬 Video player and AI subtitle translation
- Play supported local videos and supported online video sources.
- Generate subtitles by transcribing speech and translating it with the configured AI workflow.
- Display original and translated text with configurable subtitle appearance.
- Save subtitle/progress data and resume supported translation work.
- Continue translation tasks in the background where Android permits.
- Review or retry failed/incomplete translation segments.
- Support for supported HLS (`.m3u8`) media sources; availability depends on the source and its access restrictions.
- Picture-in-picture support on compatible Android devices.
- Subtitle styling options, including font and display customization.

### 🧠 AI and speech recognition
- AI-assisted transcription and translation through configured Gemini-compatible service access.
- Optional on-device/offline speech recognition using supported Whisper/Sherpa components and downloaded models on compatible devices.
- A fallback workflow can use online transcription when offline recognition is unavailable or fails, depending on settings and credentials.
- Translation options for language, dialect, tone, and subtitle presentation.
- Live-caption/translation overlay functionality on supported Android versions and for audio sources that permit capture.
- Background translation/task monitoring.

**Important:** AI features may require a valid API key, internet access, sufficient quota, and appropriate configuration. Offline speech recognition may require downloading a model and a compatible 64-bit device. Live capture can be blocked by apps that restrict audio capture or use DRM.

### 🌐 Built-in web browser
- Browse websites inside Subtitler.
- Multiple tabs, including private/incognito tabs.
- Browsing history and bookmarks/favorites.
- Search the current page.
- Desktop-site mode and browser settings.
- Save/restore tabs when configured.
- Share or copy links, and download supported files/media.
- Clear cache, cookies, and browsing history.
- Ad-blocking option.
- Detect supported media links from web pages and offer them for playback in the integrated player when detection succeeds.

Private tabs are designed not to save normal browsing history, but this should not be treated as a guarantee of anonymity from websites, networks, or internet providers.

### ▶️ YouTube browsing and video access
- Open YouTube content from the dedicated YouTube area or browser.
- Search/open supported video links.
- Keep a local YouTube viewing history.
- Manage a list of channel subscriptions and import channel information from supported text/CSV sources.
- Open supported videos in Subtitler's player, with translation or playback-only options where available.

YouTube's website and media-delivery behavior can change. Sign-in, playback, link detection, and downloads may not work for every video or account.

### 🎵 Music player
- Scan and play supported local audio files, including common formats such as MP3, M4A, FLAC, OGG, and WAV when supported by Android.
- Browse music from the device library and folders.
- Playback queue and next-track controls.
- Shuffle, playback-speed controls, and sleep timer.
- Background playback through an Android media service.
- Music artwork/metadata display where available.
- Lyrics panel with manual lyric entry and local saved lyric versions.
- Search supported online lyric sources, including Musixmatch and YouTube Music-based retrieval.
- Display synchronized (LRC/timed) or plain lyrics when available.
- Translate foreign-language lyric lines into Arabic through the configured AI workflow.
- Adjust lyric timing and appearance.

Lyrics availability, timing accuracy, source access, and automatic translation depend on the song metadata, network, and source responses.

### 🧰 Video and media tools
The player includes a tools menu that can submit processing jobs to the task queue:

- **Trim video:** export a selected time range.
- **Extract audio:** save audio from a supported video as an audio file.
- **Create GIF:** generate an animated GIF from a selected part of a video, with duration and resolution/quality choices.
- **Compress/change resolution:** re-encode supported video to reduce size or change output resolution.
- **Burn subtitles into video (hard subtitles):** render available original or translated subtitles into the video image, with appearance options.

Processing time and output size depend on the input, chosen quality, device performance, and available storage. Online media may need to be downloaded to a temporary file before processing.

**Video merging:** A dedicated video-merge feature was not confirmed in the supplied source archive, so it is intentionally not advertised here.

### ⏳ Task queue and file library
- Track media-processing and translation jobs.
- View progress and task status.
- Pause, resume, retry, cancel, open, share, or delete completed jobs where supported.
- Keep translated subtitle files (SRT) alongside supported media workflows.
- Browse and organize local video files by folders, search, and sorting.
- Save playback/translation progress for supported items.

### 🎨 Personalization and settings
- Multiple visual themes.
- Controls for subtitle typography, colors, animation, and presentation.
- AI model, language, translation style, and audio-processing settings.
- Music, lyrics, background-work, and privacy-related browser settings.
- Backup/restore options for supported application data.

## Requirements

- Android 8.0 (API 26) or newer, based on the current app module configuration.
- Internet access for online browsing and online AI/lyrics services.
- An AI service API key for features that use that service.
- Additional storage and a compatible 64-bit device may be required for offline speech-recognition models.

## Getting started

1. Install a trusted APK from the project's official Releases page when a release is available.
2. Open Subtitler and grant only the Android permissions needed for the features you want to use.
3. Configure the required AI credentials for online AI features.
4. For offline transcription, download a supported model and enable the relevant setting if your device supports it.
5. Open a local video, supported online video, or local audio file and explore the relevant tools.

Do not install APKs from untrusted third-party sources.

## Build from source

The project uses Kotlin, Gradle, Android SDK tooling, AndroidX Media3/ExoPlayer, WebView, and native components for supported speech/media processing.

Typical local build command:

```bash
./gradlew assembleDebug
```

The repository's current Gradle configuration downloads a speech-recognition AAR during configuration/build if the expected local dependency is missing. A working internet connection is therefore needed for a clean build.

The debug APK is normally produced under:

```text
app/build/outputs/apk/debug/
```

Build configuration and dependency versions may change. Use a compatible JDK/Android SDK setup and inspect the current Gradle files before building.

## Security and privacy

- Never commit API keys, signing credentials, private tokens, or personal data.
- Use a local, untracked configuration or GitHub Actions secrets for sensitive build values.
- Review the permissions requested by the app and grant only what is needed.
- Web pages, audio, and video may be sent to third-party services when online AI features are used. Review those providers' privacy policies before sending sensitive content.
- Private browsing does not make network activity invisible to websites, employers, network operators, or internet providers.
- Do not redistribute media unless you have the necessary rights and permissions.

## Known limitations

- Online media detection and playback depend on source websites and can break when those sites change.
- DRM-protected content and some live streams may not be available for extraction or translation.
- Offline transcription requires a supported model and compatible hardware.
- AI-generated transcripts, translations, and lyrics may contain errors and should be reviewed when accuracy matters.
- A feature appearing in the code does not guarantee flawless behavior on every Android device; releases should be tested before being marked stable.

## Releases

Stable builds should be published on the GitHub Releases page with:
- A version tag such as `v1.0.0`.
- The installable APK attached as a release asset.
- A changelog describing new features, fixes, and known issues.
- A note indicating whether the build is stable, beta, or experimental.

## License

No project license was confirmed in the supplied archive. Until a license is added to the repository, users should not assume that the source code is available for unrestricted reuse or redistribution.

## Developer

Maintained by [alisaeed9x](https://github.com/alisaeed9x).

- **Repository:** https://github.com/alisaeed9x/Subtitler
- **Issues and feedback:** Use the repository's GitHub Issues page when enabled.
