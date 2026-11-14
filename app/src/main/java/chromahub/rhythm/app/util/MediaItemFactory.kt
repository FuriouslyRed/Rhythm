/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.util

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import chromahub.rhythm.app.core.domain.model.PlayableItem
import chromahub.rhythm.app.shared.data.model.Song

/**
 * Single place where library models become [MediaItem]s.
 *
 * Both the phone app (queue building in `MusicViewModel`) and the playback
 * service (Android Auto browse tree, playback resumption) use this, so the car
 * sees exactly the metadata the phone builds — including the now-playing
 * "Artist • Lossless" decoration.
 */
object MediaItemFactory {

    const val EXTRA_ORIGINAL_TITLE = "chromahub.rhythm.app.extra.original_title"
    const val EXTRA_ORIGINAL_ARTIST = "chromahub.rhythm.app.extra.original_artist"
    const val EXTRA_ORIGINAL_ALBUM = "chromahub.rhythm.app.extra.original_album"

    fun fromSong(song: Song, includeQuality: Boolean): MediaItem {
        val extension = song.path?.substringAfterLast('.', "")?.lowercase() ?: ""
        val mimeType = mimeTypeForExtension(extension)
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(song.uri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(NowPlayingMetadata.displayArtist(song, includeQuality))
                    .setAlbumTitle(song.album)
                    .setDurationMs(song.duration)
                    .setArtworkUri(song.artworkUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(originalMetadataExtras(song.title, song.artist, song.album))
                    .build()
            )
            .build()
    }

    /**
     * For streaming / Go-mode items that only expose the [PlayableItem] contract.
     * They carry no file codec information, so no quality suffix is added.
     */
    fun fromPlayable(item: PlayableItem): MediaItem {
        val artwork = item.artworkUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(item.getPlaybackUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setDurationMs(item.duration)
                    .apply { if (artwork != null) setArtworkUri(artwork) }
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(originalMetadataExtras(item.title, item.artist, item.album))
                    .build()
            )
            .build()
    }

    fun originalMetadataExtras(title: String, artist: String, album: String): Bundle =
        Bundle().apply {
            putString(EXTRA_ORIGINAL_TITLE, title)
            putString(EXTRA_ORIGINAL_ARTIST, artist)
            putString(EXTRA_ORIGINAL_ALBUM, album)
        }

    fun mimeTypeForExtension(extension: String): String? = when (extension.lowercase()) {
        "opus", "opa" -> "audio/ogg"
        "ogg", "oga" -> "audio/ogg"
        "mkv", "mka" -> "audio/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "aac", "adts" -> "audio/aac"
        "ac3" -> "audio/ac3"
        "eac", "eac3" -> "audio/eac3"
        "ac4" -> "audio/ac4"
        "mhm", "mhm1" -> "audio/mhm1"
        "mid", "midi" -> "audio/midi"
        "ape" -> "audio/x-ape"
        "wv" -> "audio/x-wavpack"
        "tta" -> "audio/x-tta"
        "tak" -> "audio/x-tak"
        "aiff", "aif" -> "audio/aiff"
        "dsf", "dff", "dsd" -> "audio/dsd"
        else -> null
    }
}
