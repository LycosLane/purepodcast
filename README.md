# PurePodcast

> Pure, fast, ad-free & open-source Android podcast player.  
> Built natively with Kotlin, Jetpack Compose, Room, WorkManager, and AndroidX Media3 (ExoPlayer) — zero ads, zero tracking, zero bloatware.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B-0078D6.svg)]()
[![Zero Telemetry](https://img.shields.io/badge/Telemetry-Zero-brightgreen.svg)]()
[![No Ads](https://img.shields.io/badge/Ads-None-success.svg)]()

---

## Why I Built This

Traditional podcast apps like Podcast Addict have become bloated with ads, tracking, and deeply nested menus.  
**PurePodcast** focuses purely on listening to free, open RSS podcasts:

* **Thumbnail Overview:** Switch between your subscribed podcasts in one tap.
* **Direct Gestures (No 15 Submenus):**
  * **Swipe right:** Add episode to queue.
  * **Swipe left:** Delete download or mark as played.
  * **Long-press:** Instant bottom sheet with *Download newer from here*, *Delete & mark all older as played*, *Show Notes*, and more.
* **Battery-Friendly & Offline-First:** Uses native `WorkManager` for scheduled feed refreshes and offline downloads, and `Media3 MediaSessionService` for background playback, lockscreen controls, **Silence-Skipping**, **variable speed**, and **Sleep Timer**.
* **Easy Discovery & Migration:** Search via iTunes Search API, paste any RSS URL, or import an **OPML** export directly from Podcast Addict.

---

## Architecture

```text
app/src/main/java/com/lycoslane/purepodcast/
├── core/
│   ├── audio/      # Media3 ExoPlayer + MediaSessionService + PlayerController
│   ├── database/   # Room entities, DAO, batch cleanup & queue queries
│   ├── download/   # WorkManager workers for offline MP3 downloads & feed refresh
│   └── network/    # Native HttpURLConnection + XmlPullParser (RSS/OPML) + iTunes Search
├── domain/
│   └── repository/ # PodcastRepository facade
└── ui/
    ├── home/       # Thumbnail grid overview
    ├── detail/     # Episode list, swipe gestures, long-press sheet, show notes
    ├── search/     # Podcast search, RSS URL & OPML import
    ├── queue/      # Playback queue
    └── player/     # Persistent mini-player & expandable full player
```
