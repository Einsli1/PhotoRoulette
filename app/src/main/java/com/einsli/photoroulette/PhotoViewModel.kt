package com.einsli.photoroulette

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.einsli.photoroulette.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
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
    val cumulative: StatsCounters = StatsCounters(),
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
    // The undo stack records each action's OWN direction. Negating the session's current
    // lastActionDir instead would alternate right/left across consecutive undos (5 keeps then 5
    // undos would return from 右→左→右→左), which is exactly the "左一张右一张" the user saw.
    private class UndoEntry(val photo: PhotoEntity, val oldState: PhotoState, val dir: Int)
    private val undoStack = ArrayDeque<UndoEntry>()
    private val counts = combine(repository.totalCount, repository.processedCount) { total, processed -> total to processed }
    private val statsCounters = settingsRepository.statsCounters
    val trashItems: Flow<List<PhotoEntity>> = repository.trashItems
    private val homeStats = combine(
        repository.keptCount,
        repository.processedDays.map { computeStreak(it) },
        repository.trashBytes,
        repository.memoryCandidates.map { buildMemory(it) }
    ) { kept, streak, bytes, memory ->
        HomeStats(kept, streak, bytes, memory)
    }
    // 「本周整理」用自然周窗口:本周一 00:00 起,与图表的 周一..周日 七个固定槽位一一对应。
    private val weekSince: Long
        get() = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val weekStats = combine(
        repository.dayCountsSince(weekSince),
        repository.weekKept(weekSince),
        repository.weekFreedBytes(weekSince)
    ) { dayCounts, kept, freed ->
        val byDay = dayCounts.associate { it.day to it.cnt }
        val monday = LocalDate.now().with(DayOfWeek.MONDAY)
        val days = (0 until 7).map { offset ->
            byDay[monday.plusDays(offset.toLong()).toString()] ?: 0
        }
        WeekStats(days, days.sum(), kept, freed)
    }
    // combine() only has typed overloads up to 5 flows; merge the counters in a second stage.
    val ui = combine(
        combine(settings, session, counts, homeStats, weekStats) { config, sess, c, stats, week ->
            AppUiState(sess == null, sess, c.first, c.second, config, stats, week)
        },
        statsCounters
    ) { base, cum -> base.copy(cumulative = cum) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())

    init {
        restoreSession()
        reconcileQuietly()
        // Seed the lifetime counters from the current DB state on the first run after upgrade,
        // so existing installs don't start at zero. No-op afterwards.
        viewModelScope.launch {
            val processed = repository.processedCount.first()
            val kept = repository.keptCount.first()
            settingsRepository.backfillStatsCountersIfAbsent(kept, (processed - kept).coerceAtLeast(0))
        }
    }

    fun scan() = reload()

    /** Save the album selection, then rebuild — the reconcile inside reload() rescans, so the
     *  photo total reflects it. The scan only INSERTs photos that are new to the DB — processed
     *  history (kept / deleted / streak) is preserved; this is NOT a reset. */
    fun updateAlbums(albums: List<String>) = viewModelScope.launch {
        val next = settings.value.copy(includedAlbums = albums)
        settingsRepository.save(next)
        reload()
    }
    suspend fun availableAlbums(): List<String> = repository.listAlbums(settings.value.includeVideos)

    fun reload() {
        Log.d(TAG, "=== reload() called, undoStack.size=${undoStack.size} ===")
        undoStack.clear()
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        session.value = null
        val version = ++buildVersion
        viewModelScope.launch {
            // 对账(含增量扫描)先于建队列:系统相册删除 / 系统回收站清空 / 系统相册侧恢复回收站
            // 照片,都在这里同步进本地库。配置直接读 DataStore 的首个真实值——绝不拿默认配置跑
            // 对账(会按"不含视频"误删视频行);读取失败或对账失败都只跳过对账,照常建队列。
            val cfg = try { settingsRepository.settings.first() } catch (_: Exception) { null }
            if (cfg != null) {
                try {
                    val r = repository.reconcile(cfg)
                    Log.d(TAG, "reconcile: poolDeleted=${r.poolDeleted}, goneMarked=${r.goneMarked}, restored=${r.restoredCount}, livenessDead=${r.livenessDead}")
                    if (r.restoredCount > 0) settingsRepository.updateStatsCounters(0, -r.restoredCount)
                } catch (e: Exception) {
                    Log.w(TAG, "reconcile failed, skipping this round", e)
                }
            }
            val restored = repository.sessionQueue(cfg ?: settings.value)
            if (version == buildVersion) {
                Log.d(TAG, "reload: publishing session $version with ${restored.queue.size} photos, position=${restored.position}")
                session.value = ReviewSession(version, restored.queue, restored.position)
                cardShownAt = SystemClock.elapsedRealtime()
            } else {
                Log.d(TAG, "reload: version mismatch ($version vs $buildVersion), discarding")
            }
        }
    }

    /** 后台对账但不打断当前会话:首页点「继续整理」续用会话、或冷启动 restoreSession 之后
     *  的补跑路径。对账若判定队列里有照片已被外部删除(gone),原位重算队列,位置尽量保留。 */
    fun reconcileQuietly() = viewModelScope.launch {
        val cfg = try { settingsRepository.settings.first() } catch (_: Exception) { null } ?: return@launch
        val version = buildVersion
        try {
            val r = repository.reconcile(cfg)
            Log.d(TAG, "quiet reconcile: poolDeleted=${r.poolDeleted}, goneMarked=${r.goneMarked}, restored=${r.restoredCount}, livenessDead=${r.livenessDead}")
            if (r.restoredCount > 0) settingsRepository.updateStatsCounters(0, -r.restoredCount)
            if (r.poolDeleted > 0 || r.goneMarked > 0) resyncSession(version)
        } catch (e: Exception) {
            Log.w(TAG, "quiet reconcile failed", e)
        }
    }

    /** 对账后把当前会话队列里的死行(gone=1)剔除:保持剩余照片的相对顺序,当前位置优先
     *  对准用户正在看的那张;同时把调整后的队列写回 DataStore,进程被杀后也能恢复正确状态。 */
    private suspend fun resyncSession(version: Long) {
        val cur = session.value ?: return
        if (version != buildVersion) return  // 期间用户重建了会话,旧的对账结果不覆盖新队列
        val ids = cur.queue.map { it.mediaId }
        val live = repository.liveQueuePhotos(ids)
        if (live.size == ids.size) return  // 队列照片没有受影响
        val byId = live.associateBy { it.mediaId }
        val newQueue = ids.mapNotNull { byId[it] }
        val curId = cur.current?.mediaId
        val newPos = curId?.let { id -> newQueue.indexOfFirst { it.mediaId == id } }?.takeIf { it >= 0 }
            ?: cur.position.coerceAtMost(newQueue.size)
        Log.d(TAG, "resync session: ${ids.size} -> ${newQueue.size} photos, position ${cur.position} -> $newPos")
        session.value = cur.copy(queue = newQueue, position = newPos)
        cardShownAt = SystemClock.elapsedRealtime()
        repository.savePosition(newPos, newQueue.map { it.mediaId })
    }

    /** 冷启动/继续入口的快速路径:跳过对账,直接按存档队列(或按策略现取,均为毫秒级)把
     *  会话恢复出来,首页/整理页即刻有内容;对账交给 [reconcileQuietly] 在后台补跑,若对账
     *  判定队列里有照片已被外部删除,再由其内部 [resyncSession] 原位剔除(位置尽量不动)。 */
    fun restoreSession() {
        Log.d(TAG, "=== restoreSession() called ===")
        undoStack.clear()
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        session.value = null
        val version = ++buildVersion
        viewModelScope.launch {
            // 等 DataStore 的真实配置(仅建新队列时用到策略/范围;恢复存档队列用不上,但读取也就几毫秒)
            val cfg = try { settingsRepository.settings.first() } catch (_: Exception) { null } ?: AppSettings()
            val restored = repository.sessionQueue(cfg)
            if (version == buildVersion) {
                Log.d(TAG, "restore: publishing session $version with ${restored.queue.size} photos, position=${restored.position}")
                session.value = ReviewSession(version, restored.queue, restored.position)
                cardShownAt = SystemClock.elapsedRealtime()
            } else {
                Log.d(TAG, "restore: version mismatch ($version vs $buildVersion), discarding")
            }
        }
    }

    /** 首页「开始整理」(无进行中会话)与通知直达:立即出会话,同时对账后台补跑。 */
    fun startSession() {
        restoreSession()
        reconcileQuietly()
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
        undoStack.addLast(UndoEntry(photo, photo.state, dir))
        val queueIds = cur.queue.map { it.mediaId }
        viewModelScope.launch {
            repository.apply(photo, state)
            repository.savePosition(newPos, queueIds)
            when (state) {
                PhotoState.KEEP -> settingsRepository.updateStatsCounters(1, 0)
                PhotoState.DELETE_PENDING -> settingsRepository.updateStatsCounters(0, 1)
                else -> {}
            }
        }
        return true
    }

    fun undo() {
        val entry = undoStack.removeLastOrNull() ?: run {
            Log.d(TAG, "undo: empty stack, ignoring")
            return
        }
        val photo = entry.photo
        val oldState = entry.oldState
        val cur = session.value
        if (cur == null) { Log.d(TAG, "undo: no session"); return }
        val idx = cur.queue.indexOfFirst { it.mediaId == photo.mediaId }
        if (idx < 0) { Log.d(TAG, "undo: photo not in queue"); return }
        // lastActionDir comes from THIS action's own direction (negated), not the session's
        // current one — consecutive undos must each recall their own swipe side.
        Log.d(TAG, "undo: position ${cur.position} → $idx, dir ${cur.lastActionDir} → ${-entry.dir}")
        session.value = cur.copy(position = idx, lastActionDir = -entry.dir)
        ghostPhotoId = 0L
        ghostLockUntil = 0L
        cardShownAt = SystemClock.elapsedRealtime()
        lastActionAt = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            repository.apply(photo, oldState)
            repository.savePosition(idx, cur.queue.map { it.mediaId })
            // Reverse this action's own counter contribution (dir: 1=keep, -1=delete).
            if (entry.dir == 1) settingsRepository.updateStatsCounters(-1, 0)
            else settingsRepository.updateStatsCounters(0, -1)
        }
    }

    // Individual setters — save immediately without rebuilding the queue. The new values take
    // effect on the next session / 开始整理 (or immediately for darkMode via theme recomposition).
    fun setDailyCount(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(dailyCount = v)) }
    // Toggling 包含视频 rescans via reload()'s reconcile: turning it ON pulls videos in right
    // away, turning it OFF drops unprocessed videos from the pool.
    fun setIncludeVideos(v: Boolean) = viewModelScope.launch {
        val next = settings.value.copy(includeVideos = v)
        settingsRepository.save(next)
        reload()
    }
    fun setIncludeScreenshots(v: Boolean) = viewModelScope.launch { settingsRepository.save(settings.value.copy(includeScreenshots = v)) }
    fun setReminderHour(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(reminderHour = v)) }
    fun setReminderMinute(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(reminderMinute = v)) }
    fun setDarkMode(v: Int) = viewModelScope.launch { settingsRepository.save(settings.value.copy(darkMode = v)) }
    fun setPhotoRange(v: String) = viewModelScope.launch { settingsRepository.save(settings.value.copy(photoRange = v)) }
    fun setCustomRangeStart(v: Long) = viewModelScope.launch { settingsRepository.save(settings.value.copy(photoRange = "custom", customRangeStart = v)) }
    fun setStrategy(v: String) = viewModelScope.launch { settingsRepository.save(settings.value.copy(strategy = v)) }
    /** 处理删除并继续整理:清掉存档队列后立即建下一批(刚删的这批 confirmDeleted 已标
     *  inTrash=1,建队列 SQL 本来就排除,不用等对账),对账照旧后台补跑。 */
    fun nextSession() = viewModelScope.launch { Log.d(TAG, "nextSession() starting"); repository.startNextSession(); Log.d(TAG, "nextSession() calling startSession"); startSession() }
    suspend fun pendingDeletes() = repository.pendingDeletes()
    fun confirmDeleted(ids: List<Long>) = viewModelScope.launch { Log.d(TAG, "confirmDeleted(${ids.size} photos)"); repository.confirmDeleted(ids) }
    suspend fun trashList(): List<PhotoEntity> = repository.trashList()
    fun restoreFromTrash(ids: List<Long>) = viewModelScope.launch {
        repository.restoreFromTrash(ids)
        settingsRepository.updateStatsCounters(0, -ids.size)
    }
    fun revertPendingDeletes(ids: List<Long>) = viewModelScope.launch {
        Log.d(TAG, "revertPendingDeletes(${ids.size})")
        repository.revertPendingDeletes(ids)
        settingsRepository.updateStatsCounters(0, -ids.size)
    }
    fun deleteFromTrash(ids: List<Long>) = viewModelScope.launch { repository.deleteFromTrash(ids) }
    fun reset() = viewModelScope.launch { repository.reset(); settingsRepository.resetStatsCounters(); reload() }

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
