<div align="center">

  <img src="src/jvmMain/resources/icon.ico" width="96" height="96" alt="CloudStream Windows Logo" />

  # CloudStream Desktop

  **A modern, lightning-fast native Windows desktop application for CloudStream.**  
  *Built with Kotlin, Compose Multiplatform & LibVLC.*

  [![Platform](https://img.shields.io/badge/Platform-Windows%2010%20%7C%2011-0078D4?style=for-the-badge&logo=windows&logoColor=white)](https://cdn.ntechbd.app/cloudstream/CloudStream%20Desktop.msi)
  [![Language](https://img.shields.io/badge/Kotlin-2.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Framework](https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)
  [![License](https://img.shields.io/badge/License-GPL%20v3.0-blue?style=for-the-badge)](LICENSE)

  <br />

  <img src="home.webp" width="100%" alt="CloudStream Windows Desktop Preview" />

</div>

---

## 🌟 Overview

**CloudStream Desktop** brings the power of CloudStream to Windows 10 & 11 with a native desktop interface following Windows 11 Fluent UI guidelines. It combines multi-threaded media indexing, built-in VLC playback core, automatic subtitle fetching, and native BDIX FTP extension support into a single high-performance app.

---

## ✨ Features

- 🎨 **Fluent Dark Theme**: Windows 11 Acrylic surface cards, smooth micro-animations, and clean typography.
- ⚡ **Multi-Threaded Stream Extractor**: Simultaneously queries video mirrors and automatically prioritizes 1080p / 4K high bitrate streams.
- 🎬 **Embedded LibVLC Engine**: Plays HLS (m3u8), MP4, MKV, AVI, and WebM seamlessly with low CPU overhead.
- 🔌 **BDIX & Plugin Ecosystem**: Native integration for BDIX FTP providers (CircleFTP, DiscoveryFTP, SamOnline, FTPBD, etc.) plus `.jar` plugin loaders.
- ⌨️ **Full Keyboard & D-Pad Support**: Complete keyboard shortcuts for power users (`Space`, `Arrows`, `F11`, `Escape`, `Ctrl+F`, `Alt+1..5`).
- 💬 **Multi-Language Subtitles**: Embedded subtitle track picker + automatic OpenSubtitles & Addic7ed search.
- 🔖 **Watch History & Progress**: Automatically saves playback progress and continues from where you left off.
- 📦 **Standalone Windows Installer**: Download direct `.msi` package with start menu shortcuts and clean installation.

---

## ⌨️ Keyboard Shortcuts Reference

| Shortcut | Action |
|---|---|
| **`Spacebar`** / **`K`** | Play / Pause video |
| **`Left Arrow`** / **`J`** | Rewind 10 Seconds |
| **`Right Arrow`** / **`L`** | Fast-Forward 10 Seconds |
| **`Up Arrow`** / **`Down Arrow`** | Adjust Volume (+10% / -10%) |
| **`M`** | Mute / Unmute audio |
| **`F`** / **`F11`** | Toggle Fullscreen Mode |
| **`Ctrl + F`** or **`/`** | Open Search Bar |
| **`Alt + 1`** ... **`5`** | Switch Tabs (Home, Search, Library, Plugins, Settings) |
| **`Escape`** / **`Backspace`** | Go Back to previous screen / Exit player |

---

## 📥 Installation

### Option 1: Direct MSI Installer (Recommended)
Download the latest pre-built Windows 64-bit installer:

👉 **[Download CloudStream Desktop (MSI)](https://cdn.ntechbd.app/cloudstream/CloudStream%20Desktop.msi)**

Run `CloudStream Desktop.msi` to complete setup.

### Option 2: Portable Build / Run from Source
Prerequisites: JDK 17 or higher.

The application includes its own Gradle 9.9.2 wrapper with a pinned SHA-256 distribution checksum. Kotlin 2.4.0's [fully supported Gradle range](https://kotlinlang.org/docs/gradle-configure-project.html) ends at 9.5.0; validate your build when changing either version. Existing plugins emit deprecation warnings.

```bash
# Clone all three repositories into one parent directory.
# Exact dependency revisions are recorded in build-dependencies.json.
git clone https://github.com/sheikhshariarnehal/Cloudstream-desktop.git Cloudstream-desktop-Application
git clone https://github.com/recloudstream/cloudstream.git cloudstream
git -C cloudstream checkout --detach 2dcc25c3945c61dfa95d1710ced7c74acfe84baa
git clone https://github.com/nehalDIU/nehal-CloudStream.git nehal-CloudStream
git -C nehal-CloudStream checkout --detach 673f061d7a796a693cb744c218e9f4141a9867fe
cd Cloudstream-desktop-Application

# Run regression tests
.\gradlew.bat jvmTest --max-workers=2

# Run application locally
.\gradlew.bat run

# Create MSI Installer Package
.\gradlew.bat packageMsi --max-workers=2
```

The compiled MSI installer will be generated at:
`build/compose/binaries/main/msi/CloudStream Desktop-1.0.3.msi`

Windows source builds also require a VLC installation matching the JVM architecture for playback. Provider registration and JVM tests do not verify every live provider or physical audio device. The Windows workflow checks out the same pinned dependencies, runs tests and packages the MSI.

---

## 🏗️ Architecture & Project Structure

```text
src/jvmMain/kotlin/com/lagradost/cloudstream/desktop/
├── Main.kt                 # Window entry point & global keyboard navigation
├── plugins/                # Plugin loader & native BDIX provider bindings
├── storage/                # Watch history & settings persistent data store
├── ui/
│   ├── components/         # Media cards, scroll listeners & UI primitives
│   ├── navigation/         # Sidebar navigation state & screen routes
│   ├── screens/
│   │   ├── home/           # Main catalog & category feeds
│   │   ├── search/         # Parallel provider search engine
│   │   ├── result/         # Media details & link extractor launcher
│   │   ├── player/         # LibVLC video player & control overlay
│   │   ├── library/        # Saved watchlist & watch history
│   │   ├── plugins/        # Local plugin installer & status
│   │   └── settings/       # App preferences & theme configuration
│   └── theme/              # Win11 color tokens & typography
└── utils/                  # Stream proxy & Coil 3 image loader setup
```

---

## 🛡️ License & Disclaimer

- **License**: Released under the [GNU General Public License v3.0](LICENSE).
- **Disclaimer**: CloudStream Desktop does not host, store, or transmit video content. It functions as an indexer and media player utilizing user-installed extensions and open APIs.

---

<div align="center">
  <sub>Built with ❤️ for the CloudStream community.</sub>
</div>
