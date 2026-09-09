package com.einsli.photoroulette.model

import com.einsli.photoroulette.data.PhotoEntity
import com.einsli.photoroulette.data.PhotoState

/**
 * UI 层照片模型(评审 中-6):Room 的 [PhotoEntity] 不再直接进入 UI——ViewModel 在
 * 边界上映射成本模型,DB 演进(加列/改列/改索引)只波及 [toItem],不再波及任何
 * Composable。字段是 UI 实际渲染/发起请求所需的最小集:
 * - [mediaId]:稳定 key(shared element、LazyGrid key、动作定位、AspectCache);
 * - [uri] / [mimeType]:Coil 请求与视频分支;
 * - [displayName]:contentDescription 与预览标题;
 * - [dateTaken]:预览头部日期、整理页时间戳;
 * - [duration]:视频角标与播放器时长初值;
 * - [state]:撤销栈需要「动作前」的状态(ViewModel 用),UI 不渲染它。
 * 刻意不暴露 album/size/lastShownDay/processedAt/inTrash/gone——UI 从未用过,
 * 一旦需要再按需加字段,而不是把整张表漏上去。
 */
data class PhotoItem(
    val mediaId: Long,
    val uri: String,
    val displayName: String,
    val dateTaken: Long,
    val mimeType: String,
    val duration: Long = 0,
    val state: PhotoState = PhotoState.UNSEEN,
)

/** Repository → ViewModel 边界映射:DB 加列时只需在这里补一行。 */
fun PhotoEntity.toItem(): PhotoItem = PhotoItem(
    mediaId = mediaId,
    uri = uri,
    displayName = displayName,
    dateTaken = dateTaken,
    mimeType = mimeType,
    duration = duration,
    state = state,
)

/** [toItem] 的批量版。 */
fun List<PhotoEntity>.toItems(): List<PhotoItem> = map { it.toItem() }
