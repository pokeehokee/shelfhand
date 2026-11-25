package app.shelfhand.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogDb
import app.shelfhand.core.catalog.Filter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Find, over the on-phone catalog database. Every search is a query, so the whole catalog never sits in memory. */
@OptIn(FlowPreview::class)
class FindModel(app: Application) : AndroidViewModel(app) {
    private val store = CatalogStore(app)
    private val feeds = FeedStore(app)
    private val db by lazy { CatalogHolder.get(app) }

    // English by default because most of the catalog is; one tap clears it.
    val filter = MutableStateFlow(Filter(languages = setOf("English")))
    val results = MutableStateFlow<List<CatalogBook>>(emptyList())
    val total = MutableStateFlow(0)
    val catalogSize = MutableStateFlow(0)
    val genres = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val languages = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    /** Every source in the database (LibriVox and the user's), for the Source chips. */
    val collections = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val sources = MutableStateFlow<List<CatalogDb.Source>>(emptyList())
    val status = MutableStateFlow<String?>(null)
    val sourceStatus = MutableStateFlow<String?>(null)
    val lookupStatus = MutableStateFlow<String?>(null)
    /** Bumped whenever the database changes, so the current search runs again. */
    private val version = MutableStateFlow(0)
    private var refreshJob: Job? = null
    private var feedJob: Job? = null

    init {
        LookupWorker.schedule(app)
        viewModelScope.launch {
            combine(filter.debounce(150), version) { f, _ -> f }.collectLatest { f ->
                val page = withContext(Dispatchers.IO) { db.search(f) }
                results.value = page.books
                total.value = page.total
            }
        }
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        withContext(Dispatchers.IO) {
            val all = db.sources()
            sources.value = all.filter { it.kind == "feed" }
            collections.value = all.filter { it.items > 0 }.map { it.title to it.items }.sortedByDescending { it.second }
            catalogSize.value = db.count()
            genres.value = db.genreCounts()
            languages.value = db.languageCounts()
            lookupStatus.value = if (catalogSize.value == 0) null
                else "Details looked up for ${"%,d".format(db.lookupsDone())} of ${"%,d".format(catalogSize.value)} books"
        }
        status.value = librivoxLine()
        version.value++
    }

    /** The long description, read from the database only when a title is opened. */
    suspend fun description(b: CatalogBook): String? = b.dbId?.let { id -> withContext(Dispatchers.IO) { db.description(id) } }

    /** The book's genres, each marked true where Open Library supplied it rather than the source. */
    suspend fun genresOf(b: CatalogBook): List<Pair<String, Boolean>> =
        b.dbId?.let { id -> withContext(Dispatchers.IO) { db.genresOf(id) } } ?: b.genres.map { it to false }

    fun lookUpNow() {
        LookupWorker.runNow(getApplication())
        lookupStatus.value = "Looking up details now. This runs in the background, about one book a second."
    }

    /** Adds a site or feed by its address; [done] runs only when it worked, so the box can clear. */
    fun addSource(address: String, done: () -> Unit) {
        if (address.isBlank() || feedJob?.isActive == true) return
        feedJob = viewModelScope.launch {
            sourceStatus.value = "Looking for a feed at $address..."
            try {
                val (s, n) = feeds.add(address) { sourceStatus.value = it }
                reload()
                sourceStatus.value = "Added ${s.title}: ${"%,d".format(n)} ${if (n == 1) "entry" else "entries"}"
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sourceStatus.value = "Couldn't add it: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun removeSource(s: CatalogDb.Source) {
        viewModelScope.launch {
            feeds.remove(s.id)
            update { it.copy(collections = it.collections - s.title) }
            reload()
            sourceStatus.value = "Removed ${s.title}"
        }
    }

    fun refreshSources() {
        if (feedJob?.isActive == true) return
        feedJob = viewModelScope.launch {
            sourceStatus.value = "Updating ${sources.value.size} feeds..."
            val errors = feeds.refreshAll { sourceStatus.value = it }
            reload()
            sourceStatus.value = if (errors.isEmpty()) "Feeds updated" else "Some feeds didn't update: " + errors.joinToString("; ")
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            status.value = "Downloading the LibriVox catalog..."
            try {
                store.refresh { n -> status.value = "Downloading the LibriVox catalog: ${"%,d".format(n)} books so far" }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Could not download the catalog: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun update(f: (Filter) -> Filter) { filter.value = f(filter.value) }

    private suspend fun librivoxLine(): String? = withContext(Dispatchers.IO) {
        val n = store.count
        if (n == 0) return@withContext null
        val at = store.updatedAt?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it)) }
        "${"%,d".format(n)} LibriVox books" + (at?.let { ", updated $it" } ?: "")
    }
}
