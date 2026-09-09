# PhotoRoulette 整体架构评审

> 评审对象：versionName 1.7（versionCode 15）全部源码约 8000 行 Kotlin + 构建配置。
> 方法：分四路全文细读（data 持久层 / 应用骨架与媒体层 / UI 支撑组件 / App.kt 巨型文件），
> 另核对了 Manifest、build.gradle 与 README。所有结论均附行号证据；标注「推测」处未经运行时验证。

---

## ✅ 重构进度（2026-09-08 起，refactor/architecture-review 已合回 master；2026-09-09 更新：ee4201 完成任务 1–3；最新：MediaGridScreen 去重完成（中-5 全落地）、高-4 通知权限提示、低-19 视频轮询优化）

- **高-2 主线程 IO**：已修——movePendingToTrash/restoreFromSystemTrash 的 URI 校验循环移入 `withContext(Dispatchers.IO)`。
- **高-3 迁移即清库**：已修——`exportSchema` 开启（`app/schemas/6.json`、`7.json` 入库）；version 7 显式迁移补 4 个索引（state/inTrash/dateTaken/album）；无条件破坏性回退改为仅 v1/v2 兜底；`MigrationTest` 迁移测试已上真机跑通 6→7。
- **高-4 静默失败 UI 信号**：部分完成——通知权限被拒的降级提示已做：设置页「每日提醒」卡片内新增「开启通知提醒」提示行（与精确闹钟提示行同款：权限未授时显示、点击跳系统通知设置页 `ACTION_APP_NOTIFICATION_SETTINGS`、onResume 自动刷新）；**仍待产品设计**：扫描失败返回空表（`MediaScanner.kt:45,138`）与 reconcile 异常仅记日志（`PhotoViewModel.kt:319-320,344-345`）的 UI 信号。
- **中-5 App.kt god file**：已完成——回收站/回忆/整理/设置四屏与预载引擎、滚动条已拆独立文件（App.kt 2195→465 行），对外接缝与行为零变化；路由魔法 int 已改 `Page` enum（ee4201：`when` 分发由编译器保证穷尽、enum 为 Serializable 可直接进 `rememberSaveable`，新增页面漏写分支直接编译失败）；最后一块 **MediaGridScreen 去重**（RecycleBin↔MemoryViewer 约 85% 重复）已完成——新增 `ui/MediaGridScreen.kt` 共享宫格页骨架（预览状态机/SpringPull 弹性宫格/渐变悬浮头/选择模式/预览 overlay），RecycleBin.kt 570→106 行、MemoryViewer.kt 260→45 行，两页差异收敛为参数 + 插槽（配色/留白/解码比例/滚动条/头部/预览头/`MediaGridSelection` 选择动作）。
- **中-6 实体直漏 UI**：已修（ee4201）——新增 `model/PhotoItem.kt` UI 模型（mediaId/uri/displayName/dateTaken/mimeType/duration/state 最小集，UI 需要时再按需加字段）；Repository 的 Room Flow 在 ViewModel 边界经 `toItem()/toItems()` 映射：trashItems、会话队列（`ReviewSession.queue`）、pendingDeletes/trashList、memoryCandidates（buildMemory）全部改吐 `PhotoItem`，`ui/` 各 Composable 与 MainActivity 已零 `PhotoEntity` 引用（现仅存于 data/media 层与映射函数处，ViewModel 内部零 Entity 引用）。
- **中-7 Room 细节**：索引与 `@Transaction`（DAO default 方法 `applyScan`/`applyReconcile`，慢 IO 留在事务外）已做；SQL 双份（trashItems/trashNow 等）按务实处理紧邻放置并加同步注释，未消除双份。
- **中-8 手写 DI 分散**：已修——`AppContainer` 挂在 Application 上统一组装 database/settings/mediaScanner/repository；ReminderScheduler 经容器取 SettingsRepository，守卫/降级语义逐字未动。
- **中-9 一致性小坑**：restoreFromTrash「丢 FAVORITE」查证为不可达路径（FAVORITE 全工程无写入，已加注释）；weekStats 跨周不刷新已修（冷流按日重算周一）；save→ReminderScheduler 耦合刻意保留（真机时序竞态，见代码注释）；相册规则已单一来源（坑 23 修净，本轮复核确认）。
- **中-10 重复代码**：formatBytes 三份归一为 `Format.kt/formatCapacity`；进度卡/StatItem≈BigStat/placeholder 淡入经逐对 diff 均有实质差异（圆角、字号、动画方向等），按「宁缺毋滥」维持现状；SwipePhoto 手写缓动三份随拆分原样搬入 Review.kt，未去重。
- **低优先级**：O(n²) `it !in valid` 已修（mediaId HashSet）；PhotoAspectCache 改 `LruCache(512)`；dynamicColor 误导参数已文档化（调用方在 App.kt，行为不动）；kapt→KSP（1.9.25-1.0.20）；未使用的 navigation-compose 依赖已删；tmp_shared_src 已移出版本控制（磁盘保留）；README 已校正（7af8ae1）；**低-19 VideoPhoto 轮询优化已修**——控制条拆独立组件 `VideoControlsBar`（250ms 轮询只重组控制条自身，不再波及播放器/静态帧/触摸层），拖动进度只在松手时 seekTo 一次（消除逐帧 seekTo），拖动中显示本地进度不抖。
- **未做**：高-4 剩余两点（扫描失败空表、reconcile 失败日志的 UI 信号，均需产品设计）。

---

## 一、项目画像

完全离线的 Android 相册整理工具：宫格浏览 → 轮盘决策保留/删除 → 回收站 → 统计/回忆。无后端、无网络。

技术栈：Kotlin 1.9.25 + AGP 8.9.1、Compose BOM 2024.12、Room 2.6.1（kapt）、DataStore Preferences、
Coil 2.7（含 coil-video）、AlarmManager 精确闹钟（刻意不用 WorkManager，`ReminderScheduler.kt:22-41` 有论证）。
`minSdk = 36`，仅支持 Android 16（`app/build.gradle.kts:13`）。`android:largeHeap="true"`（Manifest:7）。

## 二、实际分层与数据流

无 DI 框架的轻量 MVVM，单向依赖，骨架正确：

```
MediaStore ──MediaScanner──┐
DataStore ──SettingsRepo───┼→ PhotoRepository ──→ PhotoViewModel(544 行, 多路 Flow combine → AppUiState)
Room(Dao/Database) ────────┘            │                    │
AlarmManager ←──(save 副作用)────────────┘                    ▼
                            App.kt 自研路由（page int + HorizontalPager + AnimatedContent）
                            ├─ Home / StatsScreen（独立文件，质量较好）
                            └─ RecycleBin / Review / Settings / MemoryViewer（全挤在 App.kt，2195 行）
```

- 导航是自研的：整型 `page`（`App.kt:151`，rememberSaveable）是唯一路由源；Tab 页 0/4/1 住
  HorizontalPager（`App.kt:304-326`），沉浸页 2/3/5 由 AnimatedContent 从入口锚点盖住 Tab 层（`App.kt:332-379`）。
- ViewModel 是汇聚中心：Room/ContentResolver/DataStore 的多路 Flow combine 成 `ui`（`PhotoViewModel.kt:238-244`），
  仓库依赖由 MainActivity 手写 lazy 注入（`MainActivity.kt:35-38`）。
- 提醒链路：设置保存 → SettingsRepository 联动重排（`SettingsRepository.kt:68-76`）→ AlarmManager 单次闹钟
  （三级降级，`ReminderScheduler.kt:95-106`）→ Receiver 到点发通知并重排明天（`ReminderReceiver.kt:13-29`）→
  通知点击带 EXTRA 直达整理页。

## 三、分层评价

| 层 | 评价 |
|---|---|
| 数据层 | **全项目最扎实**。Flow/suspend 约定清晰（`PhotoRepository.kt:16-21,140`）；对账 reconcile 防御性极强：空判即放弃（:82）、MIUI 活性探测 fail-open（:91-97）、`chunked(900)` 防 SQLite 变量上限（:101）。短板：无索引、无 @Transaction 包多步写、`exportSchema=false` + 破坏性回退 |
| ViewModel | 合格但偏胖：会话/统计/回忆/撤销/防幽灵点击/十几个 setter 全在一个 544 行类里（`PhotoViewModel.kt:279-539`）；冷启动 `runBlocking` 快照（:190-235）有注释背书但仍是主线程风险 |
| UI 屏幕层 | **曾是最重债务，已大部还清**：App.kt 已拆至 465 行；RecycleBin↔MemoryViewer 约 85% 重复已收敛为共享 `ui/MediaGridScreen.kt`（RecycleBin.kt 570→106 行、MemoryViewer.kt 260→45 行，差异走参数+插槽） |
| UI 组件层 | **独立性最好**。SpringPull / PopupWheel / ZoomablePhoto / PhotoSharedTransition 基本可脱离业务复用，手势细节（阻尼、坐标补偿、逐事件钳制）水准高 |

## 四、问题清单（按优先级）

### 高（正确性/稳定性）

1. **Coil 加载器互相覆盖（已在本轮修复）**：`PhotoRouletteApp.kt` 用 ImageLoaderFactory 设 40% 堆内存缓存，
   `MainActivity.kt:74` 又 `Coil.setImageLoader` 只注册视频解码器——Coil 2 懒构建单例中覆盖工厂优先且配置不合并，
   实际单例永远不带 40% 缓存，滚动性能设计整体落空。已合并到 `newImageLoader()` 唯一配置点并删除覆盖。
2. **主线程 IO**：`movePendingToTrash`/`restoreFromSystemTrash` 在主协程逐条 `contentResolver.query` 校验 URI
   （`MainActivity.kt:113-128, 153-161`），批量操作会卡 UI；建议移入 `withContext(Dispatchers.IO)`。
3. **迁移即清库**：`exportSchema=false`（`PhotoDatabase.kt:10`）+ `fallbackToDestructiveMigration`（:39）——
   漏写一条迁移就静默清掉全部历史统计，与「统计页是核心卖点」矛盾。建议开 exportSchema + 迁移测试。
4. **静默失败面大（部分已修）**：扫描失败返回空表无 UI 信号（`MediaScanner.kt:45,138`）、reconcile 异常仅记日志
   （`PhotoViewModel.kt:296-298`）、通知权限被拒时 `postNotification` 无降级提示（`ReminderScheduler.kt:119-137`）。
   ——通知权限降级提示已做（设置页「开启通知提醒」提示行，同精确闹钟样式，见顶部进度）；扫描/reconcile 两点仍待产品设计。

### 中（可维护性/性能）

5. **App.kt god file（2195 行，已修）**：导航 + 4 屏 + 15 组件 + 预载引擎全在一文件。
   超长 Composable：RecycleBin≈395 行（678-1072）、SwipePhoto≈244（1351-1594）、PhotoRouletteApp≈241（147-387）、
   Settings≈220（1596-1815）、MemoryViewer≈215（1981-2195）。
   **RecycleBin↔MemoryViewer 约 85% 重复**：6 态 shared-key 状态机（692-716↔1989-1999）、key 重开 scope
   （757-758↔2029-2030）、SpringPullBox lambda（785-796↔2050-2061）、宫格配置（806-814↔2071-2079）、
   头部渐变+吞触碰（896-911↔2115-2130）、SharedPhotoPreview 配置（984-1041↔2153-2188）。
   路由用魔法 int（128/131/404-434），PageContent 12 参（391-403）。
   ——上述已全部落地：四屏拆独立文件（App.kt 2195→465 行）、`Page` enum 路由、预载/滚动条独立文件、
   MediaGridScreen 去重完成（见顶部进度）。
6. **实体直漏 UI**：`PhotoEntity` Flow 直接进界面（`PhotoRepository.kt:21,140`），DB 演进波及全部 UI。
7. **Room 细节**：state/inTrash/gone/dateTaken/album 无索引（`PhotoEntity.kt` 全文无 @Index）；
   reconcile/upsertFromScan 多步写无 @Transaction（`PhotoRepository.kt:47-65, 98-108`），一致性全靠幂等重试兜底；
   `trashItems/trashNow`（`PhotoDao.kt:67/74`）、`totalCount/totalNow`（:97/100）SQL 双份易漂移。
8. **手写 DI 分散**：`MainActivity.kt:35-38` lazy 组装、`ReminderScheduler.kt:60` 每次 new SettingsRepository，
   测试无从下手。
9. **一致性小坑**：`restoreFromTrash` 重置 UNSEEN 会丢 FAVORITE（`PhotoDao.kt:77`，推测）；`weekStats` 固定构造
   时的周一、跨天不刷新（`PhotoViewModel.kt:155`，推测）；`SettingsRepository.save` 副作用调用 ReminderScheduler
   （:76）数据层耦合 worker；相册保留规则在 Repository 与 MediaScanner 双写（`PhotoRepository.kt:53-56` 注释自认）。
10. **重复代码散布**：`formatBytes` 双份（`Home.kt:35`/`StatsScreen.kt:65`）、进度卡两份（Home:309-323/Stats:290-304）、
    `StatItem`:375≈`BigStat`:731、placeholder 淡入两份（`VideoSupport.kt:287-319`/`ZoomablePhoto.kt:159-199`）、
    SwipePhoto 手写缓动三份拷贝（`App.kt:1460-1500`）。

### 低（细节）

11. Coil 加载器曾与 App 类冲突（已修，见高-1）。
12. O(n²) 小问题：`it !in valid`（`MainActivity.kt:130`）、队列恢复 `indexOf`（`PhotoRepository.kt:116`，队列小无碍）。
13. `PhotoAspectCache` 无界 HashMap（`PhotoSharedTransition.kt:88-94`，推测 MB 级以内）。
14. `Theme.kt:75` 的 `dynamicColor` 参数默认 true 却从不生效（:79-81），API 误导。
15. `largeHeap=true` 是内存设计的补丁（配合 40% 缓存）；kapt → 建议迁 KSP。
16. 声明未用的 `navigation-compose` 依赖（`app/build.gradle.kts:39`，导航实为自研）。
17. `tmp_shared_src/` 参考源码混在仓库根目录，应移出或 ignore。
18. README 漂移：仍写「WorkManager 每日提醒调度器」，实际为 AlarmManager 方案。
19. VideoPhoto 每 250ms 轮询 positionMs 触发重组、Slider 逐帧 seekTo（`VideoSupport.kt:249-255, 383-385`，推测有卡顿风险）——已修：控制条拆独立组件 + 松手才 seekTo（见顶部进度）。

## 五、突出亮点

- **国产 ROM 实战适配是最大资产**：MIUI 无障碍注入点击的三重过滤（`PhotoViewModel.kt:375-409`，附真机证据注释）、
  对账带回收站快照 + 文件描述符探活防误删（`MediaScanner.kt:55-96`）、提醒三级降级 + 漏发守卫 + Boot/时区恢复闭环
  （`ReminderScheduler.kt:59-113` + `ReminderReceiver.kt:33-52`）。
- **性能设计成体系**：固定窗口预载（停稳 150ms 防抖 + 窗口外取消，`App.kt:597-656`）、70% 缩略图解码（:741-744）、
  graphicsLayer 弹性拉不走重组（`SpringPull.kt:241`）、fitOnEnter 只让返回 cell 订阅转场（`PhotoSharedTransition.kt:204`）、
  thumbSnap 命中免淡入（:226-249）。
- **注释文化罕见**：pager 打断互锁与 trampoline 饿死分析（`App.kt:160-198`）、shared-element 两段式飞行（:693-716）、
  滚动条热区整层挂/卸修「右列点不了」（:1129-1136）等关键坑都把原因与真机实测写进了代码。
- **数据层防御性**：gone 软标记保历史统计（`PhotoDao.kt:89-90`）、累计计数器与照片表分离且原子可回填
  （`SettingsRepository.kt:95-113`）、会话队列跨进程恢复含死队列防护（`PhotoRepository.kt:117-122`）。
- **冷启动优化**：并行快照换首帧（`PhotoViewModel.kt:185-235`）、buildVersion 防过期会话覆盖（:301,330,359）。

## 六、重构路线（投入产出比排序）

1. ~~修 Coil 加载器冲突~~（本轮已完成）+ 回收站校验移 IO 线程（半天内可完成的两个高危）。
2. 开 `exportSchema` + Room 迁移测试，去掉破坏性回退；给 photos 表补索引（state/inTrash/dateTaken/album）；
   reconcile/upsertFromScan 包 @Transaction。
3. 拆 App.kt：~~再拆预载引擎与滚动条到独立文件，路由魔法 int 改 enum~~（已完成，enum 见 ee4201）；~~提公共 `MediaGridScreen`（回收站/回忆共用 85% 重复）~~（已完成——中-5 最后一块落地，见顶部进度）。
4. ~~收敛为一个 `AppContainer`，统一组装仓库与调度器，为测试铺路。~~（已完成）
5. ~~引入 UI 模型层切断 Entity 直漏~~（已完成，ee4201）；~~顺手清理低优先级项（KSP、README、tmp_shared_src、重复工具函数）~~（已完成）。

## 一句话总结

**骨架正确、数据层扎实、实战防御性设计与注释文化出色；债务集中在 UI 层的 App.kt 堆积与工具链现代化
（迁移安全/事务/索引/DI）上**——按 1→5 顺序还债，每一步都是独立可验证的小改动。
