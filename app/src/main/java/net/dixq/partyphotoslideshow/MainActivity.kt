package net.dixq.partyphotoslideshow

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DeletedMetadata
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderContinueErrorException
import com.google.android.material.snackbar.Snackbar
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.*
import net.dixq.partyphotoslideshow.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class MainActivity : AppCompatActivity() {

    // --- ビューバインディングとクラス変数 ---
    private lateinit var binding: ActivityMainBinding
    private var dbxClient: DbxClientV2? = null // Dropbox APIクライアント
    private var imagePaths = Collections.synchronizedList(mutableListOf<String>()) // 表示する画像のファイル名リスト（スレッドセーフ）
    private var currentImageIndex = 0 // 現在表示している画像のインデックス
    private var folderCursor: String? = null // Dropboxフォルダの変更を追跡するためのカーソル

    // --- ジョブ管理 ---
    private var slideshowJob: Job? = null // スライドショージョブ
    private var longpollJob: Job? = null // Dropboxの変更を監視するジョブ

    // --- クロスフェード用フラグ ---
    private var isImageView1Active = true // メイン画像のImageView1が現在表示中か
    private var isBackgroundView1Active = true // 背景画像のImageView1が現在表示中か

    // --- 定数 ---
    companion object {
        private const val TAG = "MainActivity" // ログ用タグ
        private const val SLIDESHOW_INTERVAL_MS = 5000L // スライドショーの切り替え間隔
        private const val THUMBNAIL_DISPLAY_DURATION_MS = 8000L // 新着サムネイルの表示時間
        private const val FADE_DURATION_MS = 600L // クロスフェードアニメーションの時間
        private const val SNACKBAR_TEXT_SIZE_SP = 24f // Snackbarの文字サイズ
    }

    // --- ライフサイクルメソッド ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // 全画面表示（ステータスバーやナビゲーションバーを隠す）
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onResume() {
        super.onResume()
        // Dropboxの認証トークンを確認
        val storedToken = getAndStoreAccessToken()
        if (storedToken == null) {
            // トークンがなければ認証画面を開始
            Auth.startOAuth2Authentication(this, getString(R.string.dropbox_app_key))
            return
        }
        // Dropboxクライアントを初期化
        if (dbxClient == null) {
            val requestConfig = DbxRequestConfig.newBuilder("PartyPhotoSlideshow").build()
            dbxClient = DbxClientV2(requestConfig, storedToken)
            Log.d(TAG, "Dropbox client initialized.")
        }
        // アプリ起動時のキャッシュ同期処理を開始
        lifecycleScope.launch {
            syncLocalCache()
        }
    }

    override fun onPause() {
        super.onPause()
        // アプリが非表示になったら、すべてのバックグラウンド処理を停止
        stopPollingAndSlideshow()
    }

    // --- Dropbox認証 ---
    /**
     * SharedPreferencesからアクセストークンを取得。
     * 認証画面からのリダイレクトであれば、新しいトークンを保存する。
     */
    private fun getAndStoreAccessToken(): String? {
        val prefs = getSharedPreferences("dropbox-prefs", MODE_PRIVATE)
        var accessToken = prefs.getString("access-token", null)
        Auth.getDbxCredential()?.let {
            accessToken = it.accessToken
            prefs.edit().putString("access-token", accessToken).apply()
            Log.d(TAG, "New access token stored.")
        }
        return accessToken
    }

    // --- キャッシュ同期 ---
    /**
     * 起動時にローカルキャッシュとDropbox上のファイルを比較し、同期する。
     */
    private suspend fun syncLocalCache() {
        withContext(Dispatchers.Main) {
            // 進捗表示UIを表示
            binding.progressLayout.visibility = View.VISIBLE
            binding.progressTextView.text = "ファイルリストを取得中..."
        }

        withContext(Dispatchers.IO) {
            try {
                // Dropbox上の全ファイルリストを取得
                val folderPath = getString(R.string.dropbox_folder_path)
                val remoteResult = dbxClient!!.files().listFolder(folderPath)
                folderCursor = remoteResult.cursor // 変更監視のためのカーソルを保存
                val remoteFiles = remoteResult.entries
                    .mapNotNull { it as? FileMetadata }
                    .filterNot { it.name.matches(Regex(".* \\(\\d+\\)\\.jpg$")) } // 重複ファイル(...(1).jpgなど)を無視

                val remoteFileNames = remoteFiles.map { it.name }.toSet()

                // ローカルキャッシュのファイルリストを取得
                val cacheDir = externalCacheDir ?: return@withContext
                val localFiles = cacheDir.listFiles { _, name -> name.endsWith(".jpg") } ?: emptyArray()
                val localFileNames = localFiles.map { it.name }.toSet()

                // 1. ローカルにのみ存在するファイル（Dropboxから削除されたファイル）をキャッシュから削除
                val filesToDelete = localFileNames - remoteFileNames
                filesToDelete.forEach { fileName ->
                    File(cacheDir, fileName).delete()
                    Log.d(TAG, "Cache deleted: $fileName")
                    withContext(Dispatchers.Main) {
                        showCustomSnackbar("$fileName を削除しました")
                    }
                }

                // 2. Dropboxにのみ存在するファイル（新しいファイル）をダウンロード
                val filesToDownload = remoteFiles.filter { it.name !in localFileNames }
                filesToDownload.forEachIndexed { index, fileMetadata ->
                    withContext(Dispatchers.Main) {
                        binding.progressTextView.text = "キャッシュを同期中... (${index + 1}/${filesToDownload.size})"
                    }
                    downloadFile(fileMetadata)
                }

                // スライドショーで使うファイル名リストを更新
                val finalFileNames = remoteFiles.map { it.name }.sorted()
                synchronized(imagePaths) {
                    imagePaths.clear()
                    imagePaths.addAll(finalFileNames)
                }

                // 同期完了後、UIを更新してスライドショーとリアルタイム監視を開始
                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    if (imagePaths.isNotEmpty()) {
                        showNextImage(isInitialImage = true)
                        startSlideshow()
                    }
                    startLongPollingForChanges()
                }

            } catch (e: DbxException) {
                Log.e(TAG, "Error during cache sync", e)
                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    showCustomSnackbar("キャッシュ同期エラー: ${e.message}")
                }
            }
        }
    }

    /**
     * 指定されたファイルをDropboxからダウンロードしてキャッシュに保存する。
     */
    private suspend fun downloadFile(fileMetadata: FileMetadata) {
        val cacheDir = externalCacheDir ?: return
        val file = File(cacheDir, fileMetadata.name)
        try {
            FileOutputStream(file).use { outputStream ->
                dbxClient!!.files().download(fileMetadata.pathLower, fileMetadata.rev)
                    .download(outputStream)
            }
            Log.d(TAG, "Cache downloaded: ${fileMetadata.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${fileMetadata.name}", e)
        }
    }

    // --- リアルタイム監視 (Long polling) ---
    /**
     * Dropboxフォルダの変更をリアルタイムで監視する処理を開始する。
     */
    private fun startLongPollingForChanges() {
        if (longpollJob?.isActive == true) return
        longpollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (folderCursor == null) {
                        Log.w(TAG, "Cursor is null, cannot longpoll. Retrying sync.")
                        syncLocalCache() // カーソルがない場合、同期からやり直す
                        continue
                    }
                    // 変更があるまで待機
                    val longpollResult = dbxClient!!.files().listFolderLongpoll(folderCursor, 60L)
                    if (longpollResult.changes) {
                        // 変更があれば差分を取得
                        fetchLatestChanges()
                    }
                } catch (e: DbxException) {
                    Log.e(TAG, "Error during longpoll, resetting.", e)
                    folderCursor = null // エラー時はカーソルをリセット
                }
            }
        }
    }

    /**
     * 変更があった場合に、その差分（追加・削除）を取得して処理する。
     */
    private suspend fun fetchLatestChanges() {
        try {
            val result = dbxClient!!.files().listFolderContinue(folderCursor)
            folderCursor = result.cursor // 次の監視のためにカーソルを更新

            val allAddedMetadata = result.entries
                .filterIsInstance<FileMetadata>()
                .filterNot { it.name.matches(Regex(".* \\(\\d+\\)\\.jpg$")) } // 重複ファイルを無視

            val deletedMetadataList = result.entries.filterIsInstance<DeletedMetadata>()

            // Dropboxのファイルリクエストでは、一時ファイルが作られ、リネームされることがある。
            // 最終的なファイル名（タイムスタンプで始まる）のみを「本当の追加」として扱う。
            val finalAddedMetadata = allAddedMetadata.filter { it.name.matches(Regex("^\\d{8}_\\d{6}.*")) }

            // ファイル追加と削除が同時に発生した場合（＝リネーム処理）、削除通知を抑制する。
            val suppressDeleteNotification = finalAddedMetadata.isNotEmpty()

            // 削除処理
            deletedMetadataList.forEach { metadata ->
                val fileName = File(metadata.pathLower).name
                File(externalCacheDir, fileName).delete()
                synchronized(imagePaths) {
                    imagePaths.remove(fileName)
                }
                Log.d(TAG, "Dynamic cache deleted: $fileName")

                if (!suppressDeleteNotification) {
                    withContext(Dispatchers.Main) {
                        showCustomSnackbar("$fileName を削除しました")
                    }
                }
            }

            // 追加処理 (一時ファイルも含むすべての追加ファイルをダウンロードし、リストに反映)
            allAddedMetadata.forEach { metadata ->
                downloadFile(metadata)
                synchronized(imagePaths) {
                    if (!imagePaths.contains(metadata.name)) {
                        imagePaths.add(metadata.name)
                    }
                }
            }

            // 「本当の追加」があった場合のみ、通知とUI更新を行う
            if (finalAddedMetadata.isNotEmpty()) {
                val listWasEmpty = imagePaths.size == finalAddedMetadata.size

                // リストをソート
                synchronized(imagePaths) {
                    imagePaths.sort()
                }

                withContext(Dispatchers.Main) {
                    // 追加された最後のファイルについて通知を表示
                    showNewPhotoNotification(finalAddedMetadata.last().name)
                    // スライドショーが止まっていれば再開
                    if (listWasEmpty) {
                        showNextImage(isInitialImage = true)
                        startSlideshow()
                    }
                }
            }

            withContext(Dispatchers.Main) { updateCounter() }

        } catch (e: ListFolderContinueErrorException) {
            if (e.errorValue.isReset) folderCursor = null
        } catch (e: DbxException) {
            Log.e(TAG, "Error fetching latest changes", e)
        }
    }


    // --- スライドショー制御 ---
    /**
     * スライドショーを開始する。
     */
    private fun startSlideshow() {
        if (slideshowJob?.isActive == true) return
        slideshowJob = lifecycleScope.launch {
            delay(SLIDESHOW_INTERVAL_MS)
            while (isActive) {
                showNextImage()
                delay(SLIDESHOW_INTERVAL_MS)
            }
        }
    }

    /**
     * すべてのバックグラウンド処理（監視とスライドショー）を停止する。
     */
    private fun stopPollingAndSlideshow() {
        longpollJob?.cancel()
        slideshowJob?.cancel()
    }

    /**
     * 次に表示する画像を決定し、読み込み処理を呼び出す。
     */
    private fun showNextImage(isInitialImage: Boolean = false) {
        if (imagePaths.isEmpty()) {
            // 表示する画像がなければ何もしない
            return
        }
        if (!isInitialImage) {
            // 初回表示でなければ、次の画像のインデックスに進める
            currentImageIndex = (currentImageIndex + 1) % imagePaths.size
        }
        val fileName = imagePaths[currentImageIndex]
        loadImageFromCache(fileName, isInitialImage)
    }

    /**
     * キャッシュから画像を読み込み、クロスフェードで表示する。
     */
    private fun loadImageFromCache(fileName: String, isInitialImage: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(externalCacheDir, fileName)
                if (!file.exists()) return@launch

                // Glideで画像をBitmapとしてプリロード
                val bitmap: Bitmap = Glide.with(this@MainActivity).asBitmap().load(file).submit().get()

                withContext(Dispatchers.Main) {
                    // 現在表示中/非表示中のImageViewを特定
                    val activeImageView = if (isImageView1Active) binding.slideshowImageView1 else binding.slideshowImageView2
                    val inactiveImageView = if (isImageView1Active) binding.slideshowImageView2 else binding.slideshowImageView1
                    val activeBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView1 else binding.backgroundImageView2
                    val inactiveBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView2 else binding.backgroundImageView1

                    // 非表示側のImageViewに次の画像をセット
                    inactiveImageView.setImageBitmap(bitmap)
                    Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(inactiveBackgroundView)

                    if (isInitialImage) {
                        // 初回表示はアニメーションなし
                        activeImageView.setImageBitmap(bitmap)
                        Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(activeBackgroundView)
                        updateCounter()
                        return@withContext
                    }

                    // クロスフェードアニメーション
                    activeImageView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                    inactiveImageView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction {
                        isImageView1Active = !isImageView1Active
                        updateCounter() // アニメーション完了時にカウンターを更新
                    }.start()

                    activeBackgroundView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                    inactiveBackgroundView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction {
                        isBackgroundView1Active = !isBackgroundView1Active
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from cache: $fileName", e)
            }
        }
    }

    // --- UI更新ヘルパー ---
    /**
     * 画像カウンター (例: "3 / 10") の表示を更新する。
     */
    private fun updateCounter() {
        if (imagePaths.isEmpty()) {
            binding.counterTextView.visibility = View.GONE
        } else {
            binding.counterTextView.visibility = View.VISIBLE
            binding.counterTextView.text = getString(R.string.image_counter_format, currentImageIndex + 1, imagePaths.size)
        }
    }

    /**
     * ファイル名から投稿者名を抽出する。
     * "タイムスタンプ 名前.jpg" の形式に対応。
     * @return 抽出した名前。命名規則に合わない場合はnull。
     */
    private fun parseUploaderName(fileName: String): String? {
        // ファイル名が "YYYYMMDD_HHMMSS" のパターンで始まっているかチェック
        if (!fileName.matches(Regex("^\\d{8}_\\d{6}.*"))) {
            return null
        }

        val namePart = fileName.substringBeforeLast('.', fileName)
        val firstSpaceIndex = namePart.indexOf(' ')

        if (firstSpaceIndex != -1 && firstSpaceIndex < namePart.length - 1) {
            return namePart.substring(firstSpaceIndex + 1).trim()
        }

        return null
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
     * 新しい写真が追加された際の通知（Snackbarとサムネイル）を表示する。
     */
    private fun showNewPhotoNotification(fileName: String) {
        val uploaderName = parseUploaderName(fileName)
        val message = if (uploaderName != null) {
            "$uploaderName さんが新しい画像を投稿しました！"
        } else {
            getString(R.string.notification_new_photo_added)
        }
        showCustomSnackbar(message)

        lifecycleScope.launch {
            try {
                val file = File(externalCacheDir, fileName)
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
                Log.e(TAG, "Error showing thumbnail for $fileName", e)
            }
        }
    }
}
