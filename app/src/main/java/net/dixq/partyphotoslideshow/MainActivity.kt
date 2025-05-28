package net.dixq.partyphotoslideshow

import android.animation.*
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.*
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
// import android.view.animation.OvershootInterpolator // Snackbar アニメーションで使われていなければ不要
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.dropbox.core.DbxHost
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.google.android.material.snackbar.Snackbar
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.*
import net.dixq.partyphotoslideshow.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.coroutineContext
// import kotlin.random.Random // Random を使わなくなったので不要なら削除

class MainActivity : AppCompatActivity() {

    /* ───────── フィールド ───────── */
    private lateinit var binding: ActivityMainBinding
    private lateinit var dbxClient: DbxClientV2
    // private lateinit var particleView: ParticleView // ParticleView 削除

    private lateinit var backgroundImageView1: ImageView
    private lateinit var backgroundImageView2: ImageView
    private var isBackground1Active = true
    private var currentBackgroundAnimatorSet: AnimatorSet? = null

    private val idToFile = mutableMapOf<String, File>()
    private val imageFiles = CopyOnWriteArrayList<File>()
    private var currentIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var currentAnimatorSet: AnimatorSet? = null

    private lateinit var dropboxFolderPath: String
    private lateinit var photoDir: File

    private var isAppStarted = false
    private var watchJob: Job? = null

    private val TAG = "PartySlideshow"

    private val finalNameRegex =
        Regex(""".+\s-\s.+\.(jpg|jpeg|png)""", RegexOption.IGNORE_CASE)

    private val scopes = arrayOf(
        "files.metadata.read",
        "files.content.read",
        "files.content.write"
    )

    // TransitionType enum 削除

    private val slideRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed && imageFiles.isNotEmpty()) {
                showNextImage()
            }
            handler.postDelayed(this, 6_000L)
        }
    }

    /* ───────── lifecycle ───────── */
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false) // ★ これを super.onCreate の直後、setContentView の前に

        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        backgroundImageView1 = binding.backgroundImageView1
        backgroundImageView2 = binding.backgroundImageView2
        backgroundImageView2.alpha = 0f

        // ★ WindowInsetsControllerCompat を使ったフルスクリーン設定
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        // システムバー（ステータスバーとナビゲーションバー）を隠す
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        // スワイプで一時的に表示する際の挙動を設定
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        dropboxFolderPath = getString(R.string.dropbox_folder_path)
        photoDir = File(externalCacheDir, "photos").apply { if (!exists()) mkdirs() }

        photoDir.listFiles()?.forEach { imageFiles += it }
        binding.tvPhotoCount.text = getString(R.string.photo_count_format, imageFiles.size)


        binding.imageView2.visibility = View.INVISIBLE

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
        // particleView.resume() // ParticleView 削除
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
        currentAnimatorSet?.cancel()
        currentBackgroundAnimatorSet?.cancel()
        watchJob?.cancel()
        // particleView.pause() // ParticleView 削除
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

        if (imageFiles.isNotEmpty()) {
            Glide.with(this)
                .load(imageFiles[0])
                .into(binding.imageView)
            currentIndex = 1

            Glide.with(this@MainActivity)
                .load(imageFiles[0])
                .transform(MultiTransformation(CenterCrop(), BlurTransformation(25, 3)))
                .into(backgroundImageView1)
            backgroundImageView1.alpha = 1f
            isBackground1Active = true
        }

        handler.post(slideRunnable)
        watchJob = scope.launch { watchLongpoll() }
    }

    /* ───────── 画像切替とトランジション ───────── */
    private fun showNextImage() {
        if (imageFiles.isEmpty()) return

        // val transitionType = TransitionType.values().random() // TransitionType 削除のため不要
        val nextImageFile = imageFiles[currentIndex]

        val currentForeImageView: ImageView
        val nextForeImageView: ImageView

        if (binding.imageView.visibility == View.VISIBLE) {
            currentForeImageView = binding.imageView
            nextForeImageView = binding.imageView2
        } else {
            currentForeImageView = binding.imageView2
            nextForeImageView = binding.imageView
        }

        Glide.with(this)
            .load(nextImageFile)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?,
                    target: Target<Drawable>?,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "Foreground image load failed for: ${nextImageFile.path}", e)
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable?, model: Any?,
                    target: Target<Drawable>?,
                    dataSource: DataSource?, isFirstResource: Boolean
                ): Boolean {
                    performTransition(currentForeImageView, nextForeImageView) // transitionType 引数削除

                    val currentBgImageView = if (isBackground1Active) backgroundImageView1 else backgroundImageView2
                    val nextBgImageView = if (isBackground1Active) backgroundImageView2 else backgroundImageView1

                    Glide.with(this@MainActivity)
                        .load(nextImageFile)
                        .transform(MultiTransformation(CenterCrop(), BlurTransformation(25, 3)))
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, modelBg: Any?, targetBg: Target<Drawable>?, isFirstResourceBg: Boolean): Boolean {
                                Log.e(TAG, "Background image load failed for: ${nextImageFile.path}", e)
                                nextBgImageView.alpha = 0f
                                return false
                            }

                            override fun onResourceReady(
                                resourceBg: Drawable?, modelBg: Any?,
                                targetBg: Target<Drawable>?, dataSourceBg: DataSource?,
                                isFirstResourceBg: Boolean
                            ): Boolean {
                                performBackgroundCrossfade(currentBgImageView, nextBgImageView)
                                return false
                            }
                        })
                        .into(nextBgImageView)
                    return false
                }
            })
            .into(nextForeImageView)

        currentIndex = (currentIndex + 1) % imageFiles.size
    }

    private fun performBackgroundCrossfade(currentBgView: ImageView, nextBgView: ImageView) {
        currentBackgroundAnimatorSet?.cancel()

        nextBgView.alpha = 0f
        if (nextBgView.visibility == View.INVISIBLE) {
            nextBgView.visibility = View.VISIBLE
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            ObjectAnimator.ofFloat(currentBgView, "alpha", 1f, 0f),
            ObjectAnimator.ofFloat(nextBgView, "alpha", 0f, 1f)
        )
        animatorSet.duration = 1500L
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentBgView.visibility = View.INVISIBLE
                isBackground1Active = (nextBgView == backgroundImageView1)
                currentBackgroundAnimatorSet = null
            }
            override fun onAnimationCancel(animation: Animator) {
                currentBackgroundAnimatorSet = null
            }
        })
        currentBackgroundAnimatorSet = animatorSet
        animatorSet.start()
    }

    // performTransition を FADE 専用に簡略化
    private fun performTransition(
        currentView: ImageView,
        nextView: ImageView
    ) {
        currentAnimatorSet?.cancel()

        val animatorSet = AnimatorSet()
        val defaultDuration = 1500L

        nextView.alpha = 0f
        nextView.visibility = View.VISIBLE
        animatorSet.playTogether(
            ObjectAnimator.ofFloat(currentView, "alpha", 1f, 0f),
            ObjectAnimator.ofFloat(nextView, "alpha", 0f, 1f)
        )
        animatorSet.duration = defaultDuration
        animatorSet.interpolator = AccelerateDecelerateInterpolator()

        animatorSet.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                currentView.visibility = View.INVISIBLE
                currentView.apply { // リセットは残しておいても良い
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    translationX = 0f
                    translationY = 0f
                    rotation = 0f
                    rotationX = 0f
                    rotationY = 0f
                }
            }
        })

        currentAnimatorSet = animatorSet
        animatorSet.start()
    }

    /* ───────── 初回 DL ───────── */
    private suspend fun initialDownload() {
        val entries = dbxClient.files()
            .listFolder(dropboxFolderPath)
            .entries.filterIsInstance<FileMetadata>()
            .filter { finalNameRegex.matches(it.name) }

        Log.e(TAG, "size = "+entries.size);
        withContext(Dispatchers.Main) {
            binding.progressOverlay.visibility = View.VISIBLE
            binding.progressBar.max = entries.size
            binding.tvProgress.text = getString(R.string.progress_format, 0, entries.size)
        }

        entries.forEachIndexed { idx, meta ->
            if (idToFile[meta.id] == null) downloadAndAdd(meta)
            withContext(Dispatchers.Main) {
                binding.progressBar.progress = idx + 1
                binding.tvProgress.text = getString(R.string.progress_format, idx + 1, entries.size)
            }
        }

        withContext(Dispatchers.Main) {
            binding.progressOverlay.visibility = View.GONE
            binding.tvPhotoCount.text = getString(R.string.photo_count_format, imageFiles.size)


            if (imageFiles.isNotEmpty() && binding.imageView.drawable == null) {
                Glide.with(this@MainActivity)
                    .load(imageFiles[0])
                    .into(binding.imageView)
                currentIndex = 1

                Glide.with(this@MainActivity)
                    .load(imageFiles[0])
                    .transform(MultiTransformation(CenterCrop(), BlurTransformation(25, 3)))
                    .into(backgroundImageView1)
                backgroundImageView1.alpha = 1f
                backgroundImageView2.alpha = 0f
                backgroundImageView2.visibility = View.INVISIBLE
                isBackground1Active = true
            }
            if (handler.hasCallbacks(slideRunnable).not() && imageFiles.isNotEmpty()) {
                handler.post(slideRunnable)
            }
        }
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
                        .filter { finalNameRegex.matches(it.name) }
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
            // particleView.createBurst(...) // ParticleView 削除

            Snackbar.make(binding.root,
                getString(R.string.new_photo_notification),
                Snackbar.LENGTH_LONG).show()

            binding.thumbOverlay.visibility = View.VISIBLE
            binding.thumbOverlay.alpha = 0f
            binding.thumbOverlay.scaleX = 0.5f
            binding.thumbOverlay.scaleY = 0.5f

            Glide.with(this@MainActivity).load(idToFile[meta.id]).into(binding.thumbOverlay)

            val thumbAnimator = AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(binding.thumbOverlay, "alpha", 0f, 1f),
                    ObjectAnimator.ofFloat(binding.thumbOverlay, "scaleX", 0.5f, 1f),
                    ObjectAnimator.ofFloat(binding.thumbOverlay, "scaleY", 0.5f, 1f)
                )
                duration = 500
                // interpolator = OvershootInterpolator() // OvershootInterpolator が不要なら削除
            }
            thumbAnimator.start()

            handler.postDelayed({
                val hideAnimator = ObjectAnimator.ofFloat(binding.thumbOverlay, "alpha", 1f, 0f).apply {
                    duration = 300
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            binding.thumbOverlay.visibility = View.GONE
                        }
                    })
                }
                hideAnimator.start()
            }, 4_000L)

            if (imageFiles.size == 1 && binding.imageView.drawable == null) {
                Glide.with(this@MainActivity)
                    .load(imageFiles[0])
                    .into(binding.imageView)
                currentIndex = 1

                Glide.with(this@MainActivity)
                    .load(imageFiles[0])
                    .transform(MultiTransformation(CenterCrop(), BlurTransformation(25, 3)))
                    .into(backgroundImageView1)
                backgroundImageView1.alpha = 1f
                isBackground1Active = true

                if (handler.hasCallbacks(slideRunnable).not()) {
                    handler.post(slideRunnable)
                }
            }
        }
    }

    private fun downloadAndAdd(meta: FileMetadata) {
        val fid = meta.id ?: return
        val local = File(photoDir, meta.name)
        try {
            dbxClient.files().download(meta.pathLower).inputStream.use { i ->
                FileOutputStream(local).use { o -> i.copyTo(o) }
            }
            idToFile[fid] = local
            handler.post {
                imageFiles.add(local)
                binding.tvPhotoCount.text = getString(R.string.photo_count_format, imageFiles.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download or save file: ${meta.name}", e)
            // 必要であればエラーをユーザーに通知
        }
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

// ParticleView クラス定義全体を削除