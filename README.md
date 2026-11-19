<p align="center">
  <img src="assets/icons/app_icon.png" width="128" alt="PurePodcast Icon" />
</p>

# PurePodcast

[![Version: 1.1.0](https://img.shields.io/badge/version-1.1.0-blue.svg)](https://github.com/LycosLane/PurePodcast/releases)
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
* **Seamless Resume:** The mini-player remembers your last in-progress episode across app restarts, and the queue highlights your active or most recently played episode.
* **Per-Podcast Audio Controls:** Fine-tune playback speed (`0.5x` - `3.0x` slider with `0.02x` steps) and silence trimming (`Off`, `Soft`, `Medium`, `Strong`) individually for each podcast.
* **Comfortable Listening:** Smart rewind after pauses, optional volume normalization, a flexible sleep timer slider (`5` - `120` min) that continues across queued episodes, and configurable Bluetooth next/previous buttons (`-10s` / `+30s` seek or episode skip).
* **Chapters & Show Notes:** Navigate episode chapters and read show notes with pinch-to-zoom text scaling and a distraction-free reader view.

### Appearance & Settings
* **Light & Dark Themes:** Choose System, Light, or Dark mode, plus 6 curated accent colors separately for Light and Dark mode.
* **Fullscreen Settings:** Clean, self-explanatory settings screen with background sync intervals from `15` min to `24` hours.

### Quick Gestures
* **Swipe right:** Add episode to the queue.
* **Swipe left:** Hide or unhide an episode (or permanently delete it from Trash).
* **Double-tap:** Mark episode as played or unplayed.
* **Long-press:** Open batch and episode actions (*Download newer from here*, *Delete & mark older as played*, *Show Notes*, *Hide*).

### Offline Downloads, Storage & Backup
* **Automatic & Manual Downloads:** Download new or queued episodes over Wi-Fi or mobile data, limit maximum downloads per podcast, and automatically remove played downloads (*Immediately*, *1 day*, *3 days*, or *7 days*).
* **Trash Protection:** Deleted downloads move to an in-app trash (*Immediate*, *24h*, or *48h* retention) so you can restore accidentally removed episodes.
* **Storage-Saving Audio Compression:** Optionally compress downloaded episodes to AAC (*Low* 64 kbps, *Medium* 128 kbps, *High* 192 kbps) without ever upscaling already compact files, and inspect saved storage per episode.
* **OPML & Full Backup:** Import subscriptions from OPML files, or export and restore your entire library, playback positions, queue, and settings as a single backup file.
* **English & German UI:** Switch between English and German directly in Settings.

---

## Installation

Download the latest signed APK from the [**Releases**](https://github.com/LycosLane/PurePodcast/releases) page, or build from source:

```bash
git clone https://github.com/LycosLane/PurePodcast.git
cd PurePodcast
./gradlew assembleDebug
```

---

## License

MIT License © 2026 LycosLane
