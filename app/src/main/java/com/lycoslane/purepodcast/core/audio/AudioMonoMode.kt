package com.lycoslane.purepodcast.core.audio

/**
 * Mono downmix / channel-fix mode used across Global Settings, Podcast overrides,
 * and per-Episode Audio-Fix overrides.
 */
object AudioMonoMode {
    const val INHERIT = -1
    const val OFF = 0
    const val MIX = 1
    const val AUTO = 2
    const val LEFT = 3
    const val RIGHT = 4

    fun normalizeConcrete(mode: Int): Int = if (mode in OFF..RIGHT) mode else OFF

    fun normalizeOverride(mode: Int): Int = if (mode in INHERIT..RIGHT) mode else INHERIT

    fun resolveEffective(
        globalMonoMode: Int,
        podcastMonoOverride: Int,
        episodeMonoOverride: Int
    ): Int {
        val ep = normalizeOverride(episodeMonoOverride)
        if (ep != INHERIT) return ep
        val pod = normalizeOverride(podcastMonoOverride)
        if (pod != INHERIT) return pod
        return normalizeConcrete(globalMonoMode)
    }
}

/**
 * Three-state boolean override used by per-podcast settings that inherit a global default.
 */
object AudioOverride {
    const val INHERIT = -1
    const val OFF = 0
    const val ON = 1

    fun normalize(value: Int): Int = if (value in INHERIT..ON) value else INHERIT

    fun resolve(globalEnabled: Boolean, podcastOverride: Int): Boolean = when (normalize(podcastOverride)) {
        OFF -> false
        ON -> true
        else -> globalEnabled
    }
}
