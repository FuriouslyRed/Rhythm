/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.infrastructure.service.car

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import chromahub.rhythm.app.features.local.data.database.RhythmDatabase
import chromahub.rhythm.app.features.local.data.database.entity.PlaylistEntity
import chromahub.rhythm.app.features.local.data.database.entity.toSong
import chromahub.rhythm.app.features.local.di.LocalMusicModule
import chromahub.rhythm.app.shared.data.model.Album
import chromahub.rhythm.app.shared.data.model.Artist
import chromahub.rhythm.app.shared.data.model.Song
import chromahub.rhythm.app.shared.data.repository.PlaybackStatsRepository
import chromahub.rhythm.app.util.GenreUtils
import chromahub.rhythm.app.util.MediaItemFactory

/**
 * Browses the Rhythm library for Android Auto.
 *
 * Tree (four root tabs, all browsable, as Auto requires):
 *
 * ```
 * root
 * ├── home        → shuffle everything, recently played, recently added
 * ├── library     → albums, artists, songs, genres, downloads
 * ├── playlists   → playlist:<id>
 * └── downloads   → every song on the device
 * ```
 *
 * The car sends bare media ids, so [resolve] maps them back to playable items;
 * that is also where the `shuffle_all` pseudo-request expands to the library.
 */
class CarLibrary(private val context: Context) {

    private val repository by lazy { LocalMusicModule.provideMusicRepository(context) }
    private val playlistDao by lazy { RhythmDatabase.getInstance(context).playlistDao() }
    private val stats by lazy { PlaybackStatsRepository.getInstance(context) }

    @Volatile
    private var cachedSongs: List<Song>? = null

    @Volatile
    private var cachedSongsAt: Long = 0L

    fun rootItem(): MediaItem = folder(ROOT_ID, "Rhythm")

    suspend fun children(
        parentId: String,
        page: Int,
        pageSize: Int,
        includeQuality: Boolean
    ): List<MediaItem> = when (parentId) {
        // Large song lists are sliced *before* building MediaItems so a page
        // request never materialises the whole library.
        SONGS_ID -> pagedSongs(songs(), page, pageSize, includeQuality)
        DOWNLOADS_ID -> pagedSongs(songs().sortedBy { it.title.lowercase() }, page, pageSize, includeQuality)
        RECENTLY_ADDED_ID -> pagedSongs(
            songs().sortedByDescending { it.dateAdded }.take(RECENT_LIMIT),
            page,
            pageSize,
            includeQuality
        )
        RECENT_ID -> pagedSongs(recentSongs(), page, pageSize, includeQuality)
        else -> allChildren(parentId, includeQuality).slicePage(page, pageSize)
    }

    private fun pagedSongs(
        source: List<Song>,
        page: Int,
        pageSize: Int,
        includeQuality: Boolean
    ): List<MediaItem> = source.slicePage(page, pageSize)
        .map { MediaItemFactory.fromSong(it, includeQuality) }

    suspend fun item(mediaId: String, includeQuality: Boolean): MediaItem? {
        val song = songs().firstOrNull { it.id == mediaId }
        if (song != null) return MediaItemFactory.fromSong(song, includeQuality)

        return when {
            mediaId == ROOT_ID -> rootItem()
            mediaId == HOME_ID -> folder(HOME_ID, "Home")
            mediaId == LIBRARY_ID -> folder(LIBRARY_ID, "Library")
            mediaId == PLAYLISTS_ID -> folder(PLAYLISTS_ID, "Playlists")
            mediaId == DOWNLOADS_ID -> folder(DOWNLOADS_ID, "Downloads")
            else -> null
        }
    }

    /**
     * Turns media ids sent by the car back into playable items. `shuffle_all`
     * expands to the whole library in shuffled order.
     */
    suspend fun resolve(mediaIds: List<String>, includeQuality: Boolean): List<MediaItem> {
        if (mediaIds.isEmpty()) return emptyList()

        val allSongs = songs()
        if (mediaIds.contains(SHUFFLE_ALL_ID)) {
            return allSongs.shuffled().map { MediaItemFactory.fromSong(it, includeQuality) }
        }

        val byId = allSongs.associateBy { it.id }
        val resolved = mutableListOf<MediaItem>()
        for (id in mediaIds) {
            val direct = byId[id]
            if (direct != null) {
                resolved += MediaItemFactory.fromSong(direct, includeQuality)
                continue
            }
            // Ids that live under a folder (album:, playlist:, …) are resolved
            // when the parent list is requested; a bare song id is the common case.
            val nested = childrenOf(id, includeQuality)
            if (nested.isNotEmpty()) resolved += nested
        }
        return resolved
    }

    suspend fun search(query: String, includeQuality: Boolean): List<MediaItem> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        return songs()
            .asSequence()
            .filter { song ->
                song.title.contains(needle, ignoreCase = true) ||
                    song.artist.contains(needle, ignoreCase = true) ||
                    song.album.contains(needle, ignoreCase = true)
            }
            .take(SEARCH_RESULT_LIMIT)
            .map { MediaItemFactory.fromSong(it, includeQuality) }
            .toList()
    }

    // ---------------------------------------------------------------- tree

    private suspend fun allChildren(parentId: String, includeQuality: Boolean): List<MediaItem> = when {
        parentId == ROOT_ID -> listOf(
            folder(HOME_ID, "Home", "Jump back in"),
            folder(LIBRARY_ID, "Library", "Albums, artists, songs"),
            folder(PLAYLISTS_ID, "Playlists"),
            folder(DOWNLOADS_ID, "Downloads", "Everything on this device")
        )

        parentId == HOME_ID -> listOf(
            shuffleAllItem(),
            folder(RECENT_ID, "Recently played"),
            folder(RECENTLY_ADDED_ID, "Recently added")
        )

        parentId == LIBRARY_ID -> listOf(
            folder(ALBUMS_ID, "Albums"),
            folder(ARTISTS_ID, "Artists"),
            folder(SONGS_ID, "Songs"),
            folder(GENRES_ID, "Genres"),
            folder(DOWNLOADS_ID, "Downloads")
        )

        parentId == ALBUMS_ID -> albums().map { albumItem(it) }
        parentId == ARTISTS_ID -> artists().map { artistItem(it) }
        parentId == GENRES_ID -> genres().map { genreItem(it) }
        parentId == PLAYLISTS_ID -> playlists().map { playlistItem(it) }

        else -> childrenOf(parentId, includeQuality)
    }

    private suspend fun childrenOf(parentId: String, includeQuality: Boolean): List<MediaItem> = when {
        parentId.startsWith(ALBUM_PREFIX) -> albumSongs(parentId.removePrefix(ALBUM_PREFIX), includeQuality)
        parentId.startsWith(ARTIST_PREFIX) -> artistSongs(parentId.removePrefix(ARTIST_PREFIX), includeQuality)
        parentId.startsWith(PLAYLIST_PREFIX) -> playlistSongs(parentId.removePrefix(PLAYLIST_PREFIX), includeQuality)
        parentId.startsWith(GENRE_PREFIX) -> genreSongs(parentId.removePrefix(GENRE_PREFIX), includeQuality)
        else -> emptyList()
    }

    private suspend fun albumSongs(albumId: String, includeQuality: Boolean): List<MediaItem> {
        val album = albums().firstOrNull { it.id == albumId } ?: return emptyList()
        return album.songs.map { MediaItemFactory.fromSong(it, includeQuality) }
    }

    private suspend fun artistSongs(artistId: String, includeQuality: Boolean): List<MediaItem> {
        val artist = artists().firstOrNull { it.id == artistId } ?: return emptyList()
        val songs = artist.songs.ifEmpty { repository.getSongsForArtist(artistId) }
        return songs.map { MediaItemFactory.fromSong(it, includeQuality) }
    }

    private suspend fun playlistSongs(playlistId: String, includeQuality: Boolean): List<MediaItem> =
        playlistDao.getSongsForPlaylist(playlistId).map { MediaItemFactory.fromSong(it.toSong(), includeQuality) }

    private suspend fun genreSongs(genre: String, includeQuality: Boolean): List<MediaItem> =
        songs()
            .filter { song -> GenreUtils.splitGenres(song.genre).any { it.equals(genre, ignoreCase = true) } }
            .map { MediaItemFactory.fromSong(it, includeQuality) }

    // ---------------------------------------------------------------- items

    private fun folder(mediaId: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()

    private fun shuffleAllItem(): MediaItem = MediaItem.Builder()
        .setMediaId(SHUFFLE_ALL_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Shuffle everything")
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private fun albumItem(album: Album): MediaItem = MediaItem.Builder()
        .setMediaId(ALBUM_PREFIX + album.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(album.title)
                .setSubtitle(album.artist)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply { album.artworkUri?.let { setArtworkUri(it) } }
                .build()
        )
        .build()

    private fun artistItem(artist: Artist): MediaItem = MediaItem.Builder()
        .setMediaId(ARTIST_PREFIX + artist.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(artist.name)
                .setSubtitle(if (artist.numberOfTracks > 0) "${artist.numberOfTracks} songs" else null)
                .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply { artist.artworkUri?.let { setArtworkUri(it) } }
                .build()
        )
        .build()

    private fun genreItem(genre: String): MediaItem = MediaItem.Builder()
        .setMediaId(GENRE_PREFIX + genre)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(genre)
                .setMediaType(MediaMetadata.MEDIA_TYPE_GENRE)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun playlistItem(playlist: PlaylistEntity): MediaItem = MediaItem.Builder()
        .setMediaId(PLAYLIST_PREFIX + playlist.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playlist.name)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .apply {
                    playlist.artworkUri?.let { runCatching { setArtworkUri(Uri.parse(it)) } }
                }
                .build()
        )
        .build()

    // ---------------------------------------------------------------- data

    private suspend fun songs(): List<Song> {
        val now = System.currentTimeMillis()
        val cached = cachedSongs
        if (cached != null && now - cachedSongsAt < CACHE_TTL_MS) return cached

        val loaded = repository.loadSongs()
        cachedSongs = loaded
        cachedSongsAt = now
        return loaded
    }

    private suspend fun albums(): List<Album> = repository.loadAlbums()

    private suspend fun artists(): List<Artist> = repository.loadArtists()

    private suspend fun playlists(): List<PlaylistEntity> = playlistDao.getAllPlaylists()

    private suspend fun recentSongs(): List<Song> {
        val allSongs = songs().associateBy { it.id }
        return stats.getRecentlyPlayedSongIds(RECENT_LIMIT).mapNotNull { allSongs[it] }
    }

    private suspend fun genres(): List<String> = songs()
        .flatMap { GenreUtils.splitGenres(it.genre) }
        .filter { it.isNotBlank() }
        .map { it.trim() }
        .distinctBy { it.lowercase() }
        .sorted()

    private fun <T> List<T>.slicePage(page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || pageSize >= size) return this
        val from = page.coerceAtLeast(0) * pageSize
        if (from >= size) return emptyList()
        return subList(from, minOf(size, from + pageSize))
    }

    companion object {
        const val ROOT_ID = "root"
        const val HOME_ID = "home"
        const val LIBRARY_ID = "library"
        const val PLAYLISTS_ID = "playlists"
        const val DOWNLOADS_ID = "downloads"
        const val ALBUMS_ID = "albums"
        const val ARTISTS_ID = "artists"
        const val SONGS_ID = "songs"
        const val GENRES_ID = "genres"
        const val RECENT_ID = "recent"
        const val RECENTLY_ADDED_ID = "recently_added"
        const val SHUFFLE_ALL_ID = "shuffle_all"

        const val SONG_PREFIX = "song:"
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val PLAYLIST_PREFIX = "playlist:"
        const val GENRE_PREFIX = "genre:"

        private const val CACHE_TTL_MS = 60_000L
        private const val RECENT_LIMIT = 50
        private const val SEARCH_RESULT_LIMIT = 50
    }
}
