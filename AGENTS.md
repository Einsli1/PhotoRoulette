# PhotoRoulette — 项目速查（给 AI 助手 / 开发者）

> 这是给接手本项目的人（尤其 AI 编码助手）看的结构说明。改代码前先读这里，能省很多排查时间。

## 1. 项目是什么

**照片轮盘**：一个完全离线的 Android 相册整理工具。每天随机挑少量照片（默认 10 张），用户左滑=删除（移入系统回收站，非永久删除）、右滑=保留。保留的照片不再进随机池，删除前会走系统确认。

## 2. 技术栈与关键版本

- 纯 Kotlin + **Jetpack Compose + Material 3**（BOM 2024.12.01，material3 1.3.1）
- `minSdk = targetSdk = 36`（Android 16），JDK 17 编译（Gradle 用 Android Studio 的 JBR 21 跑）
- **Room** 数据库（v6，3→5 走显式 Migration、其余 `fallbackToDestructiveMigration`）、**DataStore** 设置、**AlarmManager** 每日提醒、**Coil** 加载图片
- `app/build.gradle.kts` 里 `versionName` 即版本号（当前 1.6）

## 3. 目录结构（`app/src/main/java/com/einsli/photoroulette/`）

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 入口。权限申请、系统回收站请求（`MediaStore.createTrashRequest`）、组装 ViewModel |
| `PhotoViewModel.kt` | 状态中枢。session 队列、统计、周趋势、回忆计算；`ui` 是 `StateFlow<AppUiState>` |
| `data/PhotoEntity.kt` | Room 实体：mediaId/uri/displayName/dateTaken/mimeType/**size**/state/lastShownDay/processedAt/inTrash |
| `data/PhotoDao.kt` | 全部 SQL：候选队列（策略×范围）、统计、回忆候选、周趋势等 |
| `data/PhotoRepository.kt` | DAO + MediaScanner + 设置 的组合层；`sessionQueue()` 按策略/范围取照片 |
| `data/SettingsRepository.kt` | DataStore 设置（含 photoRange / strategy / customRangeStart） |
| `media/MediaScanner.kt` | MediaStore 扫描，读取 SIZE 字段；`scanExisting()` 全量存在性查询（MATCH_INCLUDE 含系统回收站/PENDING 文件，返回 existing+trashed 两个集合）；`isFileReadable()` 单行文件活性探测（openFileDescriptor），供对账用 |
| `ui/App.kt` | **页面路由中枢**：底部导航 + 全部页面的组合函数（设置/整理/回收站/回忆浏览等） |
| `ui/Home.kt` | 首页（无滚动，weight spacer 铺满） |
| `ui/StatsScreen.kt` | 统计页（7 天趋势柱状图 + 本周汇总 + 长期统计 + 累计统计三卡；右上角「历史」入口按周回看任意一周，周趋势/周汇总同一套视图。周卡片内部是 HorizontalPager：整卡内容跟手连续翻周、边界=最早周/本周，落定才写回 historyWeek；每个 page 自订阅 `viewModel.weekStatsOf(monday)`，加载瞬间全 0 占位；月历弹层打开时 `userScrollEnabled=false` 禁滑；pager 边缘 stretch overscroll 用 `LocalOverscrollConfiguration=null` 关掉，内容不出卡片圆角） |
| `ui/SpringPull.kt` | **弹性滚动手感**：`SpringPullBox`（nested-scroll 弹性跟手 + 甩到极限弹簧回弹）+ `rememberGentleFlingBehavior`（0.5 阻尼惯性、撞限返回 0 剩余速度） |
| `ui/Theme.kt` | M3 浅/深 fallback 配色（紫色系）；dynamic color 关闭时生效 |
| `ui/DesignColors.kt` | **设计调色板**：深浅两套，`designColors()` 按主题自动切换 |
| `ui/PhotoSharedTransition.kt` | **Shared element 转场**：`SharedTransitionLayout`、共享 key、`SharedGridImage`/`SharedPhotoPreview`、边界+圆角动画 |
| `ui/ZoomablePhoto.kt` | 全屏照片双指缩放(1x–5x)/平移；固定尺寸 Coil 请求；关闭前回缩到 1x |
| `ui/PopupWheel.kt` | **滚轮选择组件**(从 App.kt 抽出共享):悬浮卡片弹层 `SettingPopupPicker`(药丸触发、从右上角缩放展开/收回、下方定位无空间翻转,可选 onConfirm 钩子)+ 吸附数字滚轮 `StyledNumberWheel`;设置页(数量/每日提醒)与统计页「历史整理」按周选择共用 |
| `worker/` | ReminderScheduler / ReminderReceiver / ReminderBootReceiver（**AlarmManager** 每日提醒，非 WorkManager） |

## 4. 页面路由（App.kt 内 `var page by rememberSaveable`）

- `0` 首页 Home（有底部导航）
- `1` 设置 Settings（有底部导航）
- `2` 整理页 Review（**沉浸式**：无底部导航；`BackHandler` 边缘返回首页）
- `3` 回收站 RecycleBin
- `4` 统计 StatsScreen（有底部导航）
- `5` 回忆时光机 MemoryViewer（**沉浸式**：无底部导航；BackHandler 先关预览再返回首页）
- 底部导航只显示在 `page != 2 && page != 5` 时
- 验证某个页面：临时把初始 `page` 改成目标值，重新构建截图即可

## 5. 数据流（重要）

```kotlin
ui = combine(settings, session, counts, homeStats, weekStats) { ... }
       .stateIn(..., AppUiState())
```

- `AppUiState`: loading / session / total / processed / settings / **stats**(HomeStats) / **week**(WeekStats) / **cumulative**(StatsCounters)
- **对账（reconcile，`PhotoRepository.reconcile()`）**：每次建整理队列前（`reload()`）以及首页「继续整理」续用会话时（`reconcileQuietly()`）、通知直达整理页（openReviewRequest）都会执行：增量扫描入库 → `scanExisting()` 全量存在性比对（existing + trashed 子集）→ 系统里已彻底删除的行，未处理的直接删、处理过的标 `gone=1`（从候选池/总数/回收站/回忆消失，但行保留，周统计与连续天数不被追溯改写）→ 对 trashed∩active 的行再做 `isFileReadable()` 文件活性探测（见坑 22）→ 回收站行出现在常规扫描里 = 用户在系统相册恢复了它，同步回待整理池并回退累计删除计数。**存在性查询必须 MATCH_INCLUDE trash/pending**，否则刚移入系统回收站的照片会被误判为已删除；对账读配置用 `settingsRepository.settings.first()`（等真实加载），失败则整轮跳过，绝不用默认配置跑（会误删视频/相册行）
- `HomeStats`: kept / streak（连续天数，从 processedAt 计算）/ trashBytes / memory(MemoryInfo)
- `MemoryInfo`: yearsAgo / dateText / count / photos —— 「N年前的今天」，按 dateTaken 匹配今天月日、取最久远的一组
- `WeekStats`: days（7 个每日数量，旧→新）/ organized / kept / freedBytes，`deleted = organized - kept`
- `StatsCounters`: keptTotal / deletedTotal（`organizedTotal = kept + deleted`）——统计页的「累计整理/删除/保留」。DataStore 持久化（独立于照片表，清空回收站/重扫也不减少），在 action/undo/回收站恢复/撤销待删/重置 处增减；首次升级后从 DB 现有计数一次性回填
- `ReviewSession`: sessionId / queue / position / lastActionDir —— 队列在 DataStore 里持久化，可跨重启续用

## 6. 设置项（SettingsRepository + Settings 页）

- dailyCount / includeVideos / includeScreenshots / reminderHour/Minute / includedAlbums / darkMode(0跟随系统 1浅色 2深色)
- **photoRange**：`all` | `lastYear` | `beforeLastYear` | `custom`（customRangeStart=epoch ms）
- **strategy**：`random` | `oldest` | `largest`（作用于 DAO 的 ORDER BY）
- 改动策略/范围在**下一次「开始整理」时生效**（`sessionQueue()` 时应用），不会打断当前会话

## 7. 设计系统

- **品牌色**：淡紫 Lavender。浅色 `#7A59F7` / 深色 `#A78BFA`
- 语义：**绿色只用于保留/成功**；**红色只用于删除/危险**；其余统一紫色系
- `DesignColors` 字段：pageBg/ink/slate/labelGray/card/cardSoft/accent/accentText/track/white/badgeStreak(紫)/badgeSpace(青绿)/badgeKeep(淡粉)/success/danger/dangerContainer/onDangerContainer/navBar
- `designColors()`：按 `MaterialTheme.colorScheme.background.luminance() < 0.5f` 判断深浅
- 卡片统一大圆角（18-22dp）、低海拔；首页「开始整理」是全宽紫色 Filled Button（主操作）

## 8. 构建 / 安装 / 发布

```powershell
# 编译安装到真机（脚本已配置 JDK 21 + SDK）
.\install_debug.ps1

# 发布：提交后推 GitHub + 建 Release（脚本已修复 PS5.1 stderr 陷阱）
.\push_release.ps1 "v1.2" "release notes"
```

- JDK：`E:\android studio\jbr`；SDK：`C:\Users\Einsli\AppData\Local\Android\Sdk`
- Gradle 需要读写 `C:\Users\Einsli\.gradle`（沙箱里要开 full access）
- 仓库：`https://github.com/Einsli1/PhotoRoulette`（branch: master），走代理 `127.0.0.1:7890`
- `.gitignore` 忽略 `*.png`——提交 `res/drawable-nodpi/wheel_roulette.png`（首页轮盘图）必须 `git add -f`

## 9. 真机验证技巧

- 设备：Redmi（serial `794ddcf1`），1200×2608 @ density 3.0（400dp 宽）。**adb input 注入已可用**（2026-09-06 用户在手机开启「USB 调试（安全设置）」后实测 keyevent/tap 均生效），可以直接 `adb shell input tap/swipe/keyevent` 做交互验证；若哪天又被拦（报 injection 安全错误），先让用户检查该开关
- 截图：`cmd /c "adb exec-out screencap -p > file.png"`（PowerShell `>` 会损坏二进制）
- 读文字：Windows.Media.Ocr（zh-Hans-CN），把截图区域放大后再 OCR 更准

## 10. 已踩过的坑（务必记住）

1. **Compose `Image` + 直接 `Modifier.size(dp)` 在真机会渲染异常/文字塌陷**：必须用 `Box + clip(CircleShape) + ContentScale.Crop` 包裹
2. **首页不可滚动**（fillMaxSize + 底部 weight spacer）。内容总高必须控制在 ~720dp 内（400dp 宽设备），否则「回忆时光机」卡片会顶到导航栏。调过尺寸后要截图量卡片底部与导航栏的间距
3. **状态栏图标颜色要跟随 App 主题**（不是系统）：`WindowCompat.getInsetsController(...).isAppearanceLightStatusBars = !isDark`，否则强制深色时图标看不见
4. **深色模式轮盘图标不带圆圈背景**（浅色带淡紫圆圈），且图片要放大 1.25x 裁掉白边
5. **DataStore 文件别直接改**，极易损坏导致 `CorruptionException` 崩溃；恢复=删文件让 App 重建
6. **PS 5.1 + `$ErrorActionPreference='Stop'` + `2>&1` 重定向**：git/gh 的 stderr 会变成终止错误。脚本内用 `Continue` + 手动 `$LASTEXITCODE` 检查
7. `material3 1.3.1` 里 **没有 `TimePickerDialog`**（1.4 才有）；时间选择用 `BasicAlertDialog` + `TimePicker`
8. 数据库 v6：v6 起新增列都写显式 Migration（3→4 album、4→5 duration、5→6 gone），别把已有版本号的升级交给 destructive fallback（会清空照片表）
9. **Shared element 转场两侧必须渲染一致（都 `ContentScale.Fit`）**：转场时 Compose 把进入方+退出方都画进 overlay、用同一套动画边界（进入方在上）。宫格若 Crop、预览 Fit，返回首帧进入方会以「全屏 Crop」盖在预览上闪一下。所以 `SharedGridImage` 默认 Fit；想恢复裁满宫格得另做 overlay 裁剪，不能直接改回 Crop。
10. **Shared element 首点空白**：`AsyncImage` 默认按布局约束解码，转场里动画尺寸反复变化会反复重解码。修复=宫格 cell 组合时按屏幕尺寸 `loader.enqueue(...)` 预加载 + 预览侧 `ZoomablePhoto` 用固定尺寸 `ImageRequest`（同一 cache key）。
11. **返回动画前先让目标 cell 进入 viewport**：`onClose` 里先 `revealGridItemIfOffscreen(gridState, idx)`（`requestScrollToItem` 同步、可在 grid 未组合时调用）再关预览；否则照片全屏停留、末了才跳进格子，滚动发生在动画中途还会让目标格移动。
12. 共享 key 用 `photoSharedKey(mediaId)`（稳定 mediaId），**绝不能用 index**——滑动换页/删图会让 key 错位。
13. adb 注入曾被 MIUI 拒；2026-09-06 用户开启「USB 调试（安全设置）」后已恢复可用（keyevent/tap 实测生效），正常用即可，被拦时先查这个开关
14. **Compose 1.7 的 fling 源是 `NestedScrollSource.SideEffect`**：`NestedScrollSource.Fling` 已废弃、永远不会收到。判断"惯性滚动"判 `SideEffect`（手指拖拽是 `UserInput`）。平台 stretch overscroll 只对 `UserInput` 生效；fling 的剩余速度只经 `onPostFling` 送达。
15. **撞限时 `AnimationScope.cancelAnimation()` 在 1.7.6 不抛异常**（只置 running=false，`catch (CancellationException) { throw }` 是死代码）。要让页面停在极限，fling 撞限必须**返回 0 剩余速度**，否则 leftover 会喂给平台 stretch 滚出很远。
16. **SpringPullBox 撞限弹簧**：fling 增量撞到极限时，把当帧超出部分 × `flingSpringScale` 注入 pull（踢出幅度 ∝ 到达速度），但**绝不能消费该增量**（返回 `Offset.Zero`）——消费了 `scrollBy` 就返回满 delta，fling 检测不到极限，pull 会每帧累积失控。回弹手感：`springBack()` 里 fling 走阻尼 0.4 + `StiffnessMedium`（带回弹），拖拽走 `NoBouncy`（无回弹滑回）。调手感改 `flingSpringScale` 和这两个弹簧参数。
17. **宫格单独弹性必须 `clipToBounds()`**：回收站/回忆时光机把 `SpringPullBox` 只包住宫格（头部固定）后，往上拉/弹簧踢出时宫格整体上移会盖住头部；在 `SpringPullBox` 的 modifier 上加 `clipToBounds()`，裁剪层固定在布局边界，露出来的是下方空白。
18. **每日提醒必须用 AlarmManager，别用 WorkManager**：周期任务非精确 + 国产 ROM（MIUI）应用被划掉后基本不执行，结果就是「只有打开 App 才发通知」。现在 `ReminderScheduler` 的降级链：`setExactAndAllowWhileIdle`（有 SCHEDULE_EXACT_ALARM 时）→ `setAlarmClock`（文档说免权限，但 Android 15+/HyperOS 缺权限会抛 SecurityException，已实测崩溃）→ `setAndAllowWhileIdle`（免权限、进程被杀也能触发，但窗口 +1h，MIUI 实测到点几分钟内不触发——所以「到点准发」必须让用户授权精确闹钟）。三个坑：**(a)** 授予/撤销精确闹钟权限只有用户去系统设置（`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`，设置页有入口）才能改，App 拿不到回调用 `canScheduleExactAlarms()` 现查；**(b)** HyperOS 对未开「自启动」的应用会在**每次进程启动时补投** BOOT_COMPLETED（实测），所以开机接收器重排必须走守卫 `replace=false`，否则会把「今天待发的补发」覆盖成明天；**(c)** 闹钟真正触发后的重排必须 `replace=true`（SET 语义排明天），否则守卫把「今天已过点」误判为未派发、每 15 秒无限补发。守卫逻辑（存 `last_target_ms` 到 SharedPreferences）：`replace=false` 时若存储目标落在**今天**——未来则保持同一目标（附带精确升级），已过点则立即补发 now+15s；其它情况按设置 SET（今天未来/明天）。onCreate/onResume 的环境性重排全部 `replace=false`，用户改时间的入口（SettingsRepository.save）与触发后重排才是 `replace=true`。兜底 try/catch：注册失败绝不崩 App/接收器。MIUI/HyperOS 上开机恢复还需用户开「自启动」。注意 `ACTION_MY_PACKAGE_REPLACED` 是 @hide 常量，manifest 用字符串字面量。
19. **通知点击必须给 contentIntent**：`setAutoCancel` 只负责点后消失，不点开任何东西。现在通知的 contentIntent 带 `EXTRA_OPEN_REVIEW`，MainActivity 用 `openReviewRequest`（mutableIntStateOf + onNewIntent/singleTop）驱动 Compose 直达整理页；冷启动时 `App.kt` 初始 `page` 直接从 extra 落成 2。
20. **回收站/回忆时光机的全屏预览结构**（改版后）：页面层（标题+按钮+宫格）**常驻组合**在预览下层（`AnimatedVisibility(visible=true)` 提供 scope），预览是上层 overlay（`AnimatedVisibility(visible=previewOpen)`）。三个要点：**(a)** 下拉退出时黑色 scrim 按 `dragY / (2×160dp)` 变透明，露出**整个页面**（不是副本）；**(b)** 标题/按钮与图片位移**解耦**——`dragY > 0` 时 150ms 直接飞出屏幕（`chromeProgress`），图片回到原位再飞回；**(c)** 页面常驻后没有 AnimatedContent 分支转场了，宫格 Fit→Crop morph 由调用方的 `gridMorph`（Animatable）在 onClose 里 `snapTo(0)` 后 `animateTo(1)` 驱动（与 shared element 回飞同步）；预览根部有一个全屏 pointerInput 吸收层，防止点到下层页面。
21. **全屏预览三种手势/布局（回收站、回忆时光机、整理页）**：`SharedPhotoPreview` 新参数 `fullScreenPhotoArea` / `tapToToggleChrome` / `doubleTapToZoom`，回收站/回忆时光机两个调用点都传 true。**整理页（Review）同样全屏化并去掉了单独预览**：卡片圆角/共享转场全删，`Review` 自己管理 `chromeHidden`（单击切换），`SwipePhoto` 改为全屏 `Box`：照片用 `ZoomablePhoto`（图片，双击 1x↔3x）/ `VideoPhoto`（视频内联播放，控制条悬浮），左滑/右滑保留/删除不变，底部按钮（上一张/删除/保留）随 `chromeProgress` 浮动隐藏；注意 Review 里视频是 `active=true` 自动播放的。**系统状态栏也跟随 chrome 一起隐藏/显示**：`SyncStatusBarWithChrome(hidden)`（PhotoSharedTransition.kt 里的小工具，`WindowInsetsController.hide/show(statusBars)`，组合销毁时强制恢复）——SharedPhotoPreview 传 `chromeOut`（仅 fullScreenPhotoArea），Review 传 `chromeHidden && current!=null`；整理页的时间戳**不要用 `statusBarsPadding()`**（状态栏隐藏时它会跟着变、时间戳会跳）：用进入页面时 `remember` 捕获的 `WindowInsets.statusBars.getTop()` 固定偏移（`statusBarTop + 64.dp`，避开返回按钮行 ~54dp），状态栏隐藏时位置不动；预览/整理页的 chrome Column 同样用捕获的 `padding(top = statusBarTop) + navigationBarsPadding()` 代替 `systemBarsPadding()`，状态栏隐藏时标题不跳位。状态栏收起动画是系统控制的，公开 API 无法关闭，只能把单击延迟(300→250ms)和 chrome 飞行动画(300→250ms)调快。**返回宫格闪一下的坑**：状态栏隐藏/显示会改 window insets，而 `systemBarsPadding()` 跟着变——预览的 backdrop 镜像和宫格页用固定捕获的 `rememberStatusBarTop()` + `navigationBarsPadding()` 代替（统一函数，别用 `systemBarsPadding()`），且 `SyncStatusBarWithChrome` 只传 `tapToToggleChrome && chromeHidden`（不要传 `chromeOut`，否则拖拽/关闭瞬间状态栏先藏再弹、布局跳位）。**跨页返回也闪**：整理页 chrome 隐藏时状态栏是藏的，直接返回主页会让主页首帧用错 insets、状态栏弹回时跳位——`Review` 的返回（`BackHandler` + 返回按钮）统一走 `leaveReview()`：先 `insetsController.show(statusBars)` 再 `onBack()`，让状态栏动画和页面转场重叠。**(a)** 全屏照片：照片 Box 改 `fillMaxSize` 铺满整屏（含系统栏之下），标题/按钮浮在照片上层（**不要加渐变底**——会变成用户不想要的阴影，已去掉），`dragY` 偏移和 shared element 逻辑不变；**(b)** 单击照片隐藏/显示标题和按钮：`chromeHidden` 并入 `chromeOut`，复用 `chromeProgress` 飞出/飞回动画；**(c)** 双击 1x↔3x 缩放（>1x 才允许缩回 1x，即"缩小只有放大后才有"；3x 是用户嫌 2x 太小后调的）：单击/双击在 `ZoomablePhoto` 的**同一个手势循环里手动判定**（延迟双击窗口后触发单击），不能用外部 `detectTapGestures`——缩放平移会消费事件，外部 tap 检测永远收不到；双击放大以双击点为中心，注意 `graphicsLayer` 的 scale 以**节点中心**为原点：`T' = T + (S - S')*(tap - center)`，漏掉 center 项会偏位。视频页：点击屏幕与图片一致——隐藏/显示标题+按钮+视频进度条（`VideoPhoto.onTap` 非空时不再切换播放/暂停，播放/暂停交给控制条按钮）；控制条改成悬浮圆角胶囊（`Color.Black 0.6` 圆角，**别用全宽渐变**），`bottomInset`（全屏+有按钮=导航栏+84dp，无按钮=导航栏+16dp）让它浮在按钮上方，`chromeProgress` 同步飞出/飞回。
22. **HyperOS/MIUI 回收站「永久删除」不清 provider 行（对账失效根因）**：在系统相册回收站里把照片「永久删除」后，**文件立刻消失**（原路径与 `/storage/emulated/0/.trash-storage/<原相对路径>/.trashed-<过期时间戳>-<文件名>` 都没了），但 MediaStore 行以 `is_trashed=1` 残留（等 30 天过期清扫才删行）。纯 id 存在性比对永远认为它"存在"，对账 `goneMarked` 恒 0——App 回收站里的"空占位"（缩略图加载失败的格子）永远删不掉（真机 2026-08-30 实测：两张 HEIC/GIF 卡了 2 小时+，点多少次「继续整理」都没用）。修复=对 `trashed ∩ active` 的行做 `MediaScanner.isFileReadable()`（`openFileDescriptor(uri, "r")`）：**只有 FileNotFoundException 才算死**，其余异常（SecurityException 等）一律按"还活着"处理——探测绝不能造成误删。shell 调试注意：`content query` 默认 MATCH_EXCLUDE 排除 trashed 行（显示 "No result" ≠ 行不存在）；`content --extra` 的 KEY 含冒号会解析失败；`is_trashed=1` 显式 where 会被 provider 拒绝；adb input 注入 2026-09-06 起已可用（用户开了「USB 调试（安全设置）」，见坑 13）。

23. **对账导致首页总数跳变（扫描/清理规则不一致）**：`MediaScanner.scan` 的相册过滤是**大小写不敏感前缀匹配**（选中 `Pictures/` 等于包含全部子目录），而 `deleteOutOfScope` 原来按 `UPPER(album) NOT IN (:albums)` **精确匹配**——凡是"扫描前缀放行、白名单精确匹配不上"的目录（如 `Pictures/20170525.../子目录/`、`Pictures/Gallery/owner/校运会/`，真机共 451 张），每轮对账都会被 insertAll 插入（总数+451）再被 deleteOutOfScope 删除（-451），两次 Room invalidation 让首页「整理进度」总数肉眼可见地来回跳。修复=`poolAlbums()` 取池内 DISTINCT album，用与 scan **完全相同**的规则算出越界清单，再按 `UPPER(album) IN` 精确删；两套规则单一来源。**最终语义经用户拍板为「精确匹配：选了哪个目录就算哪个，父目录不自动包含子相册」**——scan 与清理都改成 ignoreCase 精确相等，前缀语义废弃。
24. **AI 助手经脚本工具(JS 模板字符串)改 Kotlin 代码时，`${...}` 必须写成 `\${...}`**：DSH 的 run_code/edit 把编辑内容包在 JS 模板字符串里求值，Kotlin 字符串模板(`Log.d(TAG, "x=${r.poolDeleted}")`、`"...${restored.queue.size}"` 这类)不转义就会被 JS 当插值求值——轻则 ReferenceError 中断、重则把错误内容写进文件；而且 **old_string 和 new_string 两边都要转义**(old 里不转义会直接匹配失败)。同一天踩了三次(2026-08-30)，纯 Kotlin 文件的成段新增/重写优先用 write 整文件落盘，少用内嵌模板字符串做 edit。

25. **Scaffold bottomBar 槽位高度必须恒定——进沉浸页拆导航栏会让底下的页面「跳一下」**：`bottomBar = { if (page !in immersivePages) NavigationBar(...) }` 在进入回收站/整理/回忆时光机的**同一帧**把内容区 padding 从 85dp 改成导航栏 inset(20dp)，底下还看得见的页面(ExitTransition.None 静止退场)视口突然变高 → `ScrollState.maxValue` 从 626 掉到 431 → scrollable 把 scroll 从 626 **钳到 431** → 整页内容下移 65dp(用户描述「导航栏没了，整体页面下移了一小段」)。返回时 savedScroll restore 又拉回 626,所以每次进入都跳、返回后看似正常。修复=沉浸页时槽位放等高透明占位 `Spacer(Modifier.height(85.dp))`,padding 恒定、视口不变、滚动永不钳。教训:给退场页「冻结 padding」(AnimatedContent 里 remember 每状态一份)不可靠——第一次进出生效、快速往返后失效；**布局尺寸稳定性要靠让 padding 根本不变，而不是补偿它**。定位手法(2026-08-30/31 实战有效):app 内临时打点(`snapshotFlow { scroll.value to scroll.max }` + 转场 SideEffect 打 padding/maxValue/状态栏高度，`adb logcat -s TAG`)+ `adb exec-out screencap` 连拍 + node `pngjs` 逐帧像素 diff/条带互相关，一次复现即可量化「跳了多少像素、哪层在动」。HyperOS 还挡 `adb shell settings put global`(动画倍率放不了慢)和 uiautomator 对锁定屏的 dump——放慢动画只能改 app 内代码。

26. **Pager 双向同步：`animateScrollToPage` 绝不能放在 `LaunchedEffect(page)` 里——被反向通路改写 page 会重启 effect、取消动画协程，pager 冻结在两页中间**：Tab 层改 HorizontalPager 后，`page` 是唯一状态源、两条方向相反的写入通路（滑动：`snapshotFlow { currentPage }` 越中线写 page；点击：`LaunchedEffect(page)` 里 `animateScrollToPage`）。点「首页⇄设置」（跨中间页）时，动画途中 currentPage 越过统计页那一帧 → 通路 1 把 page 改成中间 Tab → `LaunchedEffect(page)` **重启并取消进行中的动画协程** → pager 冻结在两页之间，新协程的 guard（targetPage==中间页）又直接 return → 永久卡死在中间。相邻 Tab 切换没事（越线时 page 已等于目标、不触发重启），所以只有首↔末切换必现（2026-09-01）。修复=点击通路改走 `Channel(CONFLATED)`：`navigate()` 里 `trySend(目标索引)`，独立 `LaunchedEffect(pagerState)` 用 `for (idx in channel)` 串行消费——动画一旦启动就跑到完（idx<0 的沉浸页跳过），动画中的再点击接续执行、永不半途取消。教训：**keyed LaunchedEffect 里跑长挂起动画时，任何会让 key 变化的并发写都是定时炸弹**；单向驱动的异步指令用 channel 排队，别用状态 key 驱动。

