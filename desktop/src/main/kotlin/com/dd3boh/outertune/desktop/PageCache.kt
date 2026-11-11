/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.ArtistPage
import java.util.Collections

/**
 * What an artist's or album's page held when it was last fetched, so opening it again shows it at
 * once while it is fetched afresh behind - stale while it revalidates, as the phone's pages behave.
 * Each page took a second or two every time it was opened, Back and forth between an album and its
 * artist included.
 *
 * Two layers. The whole page, shelves and all, in memory for the session: YouTube's page objects
 * are not serialisable, and a shelf's contents go out of date anyway. And for artists, the part that
 * makes the page recognisable - name, about text, picture - on disk in the library (the artist_page
 * table), so the first visit after a restart opens on the real header rather than a blank one. The
 * picture itself is ArtworkCache's business, on disk already; only its address is kept here.
 */
object PageCache {

    @Volatile
    var library: LibraryStore? = null

    /**
     * Pages kept in memory. Small objects - text and a few hundred item references - so this is about
     * not growing without end over a long session rather than about memory as such.
     */
    private const val MAX_PAGES = 48

    private fun <T> recent(): MutableMap<String, T> = Collections.synchronizedMap(
        // Access-ordered, so the page dropped at the cap is the one looked at longest ago.
        object : LinkedHashMap<String, T>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > MAX_PAGES
        }
    )

    private val artists = recent<ArtistPage>()
    private val albums = recent<AlbumPage>()

    /** The artist's page as last fetched this session, or null. */
    fun artist(artistId: String): ArtistPage? = artists[artistId]

    /**
     * Keeps a freshly fetched artist page - in memory, and its header on disk. Off the main thread:
     * it writes to the database.
     */
    fun putArtist(artistId: String, page: ArtistPage) {
        artists[artistId] = page
        runCatching {
            library?.database?.putArtistPage(
                StoredArtistPage(
                    id = artistId,
                    name = page.artist.title,
                    description = page.description,
                    thumbnail = page.artist.thumbnail,
                    fetchedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** The header kept from the last time this artist's page was fetched, in any session. */
    fun storedArtist(artistId: String): StoredArtistPage? =
        runCatching { library?.database?.artistPage(artistId) }.getOrNull()

    /** The album's page as last fetched this session, or null. */
    fun album(browseId: String): AlbumPage? = albums[browseId]

    fun putAlbum(browseId: String, page: AlbumPage) {
        albums[browseId] = page
    }
}

/** The part of an artist's page kept across restarts - see [PageCache]. */
data class StoredArtistPage(
    val id: String,
    val name: String,
    val description: String?,
    val thumbnail: String?,
    val fetchedAt: Long,
)
