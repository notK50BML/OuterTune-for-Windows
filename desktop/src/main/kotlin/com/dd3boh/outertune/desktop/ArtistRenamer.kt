/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps each artist under the name its own channel goes by now - the desktop's half of the phone's
 * ArtistNameRefresher.
 *
 * A song's credit carries the artist's name as it was when the song was published, so a renamed act
 * stays under its old name on every song from before ("(G)I-DLE", now "i-dle") - and the artist page
 * it opens shows the new one. The name read off the channel is stored ([LibraryStore.renameArtist])
 * and wins from then on, everywhere a song is shown.
 *
 * Learned two ways: whenever an artist's page is fetched anyway - opening it, or fetching its picture
 * - and, for the artists of each song that plays, once a session from a queue that asks one at a time.
 */
object ArtistRenamer {

    @Volatile
    var library: LibraryStore? = null

    private val checked = ConcurrentHashMap.newKeySet<String>()

    /**
     * Every name learned this session, by artist id - including artists the library has no row for,
     * which [LibraryStore.renameArtist] cannot keep: one known only from the account's history (a
     * song played on the phone) would otherwise stay under its old name in Stats for good. State, so
     * a name learned while a screen is showing it is drawn at once.
     */
    val names = mutableStateMapOf<String, String>()

    /** [artist]'s current name: learned this session, else the library's, else as credited. */
    fun nameOf(artist: StoredArtist, index: CreditIndex): String =
        names[artist.id] ?: index.names[artist.id] ?: artist.name.withoutTopic()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (id in queue) {
                YouTube.artist(id).getOrNull()?.artist?.title?.let { learned(id, it) }
                // One at a time with a pause, so a long queue of new artists is a trickle.
                delay(1_500)
            }
        }
    }

    /** The name [id]'s channel goes by, seen on its page - kept as the artist's name. */
    fun learned(id: String, title: String) {
        if (!StoredArtist(id, title).linkable) return
        checked += id
        val name = title.withoutTopic()
        if (name.isBlank()) return
        names[id] = name
        runCatching { library?.renameArtist(id, name) }
    }

    /** Checks [artists]' current names, each at most once a session. */
    fun check(artists: List<StoredArtist>) {
        artists.filter { it.linkable && checked.add(it.id) }.forEach { queue.trySend(it.id) }
    }
}

/** The artist's name without YouTube's " - Topic" suffix, as the library stores it. */
fun String.withoutTopic(): String = replace(TOPIC_SUFFIX, "").ifBlank { this }

private val TOPIC_SUFFIX = Regex("""\s*-\s*Topic$""")

/** The library's credits and artist names, for every song shown - see [shown]. */
val LocalCredits = compositionLocalOf { CreditIndex.EMPTY }

/**
 * This song with its artists as they should be shown everywhere: the library's credits when it has
 * the song - repaired by ArtistLinks, a combined "X & Y" split and a "(feat. Z)" credited - and
 * otherwise its own credits under each artist's current name, the name their page opens under.
 *
 * Every song row, card, menu, queue and player shows a song through this, so it looks the same in a
 * search result, on Home, in the queue and in the player. Only the library's own lists used to.
 */
fun SongItem.shown(index: CreditIndex): SongItem {
    val stored = index.songs[id]
    val artists = if (!stored.isNullOrEmpty()) {
        stored.map { Artist(name = it.name, id = it.id.takeIf { _ -> it.linkable }) }
    } else {
        artists.map { artist -> artist.copy(name = artist.id?.let(index.names::get) ?: artist.name.withoutTopic()) }
    }
    return if (artists == this.artists) this else copy(artists = artists)
}

@Composable
fun SongItem.shown(): SongItem = shown(LocalCredits.current)

/**
 * A song's artists as shown - its credits under their current names. A song the library does not
 * have (one known only from the account's history) is shown through its own credits, renamed the
 * same way, rather than the credit text it was published with.
 */
fun StoredSong.shownArtists(index: CreditIndex): String {
    val credits = index.songs[id]?.takeIf { it.isNotEmpty() } ?: artistList
    if (credits.isEmpty()) return artists
    return credits.joinToString { ArtistRenamer.nameOf(it, index) }
}
