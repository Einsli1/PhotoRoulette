package com.einsli.photoroulette

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.einsli.photoroulette.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ReviewSession(
    val sessionId: Long,
    val queue: List<PhotoEntity>,
    val position: Int,
    val lastActionDir: Int = 0,
) {
    val current: PhotoEntity? get() = queue.getOrNull(position)
    val remaining: Int get() = (queue.size - position).coerceAtLeast(0)
}

/** "5年前的今天" style memory: a group of photos taken on this month/day in a past year. */
data class MemoryInfo(
    val yearsAgo: Int,
    val dateText: String,
    val count: Int,
    val photos: List<PhotoEntity>,
)

/** Last-7-days organizing trend plus weekly roll-up. */
data class WeekStats(
    val days: List<Int>,        // 7 entries, oldest first, today last
    val organized: Int,
    val kept: Int,
    val freedBytes: Long,
) {
    val deleted: Int get() = (organized - kept).coerceAtLeast(0)
}

data class HomeStats(
    val kept: Int = 0,
    val streak: Int = 0,
    val trashBytes: Long = 0,
    val memory: MemoryInfo? = null,
)

data class AppUiState(
    val loading: Boolean = true,
    val session: ReviewSession? = null,
    val total: Int = 0,
    val processed: Int = 0,
    val settings: AppSettings = AppSettings(),
    val stats: HomeStats = HomeStats(),
    val week: WeekStats = WeekStats(List(7) { 0 }, 0, 0, 0),
) {
    val remaining: Int get() = session?.remaining ?: 0
}

class PhotoViewModel(private val repository: PhotoRepository, private val settingsRepository: SettingsRepository) : ViewModel() {
    private val TAG = "PhotoVM"
    private val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val session = MutableStateFlow<ReviewSession?>(null)
    val sessionFlow: StateFlow<ReviewSession?> = session.asStateFlow()
    private var buildVersion = 0L
    // Debounce against double-dispatched gestures: after a card is swiped, Compose can replay
    // the same drag onto the freshly-swapped card (observed as a second accepted action ~340 ms
    // later in the logs). Any accepted action inside this window is treated as a ghost.
    private val actionCooldownMs = 400L
    // The MIUI handwriting service injects repeated skip-style clicks (accessibility actions
    // with no real touch events) at ~200-800ms intervals for the current card; see PhotoVM
    // logs. The latch below, keyed by the replayed photo's mediaId, absorbs the whole burst:
    // once armed it rejects every replay for that photo and renews while the burst lasts. The
    // 400ms cooldown catches a fire landing close behind another. The no-user-touch gate below
    // catches the very first fire: the UI passes the timestamp of the last real pointer event,
    // and an action for a card nobody has touched yet must be an injected click.
    private val ghostLockMs = 1500L
    private var ghostPhotoId = 0L
    private var ghostLockUntil = 0L
    private var lastActionAt = 0L
    // Minimum time between a real touch and this card becoming current. Real swipes and taps
    // always produce pointer events AFTER the card appears, so they pass; injected clicks
    // produce none.
    private val touchMarginMs = 200L
    private var cardShownAt = 0L
    private val undoStack = ArrayDeque<Pair<PhotoEntity, PhotoState>>()
    private val counts = combine(repository.totalCount, repository.processedCount) { total, processed -> total to processed }
    val trashItems: Flow<List<PhotoEntity>> = repository.trashItems
    private val homeStats = combine(
        repository.keptCount,
        repository.processedDays.map { computeStreak(it) },
        repository.trashBytes,
        repository.memoryCandidates.map { buildMemory(it) }
    ) { kept, streak, bytes, memory ->
        HomeStats(kept, streak, bytes, memory)
    }
    // Rolling 7-day window (today + previous 6 days) for the stats trend.
    private val weekSince = System.currentTimeMillis() - 6L * 24 * 3600 * 1000
    private val weekStats = combine(
        repository.dayCountsSince(weekSince),
        repository.weekKept(weekSince),
        repository.weekFreedBytes(weekSince)
    ) { dayCounts, kept, freed ->
        val byDay = dayCounts.associate { it.day to it.cnt }
        val today = LocalDate.now()
        val days = (6 downTo 0).map { offset ->
            byDay[today.minusDays(offset.toLong()).toString()] ?: 0
        }
        WeekStats(days, days.sum(), kept, freed)
    }
    val ui = combine(settings, session, counts, homeStats, weekStats) { config, sess, c, stats, week ->
        AppUiState(sess == null, sess, c.first, c.second, config, stats, week)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init { reload() }

    fun scan() = viewModelScope.launch { repository.scanGallery(settings.value); reload() }
    suspend fun availableAlbums(): List<String> = repository.listAlbums(settings.value.includeVideos)

    fun reload() {
        Log.d(TAG, "=== reload() called, undoStack.size=${undoStack.size} ===")
        undoStack.clear()
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        session.value = null
        val version = ++buildVersion
        viewModelScope.launch {
            val restored = repository.sessionQueue(settings.value)
            if (version == buildVersion) {
                Log.d(TAG, "reload: publishing session $version with ${restored.queue.size} photos, position=${restored.position}")
                session.value = ReviewSession(version, restored.queue, restored.position)
                cardShownAt = SystemClock.elapsedRealtime()
            } else {
                Log.d(TAG, "reload: version mismatch ($version vs $buildVersion), discarding")
            }
        }
    }

    fun action(mediaId: Long, state: PhotoState, dir: Int, userTouchedAt: Long): Boolean {
        val cur = session.value
        if (cur == null) {
            Log.w(TAG, "action($mediaId, $state, dir=$dir): REJECTED — session is null")
            return false
        }
        val idx = cur.queue.indexOfFirst { it.mediaId == mediaId }
        if (idx != cur.position) {
            Log.w(TAG, "action($mediaId, $state, dir=$dir): REJECTED — idx=$idx != position=${cur.position}")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now < ghostLockUntil && mediaId == ghostPhotoId) {
            // The gesture-replay burst is still firing for this photo: keep absorbing
            // and renew the lock so later replays stay blocked.
            ghostLockUntil = now + ghostLockMs
            Log.w(TAG, "action($mediaId, $state, dir=$dir): REJECTED: ghost-lock, absorbing gesture-replay burst")
            return false
        }
        if (userTouchedAt - cardShownAt < touchMarginMs) {
            // No real touch since this card appeared: an accessibility service (MIUI
            // handwriting stub) injected this click. Arm the latch so the whole burst is
            // absorbed from its very first fire.
            ghostPhotoId = mediaId
            ghostLockUntil = now + ghostLockMs
            Log.w(TAG, "action($mediaId, $state, dir=$dir): REJECTED: no-user-touch (last touch ${userTouchedAt - cardShownAt}ms relative to card), arming ghost-lock until $ghostLockUntil")
            return false
        }
        if (now - lastActionAt < actionCooldownMs) {
            // Two fires landed close together on an already-old card: treat as a burst too.
            ghostPhotoId = mediaId
            ghostLockUntil = now + ghostLockMs
            Log.w(TAG, "action($mediaId, $state, dir=$dir): REJECTED: cooldown, arming ghost-lock until $ghostLockUntil")
            return false
        }
        val photo = cur.queue[idx]
        val newPos = (idx + 1).coerceAtMost(cur.queue.size)
        Log.d(TAG, "action($mediaId, $state, dir=$dir): ACCEPTED — position ${cur.position} → $newPos, remaining=${cur.queue.size - newPos}")
        session.value = cur.copy(position = newPos, lastActionDir = dir)
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        cardShownAt = now
        lastActionAt = now
        undoStack.addLast(photo to photo.state)
        val queueIds = cur.queue.map { it.mediaId }
        viewModelScope.launch {
            repository.apply(photo, state)
            repository.savePosition(newPos, queueIds)
        }
        return true
    }

    fun undo() {
        val (photo, oldState) = undoStack.removeLastOrNull() ?: run {
            Log.d(TAG, "undo: empty stack, ignoring")
            return
        }
        val cur = session.value
        if (cur == null) { Log.d(TAG, "undo: no session"); return }
        val idx = cur.queue.indexOfFirst { it.mediaId == photo.mediaId }
        if (idx < 0) { Log.d(TAG, "undo: photo not in queue"); return }
        Log.d(TAG, "undo: position ${cur.position} → $idx, dir ${cur.lastActionDir} → ${-cur.lastActionDir}")
        session.value = cur.copy(position = idx, lastActionDir = -cur.lastActionDir)
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        cardShownAt = SystemClock.elapsedRealtime()
        lastActionAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            repository.apply(photo, oldState)
            repository.savePosition(idx, cur.queue.map { it.mediaId })
        }
    }

    fun saveSettings(newSettings: AppSettings) = viewModelScope.launch { settingsRepository.save(newSettings); reload() }
    // Individual setters — save immediately without rebuilding the queue. The new values take
    // effect on the next session / 开始整理 (or immediately for darkMode via theme recomposition).
    fun setDailyCount(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(dailyCount = v)) }
    fun setIncludeVideos(v: Boolean) = viewModelScope.launch { settingsRepository.save(settings.value.copy(includeVideos = v)) }
    fun setIncludeScreenshots(v: Boolean) = viewModelScope.launch { settingsRepository.save(settings.value.copy(includeScreenshots = v)) }
    fun setReminderHour(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(reminderHour = v)) }
    fun setReminderMinute(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(reminderMinute = v)) }
    fun setDarkMode(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(darkMode = v)) }
    fun setPhotoRange(v: String) = viewModelScope.launch { settingsRepository.save(settings.value.copy(photoRange = v)) }
    fun setCustomRangeStart(v: Long) = viewModelScope.launch { settingsRepository.save(settings.value.copy(photoRange = "custom", customRangeStart = v)) }
    fun setStrategy(v: String) = viewModelScope.launch { settingsRepository.save(settings.value.copy(strategy = v)) }
    fun nextSession() = viewModelScope.launch { Log.d(TAG, "nextSession() starting"); repository.startNextSession(); Log.d(TAG, "nextSession() calling reload"); reload() }
    suspend fun pendingDeletes() = repository.pendingDeletes()
    fun confirmDeleted(ids: List<Long>) = viewModelScope.launch { Log.d(TAG, "confirmDeleted(${ids.size} photos)"); repository.confirmDeleted(ids) }
    suspend fun trashList(): List<PhotoEntity> = repository.trashList()
    fun restoreFromTrash(ids: List<Long>) = viewModelScope.launch { repository.restoreFromTrash(ids) }
    fun revertPendingDeletes(ids: List<Long>) = viewModelScope.launch { Log.d(TAG, "revertPendingDeletes(${ids.size})"); repository.revertPendingDeletes(ids) }
    fun deleteFromTrash(ids: List<Long>) = viewModelScope.launch { repository.deleteFromTrash(ids) }
    fun reset() = viewModelScope.launch { repository.reset(); reload() }

    // ── home-screen stats helpers ────────────────────────────────────────────

    /** Consecutive days (ending today or yesterday) on which at least one photo was processed. */
    private fun computeStreak(dayStrings: List<String>): Int {
        val today = LocalDate.now()
        val days = dayStrings.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sortedDescending()
        var streak = 0
        var expected = today
        for ((i, d) in days.withIndex()) {
            if (i == 0) {
                // A streak still counts if the last active day is today OR yesterday
                // (the user simply hasn't opened the app yet today).
                if (d != expected && d != expected.minusDays(1)) break
            } else if (d != expected.minusDays(1)) break
            streak++
            expected = d
        }
        return streak
    }

    /** Oldest group of photos taken on today's month/day in a past year → "N年前的今天". */
    private fun buildMemory(candidates: List<PhotoEntity>): MemoryInfo? {
        if (candidates.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val matches = candidates.mapNotNull { photo ->
            val d = runCatching { Instant.ofEpochMilli(photo.dateTaken).atZone(zone).toLocalDate() }.getOrNull()
            if (d != null && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year) photo to d else null
        }
        if (matches.isEmpty()) return null
        val oldestYear = matches.minOf { it.second.year }
        val group = matches.filter { it.second.year == oldestYear }.map { it.first }
        val date = Instant.ofEpochMilli(group.first().dateTaken).atZone(zone).toLocalDate()
        return MemoryInfo(
            yearsAgo = today.year - oldestYear,
            dateText = "${date.year}年${date.monthValue}月${date.dayOfMonth}日",
            count = group.size,
            photos = group
        )
    }

    class Factory(private val repository: PhotoRepository, private val settings: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PhotoViewModel(repository, settings) as T
    }
}
