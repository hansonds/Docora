# PDF ScanSign

Android app to scan documents, sign PDFs and fill PDF forms. By Hanson · [phoenicx.org](https://phoenicx.org)

**Scanning**
- In-app camera with live page edge detection and auto capture (hold steady), torch, page-by-page loop
- Import from photos (many at once)
- Manual corner/edge adjustment with magnifier; perspective correction
- Filters: Magic color (removes shadows), Original, Grayscale, Black & white
- Multi-page PDF: reorder, rotate, re-crop, delete; A4 / Letter / fit; 3 quality levels

**PDF editor** (any PDF: large 1000+ page files, password-protected, damaged files repaired)
- Smooth continuous scrolling, pinch / double-tap / button zoom with sharp re-rendering
- Fast-scroll handle and Go-to-page for long documents
- Fill PDF forms: text, checkboxes, radio buttons, dropdowns, signature fields; option to lock (flatten)
- Add signatures anywhere (drag, pinch or handle to resize, × to remove)
- Add text with font, size, colour and bold; "insert today's date"; works with any language

**Signatures**
- Photograph a signature on white paper (in-app camera with 4:3 guide) or pick a photo
- Fixed 4:3 crop, automatic background removal, ink thickness and colour (original / black / blue)

Saved PDFs appear in the app and in **Downloads › PDF ScanSign**. Works offline, no ads, no account.

## Building (GitHub does it for you)

1. Create a new **private** repository on GitHub (e.g. `hansonds/PDF-ScanSign`).
2. Upload everything in this folder, including the hidden `.github` folder and `app/scansign.jks`.
3. Open the **Actions** tab — the "Build APK" workflow runs automatically (about 5 minutes).
4. When it finishes, the APK is in **Releases** (and under the run's Artifacts).

The signing key (`app/scansign.jks`) is included so every build can install over the previous one. Keep the repository private.

## Tech

Kotlin, AndroidX, Material 3, OpenCV 4.10 (edge detection, perspective warp, filters), PdfBox-Android (PDF creation and signing). minSdk 26 (Android 8.0+), arm64 and armv7.
