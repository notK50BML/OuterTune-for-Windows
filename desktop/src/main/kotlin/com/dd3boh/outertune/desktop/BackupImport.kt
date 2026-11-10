/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.ZipFile

/** What an import did, so the screen can say something specific rather than "done". */
data class ImportSummary(
    val songs: Int = 0,
    val liked: Int = 0,
    val playlists: Int = 0,
    val playlistSongs: Int = 0,
    val skippedLocal: Int = 0,
    val plays: Int = 0,
    val error: String? = null,
    /** A desktop backup, set aside to replace the library when the app next starts. */
    val restoreStaged: Boolean = false,
    /** The file a desktop backup was written to. */
    val exported: String? = null,
    /** Only plays were merged - see BackupImport.mergePlaysFrom. */
    val playsOnly: Boolean = false,
) {
    val failed: Boolean get() = error != null

    fun describe(): String = when {
        error != null -> error
        playsOnly -> when {
            plays == 0 -> "Nothing new - every play in that backup is already counted here."
            else -> buildString {
                append("Added $plays ${if (plays == 1) "play" else "plays"}.")
                if (songs > 0) append(" $songs ${if (songs == 1) "song was" else "songs were"} new to this computer.")
                append(" Plays already here were skipped, so nothing is counted twice.")
            }
        }
        exported != null -> "Backed up to $exported."
        restoreStaged -> "Restored. Restart OuterTune to load the backup; the current library is kept as library-before-restore.db."
        songs == 0 && playlists == 0 -> "Nothing to import - the backup held no songs or playlists."
        else -> buildString {
            append("Imported $songs ${if (songs == 1) "song" else "songs"}")
            if (liked > 0) append(", $liked liked")
            if (playlists > 0) {
                append(", $playlists ${if (playlists == 1) "playlist" else "playlists"}")
                append(" ($playlistSongs ${if (playlistSongs == 1) "entry" else "entries"})")
            }
            if (plays > 0) append(", $plays ${if (plays == 1) "play" else "plays"} of history")
            if (skippedLocal > 0) append(". Skipped $skippedLocal local ${if (skippedLocal == 1) "file" else "files"}")
            append(".")
        }
    }
}

/**
 * Reads a backup written by the phone into this library.
 *
 * The backup is a zip holding two things: the settings DataStore as a protobuf, and a checkpointed
 * copy of the app's Room database. Only the database is read. The settings are deliberately ignored
 * - they describe a different application with different options, and applying a phone's player
 * layout or audio preferences to this would be worse than not trying.
 *
 * **Additive, and safe to run twice.** Songs are keyed by their YouTube id, so re-importing the same
 * backup updates rather than duplicates; playlists are matched by name for the same reason. The
 * alternative - replacing the local library - would make an import that was meant to merge two
 * devices into one that quietly destroys whichever was imported into.
 *
 * Local files are counted and skipped. The phone can play a file on its own storage and this cannot,
 * so importing those rows would fill the library with entries that fail when clicked. Saying how
 * many were left behind is more honest than silently dropping them.
 */
object BackupImport {

    suspend fun importFrom(backup: File, library: LibraryStore): ImportSummary =
        withContext(Dispatchers.IO) {
            val extracted = runCatching { extractDatabase(backup) }.getOrElse {
                return@withContext ImportSummary(error = "Could not read the backup: ${it.message}")
            } ?: return@withContext ImportSummary(
                error = "That zip does not contain an OuterTune database.",
            )

            try {
                // One transaction for the whole import. Committed row by row, every song was its
                // own disk flush - several per song with its credits - and a phone library of a few
                // thousand songs took the better part of an hour, holding the database the whole
                // time so the window seemed frozen. As one unit it is seconds, and an import that
                // fails partway leaves nothing half-done behind.
                openReadOnly(extracted).use { connection ->
                    library.database.transaction { read(connection, library) }
                }
            } catch (e: Exception) {
                ImportSummary(error = "Could not read the backup's database: ${e.message}")
            } finally {
                extracted.delete()
            }
        }

    /**
     * Adds another device's plays to this library's - a phone backup or another computer's - and
     * nothing else: no like, playlist or setting is touched.
     *
     * **Never counts a play twice.** A play is its song and the exact instant it was recorded, and
     * one already here is skipped ([Database.importPlays]). Both apps keep that instant as it was
     * first written, through every merge, so merging the same backup again, or a phone backup that
     * itself holds plays merged from this computer, adds nothing it already has.
     *
     * Songs this library already has keep their own details; only songs new to it are added, with
     * their credits and length so they count towards listening time. Local files are left out, as
     * there is nothing here for their plays to point at.
     */
    suspend fun mergePlaysFrom(backup: File, library: LibraryStore): ImportSummary = withContext(Dispatchers.IO) {
        val extracted = runCatching { extractDatabase(backup) }.getOrElse {
            return@withContext ImportSummary(error = "Could not read the backup: ${it.message}")
        } ?: return@withContext ImportSummary(error = "That file does not contain an OuterTune library.")
        try {
            openReadOnly(extracted).use { connection ->
                val desktop = hasTable(connection, "play_event")
                val (songs, plays) = if (desktop) readDesktopPlays(connection) else readPhonePlays(connection)
                var added = 0
                val playsAdded = library.database.transaction {
                    songs.forEach { song ->
                        if (library.database.song(song.first.id) == null) {
                            library.database.upsertSong(song.first, durationMs = song.second)
                            added++
                        }
                    }
                    library.database.importPlays(plays)
                }
                library.refreshAll()
                ImportSummary(songs = added, plays = playsAdded, playsOnly = true)
            }
        } catch (e: Exception) {
            ImportSummary(error = "Could not read the backup's database: ${e.message}")
        } finally {
            extracted.delete()
        }
    }

    private fun hasTable(connection: Connection, name: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { it.next() }
        }

    /** A phone backup's played songs (with their lengths) and its plays. */
    private fun readPhonePlays(connection: Connection): Pair<List<Pair<StoredSong, Long>>, List<Pair<String, Long>>> {
        val credits = readCredits(connection)
        val hasDuration = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(song)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "duration" }
            }
        }
        val songs = HashMap<String, Pair<StoredSong, Long>>()
        connection.prepareStatement(
            "SELECT id, title, thumbnailUrl, isLocal${if (hasDuration) ", duration" else ""} FROM song " +
                "WHERE id IN (SELECT DISTINCT songId FROM event)"
        ).use { st ->
            st.executeQuery().use { rows ->
                while (rows.next()) {
                    if (rows.getInt("isLocal") != 0) continue
                    val id = rows.getString("id") ?: continue
                    val artists = credits[id].orEmpty()
                    val song = StoredSong(
                        id = id,
                        title = rows.getString("title") ?: continue,
                        artists = artists.joinToString { it.name },
                        thumbnail = rows.getString("thumbnailUrl").orEmpty(),
                        artistList = artists,
                    )
                    // Seconds on the phone.
                    songs[id] = song to if (hasDuration) rows.getLong("duration").coerceAtLeast(0) * 1000L else 0L
                }
            }
        }
        val plays = connection.prepareStatement("SELECT songId, timestamp FROM event").use { st ->
            st.executeQuery().use { rows ->
                buildList { while (rows.next()) rows.getString(1)?.takeIf { it in songs }?.let { add(it to rows.getLong(2)) } }
            }
        }
        return songs.values.toList() to plays
    }

    /** Another computer's library: its played songs (not its local files) and its plays. */
    private fun readDesktopPlays(connection: Connection): Pair<List<Pair<StoredSong, Long>>, List<Pair<String, Long>>> {
        val credits = HashMap<String, MutableList<StoredArtist>>()
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT sa.songId, a.id, a.name FROM song_artist sa JOIN artist a ON a.id = sa.artistId ORDER BY sa.songId, sa.position"
            ).use { rs -> while (rs.next()) credits.getOrPut(rs.getString(1)) { mutableListOf() } += StoredArtist(rs.getString(2), rs.getString(3)) }
        }
        val local = if (hasTable(connection, "local_file")) connection.createStatement().use { st ->
            st.executeQuery("SELECT songId FROM local_file").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        } else emptySet()
        val songs = HashMap<String, Pair<StoredSong, Long>>()
        connection.createStatement().use { st ->
            st.executeQuery(
                "SELECT id, title, artists, thumbnail, durationMs FROM song WHERE id IN (SELECT DISTINCT songId FROM play_event)"
            ).use { rows ->
                while (rows.next()) {
                    val id = rows.getString(1) ?: continue
                    if (id in local) continue
                    songs[id] = StoredSong(
                        id = id,
                        title = rows.getString(2) ?: continue,
                        artists = rows.getString(3).orEmpty(),
                        thumbnail = rows.getString(4).orEmpty(),
                        artistList = credits[id].orEmpty(),
                    ) to rows.getLong(5)
                }
            }
        }
        val plays = connection.createStatement().use { st ->
            st.executeQuery("SELECT songId, playedAt FROM play_event").use { rows ->
                buildList { while (rows.next()) rows.getString(1)?.takeIf { it in songs }?.let { add(it to rows.getLong(2)) } }
            }
        }
        return songs.values.toList() to plays
    }

    /**
     * Fills in album names and ids from a phone backup, and nothing else - for a library imported
     * before the import read albums. Songs it does not already have are not added, no like or
     * playlist is touched, and an album a song already has is kept. Returns how many songs gained one.
     */
    suspend fun fillAlbumsFrom(backup: File, library: LibraryStore): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val extracted = extractDatabase(backup) ?: error("That zip does not contain an OuterTune database.")
            try {
                val albums = openReadOnly(extracted).use(::readAlbums)
                library.database.fillAlbums(albums).also { library.refreshSongFacts() }
            } finally {
                extracted.delete()
            }
        }
    }

    /**
     * Each song's album name and id from the phone's database: the song's own columns, and the
     * song-to-album table and album table behind them for songs whose own are empty. A backup too
     * old for any of that gives nothing rather than failing.
     */
    private fun readAlbums(connection: Connection): Map<String, Pair<String, String>> {
        val columns = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(song)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.toSet()
            }
        }
        val name = if ("albumName" in columns) "s.albumName" else "NULL"
        val id = if ("albumId" in columns) "s.albumId" else "NULL"
        val withTables = """
            SELECT s.id,
                   COALESCE(NULLIF($name, ''), MAX(a.title), ''),
                   COALESCE(NULLIF($id, ''), MAX(m.albumId), '')
            FROM song s
            LEFT JOIN song_album_map m ON m.songId = s.id
            LEFT JOIN album a ON a.id = m.albumId
            GROUP BY s.id
        """.trimIndent()
        val songOnly = "SELECT s.id, COALESCE($name, ''), COALESCE($id, '') FROM song s"
        fun query(sql: String) = connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildMap {
                    while (rs.next()) {
                        val songId = rs.getString(1) ?: continue
                        val albumName = rs.getString(2).orEmpty()
                        val albumId = rs.getString(3).orEmpty()
                        if (albumName.isNotBlank() || albumId.isNotBlank()) put(songId, albumName to albumId)
                    }
                }
            }
        }
        // The album tables are older than the song's own columns, but a backup from another fork
        // may lack them; the song's columns alone are still worth having.
        return runCatching { query(withTables) }.getOrElse { runCatching { query(songOnly) }.getOrDefault(emptyMap()) }
    }

    /**
     * Pulls the Room database out of the zip into a file of its own.
     *
     * Extracted rather than read in place because SQLite needs a real file - it seeks, and it wants
     * to manage its own journal. Matched by name suffix rather than exact name so a backup written
     * by a build that renamed the file still opens.
     */
    private fun extractDatabase(backup: File): File? {
        ZipFile(backup).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.endsWith(".db")
            } ?: return null
            val out = File.createTempFile("outertune-import", ".db")
            zip.getInputStream(entry).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            return out
        }
    }

    private fun openReadOnly(file: File): Connection =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also {
            it.createStatement().use { statement -> statement.execute("PRAGMA query_only=true") }
        }

    private fun read(connection: Connection, library: LibraryStore): ImportSummary {
        val credits = readCredits(connection)
        // Every song's album name and id, so its row can name the album and Go to album open it.
        val albums = readAlbums(connection)

        var songs = 0
        var liked = 0
        var skippedLocal = 0
        val imported = HashMap<String, StoredSong>()

        // Older backups have no duration column; asked for only where it exists.
        val hasDuration = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(song)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "duration" }
            }
        }
        // And the album's name, which the phone keeps on the song as well as in its own table.
        val hasAlbum = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(song)").use { rs ->
                generateSequence { if (rs.next()) rs.getString("name") else null }.any { it == "albumName" }
            }
        }
        connection.prepareStatement(
            "SELECT id, title, thumbnailUrl, liked, isLocal${if (hasDuration) ", duration" else ""}" +
                "${if (hasAlbum) ", albumName" else ""} FROM song"
        ).use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    if (rows.getInt("isLocal") != 0) {
                        skippedLocal++
                        continue
                    }
                    val id = rows.getString("id") ?: continue
                    val artists = credits[id].orEmpty()
                    val song = StoredSong(
                        id = id,
                        title = rows.getString("title") ?: continue,
                        artists = artists.joinToString { it.name },
                        thumbnail = rows.getString("thumbnailUrl").orEmpty(),
                        artistList = artists,
                    )
                    // With its length (stored in seconds on the phone), so Stats can count
                    // listening time for plays that came from the phone.
                    library.database.upsertSong(
                        song,
                        durationMs = if (hasDuration) rows.getLong("duration").coerceAtLeast(0) * 1000L else 0L,
                        album = albums[id]?.first ?: if (hasAlbum) rows.getString("albumName").orEmpty() else "",
                        albumId = albums[id]?.second.orEmpty(),
                    )
                    imported[id] = song
                    songs++

                    // States the flag rather than toggling it. toggleLiked would turn every liked
                    // song *off* again on a second import - the worst possible outcome for a
                    // "just to be safe" re-run - and asking isLiked first would work but leaves the
                    // toggle sitting there for someone to use later without the guard.
                    if (rows.getInt("liked") != 0) {
                        if (!library.isLiked(id)) liked++
                        library.database.setLiked(song, liked = true, atMs = System.currentTimeMillis())
                    }
                }
            }
        }

        val (playlists, playlistSongs) = readPlaylists(connection, library, imported)
        // Every play the phone recorded - its event table - so Stats and Recently played carry the
        // phone's history rather than starting from nothing. Plays of songs that were not brought
        // across (local files) are left out, since there is nothing here for them to point at.
        val plays = runCatching {
            connection.prepareStatement("SELECT songId, timestamp FROM event").use { statement ->
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val songId = rows.getString(1) ?: continue
                            if (songId in imported) add(songId to rows.getLong(2))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        val playsAdded = library.database.importPlays(plays)

        library.refreshAll()
        return ImportSummary(
            songs = songs,
            liked = liked,
            playlists = playlists,
            playlistSongs = playlistSongs,
            skippedLocal = skippedLocal,
            plays = playsAdded,
        )
    }

    /**
     * Every song's credits, read in one pass.
     *
     * One query rather than one per song: a library of a few thousand would otherwise make a few
     * thousand round trips, and this is already the slowest part of an import.
     */
    private fun readCredits(connection: Connection): Map<String, List<StoredArtist>> {
        val credits = HashMap<String, MutableList<StoredArtist>>()
        runCatching {
            connection.prepareStatement(
                """
                SELECT m.songId AS songId, a.id AS artistId, a.name AS name
                FROM song_artist_map m JOIN artist a ON a.id = m.artistId
                ORDER BY m.songId, m.position
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val songId = rows.getString("songId") ?: continue
                        val name = rows.getString("name") ?: continue
                        val artistId = rows.getString("artistId")
                        credits.getOrPut(songId) { mutableListOf() } += when {
                            // The phone gives local-only artists ids of its own. Those mean nothing
                            // here, so they become unlinked credits keyed by name - which is what
                            // this library already does for a credit with no channel behind it.
                            artistId == null || artistId.startsWith("LA") -> StoredArtist.unlinked(name)
                            // The phone normalises on write, but a backup can predate that.
                            else -> StoredArtist(artistId.normalizeArtistId(), name)
                        }
                    }
                }
            }
        }
        return credits
    }

    private fun readPlaylists(
        connection: Connection,
        library: LibraryStore,
        imported: Map<String, StoredSong>,
    ): Pair<Int, Int> {
        var added = 0
        var entries = 0

        val existing = library.playlists.value.associateBy { it.name }
        val toImport = mutableListOf<Pair<String, String>>()
        runCatching {
            connection.prepareStatement("SELECT id, name FROM playlist").use { statement ->
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val id = rows.getString("id") ?: continue
                        val name = rows.getString("name")?.takeIf { it.isNotBlank() } ?: continue
                        toImport += id to name
                    }
                }
            }
        }

        toImport.forEach { (remoteId, name) ->
            // Matched by name, so importing the same backup twice fills one playlist rather than
            // making a second with the same title.
            val localId = existing[name]?.id ?: library.createPlaylist(name).also { added++ }
            val alreadyIn = library.playlistSongs(localId).map { it.id }.toSet()

            connection.prepareStatement(
                "SELECT songId FROM playlist_song_map WHERE playlistId = ? ORDER BY position"
            ).use { statement ->
                statement.setString(1, remoteId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val songId = rows.getString("songId") ?: continue
                        if (songId in alreadyIn) continue
                        // Only songs that made it in. A playlist entry pointing at a local file
                        // would otherwise become a row with nothing behind it.
                        val song = imported[songId] ?: continue
                        library.addToPlaylist(localId, song)
                        entries++
                    }
                }
            }
        }
        return added to entries
    }
}
