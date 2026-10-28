<p align="center">
  <img src="docs/icon.png" width="112" alt="Docora icon">
</p>

<h1 align="center">Docora — PDF Editor &amp; Scanner</h1>

<p align="center">
  Scan documents, edit, sign and fill PDFs on Android.<br>
  <b>Completely free</b> — no ads, no watermark, no account, works offline.<br>
  By Hanson · <a href="https://phoenicx.org">phoenicx.org</a>
</p>

<p align="center">
  <a href="https://github.com/hansonds/Docora/releases/latest/download/Docora.apk"><b>⬇ Download Docora.apk</b></a> ·
  <a href="https://hansonds.github.io/Docora/privacy/">Privacy policy</a>
</p>

---

## Features

**Scan**
- AI scanner (Google ML Kit, on-device): accurate edge detection, auto capture, removes shadows, stains and fingers
- Built-in camera with live edge detection as a backup (choose in Settings)
- Import from photos, adjust corners by hand with a magnifier
- Filters: Auto (default — colour or crisp black-and-white, picked per page), Magic colour, Original, Grayscale, Black & white; set your default in Settings
- Multi-page PDFs, A4 / Letter / fit, ID-card layout (both sides on one page)
- Searchable PDFs: on-device OCR adds a hidden text layer

**Read & edit** (any PDF — 1000+ pages, password-protected or damaged)
- Opens in full screen for distraction-free reading (can be turned off)
- Modern editor with a floating toolbar and tool dock, smooth zoom, fast scroll, go to page, night reading
- Sign, add text, draw, highlight, shapes, whiteout, images and stamps — move, resize and rotate
- Fill PDF forms in place; long-press to select and copy text; undo / redo
- Save (replace the original or save a copy), compress, share with size options, print

**Signatures**
- Photograph a signature — the background is removed automatically — or draw with your finger
- Share or save signatures as transparent PNG files

**PDF tools**
- Merge (drag to reorder), split, extract and organize pages
- Compress, PDF → JPG, images → PDF
- Extract text with OCR (English, German and other Latin-script languages; Hindi / Devanagari)
- Lock with a password (AES) or remove a password
- Watermark, page numbers

**Look**
- Light, dark or system theme, 7 accent colours, pure black mode
- Colourful modern icons; works on phones and tablets

Saved PDFs appear in the app and in **Downloads › Docora**.

## Privacy

Everything is processed on your phone. Your documents, scans and signatures are never uploaded. See the [privacy policy](https://hansonds.github.io/Docora/privacy/).

## Requirements

Android 8.0 or newer. The APK on this page works on all phones (64-bit and 32-bit).

## Google Play

Store listing material is in [`playstore/`](playstore): 512×512 icon, feature graphic, privacy policy and listing text ([STORE_LISTING.md](playstore/STORE_LISTING.md)). Every release also includes **Docora-playstore.aab** for uploading to Play Console.

## Building

GitHub Actions builds the app automatically on every push to `main` and publishes **Docora.apk** and **Docora-playstore.aab** under [Releases](https://github.com/hansonds/Docora/releases).

## Tech

Kotlin, AndroidX, Material 3, CameraX, OpenCV 4.12 (edge detection, perspective correction, filters), PdfBox-Android (PDF editing), Google ML Kit (document scanner, text recognition). minSdk 26, targetSdk 36, 16 KB page-size ready.
