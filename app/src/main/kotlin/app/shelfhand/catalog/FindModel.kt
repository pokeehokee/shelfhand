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

    val all = MutableStateFlow<List<CatalogBook>>(emptyList())
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
            all.value = store.load()
            status.value = if (all.value.isEmpty()) null else updatedLine()
        }
    }

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            status.value = "Downloading the LibriVox catalog..."
            try {
                all.value = store.refresh { n -> status.value = "Downloading the LibriVox catalog: $n books so far" }
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
        return "${"%,d".format(all.value.size)} LibriVox books" + (at?.let { ", updated $it" } ?: "")
    }
}
