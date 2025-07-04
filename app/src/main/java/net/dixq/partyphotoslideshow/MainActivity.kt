package net.dixq.partyphotoslideshow

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.dropbox.core.android.Auth
import com.google.android.material.snackbar.Snackbar
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.dixq.partyphotoslideshow.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: SlideshowViewModel by viewModels()

    // --- クロスフェード用フラグ ---
    private var isImageView1Active = true
    private var isBackgroundView1Active = true
    private var isFirstLoadComplete = false // アプリ起動後の初回読み込みが完了したかを管理するフラグ

    // --- 定数 ---
    companion object {
        private const val TAG = "MainActivity"
        private const val FADE_DURATION_MS = 600L
        private const val THUMBNAIL_DISPLAY_DURATION_MS = 8000L
        private const val SNACKBAR_TEXT_SIZE_SP = 24f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullScreen()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        handleAuth()
        viewModel.onResumed()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPaused()
    }

    private fun setupFullScreen() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    /**
     * Dropbox認証を処理する
     */
    private fun handleAuth() {
        val prefs = getSharedPreferences("dropbox-prefs", MODE_PRIVATE)
        var accessToken = prefs.getString("access-token", null)

        Auth.getDbxCredential()?.let {
            accessToken = it.accessToken
            prefs.edit().putString("access-token", accessToken).apply()
            Log.d(TAG, "New access token stored.")
        }

        if (accessToken != null) {
            viewModel.initialize(accessToken!!)
        } else {
            Auth.startOAuth2Authentication(this, getString(R.string.dropbox_app_key))
        }
    }

    /**
     * ViewModelからの状態とイベントを監視する
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // UI状態の監視
                launch {
                    viewModel.uiState.collectLatest { state ->
                        updateUi(state)
                    }
                }
                // UIイベントの監視
                launch {
                    viewModel.uiEvents.collect { event ->
                        handleUiEvent(event)
                    }
                }
            }
        }
    }

    /**
     * ViewModelから受け取った状態でUIを更新する
     */
    private fun updateUi(state: SlideshowState) {
        // ローディング表示
        binding.progressLayout.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.progressTextView.text = state.progressMessage ?: ""

        // 画像カウンター
        state.imageCounter?.let { (current, total) ->
            binding.counterTextView.visibility = View.VISIBLE
            binding.counterTextView.text = getString(R.string.image_counter_format, current, total)
        } ?: run {
            binding.counterTextView.visibility = View.GONE
        }

        // スライドショー画像
        state.currentImageFile?.let {
            // isInitialImageは、カウンターが1に戻った場合にtrueになる
            loadImage(it, state.imageCounter?.first == 1)
        }
    }

    /**
     * ViewModelから受け取ったイベントを処理する
     */
    private fun handleUiEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ShowSnackbar -> showCustomSnackbar(event.message)
            is UiEvent.ShowNewPhotoThumbnail -> showNewPhotoThumbnail(event.file)
        }
    }

    /**
     * キャッシュから画像を読み込み、クロスフェードで表示する
     */
    private fun loadImage(file: File, isFirstImageInLoop: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!file.exists()) return@launch
                val bitmap: Bitmap = Glide.with(this@MainActivity).asBitmap().load(file).submit().get()

                withContext(Dispatchers.Main) {
                    val activeImageView = if (isImageView1Active) binding.slideshowImageView1 else binding.slideshowImageView2
                    val inactiveImageView = if (isImageView1Active) binding.slideshowImageView2 else binding.slideshowImageView1
                    val activeBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView1 else binding.backgroundImageView2
                    val inactiveBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView2 else binding.backgroundImageView1

                    inactiveImageView.setImageBitmap(bitmap)
                    Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(inactiveBackgroundView)

                    // ★修正箇所: アニメーションを実行するかどうかの判定ロジックを変更
                    // ループの最初の画像であっても、初回読み込み完了後であればアニメーションを実行する
                    if (!isFirstImageInLoop || isFirstLoadComplete) {
                        // クロスフェードアニメーションを実行
                        activeImageView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                        inactiveImageView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction {
                            isImageView1Active = !isImageView1Active
                        }.start()

                        activeBackgroundView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                        inactiveBackgroundView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction {
                            isBackgroundView1Active = !isBackgroundView1Active
                        }.start()
                    } else {
                        // このブロックはアプリ起動後の本当に最初の画像表示の時だけ実行される
                        activeImageView.setImageBitmap(bitmap)
                        Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(activeBackgroundView)
                        isFirstLoadComplete = true // 初回読み込み完了のフラグを立てる
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from cache: ${file.name}", e)
            }
        }
    }

    /**
     * 指定されたメッセージで、文字サイズの大きいSnackbarを表示する。
     */
    private fun showCustomSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.rootLayout, message, Snackbar.LENGTH_LONG)
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.textSize = SNACKBAR_TEXT_SIZE_SP
        snackbar.show()
    }

    /**
     * 新しい写真が追加された際のサムネイルを表示する。
     */
    private fun showNewPhotoThumbnail(file: File) {
        lifecycleScope.launch {
            try {
                if (!file.exists()) return@launch
                val bitmap: Bitmap = withContext(Dispatchers.IO) {
                    Glide.with(this@MainActivity).asBitmap().load(file).submit().get()
                }
                binding.thumbnailImageView.setImageBitmap(bitmap)
                binding.thumbnailCardView.alpha = 1f
                binding.thumbnailCardView.visibility = View.VISIBLE
                delay(THUMBNAIL_DISPLAY_DURATION_MS)
                binding.thumbnailCardView.animate().alpha(0f).setDuration(500).withEndAction {
                    binding.thumbnailCardView.visibility = View.GONE
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing thumbnail for ${file.name}", e)
            }
        }
    }
}
