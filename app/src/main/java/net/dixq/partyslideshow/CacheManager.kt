package net.dixq.partyslideshow

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * ローカルキャッシュの管理を担当するクラス
 */
class CacheManager(context: Context) {
    private val cacheDir = context.externalCacheDir

    fun getCachedFileNames(): Set<String> {
        if (cacheDir == null) return emptySet()
        return cacheDir.listFiles { _, name -> name.endsWith(".jpg") }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
    }

    fun deleteFile(fileName: String) {
        if (cacheDir == null) return
        File(cacheDir, fileName).delete()
    }

    fun saveFile(fileName: String, downloadAction: (FileOutputStream) -> Unit) {
        if (cacheDir == null) return
        val file = File(cacheDir, fileName)
        try {
            FileOutputStream(file).use(downloadAction)
        } catch (e: Exception) {
            if (file.exists()) file.delete()
        }
    }

    fun getFile(fileName: String): File? {
        if (cacheDir == null) return null
        val file = File(cacheDir, fileName)
        return if (file.exists()) file else null
    }
}
