# Docora — PDF Editor & Scanner

Free Android app to scan documents, edit, sign and fill PDFs. By Hanson · [phoenicx.org](https://phoenicx.org)

Everything is free: no ads, no watermark, no account, works offline.

**Scanning**
- AI scanner (Google ML Kit, on-device): best-in-class edge detection, auto capture, removes shadows / stains / fingers
- Built-in camera with live edge detection and auto capture (backup, selectable in Settings)
- Import photos; manual corner adjustment with magnifier; filters (Magic color, B&W, Grayscale)
- Multi-page PDF, A4 / Letter / fit, quality levels, ID-card layout (2 sides on one page)
- Searchable PDFs: on-device OCR adds an invisible text layer

**PDF editor** (any PDF: 1000+ pages, password-protected, damaged)
- Smooth zoom, fast-scroll, go to page
- Edit toolbar: Sign, Text, Draw (pen), Highlight, Shapes (box, circle, line, arrow), Whiteout, Image, Stamps (✓ ✗ APPROVED, PAID, date…), Pages, Get text
- Fill PDF forms in place (text, checkboxes, radio, dropdowns, signature fields), existing answers visible
- Undo / redo

**PDF tools**
- Merge, Split (every page / every N / ranges), Extract pages, Organize (drag to reorder, rotate, delete, duplicate, blank page)
- Compress, PDF → JPG, Images → PDF
- Extract text (OCR: Latin and Devanagari scripts), Make searchable
- Lock with password (AES), Remove password
- Watermark, Page numbers

**More**: Save / Save as (choose any folder), share with optional compression, print, night reading mode, finger-drawn signatures, launcher shortcuts (Scan / Open PDF / Tools), themes (light / dark / system, 7 colours, pure black), tablet layouts.

**Library**: search, sort, share, print, rename. Saved PDFs also go to **Downloads › Docora**.

**Download:** [Docora.apk](https://github.com/hansonds/Docora/releases/latest/download/Docora.apk)

## Building (GitHub does it for you)

1. Create a new **private** repository on GitHub (e.g. `hansonds/Docora`).
2. Upload everything in this folder, including the hidden `.github` folder and `app/scansign.jks`.
3. Open the **Actions** tab — the "Build APK" workflow runs automatically (about 5 minutes).
4. When it finishes, the APK is in **Releases** (and under the run's Artifacts).

The signing key (`app/scansign.jks`) is included so every build can install over the previous one. Keep the repository private.

## Tech

Kotlin, AndroidX, Material 3, OpenCV 4.10 (edge detection, perspective warp, filters), PdfBox-Android (PDF creation and signing). minSdk 26 (Android 8.0+), arm64 and armv7.
