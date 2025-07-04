package net.dixq.partyphotoslideshow

import android.content.Context
import com.dropbox.core.v2.files.FileMetadata
import java.io.File
import java.io.FileOutputStream

/**
 * ローカルキャッシュの管理を担当するクラス
 */
class CacheManager(context: Context) {
    private val cacheDir = context.externalCacheDir

    /**
     * キャッシュディレクトリ内の全ファイル名を取得する
     */
    fun getCachedFileNames(): Set<String> {
        if (cacheDir == null) return emptySet()
        return cacheDir.listFiles { _, name -> name.endsWith(".jpg") }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
    }

    /**
     * 指定されたファイルをキャッシュから削除する
     */
    fun deleteFile(fileName: String): Boolean {
        if (cacheDir == null) return false
        return File(cacheDir, fileName).delete()
    }

    /**
     * 指定されたファイルをキャッシュに保存する
     * @return 保存されたファイルのFileオブジェクト
     */
    fun saveFile(fileName: String, downloadAction: (FileOutputStream) -> Unit): File? {
        if (cacheDir == null) return null
        val file = File(cacheDir, fileName)
        try {
            FileOutputStream(file).use { outputStream ->
                downloadAction(outputStream)
            }
            return file
        } catch (e: Exception) {
            // エラーが発生した場合は不完全なファイルを削除
            if (file.exists()) {
                file.delete()
            }
            return null
        }
    }

    /**
     * キャッシュからファイルを取得する
     */
    fun getFile(fileName: String): File? {
        if (cacheDir == null) return null
        val file = File(cacheDir, fileName)
        return if (file.exists()) file else null
    }
}
