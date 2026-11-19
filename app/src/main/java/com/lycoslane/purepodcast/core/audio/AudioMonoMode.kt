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

/**
 * Per-podcast playback speed override supporting [INHERIT] (-1.0f = follow global)
 * or an explicit multiplier in 0.50x..3.00x.
 */
object SpeedOverride {
    const val INHERIT = -1.0f

    fun isInherit(value: Float): Boolean = !value.isFinite() || value < 0f

    fun normalize(value: Float): Float =
        if (isInherit(value)) INHERIT else value.coerceIn(0.50f, 3.00f)

    fun resolve(globalSpeed: Float, podcastOverride: Float): Float {
        val norm = normalize(podcastOverride)
        return if (norm < 0f) globalSpeed.coerceIn(0.50f, 3.00f) else norm
    }
}

/**
 * Per-podcast silence trimming override supporting [INHERIT], explicit [OFF],
 * or explicit trimming intensity ([SOFT], [MEDIUM], [STRONG]).
 */
object SilenceOverride {
    const val INHERIT = -1
    const val OFF = 0
    const val SOFT = 1
    const val MEDIUM = 2
    const val STRONG = 3

    fun normalize(value: Int): Int = if (value in INHERIT..STRONG) value else INHERIT

    fun fromEnabledAndIntensity(enabled: Boolean, intensity: Int): Int =
        if (!enabled) OFF else (intensity.coerceIn(0, 2) + 1)

    fun toEnabledAndIntensity(concrete: Int): Pair<Boolean, Int> = when (concrete) {
        SOFT -> true to 0
        MEDIUM -> true to 1
        STRONG -> true to 2
        else -> false to 1
    }

    fun resolve(globalEnabled: Boolean, globalIntensity: Int, podcastOverride: Int): Pair<Boolean, Int> {
        val norm = normalize(podcastOverride)
        return if (norm == INHERIT) {
            globalEnabled to globalIntensity.coerceIn(0, 2)
        } else {
            toEnabledAndIntensity(norm)
        }
    }
}

/**
 * Per-podcast smart rewind override supporting [INHERIT], [OFF] (0s), or seconds (5..120s).
 */
object SmartRewindOverride {
    const val INHERIT = -1
    const val OFF = 0

    fun normalize(value: Int): Int = if (value < 0) INHERIT else value.coerceIn(0, 120)

    fun resolve(globalSeconds: Int, podcastOverride: Int): Int {
        val norm = normalize(podcastOverride)
        return if (norm == INHERIT) globalSeconds.coerceIn(0, 120) else norm
    }
}
