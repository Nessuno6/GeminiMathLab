# Gemini Math Lab

A handwriting notebook for maths on Android, in the style of Samsung Notes. Write with the S Pen or
your finger, and the app reads your handwriting and solves it step by step, on the device.

Made for Galaxy tablets with an S Pen, and it runs on any Android 12+ phone or tablet (64-bit).

## Install

1. Download the latest `GeminiMathLab-<version>.apk` from
   [Releases](https://github.com/Nessuno6/GeminiMathLab/releases/latest).
2. Open it on your device. Android asks you to allow installs from your browser or file manager the
   first time.

### Updates

The app updates itself from this repository's releases. Once a day, when it opens, it looks for a
newer release, shows what changed and asks before downloading. The first time, Android asks you to
allow Gemini Math Lab to install apps; turn it on, go back and tap **Update** again.

To check now, or to turn the daily check off: **Settings › About › Updates**. An update installs over
the app and keeps your notes.

## Features

**Solving**
- **Solve:** reads the line you wrote last, or what you circle with the lasso, and solves it step by
  step: equations, inequalities, $2\times 2$ and $3\times 3$ systems of equations, matrices and
  determinants (transpose, inverse, trace, powers, $2\times 2$ eigenvalues, matrix arithmetic),
  first-order differential equations, integrals (including explicit partial fractions), limits, sums,
  functions and piecewise functions.
- **2D typeset steps:** solution cards and the AI assistant render stacked fractions, radicals with
  overbars, superscripts/subscripts, integral/limit bounds, and 2D matrices natively in Compose.
- **Solve when you write =:** the answer appears when you finish a line with an equals sign.
- **Graphs:** circle a function and tap Graph to plot single or `;`-separated multi-curve functions
  with notable-point markers (roots, extrema, y-intercepts, intersections) and an interactive drag
  crosshair that snaps to key points. There's also a graphing calculator with a maths keyboard.
- **Quick LaTeX Pad:** write a formula and copy it as LaTeX.
- Handwriting recognition (Pix2Text-MFR on ONNX Runtime with XNNPACK acceleration and buffer
  reuse) and the symbolic solver run on the device.

**Writing**
- An infinite page with pinch zoom, spatially bucketed GPU ink caching with viewport culling, and
  Samsung Notes' tools: pens (fountain, calligraphy, pen, pencil, brush), highlighter, stroke and
  area eraser, lasso, tape, laser pointer (with a rainbow option), shapes, fill and layers.
- **Hold to shape:** keep the pen still at the end of a stroke and a rough line, triangle, rectangle,
  polygon or circle becomes a neat one.
- **Lasso:** move, resize, rotate and stretch a selection; change its style; convert it to text or
  maths; align handwriting; move it between layers.
- Stroke stabiliser, smoothed ink and pen-position prediction for low latency.
- **Explain with Gemini Nano:** on supported Galaxy devices, Gemini Nano explains a solution in
  words, offline.
- Pictures, multi-page PDF and image backgrounds, and many paper styles.

**Notes**
- Import Samsung Notes (`.sdocx`), `.iink`, multi-page PDFs (stacked vertically with lazy off-thread
  page decoding and a floating page jumper) and pictures; export to `.gmnote`, PDF, SVG and PNG.
- Fast startup with `.meta` note summaries, on-demand stroke hydration, and cached PNG thumbnails.
- Google Drive backup that keeps notes in sync across your devices, and a Bin for deleted notes.
- Use a phone as the toolbar of a tablet (Nearby Connections), and continue a note on another device
  with Android Handoff.

**Assistant (optional, your own key)**
- The AI assistant button at the end of the toolbar answers questions about the open page using Claude or
  Gemini. Add your API key in **Settings › AI assistant**; keys are stored encrypted on the device and sent
  only to that provider. Each question sends a picture of the page.

## Privacy

Handwriting is recognised and solved on the device. Notes leave the device only when Google Drive
backup is on, or when you ask the assistant a question (the page goes to the AI you chose). The update
check only asks GitHub for the latest release.

## Build

Requirements: JDK 17 or newer and the Android SDK (compile SDK 37).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

The debug APK is in `app/build/outputs/apk/debug/`. Release builds use R8 code and resource shrinking
(`app/proguard-rules.pro`), and GitHub Actions CI (`.github/workflows/ci.yml`) runs unit tests and
debug builds on pushes and pull requests targeting `main`.

### Releases

Release builds are signed with the key named in `keystore.properties` in the project folder. That
file is not in git:

```properties
storeFile=C:/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Keep a backup of the keystore. Updates only install over the app when they're signed with the same
key.

To publish a release:

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build it: `./gradlew :app:assembleRelease`.
3. Publish it, tagged `v` + `versionName`, with the APK attached:

```bash
gh release create v1.0.1 app/build/outputs/apk/release/app-release.apk#GeminiMathLab-1.0.1.apk --title "Gemini Math Lab 1.0.1" --notes "What changed"
```

The app's updater compares the release tag with its own `versionName` and downloads the first `.apk`
attached to the latest release.

## Licence and notices

Icons and pictures in the app come from third parties, including the Samsung Notes and Samsung
Settings apps; they are not licensed by this project. See
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) and [ONEUI-ICONS-LICENSE](ONEUI-ICONS-LICENSE).

Samsung, Galaxy, One UI and Samsung Notes are trademarks of Samsung Electronics Co., Ltd. This project
is not affiliated with or endorsed by Samsung.
