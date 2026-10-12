# QuickPDF

A small, fast Android PDF reader for studying: open a textbook, scanned book or your own notes and find anything in seconds.

## Features

- **Full-text search** across the whole document, with match highlighting on the page, previous/next navigation and a results list with snippets. Search ignores case and accents (`cafe` finds `Café`, `елка` finds `ёлка`) and finds words hyphenated across a line break. The text is indexed in the background on first open and cached, so the next search is instant.
- **Scanned books are searchable too**: pages without a text layer can be run through on-device text recognition. Latin-script books use ML Kit; Russian books use Tesseract, whose models (7.6 MB) are downloaded the first time. The app suggests this when it detects a scanned document.
- **Fast navigation**: a draggable scroller on the right edge, go to page, table of contents, bookmarks and a page-thumbnail grid.
- **Comfortable reading**: continuous vertical scrolling, pinch and double-tap zoom, tap for a distraction-free full screen, and a night mode that inverts page colors.
- **Picks up where you left off**: recent files remember your last page and bookmarks.
- Opens PDFs from the file picker, or from other apps through "Open with" or "Share". Password-protected PDFs ask for the password.

## Tech

Kotlin and Jetpack Compose (Material 3, dynamic color). Pages are rendered with the platform `PdfRenderer`, so there's no native PDF engine to bundle. Text extraction and the table of contents use [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android). OCR uses ML Kit text recognition through Google Play services, so the model isn't bundled in the APK. Cyrillic OCR uses [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) with the `rus+eng` models from [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast), downloaded from a pinned commit and checked against their SHA-1. That download is the only thing the app uses the network for.

Requires Android 8.0 (API 26) or newer.

## Build

```sh
./gradlew assembleRelease    # app/build/outputs/apk/release/app-release.apk
./gradlew testReleaseUnitTest
```

Each push also builds the APK on GitHub Actions; download it from the run's **QuickPDF-apk** artifact. The release build is signed with the debug key so it installs directly; set up your own signing config before publishing it anywhere.

## Limitations

- OCR covers Latin-script languages and Russian. Scanned books in other scripts still open and read normally but can't be made searchable yet. PDFs that already have a text layer are searchable in any language.
- Before Android 15, a password-protected PDF is rendered from a decrypted copy in the app's private cache, deleted when the document is closed. Text of password-protected PDFs is not cached, so it's re-indexed on each open.
