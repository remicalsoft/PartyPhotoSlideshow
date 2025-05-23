package net.dixq.partyphotoslideshow    // ←実際のパッケージ名に合わせてください


import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.*
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.dropbox.core.DbxHost
import com.google.android.material.snackbar.Snackbar
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import kotlinx.coroutines.*
import net.dixq.partyphotoslideshow.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.coroutines.coroutineContext


class MainActivity : AppCompatActivity() {

    /* ───────── フィールド ───────── */
    private lateinit var binding: ActivityMainBinding
    private lateinit var dbxClient: DbxClientV2

    private val idToFile = mutableMapOf<String, File>()
    private val imageFiles = CopyOnWriteArrayList<File>()      // スライド用
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scaleAnim: ObjectAnimator

    private lateinit var dropboxFolderPath: String
    private lateinit var photoDir: File

    private var isAppStarted = false
    private var watchJob: Job? = null

    private val TAG = "PartySlideshow"

    /** 最終ファイル名判定 (投稿者名 - 元名.拡張子) */
    private val finalNameRegex =
        Regex(""".+\s-\s.+\.(jpg|jpeg|png)""", RegexOption.IGNORE_CASE)

    /** PKCE スコープ */
    private val scopes = arrayOf(
        "files.metadata.read",
        "files.content.read",
        "files.content.write"
    )

    /** 画像切替 Runnable */
    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed && imageFiles.isNotEmpty()) {
                Glide.with(this@MainActivity)
                    .load(imageFiles[currentIndex])
                    .into(binding.imageView)
                currentIndex = (currentIndex + 1) % imageFiles.size
            }
            handler.postDelayed(this, 5_000L)
        }
    }

    /* ───────── lifecycle ───────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /* フルスクリーン */
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        /* パス設定 */
        dropboxFolderPath = getString(R.string.dropbox_folder_path)
        photoDir = File(externalCacheDir, "photos").apply { if (!exists()) mkdirs() }

        /* キャッシュ読込 */
        photoDir.listFiles()?.forEach { imageFiles += it }

        /* ズームアニメ */
        scaleAnim = ObjectAnimator.ofPropertyValuesHolder(
            binding.imageView,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 1.1f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 1.1f)
        ).apply {
            duration = 10_000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }

        /* 認可確認 */
        loadCredential()?.let {
            setupClient(it); startApp()
        } ?: startOAuth()
    }

    override fun onResume() {
        super.onResume()
        Auth.getDbxCredential()?.let {
            storeCredential(it)
            if (!isAppStarted) { setupClient(it); startApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Auth.getDbxCredential()?.let {
            storeCredential(it)
            if (!isAppStarted) { setupClient(it); startApp() }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(slideRunnable)
        scaleAnim.cancel()
        watchJob?.cancel()
    }

    /* ───────── 認可/初期化 ───────── */
    private fun startOAuth() {
        val cfg = DbxRequestConfig
            .newBuilder(getString(R.string.dropbox_client_identifier))
            .withUserLocale(Locale.getDefault().toString())
            .build()

        Auth.startOAuth2PKCE(
            this,
            getString(R.string.dropbox_app_key),
            cfg,
            DbxHost.DEFAULT,
            scopes.toList()
        )
    }

    private fun setupClient(cred: DbxCredential) {
        val cfg = DbxRequestConfig
            .newBuilder(getString(R.string.dropbox_client_identifier))
            .withUserLocale(Locale.getDefault().toString())
            .build()
        dbxClient = DbxClientV2(cfg, cred)
    }

    private fun startApp() {
        isAppStarted = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch { initialDownload() }

        handler.post(slideRunnable)
        scaleAnim.start()

        watchJob = scope.launch { watchLongpoll() }
    }

    /* ───────── 初回 DL ───────── */
    private suspend fun initialDownload() {
        val entries = dbxClient.files()
            .listFolder(dropboxFolderPath)
            .entries.filterIsInstance<FileMetadata>()
            .filter { finalNameRegex.matches(it.name) }

        withContext(Dispatchers.Main) {
            binding.progressOverlay.visibility = View.VISIBLE
            binding.progressBar.max = entries.size
        }

        entries.forEachIndexed { idx, meta ->
            if (idToFile[meta.id] == null) downloadAndAdd(meta)
            withContext(Dispatchers.Main) { binding.progressBar.progress = idx + 1 }
        }

        withContext(Dispatchers.Main) { binding.progressOverlay.visibility = View.GONE }
    }

    /* ───────── Long-poll 監視 ───────── */
    private suspend fun watchLongpoll() {
        var cursor = dbxClient.files()
            .listFolderGetLatestCursor(dropboxFolderPath).cursor

        while (coroutineContext.isActive) {
            try {
                val res = dbxClient.files().listFolderLongpoll(cursor, 480)
                if (res.changes) {
                    val delta = dbxClient.files().listFolderContinue(cursor)
                    cursor = delta.cursor
                    delta.entries
                        .filterIsInstance<FileMetadata>()
                        .filter { finalNameRegex.matches(it.name) }   // ★正式名だけ
                        .forEach { if (idToFile[it.id] == null) handleNewFile(it) }
                }
                res.backoff?.let { delay(it * 1_000L) }
            } catch (e: Exception) {
                Log.w(TAG, "longpoll error", e); delay(1_000L)
            }
        }
    }

    /* ───────── ファイル処理 ───────── */
    private suspend fun handleNewFile(meta: FileMetadata) {
        downloadAndAdd(meta)
        withContext(Dispatchers.Main) {
            Snackbar.make(binding.root,
                "新しい写真が投稿されました！",
                Snackbar.LENGTH_SHORT).show()
            binding.thumbOverlay.visibility = View.VISIBLE
            Glide.with(this@MainActivity).load(idToFile[meta.id]).into(binding.thumbOverlay)
            handler.postDelayed({ binding.thumbOverlay.visibility = View.GONE }, 3_000L)
        }
    }

    private fun downloadAndAdd(meta: FileMetadata) {
        val fid = meta.id ?: return
        val local = File(photoDir, meta.name)
        dbxClient.files().download(meta.pathLower).inputStream.use { i ->
            FileOutputStream(local).use { o -> i.copyTo(o) }
        }
        idToFile[fid] = local
        handler.post { imageFiles += local }
    }

    /* ───────── Credential 永続化 ───────── */
    private fun storeCredential(c: DbxCredential) =
        getSharedPreferences("dropbox", MODE_PRIVATE).edit()
            .putString("credential", c.toString()).apply()

    private fun loadCredential(): DbxCredential? =
        getSharedPreferences("dropbox", MODE_PRIVATE)
            .getString("credential", null)
            ?.let { DbxCredential.Reader.readFully(it) }
}