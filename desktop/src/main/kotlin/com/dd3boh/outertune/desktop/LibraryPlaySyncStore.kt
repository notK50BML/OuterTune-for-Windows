/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

/**
 * This computer's plays, for play sync: the play log, read and added to. A play is its song and its
 * instant exactly as stored, never converted, so one that comes back from another device is
 * recognised (see SyncPlay) - the same rule a merged backup follows (Database.importPlays).
 */
class LibraryPlaySyncStore(private val library: LibraryStore) : PlaySyncStore {

    override fun plays(): List<SyncPlay> = library.database.syncPlays()

    override fun songs(ids: Collection<String>): List<SyncSong> {
        val durations = library.database.durations(ids)
        return library.database.songs(ids).map { song ->
            SyncSong(
                id = song.id,
                title = song.title,
                thumbnail = song.thumbnail,
                durationSec = ((durations[song.id] ?: 0L) / 1000).toInt(),
                artists = song.artistList.map { SyncArtist(it.id.takeIf { _ -> it.linkable }, it.name) },
            )
        }
    }

    override fun merge(songs: List<SyncSong>, plays: List<SyncPlay>): Int {
        val added = library.database.transaction {
            val known = HashSet<String>()
            songs.forEach { song ->
                if (library.database.song(song.id) != null) {
                    known += song.id
                    return@forEach
                }
                val artists = song.artists.map { a -> a.id?.let { StoredArtist(it.normalizeArtistId(), a.name) } ?: StoredArtist.unlinked(a.name) }
                library.database.upsertSong(
                    StoredSong(
                        id = song.id,
                        title = song.title,
                        artists = artists.joinToString { it.name },
                        thumbnail = song.thumbnail,
                        artistList = artists,
                    ),
                    durationMs = song.durationSec.coerceAtLeast(0) * 1000L,
                )
                known += song.id
            }
            // Only plays of songs this library has, now: a play needs its song row.
            plays.mapTo(HashSet()) { it.songId }.filter { it !in known }.forEach { id ->
                if (library.database.song(id) != null) known += id
            }
            val usable = plays.filter { it.songId in known }
            library.database.importPlays(usable.map { it.songId to it.at })
        }
        if (added > 0) library.refreshAll()
        return added
    }
}
