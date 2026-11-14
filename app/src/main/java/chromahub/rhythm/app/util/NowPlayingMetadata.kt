/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo

/**
 * Builds the artist line that system surfaces read from the media session —
 * notification shade, lock screen, Android Auto and Bluetooth AVRCP.
 *
 * When the "quality in artist" setting is on the artist becomes
 * `Artist • Hi-Res`, `Artist • Dolby Atmos`, `Artist • Studio Master`, … using
 * the same detector as the in-app quality badges, but evaluated from the file's
 * own metadata so it is available before playback starts.
 *
 * This only affects the media metadata sent to the system; the app UI keeps
 * showing the plain artist name.
 */
object NowPlayingMetadata {

    /** Bullet separator, matching the Spotify/Apple Music now-playing style. */
    const val QUALITY_SEPARATOR = " • "

    /**
     * Short quality label (e.g. "Lossless", "Hi-Res", "Dolby Atmos") for a track,
     * or an empty string when the file carries no usable audio metadata.
     */
    fun qualityLabel(
        codec: String?,
        path: String? = null,
        sampleRateHz: Int? = null,
        bitrateBps: Int? = null,
        channelCount: Int? = null
    ): String {
        val resolvedCodec = codec?.takeIf { it.isNotBlank() }
            ?: path?.substringAfterLast('.', "")?.uppercase()?.takeIf { it.isNotBlank() }
            ?: ""
        val sampleRate = sampleRateHz ?: 0
        val bitrateKbps = (bitrateBps ?: 0) / 1000

        // With no codec, sample rate and bitrate there is nothing to show.
        if (resolvedCodec.isBlank() && sampleRate <= 0 && bitrateKbps <= 0) return ""

        val quality = AudioQualityDetector.detectQuality(
            codec = resolvedCodec,
            sampleRateHz = sampleRate,
            bitrateKbps = bitrateKbps,
            channelCount = channelCount ?: 2
        )

        // Lossy stereo tracks get a flat "Lossy" marker instead of a bitrate
        // figure: the point of the suffix is "can I tell if this is lossless",
        // not a kbps readout. Descriptive formats (Dolby, DTS, MPEG-H) keep
        // their own labels.
        if (quality.qualityType == AudioQualityDetector.QualityType.LOSSY_COMPRESSED) {
            return "Lossy"
        }
        return AudioQualityDetector.getQualityBadge(quality)
    }

    /** [qualityLabel] for a library song. */
    fun qualityLabel(song: Song): String = qualityLabel(
        codec = song.codec,
        path = song.path,
        sampleRateHz = song.sampleRate,
        bitrateBps = song.bitrate,
        channelCount = song.channels
    )

    /**
     * Short label from the decoder telemetry that also feeds the audio-quality
     * badge, so the artist line matches what the badge sheet reports.
     */
    fun labelFor(info: PlaybackQualityInfo): String = when {
        info.qualityType == AudioQualityDetector.QualityType.DOLBY_ATMOS -> "Dolby Atmos"
        info.qualityType == AudioQualityDetector.QualityType.DOLBY_LOSSLESS -> "Dolby TrueHD"
        info.isDolby && info.qualityLabel.isNotBlank() -> info.qualityLabel
        info.isDTS -> when {
            info.qualityLabel.contains("X", ignoreCase = true) -> "DTS:X"
            info.isLossless -> "DTS-HD MA"
            else -> "DTS"
        }
        info.qualityType == AudioQualityDetector.QualityType.DSD_HIGH_RES -> "DSD"
        info.qualityType == AudioQualityDetector.QualityType.HI_RES_STUDIO_MASTER -> "Studio Master"
        info.isLossless && info.isHiRes -> "Hi-Res"
        info.isLossless -> "Lossless"
        info.qualityLabel.isNotBlank() -> "Lossy"
        else -> ""
    }

    /** Joins an artist and a quality label with the bullet separator. */
    fun artistWithQuality(artist: String, qualityLabel: String): String {
        val trimmedArtist = artist.trim()
        return if (qualityLabel.isBlank()) trimmedArtist else trimmedArtist + QUALITY_SEPARATOR + qualityLabel
    }

    /**
     * Artist line for the media metadata: plain artist, or artist with the
     * quality suffix appended when [includeQuality] is true.
     */
    fun displayArtist(song: Song, includeQuality: Boolean): String {
        val artist = song.artist.trim()
        if (!includeQuality) return artist
        return artistWithQuality(artist, qualityLabel(song))
    }
}
