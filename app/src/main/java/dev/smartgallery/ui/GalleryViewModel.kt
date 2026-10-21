package dev.smartgallery.ui

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import dev.smartgallery.analysis.IndexScheduler
import dev.smartgallery.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    val repo = GalleryRepository(app)
    val filter = MutableStateFlow(GalleryFilter())
    val media = filter.flatMapLatest { repo.gallery(it) }.cachedIn(viewModelScope)
    val people =
        repo.dao
            .peopleCards()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val folders =
        repo.dao
            .folders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories =
        repo.dao
            .categories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress =
        repo.dao
            .progress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Progress(0, 0, 0))
    val undo =
        repo.dao.undoState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val exclusions =
        repo.dao
            .exclusions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)
    private var refreshJob: Job? = null
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refresh(500)
            }
        }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri("external"),
            true,
            observer,
        )
    }

    fun refresh(delayMs: Long = 0) {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch(Dispatchers.IO) {
                delay(delayMs)
                refreshing.value = true
                try {
                    repo.reconcile()
                    IndexScheduler.schedule(getApplication())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message.value = "Could not refresh media. Check access and try again."
                } finally {
                    refreshing.value = false
                }
            }
    }

    fun action(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                if (success != null) message.value = success
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                message.value =
                    if (e.message?.startsWith("These people") == true) e.message
                    else "The action could not complete. Please retry."
            } catch (_: Exception) {
                message.value = "The action could not complete. Please retry."
            }
        }
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}
