# QuickPDF

[![Build](https://github.com/RGB-Outl4w/quickpdf-android/actions/workflows/build.yml/badge.svg)](https://github.com/RGB-Outl4w/quickpdf-android/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/RGB-Outl4w/quickpdf-android)](https://github.com/RGB-Outl4w/quickpdf-android/releases/latest)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

A small, fast PDF reader for Android, built for studying. Open a textbook, a scanned book or your own notes and find anything in seconds.

[Download the latest APK](https://github.com/RGB-Outl4w/quickpdf-android/releases/latest) · [Website](https://rgb-outl4w.github.io/quickpdf-android/)

## Features

**Search**

- Full-text search across the whole document, with highlights on the page, previous/next navigation and a results list with snippets.
- Ignores case and accents: `cafe` finds `Café`, `елка` finds `ёлка`.
- Finds words hyphenated across a line break.
- Text is indexed in the background on first open and cached, so later searches are instant.

**Scanned books**

- Pages without a text layer can be made searchable with on-device OCR: ML Kit for Latin-script languages, Tesseract for Russian.
- The app detects scanned documents and offers to recognize them.

**Reading and navigation**

- Continuous vertical scrolling, pinch and double-tap zoom, and a distraction-free full screen.
- Night mode that inverts page colors.
- Edge scroller, go to page, table of contents, bookmarks and a page-thumbnail grid.
- Remembers the last page and bookmarks of every recent document.
- Opens PDFs from the file picker or from other apps via *Open with* and *Share*, including password-protected files.

## Installation

1. Download `QuickPDF-<version>.apk` from [Releases](https://github.com/RGB-Outl4w/quickpdf-android/releases/latest).
2. Open it on your device and allow installation from your browser or file manager when asked.

Requires Android 8.0 (API 26) or newer on an ARM device (`arm64-v8a` or `armeabi-v7a`).

Releases from 1.0.1 on are signed with the project's release key. Its certificate SHA-256 fingerprint is:

```
4F:FD:E9:15:9D:AA:CC:0B:A2:18:11:D5:91:6E:61:0D:E4:35:57:EA:F7:84:D7:D4:ED:7D:21:9C:F0:60:45:3E
```

Version 1.0.0 was signed with a development key. Uninstall it before installing a later version.

## Privacy

QuickPDF has no accounts, analytics or ads. Documents never leave the device. The only network access is a one-time download of the Russian OCR models (7.6 MB) from the [tesseract-ocr/tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) repository, pinned to a fixed commit and verified by checksum. Latin-script OCR runs through Google Play services, which manages its own model.

## Building from source

Requirements: JDK 17 and the Android SDK with platform 36.

```sh
./gradlew testReleaseUnitTest assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

### Release signing

Release builds are signed with the key described by these environment variables. If they are not set, the build falls back to the debug key so the APK still installs for testing.

| Variable | Description |
| --- | --- |
| `RELEASE_KEYSTORE_FILE` | Path to the keystore |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore and key password |
| `RELEASE_KEY_ALIAS` | Key alias |

GitHub Actions builds every push and pull request. The keystore is stored as the base64-encoded secret `RELEASE_KEYSTORE_BASE64`, alongside `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_ALIAS`. Pushing a tag such as `v1.2.0` builds, signs and publishes a GitHub release with the APK attached.

## Architecture

| Area | Implementation |
| --- | --- |
| UI | Kotlin, Jetpack Compose, Material 3 with dynamic color |
| Page rendering | Platform `PdfRenderer` on a dedicated thread, with an LRU bitmap cache |
| Text and outline | [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) |
| OCR | ML Kit text recognition (Latin), [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) (Russian) |

```
app/src/main/java/com/quickpdf/reader/
├── data/   Recent documents, reading positions, bookmarks
├── pdf/    Rendering, text extraction, search, OCR, index cache
└── ui/     Compose screens: home, reader, theme
```

## Limitations

- OCR covers Latin-script languages and Russian. Scanned books in other scripts open and read normally but can't be made searchable yet. PDFs with a text layer are searchable in any language.
- Each page renders as a single bitmap capped at 9 megapixels. Zooming in gains no extra sharpness on high-resolution tablets, and none beyond about 2.3× on a 1080p phone.
- Before Android 15, password-protected PDFs render from a decrypted copy in the app's private cache, deleted when the document is closed. Their text is not cached, so it is re-indexed on each open.

## License

QuickPDF is licensed under the [Apache License 2.0](LICENSE). Third-party components and their licenses are listed in [NOTICE](NOTICE).
