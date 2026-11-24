<p align="center">
  <img src="assets/icons/app_icon.png" width="128" alt="PurePodcast Icon" />
</p>

# PurePodcast

[![Version: 1.3.0](https://img.shields.io/badge/version-1.3.0-blue.svg)](https://github.com/LycosLane/PurePodcast/releases)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B-0078D6.svg)](https://github.com/LycosLane/PurePodcast)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Zero Telemetry](https://img.shields.io/badge/Telemetry-Zero-brightgreen.svg)]()
[![No Ads](https://img.shields.io/badge/Ads-None-success.svg)]()

> **A clean, open-source podcast player for Android.**  
> Subscribe to podcasts, download episodes for offline listening, or play local audio folders directly from your phone or SD card. No account, no ads, and no tracking.

---

## Key Features

### Library & Discovery
* **Podcast Search & RSS Support:** Find podcasts via search and preview episodes before subscribing, or add any RSS/Atom feed URL directly.
* **Grid & Episode Views:** Switch between your podcast grid and a unified episode feed with quick filters (*Recent 14 days*, *In progress*, *Downloaded*, *Favorites*).
* **Episode Search & Sorting:** Search episode titles and show notes within a podcast, and sort episodes newest-first or oldest-first per show.
* **Local Audio Folders:** Connect a folder on your device or SD card so subfolders appear alongside your subscribed podcasts with their cover art.

### Playback & Queue
* **Seamless Resume & Reordering:** The mini-player remembers your last in-progress episode across app restarts. Drag-and-drop episodes in the queue using the left handle, long-press for quick actions (*Play next*, *Download*, *Remove*, *Delete download*), or switch to Edit mode for batch cleanup.
* **Global & Per-Podcast Audio Controls:** Set global defaults for playback speed (`0.5x` - `3.0x` slider with `0.02x` steps), silence trimming (`Off`, `Soft`, `Medium`, `Strong`), smart rewind (`Off` or `5` - `30` sec), volume normalization, and Mono audio / Audio-Fix (`L+R`, `Smart`, `L`, `R`). Override any setting per podcast (or per episode for Mono), reset overrides back to global with one tap, or apply a show's audio setup to all podcasts.
* **Comfortable Listening:** A flexible sleep timer slider (`5` - `120` min) that continues across queued episodes, and configurable Bluetooth next/previous buttons (`-10s` / `+30s` seek or episode skip).
* **Chapters & Show Notes:** Navigate episode chapters and read show notes with pinch-to-zoom text scaling and a distraction-free reader view.

### Appearance & Settings
* **Light & Dark Themes:** Choose System, Light, or Dark mode, plus 6 curated accent colors separately for Light and Dark mode.
* **Global & Podcast Settings:** Manage app-wide defaults in a dedicated **Global settings** screen (with background sync intervals from `15` min to `24` hours), and customize audio or episode order per show in **Podcast settings**.

### Quick Gestures
* **Swipe right:** Add episode to the queue.
* **Swipe left:** Hide or unhide an episode (or permanently delete it from Trash).
* **Double-tap:** Mark episode as played or unplayed.
* **Long-press:** Open batch and episode actions (*Download newer from here*, *Delete & mark older as played*, *Show Notes*, *Hide*).

### Offline Downloads, Storage & Backup
* **Automatic & Manual Downloads:** Download new or queued episodes over Wi-Fi (with an optional one-time cellular confirmation showing file size when Wi-Fi-only is active), add all existing downloads to the queue with one tap, limit maximum downloads per podcast, and automatically remove played downloads (*Immediately*, *1 day*, *3 days*, or *7 days*) with a storage confirmation prompt on settings exit.
* **Smart New-Episode Notifications:** Optionally receive a single grouped notification after background syncs, downloading new episodes first when Wi-Fi and auto-download are available (*downloaded* vs. *available*).
* **Trash Protection:** Deleted downloads move to an in-app trash (*Immediate*, *24h*, or *48h* retention) so you can restore accidentally removed episodes.
* **Storage-Saving Audio Compression:** Optionally compress downloaded episodes to AAC (*Low* 64 kbps Mono, *Medium* 128 kbps, *High* 192 kbps) without ever upscaling already compact files, and inspect saved storage per episode.
* **OPML & Full Backup:** Import subscriptions from OPML files, or export and restore your entire library, playback positions, queue, and settings as a single backup file.
* **English & German UI:** Switch between English and German directly in Settings.

---

## Installation

### Option 1: Direct APK Download
Download and install the latest signed APK from the [**GitHub Releases**](https://github.com/LycosLane/purepodcast/releases) page.

### Option 2: F-Droid / Droid-ify / Neo Store (Recommended)
Add the LycosLane F-Droid repository for 1-tap installation and automatic updates:

1. Install **F-Droid**, **Droid-ify**, or **Neo Store** on your Android device.
2. Open **Repositories -> Add repository** and paste this URL:
   `https://lycoslane.github.io/fdroid/repo`
3. Refresh repositories, search for **PurePodcast**, and tap **Install**.

### Option 3: Automatic Updates with Obtainium
[Obtainium](https://github.com/ImranR98/Obtainium/releases) lets you install and update Android apps directly from GitHub Releases with one tap:

1. Install Obtainium from its [GitHub Releases](https://github.com/ImranR98/Obtainium/releases) page (Obtainium can also track and update itself automatically).
2. Open Obtainium, tap **Add App**, and paste this repository URL:
   `https://github.com/LycosLane/purepodcast`
3. Tap **Add**, then **Install**. Future releases will appear automatically with a 1-tap **Update** button.

### Option 4: Build from Source

```bash
git clone https://github.com/LycosLane/purepodcast.git
cd purepodcast
./gradlew assembleDebug
```

---

## License

MIT License © 2026 LycosLane
