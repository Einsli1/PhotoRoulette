package com.einsli.photoroulette

import android.Manifest
import android.app.RecoverableSecurityException
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.einsli.photoroulette.data.*
import com.einsli.photoroulette.media.MediaScanner
import com.einsli.photoroulette.ui.PhotoRouletteApp
import com.einsli.photoroulette.worker.ReminderScheduler
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val database by lazy { PhotoDatabase.create(applicationContext) }
    private val settings by lazy { SettingsRepository(applicationContext) }
    private val repository by lazy { PhotoRepository(database.photoDao(), MediaScanner(contentResolver), settings) }
    private val viewModel by viewModels<PhotoViewModel> { PhotoViewModel.Factory(repository, settings) }
    private enum class PendingOp { TRASH, RESTORE }
    private var pendingOp: PendingOp? = null
    private var pendingIds: List<Long> = emptyList()
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
        ReminderScheduler.schedule(this, 20, 0)
        requestPermissionsIfNeeded()
        setContent {
            PhotoRouletteApp(
                viewModel = viewModel,
                onAction = { mediaId, state, dir -> viewModel.action(mediaId, state, dir) },
                onCommitDeletes = ::movePendingToTrash,
                onRestoreFromTrash = ::restoreFromSystemTrash
            )
        }
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
        pendingOp = PendingOp.TRASH
        pendingIds = photos.map { it.mediaId }
        try {
            val request = MediaStore.createTrashRequest(contentResolver, photos.map { Uri.parse(it.uri) }, true)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (security: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build())
        }
    }

    private fun restoreFromSystemTrash(ids: List<Long>) = lifecycleScope.launch {
        val photos = viewModel.trashList().filter { it.mediaId in ids }
        if (photos.isEmpty()) return@launch
        pendingOp = PendingOp.RESTORE
        pendingIds = ids
        try {
            val request = MediaStore.createTrashRequest(contentResolver, photos.map { Uri.parse(it.uri) }, false)
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (security: RecoverableSecurityException) {
            deleteLauncher.launch(IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build())
        }
    }
}
