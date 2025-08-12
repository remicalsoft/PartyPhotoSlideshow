package net.dixq.partyslideshow

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

class DropboxRepository(
    private val dbxClient: DbxClientV2,
    private val cacheManager: CacheManager,
    private val folderPath: String
) {
    private var folderCursor: String? = null

    private val _fileListUpdated = MutableSharedFlow<List<String>>()
    val fileListUpdated = _fileListUpdated.asSharedFlow()

    private val _newFilesAdded = MutableSharedFlow<List<String>>()
    val newFilesAdded = _newFilesAdded.asSharedFlow()

    private val _fileDeleted = MutableSharedFlow<String>()
    val fileDeleted = _fileDeleted.asSharedFlow()

    suspend fun sync(onProgress: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            onProgress("ファイルリストを取得中...")
            Log.d("Repository", "Fetching file list from Dropbox path: $folderPath")
            val remoteResult = dbxClient.files().listFolder(folderPath)
            folderCursor = remoteResult.cursor
            val remoteFiles = remoteResult.entries.mapNotNull { it as? FileMetadata }
            val remoteFileNames = remoteFiles.map { it.name }.toSet()
            val localFileNames = cacheManager.getCachedFileNames()

            (localFileNames - remoteFileNames).forEach { fileName ->
                cacheManager.deleteFile(fileName)
                _fileDeleted.emit(fileName)
            }

            val filesToDownload = remoteFiles.filter { it.name !in localFileNames }
            filesToDownload.forEachIndexed { index, metadata ->
                onProgress("キャッシュを同期中... (${index + 1}/${filesToDownload.size})")
                downloadFile(metadata)
            }

            val finalFileNames = remoteFiles.map { it.name }.sorted()
            Log.d("Repository", "Sync successful. Found ${finalFileNames.size} files.")
            _fileListUpdated.emit(finalFileNames)
        } catch (e: DbxException) {
            // ★修正箇所: Logcatに詳細なDropbox APIエラー情報を出力する
            Log.e("Repository", "Dropbox API error during sync", e)
            throw e // 例外をViewModelに再スローしてUIに通知させる
        }
    }

    suspend fun listenForChanges() = withContext(Dispatchers.IO) {
        if (folderCursor == null) throw IllegalStateException("Cursor is null. Sync must be called first.")

        try {
            Log.d("Repository", "Polling for changes with cursor: $folderCursor")
            if (dbxClient.files().listFolderLongpoll(folderCursor).changes) {
                Log.d("Repository", "Changes detected.")
                fetchLatestChanges()
            }
        } catch(e: DbxException) {
            Log.e("Repository", "Longpoll failed, will reset cursor.", e)
            folderCursor = null
            throw e
        }
    }

    private suspend fun fetchLatestChanges() {
        try {
            Log.d("Repository", "Fetching latest changes...")
            val result = dbxClient.files().listFolderContinue(folderCursor)
            folderCursor = result.cursor

            val addedMetadata = result.entries.filterIsInstance<FileMetadata>()
            val deletedMetadata = result.entries.filterIsInstance<DeletedMetadata>()

            val finalAddedFiles = addedMetadata.filter { UploaderNameParser.parse(it.name) != null }
            val suppressDeleteNotification = finalAddedFiles.isNotEmpty()

            deletedMetadata.forEach {
                val fileName = it.name
                cacheManager.deleteFile(fileName)
                if (!suppressDeleteNotification) {
                    _fileDeleted.emit(fileName)
                }
            }

            if (finalAddedFiles.isNotEmpty()) {
                finalAddedFiles.forEach { downloadFile(it) }
                _newFilesAdded.emit(finalAddedFiles.map { it.name })
            }
        } catch (e: ListFolderContinueErrorException) {
            Log.e("Repository", "Error continuing list folder. Cursor may be reset.", e)
            if (e.errorValue.isReset) folderCursor = null
        } catch (e: DbxException) {
            Log.e("Repository", "Dropbox API error fetching latest changes", e)
            throw e
        }
    }

    private fun downloadFile(fileMetadata: FileMetadata) {
        cacheManager.saveFile(fileMetadata.name) { outputStream ->
            dbxClient.files().download(fileMetadata.pathLower, fileMetadata.rev).download(outputStream)
        }
    }
}
