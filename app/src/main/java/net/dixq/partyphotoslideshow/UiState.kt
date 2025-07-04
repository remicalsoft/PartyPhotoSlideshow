package net.dixq.partyphotoslideshow

import java.io.File

/**
 * UIの状態を表現するためのデータクラスとイベントクラス
 */
data class SlideshowState(
    val isLoading: Boolean = true,
    val progressMessage: String? = null,
    val currentImageFile: File? = null,
    val imageCounter: Pair<Int, Int>? = null // (現在のインデックス + 1, 全体の数)
)

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
    data class ShowNewPhotoThumbnail(val file: File) : UiEvent()
}
