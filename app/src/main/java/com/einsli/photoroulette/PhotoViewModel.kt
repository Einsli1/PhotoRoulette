package com.einsli.photoroulette

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.einsli.photoroulette.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** An immutable snapshot of one organizing session. The queue is fixed for the whole session, so
 *  every photo keeps a stable position and a decision can be verified against the current
 *  position — the basis for making "one swipe = one advance" structurally guaranteed.
 *  [lastActionDir] records the direction the previous card was swiped (1=right, -1=left, 2=down,
 *  -2=up) so the next card can slide in from the opposite side. On undo it is negated so the
 *  card re-enters from where it was thrown. */
data class ReviewSession(
    val sessionId: Long,
    val queue: List<PhotoEntity>,
    val position: Int,
    val lastActionDir: Int = 0,
) {
    val current: PhotoEntity? get() = queue.getOrNull(position)
    val remaining: Int get() = (queue.size - position).coerceAtLeast(0)
}

data class AppUiState(
    val loading: Boolean = true,
    val session: ReviewSession? = null,
    val total: Int = 0,
    val processed: Int = 0,
    val settings: AppSettings = AppSettings(),
) {
    val remaining: Int get() = session?.remaining ?: 0
}

class PhotoViewModel(private val repository: PhotoRepository, private val settingsRepository: SettingsRepository) : ViewModel() {
    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    // Single source of truth for the review page: null while a session is (re)building, then one
    // immutable snapshot. There is no separate queue/position pair to drift out of sync.
    private val session = MutableStateFlow<ReviewSession?>(null)
    val sessionFlow: StateFlow<ReviewSession?> = session.asStateFlow()
    // True once the session-load delay has elapsed so the new photo can be shown. Cleared
    // synchronously in reload() so the UI shows a spinner even when the DB query is so fast that
    // session=null and session=new queue happen in a single main-thread dispatch window.
    // Without this, the Review page can transition directly from "本次完成" / old card to the
    // new first card — which reads as "the photo jumped by itself". The mandatory 400 ms delay
    // guarantees the spinner is visible for at least a few frames.
    private val sessionReady = MutableStateFlow(false)
    val sessionReadyFlow: StateFlow<Boolean> = sessionReady.asStateFlow()
    // Bumped on every reload; a slow queue build only publishes if it is still the latest one.
    private var buildVersion = 0L
    // Undo history: the photo acted on and its state before the action, so an accidental swipe
    // can be rolled back to let the user re-decide.
    private val undoStack = ArrayDeque<Pair<PhotoEntity, PhotoState>>()
    private val counts = combine(repository.totalCount, repository.processedCount) { total, processed -> total to processed }
    val trashItems: Flow<List<PhotoEntity>> = repository.trashItems
    val ui = combine(settings, session, counts) { config, sess, c ->
        AppUiState(sess == null, sess, c.first, c.second, config)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init { reload() }

    fun scan() = viewModelScope.launch { repository.scanGallery(settings.value); reload() }
    suspend fun availableAlbums(): List<String> = repository.listAlbums(settings.value.includeVideos)

    /** Start or restart a session. The previous session (if any) is discarded synchronously
     *  so the UI immediately shows a spinner. Once the queue has been rebuilt a mandatory
     *  cooldown keeps the spinner visible to prevent a direct flash to the first photo. */
    fun reload() {
        undoStack.clear()
        sessionReady.value = false
        session.value = null
        val version = ++buildVersion
        viewModelScope.launch {
            val q = repository.sessionQueue(settings.value)
            if (version == buildVersion) {
                session.value = ReviewSession(version, q, 0)
                delay(400)
                sessionReady.value = true
            }
        }
    }

    /** Record a decision for the photo with [mediaId] and [dir] (the visual direction the card
     *  was swiped). Returns true only if the photo is the current one; otherwise the swipe is
     *  ignored. Because the check and the position advance happen together, synchronously, one
     *  gesture can never advance two photos. */
    fun action(mediaId: Long, state: PhotoState, dir: Int): Boolean {
        val cur = session.value ?: return false
        val idx = cur.queue.indexOfFirst { it.mediaId == mediaId }
        if (idx != cur.position) return false
        val photo = cur.queue[idx]
        session.value = cur.copy(position = (idx + 1).coerceAtMost(cur.queue.size), lastActionDir = dir)
        undoStack.addLast(photo to photo.state)
        viewModelScope.launch { repository.apply(photo, state) }
        return true
    }

    /** Step back to the last acted-on photo and restore its previous state. The card re-enters
     *  from the direction it was thrown thanks to the negated [lastActionDir]. */
    fun undo() {
        val (photo, oldState) = undoStack.removeLastOrNull() ?: return
        val cur = session.value ?: return
        val idx = cur.queue.indexOfFirst { it.mediaId == photo.mediaId }
        if (idx < 0) return
        session.value = cur.copy(position = idx, lastActionDir = -cur.lastActionDir)
        viewModelScope.launch { repository.apply(photo, oldState) }
    }

    fun saveSettings(newSettings: AppSettings) = viewModelScope.launch { settingsRepository.save(newSettings); reload() }
    fun nextSession() = viewModelScope.launch { repository.startNextSession(); reload() }
    suspend fun pendingDeletes() = repository.pendingDeletes()
    fun confirmDeleted(ids: List<Long>) = viewModelScope.launch { repository.confirmDeleted(ids) }
    suspend fun trashList(): List<PhotoEntity> = repository.trashList()
    fun restoreFromTrash(ids: List<Long>) = viewModelScope.launch { repository.restoreFromTrash(ids) }
    fun revertPendingDeletes(ids: List<Long>) = viewModelScope.launch { repository.revertPendingDeletes(ids) }
    fun deleteFromTrash(ids: List<Long>) = viewModelScope.launch { repository.deleteFromTrash(ids) }
    fun reset() = viewModelScope.launch { repository.reset(); reload() }

    class Factory(private val repository: PhotoRepository, private val settings: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PhotoViewModel(repository, settings) as T
    }
}
