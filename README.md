# Office Viewer (Ofis Görüntüleyici)

An offline Android viewer for Microsoft Office documents. Files are converted to HTML on the
device and shown in a sandboxed WebView. The app has **no INTERNET permission**, so nothing
is ever uploaded.

## Supported formats

| Family     | Formats                                   | Status        |
|------------|-------------------------------------------|---------------|
| Word       | DOCX, DOCM, DOTX, DOTM, RTF               | ✅ phase 1    |
| Excel      | XLSX, XLSM, XLTX, XLTM, CSV, TSV          | ✅ phase 1    |
| PowerPoint | PPTX, PPTM, PPSX, POTX                    | ✅ phase 1    |
| Text       | TXT                                       | ✅ phase 1    |
| Legacy     | DOC, XLS, PPT                             | ⏳ phase 2    |
| Others     | VSDX, MPP, ONE, PUB, MSG                  | 🔜 later      |

What is rendered:

- **Word:** paragraphs and character formatting, styles and theme fonts, numbered/bulleted
  lists, tables (merged cells, shading, borders), images, text boxes, hyperlinks, headers and
  footers, footnotes/endnotes, page breaks.
- **Excel:** all visible sheets with tabs, number/date/percent formats, fonts, fills, borders,
  alignment, merged cells, column widths/row heights, hyperlinks, images anchored to cells
  (charts are shown as placeholders).
- **PowerPoint:** slides at native aspect ratio, layout/master inheritance, theme colours,
  shapes (common presets), text with bullets and autofit, pictures with cropping, tables,
  SmartArt (from its saved drawing), group shapes, backgrounds.

Viewer features: pinch zoom, search in document, share, print / save as PDF, open with
another app, recent files, Turkish and English UI, light/dark theme (Material You).

## Architecture

```
core/   Pure Kotlin/JVM library (no Android dependency) - OOXML/RTF/CSV -> HTML
  xml/      tiny DOM over XmlPull, OPC package reader with zip-bomb limits, colour helpers
  docx/     WordprocessingML converter (styles, numbering)
  xlsx/     SpreadsheetML converter + Excel number-format engine
  pptx/     PresentationML converter
  text/     RTF, CSV, plain text
app/    Jetpack Compose UI (Kotlin), minSdk 26, targetSdk 35
```

The core module is unit-tested on the JVM (`./gradlew :core:test`). Pass
`-PovExtra=/some/folder` to render every file in a folder as a smoke test; the HTML output
lands in `core/build/samples-out/`.

Security: all document text is HTML-escaped, only `http/https/mailto/tel` links are kept,
SVG images are dropped, XML DOCTYPE processing is disabled, and the WebView only serves the
generated page (every other request is blocked).

## Building

- Android Studio Ladybug or newer, or the command line with JDK 17 and the Android SDK:
  `./gradlew :app:assembleDebug`
- Every push builds APKs on GitHub Actions (artifact **OfficeViewer-apk**). Pushing a `v*`
  tag also attaches the APK to a GitHub release.

The release build is signed with the debug key so it installs directly; configure your own
signing key before publishing to Google Play.
