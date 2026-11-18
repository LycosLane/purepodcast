<p align="center">
  <img src="assets/icons/app_icon.png" width="128" alt="PurePodcast Fedora Microphone Icon" />
</p>

# PurePodcast

> Pure, fast, ad-free & open-source Android podcast player.
> Built natively with Kotlin, Jetpack Compose, Room, WorkManager, and AndroidX Media3 (ExoPlayer) — zero ads, zero tracking, zero bloatware.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B-0078D6.svg)]()
[![Zero Telemetry](https://img.shields.io/badge/Telemetry-Zero-brightgreen.svg)]()
[![No Ads](https://img.shields.io/badge/Ads-None-success.svg)]()

---

## Features

* **Thumbnail Overview & Unified Episodes View:** Switch between your subscribed podcasts and a unified episode feed with filters (Recent, In Progress, Downloaded, Favorites).
* **Local Folder Support:** Point PurePodcast at any local folder (SAF) to play offline audio files alongside online podcasts.
* **Direct Gestures:**
  * **Swipe right:** Add episode to queue.
  * **Swipe left:** Hide / unhide episode (or delete permanently in Trash).
  * **Double-tap:** Toggle played status.
  * **Long-press:** Action sheet (*Download newer from here*, *Delete & mark older as played*, *Show Notes*, *Favorite*, *Hide*).
* **Battery-Friendly & Offline-First:** Native `WorkManager` for conditional feed refreshes, resumable offline downloads, and optional AAC audio compression; `Media3 MediaSessionService` with **Silence-Skipping**, **variable speed**, **Chapters**, **Audio Normalization**, **Smart Rewind**, and **Sleep Timer**.
* **Easy Discovery & Backup:** Search via iTunes Search API with in-app feed preview, paste any RSS URL, import **OPML**, and export/import full JSON backups.

---

## Architecture

```text
app/src/main/java/com/lycoslane/purepodcast/
├── core/
│   ├── audio/       # Media3 ExoPlayer + MediaSessionService + PlayerController + AudioTranscoder
│   ├── database/    # Room entities, migrations, DAO, batch cleanup & queue queries
│   ├── download/    # WorkManager workers (resumable downloads, feed refresh, audio compression)
│   ├── local/       # SAF local folder scanner & embedded cover extractor
│   ├── network/     # SSRF-hardened HttpURLConnection, XmlPullParser (RSS/OPML/Chapters), Media3 DataSource
│   └── preferences/ # SharedPreferences-backed AppSettings StateFlow
├── domain/
│   └── repository/  # PodcastRepository facade
└── ui/
    ├── common/      # Shared image cache, formatters, zoomable reader & lightbox
    ├── home/        # Podcast grid, unified episode view & settings sheet
    ├── detail/      # Episode list, swipe gestures, preview sheet & trash
    ├── search/      # Podcast search, pager preview, RSS URL & OPML import
    ├── queue/       # Playback queue
    ├── player/      # Persistent mini-player & expandable full player
    ├── theme/       # Material 3 theming
    └── viewmodel/   # MainViewModel, SearchViewModel, PodcastDetailViewModel
```
