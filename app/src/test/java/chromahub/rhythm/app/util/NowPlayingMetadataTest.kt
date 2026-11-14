/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the "Artist • Quality" line used by the notification, lock screen,
 * Android Auto and Bluetooth now-playing metadata.
 */
class NowPlayingMetadataTest {

    private fun label(
        codec: String?,
        path: String? = null,
        sampleRateHz: Int? = null,
        bitrateBps: Int? = null,
        channels: Int? = null
    ) = NowPlayingMetadata.qualityLabel(
        codec = codec,
        path = path,
        sampleRateHz = sampleRateHz,
        bitrateBps = bitrateBps,
        channelCount = channels
    )

    @Test
    fun `cd quality flac maps to Lossless`() {
        assertEquals("Lossless", label(codec = "FLAC", sampleRateHz = 44100, bitrateBps = 850_000))
    }

    @Test
    fun `hi-res flac maps to Hi-Res`() {
        assertEquals("Hi-Res", label(codec = "FLAC", sampleRateHz = 96000, bitrateBps = 2_800_000))
    }

    @Test
    fun `studio master flac maps to Studio Master`() {
        assertEquals("Studio Master", label(codec = "FLAC", sampleRateHz = 192000, bitrateBps = 6_000_000))
    }

    @Test
    fun `dolby atmos maps to Dolby Atmos`() {
        assertEquals(
            "Dolby Atmos",
            label(codec = "EAC3-JOC", sampleRateHz = 48000, bitrateBps = 768_000, channels = 6)
        )
    }

    @Test
    fun `lossy tracks get a flat Lossy marker instead of a bitrate`() {
        assertEquals("Lossy", label(codec = "MP3", sampleRateHz = 44100, bitrateBps = 320_000))
        assertEquals("Lossy", label(codec = "AAC", sampleRateHz = 44100, bitrateBps = 128_000))
    }

    @Test
    fun `codec falls back to the file extension`() {
        assertEquals(
            "Lossy",
            label(codec = null, path = "/storage/music/song.mp3", sampleRateHz = 44100, bitrateBps = 320_000)
        )
    }

    @Test
    fun `missing metadata yields no label`() {
        assertEquals("", NowPlayingMetadata.qualityLabel(codec = null, path = null))
    }

    @Test
    fun `artist line uses the bullet separator`() {
        assertEquals("Nirvana • Lossless", NowPlayingMetadata.artistWithQuality("Nirvana", "Lossless"))
    }

    @Test
    fun `blank label leaves the artist untouched`() {
        assertEquals("Nirvana", NowPlayingMetadata.artistWithQuality("Nirvana", ""))
    }

    @Test
    fun `artist whitespace is trimmed`() {
        assertEquals("Nirvana • Hi-Res", NowPlayingMetadata.artistWithQuality("  Nirvana ", "Hi-Res"))
    }

    private fun info(
        type: AudioQualityDetector.QualityType,
        label: String = "FLAC",
        lossless: Boolean = false,
        hiRes: Boolean = false,
        dolby: Boolean = false,
        dts: Boolean = false
    ) = chromahub.rhythm.app.shared.data.model.PlaybackQualityInfo(
        qualityType = type,
        qualityLabel = label,
        isLossless = lossless,
        isHiRes = hiRes,
        isDolby = dolby,
        isDTS = dts
    )

    @Test
    fun `decoder telemetry drives the label the badge shows`() {
        assertEquals(
            "Lossless",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.CD_QUALITY_LOSSLESS, lossless = true)
            )
        )
        assertEquals(
            "Hi-Res",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.HI_RES_LOSSLESS, lossless = true, hiRes = true)
            )
        )
        assertEquals(
            "Studio Master",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.HI_RES_STUDIO_MASTER, lossless = true, hiRes = true)
            )
        )
        assertEquals(
            "Lossy",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.LOSSY_COMPRESSED, label = "MP3 320kbps")
            )
        )
        assertEquals(
            "Dolby Atmos",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.DOLBY_ATMOS, dolby = true)
            )
        )
        assertEquals(
            "DTS:X",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.DTS_SURROUND, label = "DTS:X", dts = true)
            )
        )
        assertEquals(
            "DSD",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.DSD_HIGH_RES, label = "DSD64")
            )
        )
    }

    @Test
    fun `empty telemetry yields no suffix`() {
        assertEquals(
            "",
            NowPlayingMetadata.labelFor(
                info(AudioQualityDetector.QualityType.UNKNOWN, label = "")
            )
        )
    }
}
