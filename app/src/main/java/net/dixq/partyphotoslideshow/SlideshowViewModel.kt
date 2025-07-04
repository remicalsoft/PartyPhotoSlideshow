package net.dixq.partyphotoslideshow

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

class SlideshowViewModel(application: Application) : AndroidViewModel(application) {

    // --- 状態管理 ---
    private val _uiState = MutableStateFlow(SlideshowState())
    val uiState = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    // --- 内部状態 ---
    private var imageFileNames = Collections.synchronizedList(mutableListOf<String>())
    private var currentImageIndex = 0
    private var repository: DropboxRepository? = null
    private val cacheManager = CacheManager(application)

    // --- ジョブ管理 ---
    private var slideshowJob: Job? = null
    private var longpollJob: Job? = null

    init {
        // Repositoryからのイベントを監視
        viewModelScope.launch {
            repository?.fileListUpdated?.collect { names ->
                handleFileListUpdated(names)
            }
        }
        viewModelScope.launch {
            repository?.newFilesAdded?.collect { newNames ->
                handleNewFilesAdded(newNames)
            }
        }
        viewModelScope.launch {
            repository?.fileDeleted?.collect { name ->
                handleFileDeleted(name)
            }
        }
    }

    /**
     * Dropboxクライアントを初期化し、同期を開始する
     */
    fun initialize(accessToken: String) {
        if (repository != null) return
        val requestConfig = DbxRequestConfig.newBuilder("PartyPhotoSlideshow").build()
        val dbxClient = DbxClientV2(requestConfig, accessToken)
        repository = DropboxRepository(
            dbxClient,
            cacheManager,
            getApplication<Application>().getString(R.string.dropbox_folder_path)
        )
        // コレクションを再開
        viewModelScope.launch { repository?.fileListUpdated?.collect(::handleFileListUpdated) }
        viewModelScope.launch { repository?.newFilesAdded?.collect(::handleNewFilesAdded) }
        viewModelScope.launch { repository?.fileDeleted?.collect(::handleFileDeleted) }

        startInitialSync()
    }

    /**
     * アプリがフォアグラウンドになったときの処理
     */
    fun onResumed() {
        if (imageFileNames.isNotEmpty() && slideshowJob?.isActive != true) {
            startSlideshow()
        }
        if (longpollJob?.isActive != true) {
            startLongPolling()
        }
    }

    /**
     * アプリがバックグラウンドになったときの処理
     */
    fun onPaused() {
        slideshowJob?.cancel()
        longpollJob?.cancel()
    }

    private fun startInitialSync() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        try {
            repository?.sync { progressMessage ->
                _uiState.update { it.copy(progressMessage = progressMessage) }
            }
        } catch (e: Exception) {
            _uiEvents.emit(UiEvent.ShowSnackbar("同期エラー: ${e.message}"))
        } finally {
            _uiState.update { it.copy(isLoading = false, progressMessage = null) }
        }
    }

    private fun startLongPolling() {
        longpollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    repository?.listenForChanges()
                } catch (e: Exception) {
                    Log.e("ViewModel", "Long polling error", e)
                    delay(5000L) // エラー時は少し待ってリトライ
                }
            }
        }
    }

    private fun startSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            if (imageFileNames.isEmpty()) return@launch
            // 最初の画像を表示
            showNextImage(isInitial = true)
            // ループ開始
            while (isActive) {
                delay(5000L)
                showNextImage()
            }
        }
    }

    private fun showNextImage(isInitial: Boolean = false) {
        if (imageFileNames.isEmpty()) return
        if (!isInitial) {
            currentImageIndex = (currentImageIndex + 1) % imageFileNames.size
        }
        val fileName = imageFileNames[currentImageIndex]
        val file = cacheManager.getFile(fileName)
        _uiState.update {
            it.copy(
                currentImageFile = file,
                imageCounter = (currentImageIndex + 1) to imageFileNames.size
            )
        }
    }

    // --- Repositoryからのイベントハンドラ ---
    private fun handleFileListUpdated(names: List<String>) {
        synchronized(imageFileNames) {
            imageFileNames.clear()
            imageFileNames.addAll(names)
        }
        if (imageFileNames.isNotEmpty()) {
            currentImageIndex = 0
            startSlideshow()
        }
    }

    private fun handleNewFilesAdded(newNames: List<String>) = viewModelScope.launch {
        val sortedNewNames = newNames.sorted()
        synchronized(imageFileNames) {
            val insertionPoint = if (imageFileNames.isEmpty()) 0 else currentImageIndex + 1
            imageFileNames.addAll(insertionPoint, sortedNewNames)
        }
        val lastName = sortedNewNames.last()
        val uploaderName = UploaderNameParser.parse(lastName)
        val message = uploaderName?.let { "$it さんが新しい画像を投稿しました！" } ?: "新しい写真が追加されました"
        _uiEvents.emit(UiEvent.ShowSnackbar(message))
        cacheManager.getFile(lastName)?.let {
            _uiEvents.emit(UiEvent.ShowNewPhotoThumbnail(it))
        }
        if (slideshowJob?.isActive != true && imageFileNames.isNotEmpty()) {
            startSlideshow()
        }
    }

    private fun handleFileDeleted(name: String) = viewModelScope.launch {
        synchronized(imageFileNames) {
            imageFileNames.remove(name)
        }
        _uiEvents.emit(UiEvent.ShowSnackbar("$name を削除しました"))
        if (imageFileNames.isEmpty()) {
            slideshowJob?.cancel()
            _uiState.value = SlideshowState(isLoading = false) // 初期状態に戻す
        }
    }
}
