package net.dixq.partyphotoslideshow

import android.util.Log
import com.dropbox.core.DbxException
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DeletedMetadata
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderContinueErrorException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * データ層。Dropbox APIとローカルキャッシュの操作を担当する。
 */
class DropboxRepository(
    private val dbxClient: DbxClientV2,
    private val cacheManager: CacheManager,
    private val folderPath: String
) {
    private var folderCursor: String? = null

    // --- 外部に公開するフロー ---
    private val _fileListUpdated = MutableSharedFlow<List<String>>()
    val fileListUpdated = _fileListUpdated.asSharedFlow() // ファイルリスト全体の更新通知

    private val _newFilesAdded = MutableSharedFlow<List<String>>()
    val newFilesAdded = _newFilesAdded.asSharedFlow() // 新規ファイル追加の通知

    private val _fileDeleted = MutableSharedFlow<String>()
    val fileDeleted = _fileDeleted.asSharedFlow() // ファイル削除の通知

    /**
     * 起動時のキャッシュ同期処理
     */
    suspend fun sync(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress("ファイルリストを取得中...")
        val remoteResult = dbxClient.files().listFolder(folderPath)
        folderCursor = remoteResult.cursor
        val remoteFiles = remoteResult.entries.mapNotNull { it as? FileMetadata }
        val remoteFileNames = remoteFiles.map { it.name }.toSet()
        val localFileNames = cacheManager.getCachedFileNames()

        // ローカルにのみ存在するファイルを削除
        (localFileNames - remoteFileNames).forEach { fileName ->
            cacheManager.deleteFile(fileName)
            _fileDeleted.emit(fileName)
        }

        // Dropboxにのみ存在するファイルをダウンロード
        val filesToDownload = remoteFiles.filter { it.name !in localFileNames }
        filesToDownload.forEachIndexed { index, metadata ->
            onProgress("キャッシュを同期中... (${index + 1}/${filesToDownload.size})")
            downloadFile(metadata)
        }

        // 最終的なファイルリストを通知
        val finalFileNames = remoteFiles.map { it.name }.sorted()
        _fileListUpdated.emit(finalFileNames)
    }

    /**
     * Dropboxフォルダの変更を監視する
     */
    suspend fun listenForChanges() = withContext(Dispatchers.IO) {
        if (folderCursor == null) {
            Log.w("Repository", "Cursor is null. Sync required.")
            return@withContext
        }
        try {
            val result = dbxClient.files().listFolderLongpoll(folderCursor, 60L)
            if (result.changes) {
                fetchLatestChanges()
            }
        } catch (e: DbxException) {
            Log.e("Repository", "Longpoll failed, resetting cursor.", e)
            folderCursor = null
        }
    }

    /**
     * 差分を取得して処理する
     */
    private suspend fun fetchLatestChanges() {
        val result = dbxClient.files().listFolderContinue(folderCursor)
        folderCursor = result.cursor

        val addedMetadata = result.entries.filterIsInstance<FileMetadata>()
        val deletedMetadata = result.entries.filterIsInstance<DeletedMetadata>()

        val suppressDeleteNotification = addedMetadata.any { UploaderNameParser.parse(it.name) != null }

        deletedMetadata.forEach {
            val fileName = it.name
            cacheManager.deleteFile(fileName)
            if (!suppressDeleteNotification) {
                _fileDeleted.emit(fileName)
            }
        }

        val finalAddedFiles = addedMetadata.filter { UploaderNameParser.parse(it.name) != null }
        if (finalAddedFiles.isNotEmpty()) {
            finalAddedFiles.forEach { downloadFile(it) }
            _newFilesAdded.emit(finalAddedFiles.map { it.name })
        }
    }

    private fun downloadFile(fileMetadata: FileMetadata) {
        cacheManager.saveFile(fileMetadata.name) { outputStream ->
            dbxClient.files().download(fileMetadata.pathLower, fileMetadata.rev).download(outputStream)
        }
    }
}
