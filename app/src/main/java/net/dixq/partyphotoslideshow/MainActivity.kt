package net.dixq.partyphotoslideshow

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import net.dixq.partyphotoslideshow.databinding.ActivityMainBinding
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var dbxClient: DbxClientV2
    private val downloadedPaths = mutableSetOf<String>()
    private val imageFiles = mutableListOf<File>()
    private var currentIndex = 0
    private val handler = Handler(Looper.getMainLooper())

    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed && imageFiles.isNotEmpty()) {
                val file = imageFiles[currentIndex]
                Glide.with(this@MainActivity)
                    .load(file)
                    .into(binding.imageView)
                currentIndex = (currentIndex + 1) % imageFiles.size
            }
            handler.postDelayed(this, 5_000L)
        }
    }

    private lateinit var scaleAnimator: ObjectAnimator
    private lateinit var dropboxFolderPath: String
    private lateinit var photoDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Dropbox クライアント初期化
        val token = getString(R.string.dropbox_access_token)
        val cfg = DbxRequestConfig.newBuilder(getString(R.string.dropbox_client_identifier)).build()
        dbxClient = DbxClientV2(cfg, token)

        // フォルダパス
        dropboxFolderPath = getString(R.string.dropbox_folder_path)

        // 保存先フォルダ (externalCacheDir)
        photoDir = File(externalCacheDir, "photos").apply { if (!exists()) mkdirs() }

        // 既存ファイル読み込み
        photoDir.listFiles()?.forEach { file ->
            imageFiles.add(file)
            downloadedPaths.add((dropboxFolderPath + "/" + file.name).lowercase())
        }

        // 拡大縮小アニメーション
        scaleAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.imageView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f)
        ).apply { duration = 10_000L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE }

        // 初期ダウンロード
        CoroutineScope(Dispatchers.IO).launch {
            initialDownload()
            withContext(Dispatchers.Main) {
                if (!isDestroyed) {
                    binding.progressOverlay.visibility = View.GONE
                    handler.post(slideRunnable)
                    scaleAnimator.start()
                    startPollingFolder()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(slideRunnable)
        scaleAnimator.cancel()
        binding.imageView.scaleX = 1f
        binding.imageView.scaleY = 1f
    }

    private suspend fun initialDownload() {
        val entries = dbxClient.files().listFolder(dropboxFolderPath)
            .entries.filterIsInstance<FileMetadata>()
        val newEntries = entries.filter { (it.pathLower ?: "") !in downloadedPaths }
        val total = newEntries.size
        withContext(Dispatchers.Main) {
            if (!isDestroyed) {
                binding.progressOverlay.visibility = View.VISIBLE
                binding.progressBar.max = total
                binding.tvProgress.text = "0 / $total"
            }
        }
        newEntries.forEachIndexed { idx, entry ->
            val path = entry.pathLower ?: return@forEachIndexed
            downloadedPaths.add(path)
            val localFile = File(photoDir, entry.name)
            dbxClient.files().download(path).inputStream.use { input ->
                FileOutputStream(localFile).use { out -> input.copyTo(out) }
            }
            imageFiles.add(localFile)
            withContext(Dispatchers.Main) {
                if (!isDestroyed) {
                    binding.progressBar.progress = idx + 1
                    binding.tvProgress.text = "${idx + 1} / $total"
                }
            }
        }
    }

    private fun startPollingFolder() {
        handler.post(object : Runnable {
            override fun run() {
                CoroutineScope(Dispatchers.IO).launch { checkNewFiles() }
                handler.postDelayed(this, 5_000L) // ポーリング間隔を5秒
            }
        })
    }

    private suspend fun checkNewFiles() {
        val entries = dbxClient.files().listFolder(dropboxFolderPath)
            .entries.filterIsInstance<FileMetadata>()
        for (entry in entries) {
            val path = entry.pathLower ?: continue
            if (downloadedPaths.add(path)) {
                val localFile = File(photoDir, entry.name)
                dbxClient.files().download(path).inputStream.use { input ->
                    FileOutputStream(localFile).use { out -> input.copyTo(out) }
                }
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        imageFiles.add(localFile)
                        Snackbar.make(binding.root, "新しい写真が投稿されました！", Snackbar.LENGTH_SHORT).show()
                        binding.thumbOverlay.visibility = View.VISIBLE
                        Glide.with(this@MainActivity)
                            .load(localFile)
                            .into(binding.thumbOverlay)
                        handler.postDelayed({ binding.thumbOverlay.visibility = View.GONE }, 4_000L)
                    }
                }
            }
        }
    }
}