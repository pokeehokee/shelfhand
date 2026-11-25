package app.shelfhand.catalog

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shelfhand.core.catalog.Access
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.GenreMatch
import app.shelfhand.core.catalog.SeriesFilter
import app.shelfhand.core.catalog.Sort

private data class LengthBand(val label: String, val min: Long?, val max: Long?)
private val BANDS = listOf(
    LengthBand("Any length", null, null),
    LengthBand("Under 1h", null, 3600),
    LengthBand("1-3h", 3600, 3 * 3600L),
    LengthBand("3-8h", 3 * 3600L, 8 * 3600L),
    LengthBand("8-15h", 8 * 3600L, 15 * 3600L),
    LengthBand("15-25h", 15 * 3600L, 25 * 3600L),
    LengthBand("25h+", 25 * 3600L, null),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FindScreen(model: FindModel) {
    val size by model.catalogSize.collectAsState()
    val results by model.results.collectAsState()
    val total by model.total.collectAsState()
    val collections by model.collections.collectAsState()
    val lookupStatus by model.lookupStatus.collectAsState()
    val f by model.filter.collectAsState()
    val status by model.status.collectAsState()
    val genres by model.genres.collectAsState()
    val languages by model.languages.collectAsState()
    var showFilters by remember { mutableStateOf(true) }
    var allGenres by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    val sources by model.sources.collectAsState()
    val sourceStatus by model.sourceStatus.collectAsState()
    var showSources by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }

    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (size == 0) {
                    Text("Search free audiobooks with filters the sites do not have: length, series or standalone, and genres combined. " +
                        "Start with the LibriVox catalog, or add any site's feed below.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = model::refresh) { Text("Download the LibriVox catalog") }
                    status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status ?: "LibriVox not downloaded", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = model::refresh) { Text("Update") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sources.isEmpty()) "Your sites and feeds" else "Your sites and feeds (${sources.size})",
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSources = !showSources }) { Text(if (showSources) "Hide" else "Add or manage") }
                }
                if (showSources) {
                    Text("Paste a site, podcast, RSS, Atom or OPDS address. For a web page, Shelfhand uses the feed the page lists.",
                        style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = address, onValueChange = { address = it }, singleLine = true,
                            label = { Text("Address") }, modifier = Modifier.weight(1f))
                        TextButton(onClick = { model.addSource(address) { address = "" } }) { Text("Add") }
                    }
                    sources.forEach { src ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(src.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${"%,d".format(src.items)} entries · ${src.url}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { model.removeSource(src) }) { Text("Remove") }
                        }
                    }
                    if (sources.isNotEmpty()) TextButton(onClick = model::refreshSources) { Text("Update all feeds") }
                }
                sourceStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (size > 0) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = f.text, onValueChange = { t -> model.update { it.copy(text = t) } },
                    label = { Text("Title, author or genre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${"%,d".format(total)} match", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    SortMenu(sortMenu, { sortMenu = it }, f.sort) { s -> model.update { it.copy(sort = s) } }
                    TextButton(onClick = { showFilters = !showFilters }) { Text(if (showFilters) "Hide filters" else "Filters") }
                }
                if (showFilters) {
                    if (collections.size > 1) {
                        Label("Source")
                        ChipRow {
                            FilterChip(selected = f.collections.isEmpty(), onClick = { model.update { it.copy(collections = emptySet()) } }, label = { Text("All") })
                            collections.forEach { (c, n) ->
                                FilterChip(selected = c in f.collections,
                                    onClick = { model.update { it.copy(collections = it.collections.toggle(c)) } }, label = { Text("$c $n") })
                            }
                        }
                    }
                    Label("Get it as")
                    ChipRow {
                        listOf(Access.ANY to "Any", Access.DOWNLOAD to "Direct download", Access.TORRENT to "Torrent")
                            .forEach { (v, l) -> FilterChip(selected = f.access == v, onClick = { model.update { it.copy(access = v) } }, label = { Text(l) }) }
                    }
                    Label("Length")
                    ChipRow {
                        BANDS.forEach { b ->
                            FilterChip(selected = f.minSeconds == b.min && f.maxSeconds == b.max,
                                onClick = { model.update { it.copy(minSeconds = b.min, maxSeconds = b.max) } }, label = { Text(b.label) })
                        }
                    }
                    Label("Series")
                    ChipRow {
                        listOf(SeriesFilter.ANY to "Either", SeriesFilter.SERIES_ONLY to "Part of a series", SeriesFilter.STANDALONE_ONLY to "Standalone")
                            .forEach { (v, l) -> FilterChip(selected = f.series == v, onClick = { model.update { it.copy(series = v) } }, label = { Text(l) }) }
                    }
                    Label("Language")
                    ChipRow {
                        FilterChip(selected = f.languages.isEmpty(), onClick = { model.update { it.copy(languages = emptySet()) } }, label = { Text("All") })
                        languages.take(12).forEach { (lang, n) ->
                            FilterChip(selected = lang in f.languages,
                                onClick = { model.update { it.copy(languages = it.languages.toggle(lang)) } }, label = { Text("$lang $n") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("Genres", Modifier.weight(1f))
                        FilterChip(selected = f.genreMatch == GenreMatch.ALL,
                            onClick = { model.update { it.copy(genreMatch = if (it.genreMatch == GenreMatch.ALL) GenreMatch.ANY else GenreMatch.ALL) } },
                            label = { Text(if (f.genreMatch == GenreMatch.ALL) "Must have all" else "Any of them") })
                        if (f.genres.isNotEmpty() || f.excludeGenres.isNotEmpty())
                            TextButton(onClick = { model.update { it.copy(genres = emptySet(), excludeGenres = emptySet()) } }) { Text("Clear") }
                    }
                    Text("Tap once to include, twice to exclude.", style = MaterialTheme.typography.labelSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (allGenres) genres else genres.take(24)).forEach { (g, n) ->
                            val inc = g in f.genres
                            val exc = g in f.excludeGenres
                            FilterChip(
                                selected = inc || exc,
                                onClick = {
                                    model.update {
                                        when {
                                            inc -> it.copy(genres = it.genres - g, excludeGenres = it.excludeGenres + g)
                                            exc -> it.copy(excludeGenres = it.excludeGenres - g)
                                            else -> it.copy(genres = it.genres + g)
                                        }
                                    }
                                },
                                label = { Text(if (exc) "not $g" else "$g $n", textDecoration = if (exc) TextDecoration.LineThrough else null) },
                            )
                        }
                    }
                    lookupStatus?.let { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = model::lookUpNow) { Text("Look up now") }
                        }
                    }
                    if (genres.size > 24) TextButton(onClick = { allGenres = !allGenres }) { Text(if (allGenres) "Fewer genres" else "All ${genres.size} genres") }
                }
                HorizontalDivider()
            }
        }
        items(results, key = { it.source + it.id }) { b ->
            val k = b.source + b.id
            Result(b, model, expanded = open == k, onClick = { open = if (open == k) null else k })
            HorizontalDivider()
        }
        if (total > results.size) item {
            Text("Showing the first ${results.size}. Narrow the filters to see the rest.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun SortMenu(open: Boolean, setOpen: (Boolean) -> Unit, sort: Sort, onSort: (Sort) -> Unit) {
    androidx.compose.foundation.layout.Box {
        TextButton(onClick = { setOpen(true) }) { Text(sortLabel(sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { setOpen(false) }) {
            Sort.entries.forEach { s -> DropdownMenuItem(text = { Text(sortLabel(s)) }, onClick = { onSort(s); setOpen(false) }) }
        }
    }
}

private fun sortLabel(s: Sort) = when (s) {
    Sort.TITLE -> "Title"
    Sort.AUTHOR -> "Author"
    Sort.SHORTEST -> "Shortest"
    Sort.LONGEST -> "Longest"
    Sort.NEWEST_ADDED -> "Newest added"
    Sort.OLDEST_WORK -> "Oldest work"
    Sort.NEWEST_WORK -> "Newest work"
}

@Composable
private fun Label(t: String, modifier: Modifier = Modifier) = Text(t, style = MaterialTheme.typography.labelLarge, modifier = modifier.padding(top = 4.dp))

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

private fun <T> Set<T>.toggle(v: T) = if (v in this) this - v else this + v

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Result(b: CatalogBook, model: FindModel, expanded: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    // The long description and the looked-up genres are read only once the row is opened.
    var description by remember(b.dbId) { mutableStateOf<String?>(null) }
    var genreMarks by remember(b.dbId) { mutableStateOf<List<Pair<String, Boolean>>?>(null) }
    if (expanded) LaunchedEffect(b.dbId) {
        description = model.description(b) ?: b.description
        genreMarks = model.genresOf(b)
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
        val line = listOfNotNull(
            b.authors.joinToString(", ").ifBlank { null },
            b.seconds?.let(::hours) ?: "length unknown",
            b.series?.let { s -> "series: " + s.name + (s.index?.let { " #" + (if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) } ?: "") },
        ).joinToString(" · ")
        Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (b.genres.isNotEmpty()) Text(b.genres.joinToString(", "), style = MaterialTheme.typography.labelSmall, maxLines = if (expanded) 3 else 1, overflow = TextOverflow.Ellipsis)
        if (expanded) {
            description?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            genreMarks?.filter { it.second }?.takeIf { it.isNotEmpty() }?.let { g ->
                Text("Subjects from Open Library: " + g.joinToString(", ") { it.first }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
            val meta = listOfNotNull(b.language, b.sections?.let { "$it parts" },
                b.year?.let { "first published $it" + if ("year" in b.lookedUp) " (Open Library)" else "" }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            b.collection?.let { Text("From $it", style = MaterialTheme.typography.labelSmall) }
            if (b.series != null) Text(if ("series" in b.lookedUp) "Series from Open Library." else "Series is guessed from the title; the source does not record it.",
                style = MaterialTheme.typography.labelSmall)
            var problem by remember { mutableStateOf<String?>(null) }
            fun open(u: String, none: String) {
                problem = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }.exceptionOrNull()?.let { none }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                b.torrentUrl?.let { u -> Button(onClick = { open(u, "No torrent app on this phone can open it.") }) { Text("Get torrent") } }
                b.downloadUrl?.let { u -> Button(onClick = { open(u, "No app on this phone can open the download.") }) { Text("Download") } }
                b.feedUrl?.let { u -> OutlinedButton(onClick = { open(u, "No podcast or browser app can open the feed.") }) { Text("Open feed") } }
                b.pageUrl?.let { u -> OutlinedButton(onClick = { open(u, "No browser can open the page.") }) { Text("Open page") } }
            }
            problem?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun hours(s: Long): String = if (s < 3600) "${s / 60}m" else "${s / 3600}h ${(s % 3600) / 60}m"
