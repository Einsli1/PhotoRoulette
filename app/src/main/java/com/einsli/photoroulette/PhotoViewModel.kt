package com.einsli.photoroulette

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.einsli.photoroulette.data.*
import com.einsli.photoroulette.media.PreviewCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    // ── 首页封面预热(PreviewCache):生成时机 = 数据确定的一刻,全部先于首页首帧,
    //    新内容第一次出现即命中,冷启动首页两卡打开即显。 ──
    init {
        // 会话封面(current ?: queue[0]):冷启动恢复存档队列、开始/下一批/推进/撤销/
        // 对账剔除都会改写 session —— 一个 collector 覆盖所有入口。
        viewModelScope.launch {
            session
                .map { it?.current ?: it?.queue?.firstOrNull() }
                .distinctUntilChangedBy { it?.mediaId }
                .collect { photo -> photo?.let { PreviewCache.ensure(settingsRepository.appContext, it) } }
        }
        // 回忆封面:memory 按 dateTaken 确定性查询,只在跨天/重扫后变化;每次变化补 take(2)。
        viewModelScope.launch {
            repository.memoryCandidates
                .map { buildMemory(it) }
                .distinctUntilChanged()
                .collect { memory ->
                    memory?.photos?.take(2)?.forEach { PreviewCache.ensure(settingsRepository.appContext, it) }
                }
        }
    }
    // ── 周统计:任意一周(周一..周日)的 7 天趋势 + 汇总。「本周整理」与「历史整理」
    //    共用同一套窗口查询——历史记录也是按周显示和切换的。 ──
    private fun weekStatsFlow(monday: LocalDate): Flow<WeekStats> {
        val zone = ZoneId.systemDefault()
        val start = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        return combine(
            repository.dayCountsBetween(start, end),
            repository.weekKeptBetween(start, end),
            repository.weekFreedBytesBetween(start, end)
        ) { dayCounts, kept, freed ->
            val byDay = dayCounts.associate { it.day to it.cnt }
            val days = (0 until 7).map { offset ->
                byDay[monday.plusDays(offset.toLong()).toString()] ?: 0
            }
            WeekStats(days, days.sum(), kept, freed)
        }
    }
    // 「本周整理」用自然周窗口:本周一 00:00 起,与图表的 周一..周日 七个固定槽位一一对应。
    private val weekStats = weekStatsFlow(LocalDate.now().with(DayOfWeek.MONDAY))
    // ── 历史整理:统计页选中查看的某一周(null = 未选,仍显示本周),存该周的周一 ──
    private val selectedWeek = MutableStateFlow<LocalDate?>(null)
    val historyWeek: StateFlow<LocalDate?> = selectedWeek.asStateFlow()
    fun selectHistoryWeek(weekMonday: LocalDate?) { selectedWeek.value = weekMonday }
    // 统计页周卡片按需取某一周的统计:每个 pager 页面自订阅自己那周(冷流,离开视口即停订),
    // 取代原先只跟选中周的 historyWeekStats 单流——连续翻周时相邻页也要有数据。
    fun weekStatsOf(monday: LocalDate): Flow<WeekStats> = weekStatsFlow(monday)
    // 历史月历的起始月 = 最早的 processedAt 月份。processedAt 只会是"现在",下限不会变,
    // 进程内缓存即可;重置整理记录(reset)后失效重查。
    @Volatile private var minHistoryMonthCache: YearMonth? = null
    suspend fun earliestHistoryMonth(): YearMonth {
        minHistoryMonthCache?.let { return it }
        val now = YearMonth.now()
        val m = repository.earliestProcessedAt()
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
            ?.let { YearMonth.from(it) }
            ?: now
        return m.coerceAtMost(now).also { minHistoryMonthCache = it }
    }

    /** 某个月每一天的整理量(统计页月历用),缺勤日不在 map 里。 */
    suspend fun monthDayCounts(month: YearMonth): Map<LocalDate, Int> {
        val zone = ZoneId.systemDefault()
        val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository.dayCountsBetween(start, end)
            .first()
            .associate { LocalDate.parse(it.day) to it.cnt }
    }
    // ── 冷启动首帧写死(杀后台重启不许看到加载态):构造期用 runBlocking 同步读一份全量快照,
    //    作为 ui 的 stateIn 初始值;配合 init 里同步发布会话,第一帧就是完整首页——包括回忆
    //    时光机卡。关键在于所有读取「并行」发起:总耗时≈最慢一路(DataStore 读盘 或 Room 开库),
    //    而不是当初串行版的逐项叠加(那版首帧 750~1065ms)。快照失败(DataStore/Room 异常或
    //    1.5s 超时)则整体退回旧的异步加载路径。──
    private val bootState: AppUiState = runBlocking {
        val t0 = SystemClock.elapsedRealtime()
        val snapshot = buildBootState()
        if (snapshot != null) {
            Log.d(TAG, "boot snapshot ok in " + (SystemClock.elapsedRealtime() - t0) + "ms: total=" + snapshot.total + ", queue=" + (snapshot.session?.queue?.size ?: 0))
            snapshot
        } else {
            Log.w(TAG, "boot snapshot failed/timed out, falling back to async restore")
            AppUiState()
        }
    }

    /** 同步冷启动快照:全部读取并行发起,总耗时≈最慢一路。任何一步抛异常/超时都返回 null,
     *  由调用方退回异步加载。 */
    private suspend fun buildBootState(): AppUiState? = withTimeoutOrNull(1_500) {
        coroutineScope {
            val totalDef = async(Dispatchers.IO) { repository.totalCount.first() }
            val processedDef = async(Dispatchers.IO) { repository.processedCount.first() }
            val keptDef = async(Dispatchers.IO) { repository.keptCount.first() }
            val daysDef = async(Dispatchers.IO) { repository.processedDays.first() }
            val bytesDef = async(Dispatchers.IO) { repository.trashBytes.first() }
            val memoryDef = async(Dispatchers.IO) { buildMemory(repository.memoryCandidates.first()) }
            val weekDef = async(Dispatchers.IO) { weekStatsFlow(LocalDate.now().with(DayOfWeek.MONDAY)).first() }
            val cumulativeDef = async(Dispatchers.IO) { settingsRepository.statsCounters.first() }
            withContext(Dispatchers.IO) {
                val cfg = settingsRepository.settings.first()
                // 存档会话按 id 取行——库已被上面的并行统计查询打开,这里不再付开库成本
                val restored = repository.sessionQueue(cfg)
                AppUiState(
                    loading = false,
                    session = ReviewSession(1L, restored.queue, restored.position),
                    total = totalDef.await(),
                    processed = processedDef.await(),
                    settings = cfg,
                    stats = HomeStats(
                        keptDef.await(),
                        computeStreak(daysDef.await()),
                        bytesDef.await(),
                        memoryDef.await(),
                    ),
                    week = weekDef.await(),
                    cumulative = cumulativeDef.await(),
                )
            }
        }
    }

    // combine() only has typed overloads up to 5 flows; merge the counters in a second stage.
    val ui = combine(
        combine(settings, session, counts, homeStats, weekStats) { config, sess, c, stats, week ->
            AppUiState(sess == null, sess, c.first, c.second, config, stats, week)
        },
        statsCounters
    ) { base, cum -> base.copy(cumulative = cum) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, bootState)

    init {
        // 冷启动的会话已由 bootState 同步恢复,这里直接发布——不走 restoreSession 的
        // 「先置 null 再异步发布」(那会在首帧闪加载态)。快照失败才退回异步恢复。
        val restored = bootState.session
        if (restored != null) {
            session.value = restored
            buildVersion = 1L
            cardShownAt = SystemClock.elapsedRealtime()
        } else {
            restoreSession()
        }
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
    fun reset() = viewModelScope.launch {
        repository.reset(); settingsRepository.resetStatsCounters()
        minHistoryMonthCache = null // 整理记录清空后,月历的起始月要重查
        selectedWeek.value = null   // 回到本周视图
        reload()
    }

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
