package net.dixq.partyslideshow

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dropbox.core.InvalidAccessTokenException
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
import java.util.Collections

class SlideshowViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SlideshowState())
    val uiState = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    private var imageFileNames = Collections.synchronizedList(mutableListOf<String>())
    private var currentImageIndex = 0
    private var repository: DropboxRepository? = null
    private val cacheManager = CacheManager(application)

    private var slideshowJob: Job? = null
    private var longpollJob: Job? = null

    fun initialize(accessToken: String) {
        if (repository != null) return

        val requestConfig = DbxRequestConfig.newBuilder("PartyPhotoSlideshow").build()
        val dbxClient = DbxClientV2(requestConfig, accessToken)
        repository = DropboxRepository(
            dbxClient,
            cacheManager,
            getApplication<Application>().getString(R.string.dropbox_folder_path)
        ).also {
            viewModelScope.launch { it.fileListUpdated.collect(::handleFileListUpdated) }
            viewModelScope.launch { it.newFilesAdded.collect(::handleNewFilesAdded) }
            viewModelScope.launch { it.fileDeleted.collect(::handleFileDeleted) }
        }
        startInitialSync()
    }

    fun onResumed() {
        if (imageFileNames.isNotEmpty() && slideshowJob?.isActive != true) startSlideshow()
        if (repository != null && longpollJob?.isActive != true) startLongPolling()
    }

    fun onPaused() {
        slideshowJob?.cancel()
        longpollJob?.cancel()
    }

    private fun startInitialSync() = viewModelScope.launch {
        _uiState.update { it.copy(isLoading = true) }
        try {
            Log.d("ViewModel", "Starting initial sync...")
            repository?.sync { progressMessage ->
                _uiState.update { it.copy(progressMessage = progressMessage) }
            }
            Log.d("ViewModel", "Initial sync finished successfully.")
        } catch (e: Exception) {
            // ★修正箇所: InvalidAccessTokenExceptionを特別に処理する
            if (e is InvalidAccessTokenException) {
                _uiEvents.emit(UiEvent.ReAuthenticationRequired)
            } else {
                Log.e("ViewModel", "Error during initial sync", e)
                _uiEvents.emit(UiEvent.ShowSnackbar("同期エラー: ${e.message}"))
            }
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
                    // ★修正箇所: InvalidAccessTokenExceptionを特別に処理する
                    if (e is InvalidAccessTokenException) {
                        _uiEvents.emit(UiEvent.ReAuthenticationRequired)
                        longpollJob?.cancel() // 無限ループを防ぐためにジョブを停止
                    } else {
                        Log.e("ViewModel", "Long polling failed, will re-sync.", e)
                        startInitialSync() // その他のエラー時は再同期
                        delay(10000L) // 少し待ってから再開
                    }
                }
            }
        }
    }

    private fun startSlideshow() {
        slideshowJob?.cancel()
        slideshowJob = viewModelScope.launch {
            if (imageFileNames.isEmpty()) return@launch
            showNextImage(isInitial = true)
            while (isActive) {
                delay(5000L)
                showNextImage()
            }
        }
    }

    private fun showNextImage(isInitial: Boolean = false) {
        if (imageFileNames.isEmpty()) return
        if (!isInitial) currentImageIndex = (currentImageIndex + 1) % imageFileNames.size

        val fileName = imageFileNames.getOrNull(currentImageIndex) ?: return
        val file = cacheManager.getFile(fileName)
        _uiState.update {
            it.copy(
                currentImageFile = file,
                imageCounter = (currentImageIndex + 1) to imageFileNames.size
            )
        }
    }

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
        val listWasEmpty = imageFileNames.isEmpty()
        synchronized(imageFileNames) {
            val insertionPoint = if (listWasEmpty) 0 else currentImageIndex + 1
            imageFileNames.addAll(insertionPoint, sortedNewNames)
        }
        val lastName = sortedNewNames.last()
        val uploaderName = UploaderNameParser.parse(lastName)
        val message = uploaderName?.let { "$it さんが新しい画像を投稿しました！" } ?: "新しい写真が追加されました"
        _uiEvents.emit(UiEvent.ShowSnackbar(message))
        cacheManager.getFile(lastName)?.let {
            _uiEvents.emit(UiEvent.ShowNewPhotoThumbnail(it))
        }
        if (listWasEmpty && imageFileNames.isNotEmpty()) {
            startSlideshow()
        }
    }

    private fun handleFileDeleted(name: String) = viewModelScope.launch {
        var shouldResetSlideshow = false
        synchronized(imageFileNames) {
            val currentIndexIsValid = currentImageIndex < imageFileNames.size
            val currentFileName = if (currentIndexIsValid) imageFileNames[currentImageIndex] else null

            imageFileNames.remove(name)

            if (name == currentFileName || currentImageIndex >= imageFileNames.size) {
                shouldResetSlideshow = true
            }
        }
        _uiEvents.emit(UiEvent.ShowSnackbar("$name を削除しました"))

        if (imageFileNames.isEmpty()) {
            slideshowJob?.cancel()
            _uiState.value = SlideshowState(isLoading = false)
        } else if (shouldResetSlideshow) {
            currentImageIndex = 0
            startSlideshow()
        }
    }
}
