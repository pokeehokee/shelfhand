package app.shelfhand.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogFilter
import app.shelfhand.core.catalog.Filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class FindModel(app: Application) : AndroidViewModel(app) {
    private val store = CatalogStore(app)
    private val feeds = FeedStore(app)

    private val librivox = MutableStateFlow<List<CatalogBook>>(emptyList())
    private val feedBooks = MutableStateFlow<List<CatalogBook>>(emptyList())
    val sources = MutableStateFlow<List<FeedSource>>(emptyList())
    val sourceStatus = MutableStateFlow<String?>(null)
    val all: StateFlow<List<CatalogBook>> = combine(librivox, feedBooks) { a, b -> a + b }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // English by default because most of the catalog is; one tap clears it.
    val filter = MutableStateFlow(Filter(languages = setOf("English")))
    val status = MutableStateFlow<String?>(null)
    private var refreshJob: Job? = null

    val results: StateFlow<List<CatalogBook>> = combine(all, filter.debounce(150)) { books, f -> CatalogFilter.apply(books, f) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val genres: StateFlow<List<Pair<String, Int>>> = all.map { CatalogFilter.genreCounts(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val languages: StateFlow<List<Pair<String, Int>>> = all.map { CatalogFilter.languageCounts(it) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            librivox.value = store.load()
            status.value = if (librivox.value.isEmpty()) null else updatedLine()
        }
        viewModelScope.launch { reloadFeeds() }
    }

    private suspend fun reloadFeeds() {
        sources.value = feeds.sources()
        feedBooks.value = feeds.load()
    }

    /** Adds a site or feed by its address; [done] runs only when it worked, so the box can clear. */
    fun addSource(address: String, done: () -> Unit) {
        if (address.isBlank()) return
        viewModelScope.launch {
            sourceStatus.value = "Looking for a feed at $address..."
            try {
                val s = feeds.add(address)
                reloadFeeds()
                val n = feedBooks.value.count { it.collection == s.title }
                sourceStatus.value = "Added ${s.title}: $n ${if (n == 1) "entry" else "entries"}"
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceStatus.value = "Couldn't add it: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun removeSource(s: FeedSource) {
        viewModelScope.launch {
            feeds.remove(s.feedUrl)
            update { it.copy(collections = it.collections - s.title) }
            reloadFeeds()
            sourceStatus.value = "Removed ${s.title}"
        }
    }

    fun refreshSources() {
        viewModelScope.launch {
            sourceStatus.value = "Updating ${sources.value.size} feeds..."
            val errors = feeds.refreshAll()
            reloadFeeds()
            sourceStatus.value = if (errors.isEmpty()) "Feeds updated" else "Some feeds didn't update: " + errors.joinToString("; ")
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            status.value = "Downloading the LibriVox catalog..."
            try {
                librivox.value = store.refresh { n -> status.value = "Downloading the LibriVox catalog: $n books so far" }
                status.value = updatedLine()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Could not download the catalog: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun update(f: (Filter) -> Filter) { filter.value = f(filter.value) }

    private fun updatedLine(): String {
        val at = store.updatedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        return "${"%,d".format(librivox.value.size)} LibriVox books" + (at?.let { ", updated $it" } ?: "")
    }
}
