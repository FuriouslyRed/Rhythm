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
 * the same detector as the in-app quality badges. Lossy tracks keep the plain
 * artist name, with no suffix at all.
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

        // Lossy tracks carry no suffix at all: the point of the suffix is to
        // mark lossless and special formats, and a bare "Lossy" on most of a
        // library is noise. Descriptive formats (Dolby, DTS, MPEG-H) keep
        // their own labels.
        if (quality.qualityType == AudioQualityDetector.QualityType.LOSSY_COMPRESSED) {
            return ""
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
     * badge. The badge's own wording is used verbatim so the artist line and the
     * quality sheet can never disagree (surround, MPEG-H, Dolby, DTS, DSD, ...).
     */
    fun labelFor(info: PlaybackQualityInfo): String {
        // Nothing for lossy audio — see qualityLabel().
        if (info.qualityType == AudioQualityDetector.QualityType.LOSSY_COMPRESSED) return ""

        val badge = AudioQualityDetector.getQualityBadge(
            AudioQualityDetector.AudioQuality(
                qualityType = info.qualityType,
                qualityLabel = info.qualityLabel,
                qualityDescription = info.qualityDescription,
                isLossless = info.isLossless,
                isHiRes = info.isHiRes,
                isDolby = info.isDolby,
                isDTS = info.isDTS,
                bitDepthEstimate = info.sourceBitDepth,
                category = info.qualityCategory
            )
        )
        if (badge.isNotBlank()) return badge

        // Telemetry without a resolved type: fall back to the flags.
        return when {
            info.isDolby && info.qualityLabel.isNotBlank() -> info.qualityLabel
            info.isDTS -> "DTS"
            info.isLossless && info.isHiRes -> "Hi-Res"
            info.isLossless -> "Lossless"
            else -> ""
        }
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
