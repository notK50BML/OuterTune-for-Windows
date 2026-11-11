/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.ArtistSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** What an artist screen has managed to gather. */
private sealed interface ArtistContent {
    data object Loading : ArtistContent
    data class Loaded(val page: ArtistPage?) : ArtistContent
    data class Failed(val reason: String) : ArtistContent
}

/** A section's "see all" page, once opened. */
private sealed interface MoreContent {
    data object Loading : MoreContent
    data class Loaded(val title: String, val items: List<YTItem>) : MoreContent
    data class Failed(val reason: String) : MoreContent
}

/**
 * One artist, laid out as the phone does it.
 *
 * A large picture with the name over it and Play and Shuffle beneath; then the artist's top songs,
 * five of them, with an arrow through to the rest; then a carousel for each of the other things
 * YouTube lists - albums, singles and EPs, videos, live performances, appearances, similar artists -
 * each with its own arrow through to everything in it. What the library already holds by them sits
 * at the end, where the phone puts it too, and is there at once while the rest is fetched.
 */
@Composable
fun ArtistPane(
    requested: StoredArtist,
    library: LibraryStore,
    currentId: String?,
    onBack: () -> Unit,
    onPlay: (List<SongItem>, Int) -> Unit,
    onAddToPlaylist: (StoredSong) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (StoredArtist) -> Unit = {},
    onOpenPlaylist: (PlaylistItem) -> Unit = {},
    hideExplicit: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    // Bumped when a link is made or undone here, so the page re-reads which artist it is showing.
    var linkVersion by remember { mutableStateOf(0) }
    // A local artist linked to YouTube Music shows the real artist's page - see ArtistLinks.
    val artist = remember(requested.id, linkVersion) {
        if (requested.linkable) requested
        else library.database.artistLink(ArtistLinks.key(requested.name))?.channelId
            ?.let { StoredArtist(it, requested.name) } ?: requested
    }
    // The local names tied to this channel, for undoing a link that was wrong.
    val linkedFrom = remember(artist.id, linkVersion) {
        if (artist.linkable) library.database.linksToChannel(artist.id) else emptyList()
    }
    var linking by remember { mutableStateOf(false) }

    // Stale while it revalidates - see PageCache. The page as it was when last opened this session
    // shows at once, whole; failing that, the header kept from any earlier session (name, about
    // text, picture). Either way it is fetched afresh below and replaced when that arrives. Read
    // here rather than in the effect so the first frame already has it: one row by primary key.
    val cachedPage = remember(artist.id) { if (artist.linkable) PageCache.artist(artist.id) else null }
    val stored = remember(artist.id) {
        if (artist.linkable && cachedPage == null) PageCache.storedArtist(artist.id) else null
    }
    // The picture the library already has for them, fetched for its artist grid or Stats - better
    // than an empty circle on a first visit.
    val knownThumbnail = remember(artist.id) {
        if (artist.linkable) runCatching { library.artistThumbnail(artist.id) }.getOrNull()?.takeIf { it.isNotBlank() }
        else null
    }
    var content by remember(artist.id) {
        mutableStateOf<ArtistContent>(cachedPage?.let { ArtistContent.Loaded(it) } ?: ArtistContent.Loading)
    }
    val librarySongs = remember(artist.id) { library.songsByArtist(artist.id) }
    var more by remember(artist.id) { mutableStateOf<ArtistSection?>(null) }

    LaunchedEffect(artist.id) {
        // A locally-keyed credit has no channel to ask about - see StoredArtist. The page is still
        // worth showing for the library half.
        if (!artist.linkable) {
            content = ArtistContent.Loaded(null)
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            YouTube.artist(artist.id).onSuccess {
                // Kept for Stats and the Library, which show this artist without opening them.
                ArtistImages.remember(artist.id, it.artist.thumbnail)
                // And its current name, which every song crediting it then shows.
                ArtistRenamer.learned(artist.id, it.artist.title)
                // And the page itself, for opening it again.
                PageCache.putArtist(artist.id, it)
            }
        }
        val fresh = result.getOrNull()
        if (fresh != null) {
            content = ArtistContent.Loaded(fresh)
        } else if ((content as? ArtistContent.Loaded)?.page == null) {
            // A page already showing from the cache stays as it is: offline, or YouTube having a
            // bad moment, should not take away what was there. Otherwise - YouTube's own error is a
            // wall of JSON that helps nobody - with songs by this artist in the library the page
            // shows those, as for an unlinked artist; with none, a sentence.
            content = if (librarySongs.isNotEmpty()) ArtistContent.Loaded(null)
            else ArtistContent.Failed("Couldn't find this artist on YouTube Music.")
        }
    }

    fun List<YTItem>.visible() = filter { it.isSupported && !(hideExplicit && it.explicit) }

    fun open(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is SongItem -> {
                val songs = siblings.filterIsInstance<SongItem>()
                onPlay(songs, songs.indexOf(item).coerceAtLeast(0))
            }
            is AlbumItem -> onOpenAlbum(item.browseId)
            is ArtistItem -> onOpenArtist(StoredArtist(item.id, item.title))
            is PlaylistItem -> onOpenPlaylist(item)
        }
    }

    more?.let { section ->
        MoreItems(
            artistName = (content as? ArtistContent.Loaded)?.page?.artist?.title?.withoutTopic() ?: artist.name,
            section = section,
            currentId = currentId,
            visible = { it.visible() },
            onBack = { more = null },
            onOpen = ::open,
            modifier = modifier,
        )
        return
    }

    val page = (content as? ArtistContent.Loaded)?.page
    // Nothing of the page yet - neither fetched nor kept from this session - so its shape is drawn
    // instead (see artistPlaceholders), and the header holds whatever is already known.
    val waiting = content is ArtistContent.Loading
    val name = page?.artist?.title?.withoutTopic() ?: stored?.name?.withoutTopic()?.ifBlank { null } ?: artist.name
    val thumbnail = page?.artist?.thumbnail ?: stored?.thumbnail?.ifBlank { null } ?: knownThumbnail
    // The fetched page's word on the description is final, none included; until then, the kept one.
    val description = if (page != null) page.description else stored?.description
    val sections = page?.sections.orEmpty()
        .map { it.copy(items = it.items.visible()) }
        .filter { it.items.isNotEmpty() }
    val allSongs = sections.flatMap { it.items.filterIsInstance<SongItem>() }.distinctBy { it.id }
        .ifEmpty { librarySongs.map { it.toItem() } }

    val listState = rememberLazyListState()
    Column(modifier = modifier.fillMaxSize()) {
    // Pinned above the list, as every other page's is: inside the header it scrolled away with the
    // photo, and going back from the bottom of a long discography meant scrolling all the way up.
    // The name joins it once the header is out of sight, so the page still says whose it is.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        TextButton(onClick = onBack) { Text("← Back") }
        androidx.compose.animation.AnimatedVisibility(
            visible = listState.firstVisibleItemIndex > 0,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // Every item keyed, so the list keeps its place by item rather than by position as the page
    // fills in and as a fresh copy replaces a cached one.
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "header") {
            ArtistHeader(
                name = name,
                thumbnail = thumbnail,
                description = description,
                audience = page?.monthlyAudience,
                loading = waiting,
                canPlay = allSongs.isNotEmpty(),
                onPlay = { onPlay(allSongs, 0) },
                onShuffle = {
                    val order = allSongs.shuffled()
                    if (order.isNotEmpty()) onPlay(order, 0)
                },
            )
        }

        if (!artist.linkable || linkedFrom.isNotEmpty()) {
            item(key = "link") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 28.dp),
                ) {
                    Text(
                        if (!artist.linkable) "Not linked to YouTube Music"
                        else "Your local files by ${linkedFrom.joinToString { it.name }} are linked here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!artist.linkable) {
                        TextButton(onClick = { linking = true }) { Text("Find on YouTube Music…") }
                    } else {
                        TextButton(onClick = {
                            scope.launch {
                                ArtistLinks.unlink(library, artist.id)
                                linkVersion++
                            }
                        }) { Text("Unlink") }
                    }
                }
            }
        }

        (content as? ArtistContent.Failed)?.let { failed ->
            item(key = "failed") {
                Text(
                    failed.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
        }

        if (waiting) artistPlaceholders()

        // Keyed by position as well as title: two shelves YouTube happens to title alike would
        // otherwise share a key, which a lazy list does not survive.
        sections.forEachIndexed { sectionIndex, section ->
            val songsOnly = section.items.all { it is SongItem }
            item(key = "head-$sectionIndex-${section.title}") {
                SectionHeader(
                    title = section.title,
                    onMore = section.moreEndpoint?.let { { more = section } },
                )
            }
            if (songsOnly) {
                // The phone's top-songs list: five rows, the rest behind the arrow.
                val shown = section.items.filterIsInstance<SongItem>().take(TOP_SONGS)
                val shownKeys = uniqueKeys(shown) { it.id }
                itemsIndexed(shown, key = { i, _ -> "song-$sectionIndex-${shownKeys[i]}" }) { index, song ->
                    SongListRow(song, index + 1, playing = song.id == currentId) { open(song, section.items) }
                }
            } else {
                item(key = "row-$sectionIndex-${section.title}") {
                    val sideState0 = rememberLazyListState()
                    LazyRow(
                        state = sideState0,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp),
                        modifier = Modifier.sideScroll(sideState0),
                    ) {
                        val itemsUniqueKeys = uniqueKeys(section.items) { it.id }
                        itemsIndexed(section.items, key = { i, _ -> itemsUniqueKeys[i] }) { _, item ->
                            Card(item = item, playing = item.id == currentId) { open(item, section.items) }
                        }
                    }
                }
            }
        }

        if (librarySongs.isNotEmpty()) {
            item(key = "lib-head") { SectionHeader(title = "In your library", onMore = null) }
            val libraryKeys = uniqueKeys(librarySongs) { it.id }
            itemsIndexed(librarySongs, key = { i, _ -> "lib-${libraryKeys[i]}" }) { index, song ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        StoredSongRow(song, playing = song.id == currentId) {
                            onPlay(librarySongs.map { it.toItem() }, index)
                        }
                    }
                }
            }
        }

        if (content !is ArtistContent.Loading && sections.isEmpty() && librarySongs.isEmpty()) {
            item(key = "empty") {
                Box(modifier = Modifier.fillMaxWidth().padding(64.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing to show for this artist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    }

    if (linking) {
        LinkArtistDialog(
            name = artist.name,
            onDismiss = { linking = false },
            onPick = { picked ->
                linking = false
                scope.launch {
                    ArtistLinks.linkByHand(library, artist.name, picked)
                    linkVersion++
                }
            },
        )
    }
}

/** How many of an artist's songs the page shows before the arrow. The phone's number. */
private const val TOP_SONGS = 5

/** The artist header's band: tall enough for the portrait, never a wall of photo on a wide window. */
private val ARTIST_HEADER_HEIGHT = 232.dp
private val ARTIST_PORTRAIT = 176.dp

/**
 * The picture, the name and the buttons.
 *
 * The whole photo, in a circle as the phone draws artists, beside the name - over a blurred copy of
 * itself. It used to be stretched across the page and cropped to a strip 42% of the width tall:
 * YouTube's artist pictures are mostly square, so a wide window cut heads off and still filled half
 * the screen. A circle of the full picture can't lose anything, and the blur behind is free to be
 * cropped any way the window likes because nothing in it needs to be recognisable.
 */
@Composable
internal fun ArtistHeader(
    name: String,
    thumbnail: String?,
    description: String?,
    /** "359M monthly audience" under the name, once the live page has said so. */
    audience: String?,
    loading: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fullWidth = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ARTIST_HEADER_HEIGHT)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
        ) {
            if (thumbnail != null) {
                Artwork(
                    thumbnail,
                    size = fullWidth,
                    height = ARTIST_HEADER_HEIGHT,
                    cornerRadius = 0.dp,
                    modifier = Modifier.blur(56.dp),
                )
                // Pulled toward the page colour so the name reads on any photo, light or dark.
                Box(modifier = Modifier.fillMaxSize().background(background.copy(alpha = 0.5f)))
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            ) {
                if (thumbnail != null) {
                    // From ArtworkCache, memory then disk, so a picture seen before draws at once.
                    Artwork(thumbnail, size = ARTIST_PORTRAIT, cornerRadius = ARTIST_PORTRAIT / 2)
                } else if (loading) {
                    // No picture known yet, but one may be coming: a plain disc the picture's size
                    // rather than the no-picture icon, which would read as "this artist has none".
                    PlaceholderBox(Modifier.size(ARTIST_PORTRAIT), cornerRadius = ARTIST_PORTRAIT / 2)
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(ARTIST_PORTRAIT)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(
                            OuterTuneIcons.person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(ARTIST_PORTRAIT * 0.45f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(28.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Artist",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (loading) {
                            Spacer(modifier = Modifier.width(12.dp))
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (!audience.isNullOrBlank()) {
                        Text(
                            audience,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
    ) {
        Button(onClick = onPlay, enabled = canPlay) {
            Icon(OuterTuneIcons.play, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShuffle, enabled = canPlay) {
            Icon(OuterTuneIcons.shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Shuffle")
        }
    }
    if (!description.isNullOrBlank()) {
        var expanded by remember(description) { mutableStateOf(false) }
        // Clipped to a rounded box with the text padded inside it, as the song rows are, so its
        // highlight is a panel around the paragraph rather than a hard edge along the letters. The
        // outer and inner padding add up to the 28dp every other line on the page starts at.
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    } else if (loading) {
        // Room for the description that may be coming - the collapsed paragraph's three lines and
        // the same padding - so the shelves below do not move down when it arrives.
        PlaceholderLines(
            MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            lines = DESCRIPTION_LINES,
            lastLineFraction = 0.4f,
        )
    }
}

/** The description's lines before it is clicked open. */
private const val DESCRIPTION_LINES = 3

/** A [SectionHeader] while its title is not known yet, the same height and inset. */
@Composable
private fun SectionHeaderPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        PlaceholderLines(MaterialTheme.typography.titleLarge, modifier = Modifier.width(180.dp), lastLineFraction = 1f)
    }
}

/**
 * The shape of an artist's page before any of it has arrived: top songs, then two shelves of cards -
 * what nearly every artist has, at their real sizes. The real sections replace these from the top,
 * so the header and description above never move.
 */
private fun LazyListScope.artistPlaceholders() {
    item(key = "placeholder-songs-head") { SectionHeaderPlaceholder() }
    items(TOP_SONGS, key = { "placeholder-song-$it" }) {
        // SongListRow's insets: 20dp outside, 8dp inside, then the 28dp rank column before the
        // thumbnail - less the 4dp SongRowPlaceholder pads with itself.
        SongRowPlaceholder(modifier = Modifier.padding(start = 52.dp, end = 24.dp))
    }
    repeat(2) { shelf ->
        item(key = "placeholder-shelf-head-$shelf") { SectionHeaderPlaceholder() }
        item(key = "placeholder-shelf-$shelf") {
            // A lazy row so the cards past the window's edge are simply not drawn; not scrollable,
            // as there is nothing to scroll to.
            LazyRow(
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(horizontal = 28.dp),
            ) {
                items(PLACEHOLDER_CARDS, key = { "placeholder-card-$it" }) { CardPlaceholder() }
            }
        }
    }
}

/** Enough placeholder cards to fill the widest window's shelf. */
private const val PLACEHOLDER_CARDS = 12

/** Enough placeholder song rows to fill a tall window. */
private const val PLACEHOLDER_ROWS = 14

/** A section title, with an arrow through to everything in it when there is more. */
@Composable
internal fun SectionHeader(title: String, onMore: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The highlight is a rounded box with room above and below the title, rather than a hard strip
    // hugging the line of text. The spacing is split around it so the title sits where it always did.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .let { if (onMore != null) it.hoverable(interaction).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onMore) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onMore != null) {
            Icon(
                OuterTuneIcons.expandLess,
                contentDescription = "See all $title",
                tint = if (hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp).rotate(90f),
            )
        }
    }
}

/** One of an artist's songs, numbered as a chart is. */
@Composable
private fun SongListRow(song: SongItem, rank: Int, playing: Boolean, onClick: () -> Unit) = SongMenuArea(song) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp),
        )
        SongArtwork(song.thumbnail, size = 48.dp, playing = playing, cornerRadius = 6.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.shown().detailsLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowActions(song)
    }
}

/**
 * Everything in one section - every song, every album - fetched from the section's own page and
 * followed through its continuations. Songs as a list, everything else as a grid of cards.
 */
@Composable
private fun MoreItems(
    artistName: String,
    section: ArtistSection,
    currentId: String?,
    visible: (List<YTItem>) -> List<YTItem>,
    onBack: () -> Unit,
    onOpen: (YTItem, List<YTItem>) -> Unit,
    modifier: Modifier,
) {
    var state by remember(section) { mutableStateOf<MoreContent>(MoreContent.Loading) }
    LaunchedEffect(section) {
        val endpoint = section.moreEndpoint ?: return@LaunchedEffect
        state = withContext(Dispatchers.IO) {
            YouTube.artistItems(endpoint).fold(
                onSuccess = { first ->
                    val items = first.items.toMutableList()
                    var next = first.continuation
                    var pages = 0
                    while (next != null && pages < 10) {
                        val page = YouTube.artistItemsContinuation(next).getOrNull() ?: break
                        items += page.items
                        next = page.continuation
                        pages++
                    }
                    MoreContent.Loaded(first.title.ifBlank { section.title }, items.distinctBy { it.id })
                },
                onFailure = { MoreContent.Failed(it.message ?: "Could not load ${section.title}.") },
            )
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
            TextButton(onClick = onBack) { Text("← $artistName") }
            Text(
                (state as? MoreContent.Loaded)?.title ?: section.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        when (val current = state) {
            // The list or grid's shape rather than a spinner, guessed from the shelf it came from -
            // the same as the artist page's own placeholders, so the items land where these were.
            MoreContent.Loading -> if (section.items.all { it is SongItem }) {
                LazyColumn(userScrollEnabled = false, contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(PLACEHOLDER_ROWS, key = { "placeholder-$it" }) {
                        SongRowPlaceholder(modifier = Modifier.padding(start = 52.dp, end = 24.dp))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(176.dp),
                    userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                ) {
                    val round = section.items.firstOrNull() is ArtistItem
                    items(PLACEHOLDER_CARDS * 2, key = { "placeholder-$it" }) { CardPlaceholder(round = round) }
                }
            }
            is MoreContent.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(current.reason, color = MaterialTheme.colorScheme.error)
            }
            is MoreContent.Loaded -> {
                val items = visible(current.items)
                if (items.all { it is SongItem }) {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        val itemKeys = uniqueKeys(items) { it.id }
                        itemsIndexed(items, key = { i, _ -> itemKeys[i] }) { index, item ->
                            SongListRow(item as SongItem, index + 1, playing = item.id == currentId) { onOpen(item, items) }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(176.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
                    ) {
                        val itemsUniqueKeys = uniqueKeys(items) { it.id }
                        gridItemsIndexed(items, key = { i, _ -> itemsUniqueKeys[i] }) { _, item ->
                            Card(item = item, playing = item.id == currentId) { onOpen(item, items) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Artist names, with the ones that lead somewhere marked as leading somewhere.
 *
 * Only credits carrying a channel are underlined and clickable. A name with nowhere to go that
 * looked like a link would be worse than plain text, because the disappointment is on the click
 * rather than before it - and a good many credits arrive as bare names.
 *
 * Built as one annotated string rather than a Row of separate Texts so the whole credit wraps and
 * ellipsises as a sentence. A Row would break between names and leave "Artist A," on one line with
 * nothing after it.
 */
/**
 * One credit as it should be shown and what happens when it is clicked.
 *
 * [linked] is whether there is a real channel behind the name. It drives the underline only - both
 * kinds are clickable. That is deliberate: a credit that arrived as a bare name still has a page
 * worth opening, made of whatever the library already holds by that artist, and refusing the click
 * meant that on any song whose credits came back without channel ids - which is most of them from
 * some sources - nothing on the line responded to the pointer at all. Underlining only the linked
 * ones keeps the distinction visible without making half the line dead.
 */
data class ArtistCredit(val artist: StoredArtist, val linked: Boolean)

/**
 * The credits for a song, ready to draw.
 *
 * Falls back to the joined display string when there are no structured credits, so a song stored
 * before the library kept them still shows its artist rather than "Unknown artist" - it just has
 * one credit instead of several.
 */
fun creditsFor(artists: List<StoredArtist>, fallback: String): List<ArtistCredit> {
    if (artists.isNotEmpty()) return artists.map { ArtistCredit(it, it.linkable) }
    val trimmed = fallback.trim()
    if (trimmed.isEmpty()) return emptyList()
    return listOf(ArtistCredit(StoredArtist.unlinked(trimmed), linked = false))
}

/**
 * A song's credits, each name its own clickable target.
 *
 * Separate Text composables in a Row rather than one string with click offsets mapped back to
 * ranges. The offset approach worked in a test and not in the app, and even when it works it is a
 * silent failure waiting to happen - get the arithmetic wrong by two and clicking one artist opens
 * a different one, with nothing on screen to suggest anything is amiss. A composable per name cannot
 * be off by two.
 *
 * It also buys the thing a desktop expects and the annotated string could not give: the pointer
 * turns into a hand over each name, so the line advertises itself as clickable before it is clicked.
 */
@Composable
fun ArtistNames(
    artists: List<StoredArtist>,
    fallback: String,
    colour: androidx.compose.ui.graphics.Color,
    style: androidx.compose.ui.text.TextStyle,
    onClick: (StoredArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val credits = remember(artists, fallback) { creditsFor(artists, fallback) }
    if (credits.isEmpty()) {
        Text(
            text = "Unknown artist",
            style = style,
            color = colour,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    // One line that scrolls as a whole when it is too long, as the phone's player does. Laid out at
    // the width left over, the later names were squeezed down to nothing, so a song with many
    // artists simply lost the last few.
    Row(modifier = modifier.basicMarquee(iterations = 1, initialDelayMillis = 5000)) {
        credits.forEachIndexed { index, credit ->
            if (index > 0) {
                Text(text = ", ", style = style, color = colour, maxLines = 1, softWrap = false)
            }
            // Underlined under the pointer only, as a link on a web page is. Permanently underlined,
            // every credit on the player read as a hyperlink in a document rather than a title - the
            // phone shows them plain, and the hand cursor already says they can be clicked.
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Text(
                text = credit.artist.name,
                style = style.copy(
                    textDecoration = if (credit.linked && hovered) TextDecoration.Underline else null,
                ),
                color = colour,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .hoverable(interaction)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interaction, indication = null) { onClick(credit.artist) },
            )
        }
    }
}

/** Picks the YouTube Music artist a local artist is. Searched by their name to start with. */
@Composable
private fun LinkArtistDialog(name: String, onDismiss: () -> Unit, onPick: (ArtistItem) -> Unit) {
    var query by remember { mutableStateOf(name) }
    var results by remember { mutableStateOf<List<ArtistItem>?>(null) }
    LaunchedEffect(query) {
        kotlinx.coroutines.delay(350)
        results = null
        results = runCatching { ArtistLinks.candidates(query) }.getOrDefault(emptyList())
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link \"$name\" to YouTube Music") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.typingField().fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                val found = results
                when {
                    found == null -> CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(22.dp), strokeWidth = 2.dp)
                    found.isEmpty() -> Text("No artists found.", modifier = Modifier.padding(8.dp))
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(found.size) { index ->
                            val item = found[index]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(item) }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                            ) {
                                Box(modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
                                    Artwork(item.thumbnail, size = 40.dp, cornerRadius = 0.dp)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
