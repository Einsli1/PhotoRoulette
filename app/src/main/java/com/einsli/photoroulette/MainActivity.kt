package com.einsli.photoroulette

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.einsli.photoroulette.data.*
// Application 类与 ui 包的 Composable PhotoRouletteApp 同名,别名区分。
import com.einsli.photoroulette.PhotoRouletteApp as PhotoRouletteApplication
import com.einsli.photoroulette.ui.PhotoRouletteApp
import com.einsli.photoroulette.worker.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        /** 通知/系统闹钟点击携带该 extra 时,App 直达整理页(路由见 ui/App.kt 的 openReviewRequest)。 */
        const val EXTRA_OPEN_REVIEW = "com.einsli.photoroulette.open_review"
    }
    // 仓库/设置/扫描器/数据库统一从 Application 上的 AppContainer 取(进程唯一实例),
    // 不再在本 Activity 里各自 lazy 组装——worker 层的 ReminderScheduler 取的也是同一份。
    private val container by lazy { (application as PhotoRouletteApplication).container }
    private val viewModel by viewModels<PhotoViewModel> { PhotoViewModel.Factory(container.repository, container.settings) }
    private enum class PendingOp { TRASH, RESTORE }
    private var pendingOp: PendingOp? = null
    private var pendingIds: List<Long> = emptyList()
    // 每次"/通知点开直达整理页"请求 +1,驱动 Compose 侧重新导航(冷启动时由初始值直接落到整理页)。
    private val openReviewRequest = mutableIntStateOf(0)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) Toast.makeText(this, "已授权相册访问，请点击“重新扫描相册”选择要包含的相册。", Toast.LENGTH_LONG).show()
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK && pendingIds.isNotEmpty()) {
            when (pendingOp) {
                PendingOp.TRASH -> {
                    viewModel.confirmDeleted(pendingIds)
                    // The user accepted the trash request, so now continue to a fresh session.
                    viewModel.nextSession()
                }
                PendingOp.RESTORE -> viewModel.restoreFromTrash(pendingIds)
                null -> {}
            }
        } else if (pendingOp == PendingOp.TRASH && pendingIds.isNotEmpty()) {
            // User declined the trash request. Undo the delete-pending marking so these photos
            // re-enter the review pool instead of staying stuck as never-trashed pending items.
            // Deliberately do NOT start the next session here: continuing on a decline is what
            // made the declined photos appear to "jump away" by themselves.
            viewModel.revertPendingDeletes(pendingIds)
        }
        pendingOp = null
        pendingIds = emptyList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 图片加载器的全局配置（视频帧解码器 + 40% 内存缓存）统一在 PhotoRouletteApp
        // 的 newImageLoader() 里，不要在这里再调 Coil.setImageLoader 覆盖单例——覆盖
        // 不合并配置，会把那里的 40% 内存缓存整体顶掉（历史坑，见该文件注释）。
        if (intent.getBooleanExtra(EXTRA_OPEN_REVIEW, false)) openReviewRequest.intValue = 1
        // 用设置的提醒时间重排每日闹钟(不依赖上一次打开时硬编码的 20:00)。DataStore 读取失败时
        // 退回默认时间,保证闹钟总能被注册。
        rescheduleFromSettings()
        requestPermissionsIfNeeded()
        setContent {
            PhotoRouletteApp(
                viewModel = viewModel,
                onAction = { mediaId, state, dir, userTouchedAt -> viewModel.action(mediaId, state, dir, userTouchedAt) },
                onCommitDeletes = ::movePendingToTrash,
                onRestoreFromTrash = ::restoreFromSystemTrash,
                openReviewRequest = openReviewRequest.intValue
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_REVIEW, false)) openReviewRequest.intValue++
    }

    // 注意:刻意不在 onResume 里重排闹钟。onResume 重排会与 onCreate 的重排竞态——onCreate 刚
    // 补发(now+15s)后 onResume 读到新状态又按 SET 语义换回明天,把补发取消(真机已复现)。
    // 权限变化(精确闹钟授权)后的升级会在下一次触发的重排里自然生效。

    private fun rescheduleFromSettings() = lifecycleScope.launch {
        val cfg = try { container.settings.settings.first() } catch (_: Exception) { AppSettings() }
        ReminderScheduler.schedule(this@MainActivity, cfg.reminderHour, cfg.reminderMinute, replace = false)
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun movePendingToTrash() = lifecycleScope.launch {
        val photos = viewModel.pendingDeletes()
        if (photos.isEmpty()) {
            // Nothing was marked for deletion this round, so there is nothing to confirm:
            // continue straight to a fresh session.
            viewModel.nextSession()
            return@launch
        }
        // Validate each URI before calling createTrashRequest — photos may have been
        // deleted externally, and passing a stale URI to MediaStore throws
        // IllegalArgumentException ("Invalid Uri") which crashes the app.
        // contentResolver.query 是同步 binder IPC,逐条校验不能留在主线程
        // (批量时串行阻塞 N 次);withContext 结束后自动回主线程,后续 UI 操作不变。
        val valid = withContext(Dispatchers.IO) {
            photos.filter { photo ->
                try {
                    contentResolver.query(Uri.parse(photo.uri), null, null, null, null)?.use { it.count > 0 } ?: false
                } catch (_: Exception) { false }
            }
        }
        // Silently remove records for photos that no longer exist on the system.
        // mediaId 判定用 HashSet:valid 是 List,逐条 `it !in valid` 是 O(n²),批量时会卡主线程。
        val validIds = valid.mapTo(HashSet()) { it.mediaId }
        val gone = photos.filter { it.mediaId !in validIds }
        if (gone.isNotEmpty()) {
            viewModel.deleteFromTrash(gone.map { it.mediaId })
        }
        if (valid.isEmpty()) {
            // All photos were already gone — no trash dialog needed, continue.
            viewModel.nextSession()
            return@launch
        }
        pendingOp = PendingOp.TRASH
        pendingIds = valid.map { it.mediaId }
        try {
            val request = MediaStore.createTrashRequest(contentResolver, valid.map { Uri.parse(it.uri) }, true)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: IllegalArgumentException) {
            // System still rejected the URIs — confirm locally and move on.
            viewModel.confirmDeleted(pendingIds)
            viewModel.nextSession()
        } catch (security: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build())
        }
    }

    private fun restoreFromSystemTrash(ids: List<Long>) = lifecycleScope.launch {
        val photos = viewModel.trashList().filter { it.mediaId in ids }
        if (photos.isEmpty()) return@launch
        // Filter out photos whose URIs are no longer valid (deleted externally).
        // 同 movePendingToTrash:逐条 query 是阻塞 IO,移入 Dispatchers.IO。
        val valid = withContext(Dispatchers.IO) {
            photos.filter { photo ->
                try {
                    contentResolver.query(Uri.parse(photo.uri), null, null, null, null)?.use { it.count > 0 } ?: false
                } catch (_: Exception) { false }
            }
        }
        if (valid.isEmpty()) {
            // All selected photos are already gone — just remove them from our DB.
            viewModel.deleteFromTrash(photos.map { it.mediaId })
            return@launch
        }
        pendingOp = PendingOp.RESTORE
        pendingIds = valid.map { it.mediaId }
        try {
            val request = MediaStore.createTrashRequest(contentResolver, valid.map { Uri.parse(it.uri) }, false)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: IllegalArgumentException) {
            // URIs rejected — clean up locally.
            viewModel.deleteFromTrash(pendingIds)
        } catch (security: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build())
        }
    }
}
