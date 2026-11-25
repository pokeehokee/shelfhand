package app.shelfhand.catalog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shelfhand.core.catalog.CatalogBook
import app.shelfhand.core.catalog.CatalogDb
import app.shelfhand.core.catalog.Filter
import app.shelfhand.CrashLog
import app.shelfhand.core.catalog.SiteReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
    // Opened on first use, always on a background thread: opening it on the main thread could freeze the app.
    private val db by lazy { CatalogHolder.get(app) }

    /**
     * Anything that goes wrong in Find shows as a line on the screen and is kept in the crash reports,
     * rather than closing the whole app.
     */
    private val guard = CoroutineExceptionHandler { _, e ->
        CrashLog.record(app, "Find", e)
        status.value = "Something went wrong in Find: ${e.message ?: e.javaClass.simpleName}"
    }

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
        viewModelScope.launch(guard) {
            combine(filter.debounce(150), version) { f, _ -> f }.collectLatest { f ->
                // One bad search must not stop searching: say so and keep listening for the next one.
                try {
                    val page = withContext(Dispatchers.IO) { db.search(f) }
                    results.value = page.books
                    total.value = page.total
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CrashLog.record(getApplication(), "Find search", e)
                    status.value = "That search didn't work: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        viewModelScope.launch(guard) { reload() }
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
    suspend fun description(b: CatalogBook): String? = b.dbId?.let { id -> withContext(Dispatchers.IO) { runCatching { db.description(id) }.getOrNull() } }

    /** The book's genres, each marked true where Open Library supplied it rather than the source. */
    suspend fun genresOf(b: CatalogBook): List<Pair<String, Boolean>> =
        b.dbId?.let { id -> withContext(Dispatchers.IO) { runCatching { db.genresOf(id) }.getOrNull() } } ?: b.genres.map { it to false }

    fun lookUpNow() {
        LookupWorker.runNow(getApplication())
        lookupStatus.value = "Looking up details now. This runs in the background, about one book a second."
    }

    /** Adds a site or feed by its address; [done] runs only when it worked, so the box can clear. */
    fun addSource(address: String, done: () -> Unit) {
        if (address.isBlank() || feedJob?.isActive == true) return
        feedJob = viewModelScope.launch(guard) {
            sourceStatus.value = "Looking for a feed at $address..."
            try {
                val (s, o) = feeds.add(address) { sourceStatus.value = it }
                reload()
                sourceStatus.value = "Added ${s.title}: ${SiteReader.summary(o)}."
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.record(getApplication(), "Adding $address", e)
                sourceStatus.value = "Couldn't add it: ${e.message ?: e.javaClass.simpleName}"
            } catch (e: OutOfMemoryError) {
                sourceStatus.value = "That site is too big to read in one go on this phone."
            }
        }
    }

    fun removeSource(s: CatalogDb.Source) {
        viewModelScope.launch(guard) {
            feeds.remove(s.id)
            update { it.copy(collections = it.collections - s.title) }
            reload()
            sourceStatus.value = "Removed ${s.title}"
        }
    }

    fun refreshSources() {
        if (feedJob?.isActive == true) return
        feedJob = viewModelScope.launch(guard) {
            sourceStatus.value = "Updating ${sources.value.size} feeds..."
            val errors = feeds.refreshAll { sourceStatus.value = it }
            reload()
            sourceStatus.value = if (errors.isEmpty()) "Feeds updated" else "Feeds updated, with problems: " + errors.joinToString("; ")
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch(guard) {
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
