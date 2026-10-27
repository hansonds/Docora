# PDF ScanSign

Android app to scan documents and sign PDFs.

- **Scan** with your phone's camera app or pick photos from the gallery (many at once)
- **Auto edge detection** finds the page corners; drag corners or edges to fix them by hand (with a magnifier)
- **Perspective correction** straightens the page
- **Filters:** Original, Magic color (removes shadows), Grayscale, Black & white
- **Make a PDF** from all pages: reorder, rotate, re-crop or delete pages first; A4 / Letter / fit-to-image, three quality levels
- **Signatures:** photograph a signature on paper (or pick a photo); the paper background is removed automatically, with sliders for removal strength and ink thickness and a choice of original / black / blue ink
- **Sign any PDF:** place one or more signatures on any page, drag to move, pinch or drag the blue dot to resize, red × to remove
- Opens PDFs shared from other apps; saved PDFs also go to **Downloads › PDF ScanSign**
- Works fully offline, no ads, no accounts

## Building (GitHub does it for you)

1. Create a new **private** repository on GitHub (e.g. `hansonds/PDF-ScanSign`).
2. Upload everything in this folder, including the hidden `.github` folder and `app/scansign.jks`.
3. Open the **Actions** tab — the "Build APK" workflow runs automatically (about 5 minutes).
4. When it finishes, the APK is in **Releases** (and under the run's Artifacts).

The signing key (`app/scansign.jks`) is included so every build can install over the previous one. Keep the repository private.

## Tech

Kotlin, AndroidX, Material 3, OpenCV 4.10 (edge detection, perspective warp, filters), PdfBox-Android (PDF creation and signing). minSdk 26 (Android 8.0+), arm64 and armv7.
