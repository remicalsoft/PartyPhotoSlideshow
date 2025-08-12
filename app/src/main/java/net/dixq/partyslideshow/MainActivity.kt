package net.dixq.partyslideshow

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText // 追加
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.dropbox.core.DbxException
import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.android.Auth
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.DeletedMetadata
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderContinueErrorException
import com.dropbox.core.v2.files.ListFolderErrorException
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat // 追加
import com.google.zxing.MultiFormatWriter // 追加
import com.google.zxing.common.BitMatrix // 追加
import android.graphics.Color // 追加
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.*
import net.dixq.partyslideshow.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var dbxClient: DbxClientV2? = null
    private var imagePaths = Collections.synchronizedList(mutableListOf<String>())
    private var currentImageIndex = 0
    private var folderCursor: String? = null

    private var slideshowJob: Job? = null
    private var longpollJob: Job? = null

    private var isImageView1Active = true
    private var isBackgroundView1Active = true
    private var isFirstLoadComplete = false

    companion object {
        private const val TAG = "MainActivity"
        private const val SLIDESHOW_INTERVAL_MS = 5000L
        private const val THUMBNAIL_DISPLAY_DURATION_MS = 8000L
        private const val FADE_DURATION_MS = 600L
        private const val SNACKBAR_TEXT_SIZE_SP = 24f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullScreen()

        // QRコード関連のUI初期設定を追加
        binding.settingsButton.setOnClickListener { showUrlInputDialog() }
        binding.qrCodeImageView.setOnClickListener { binding.qrCodeImageView.visibility = View.GONE }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("app-state", MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("isFirstLaunch", true)

        if (isFirstLaunch && !isFinishing) { // isFinishingチェックを追加
            showFirstLaunchDialog()
        } else {
            handleAuth()
        }
    }

    override fun onPause() {
        super.onPause()
        stopAllJobs()
    }

    private fun showFirstLaunchDialog() {
        val folderPath = getString(R.string.dropbox_folder_path)
        val message = getString(R.string.welcome_dialog_message, folderPath)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.welcome_dialog_title))
            .setMessage(message)
            .setPositiveButton(R.string.ok) { dialog, _ -> // R.string.ok を使用
                getSharedPreferences("app-state", MODE_PRIVATE).edit().putBoolean("isFirstLaunch", false).apply()
                handleAuth()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupFullScreen() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    /**
     * 認証フローを管理する。
     * 1. 認証画面からの復帰をチェック
     * 2. 保存されたトークンをチェック
     * 3. どちらもなければ認証を開始
     */
    private fun handleAuth() {
        // 1. 認証画面からの復帰をチェック
        Auth.getDbxCredential()?.let { credential ->
            val newAccessToken = credential.accessToken
            getSharedPreferences("dropbox-prefs", MODE_PRIVATE).edit().putString("access-token", newAccessToken).apply()
            Log.d(TAG, "New access token stored from auth flow.")
            initializeAndSync(newAccessToken)
            return
        }

        // 2. 保存されたトークンをチェック
        val existingToken = getSharedPreferences("dropbox-prefs", MODE_PRIVATE).getString("access-token", null)
        if (existingToken != null) {
            Log.d(TAG, "Using existing stored access token.")
            initializeAndSync(existingToken)
            return
        }

        // 3. トークンがなければ認証を開始
        Log.d(TAG, "No token found, starting auth.")
        Auth.startOAuth2Authentication(this, getString(R.string.dropbox_app_key))
    }

    /**
     * Dropboxクライアントを初期化し、キャッシュ同期を開始する
     */
    private fun initializeAndSync(accessToken: String) {
        if (dbxClient == null) {
            Log.d(TAG, "Initializing Dropbox client and starting sync.")
            val requestConfig = DbxRequestConfig.newBuilder("PartyPhotoSlideshow").build()
            dbxClient = DbxClientV2(requestConfig, accessToken)
            lifecycleScope.launch { syncLocalCache() }
        }
    }

    /**
     * トークンの有効期限切れなどで再認証が必要な場合の処理
     */
    private fun handleReAuthentication() {
        // 古いトークンとクライアントをクリア
        getSharedPreferences("dropbox-prefs", MODE_PRIVATE).edit().remove("access-token").apply()
        dbxClient = null

        // ユーザーに通知し、認証フローを再開
        showCustomSnackbar("ログインが期限切れになりました。再度ログインしてください。")
        Auth.startOAuth2Authentication(this, getString(R.string.dropbox_app_key))
    }

    private suspend fun syncLocalCache() {
        withContext(Dispatchers.Main) {
            binding.progressLayout.visibility = View.VISIBLE
            binding.progressTextView.text = "ファイルリストを取得中..."
        }

        withContext(Dispatchers.IO) {
            try {
                val folderPath = getString(R.string.dropbox_folder_path)
                val remoteResult = dbxClient!!.files().listFolder(folderPath)
                folderCursor = remoteResult.cursor
                val remoteFiles = remoteResult.entries.mapNotNull { it as? FileMetadata }
                val remoteFileNames = remoteFiles.map { it.name }.toSet()

                val cacheDir = externalCacheDir ?: return@withContext
                val localFiles = cacheDir.listFiles { _, name -> name.endsWith(".jpg") } ?: emptyArray()
                val localFileNames = localFiles.map { it.name }.toSet()

                (localFileNames - remoteFileNames).forEach { fileName ->
                    File(cacheDir, fileName).delete()
                    withContext(Dispatchers.Main) { showCustomSnackbar("$fileName を削除しました") }
                }

                val filesToDownload = remoteFiles.filter { it.name !in localFileNames }
                filesToDownload.forEachIndexed { index, metadata ->
                    withContext(Dispatchers.Main) {
                        binding.progressTextView.text = "キャッシュを同期中... (${index + 1}/${filesToDownload.size})"
                    }
                    downloadFile(metadata)
                }

                val finalFileNames = remoteFiles.map { it.name }.sorted()
                synchronized(imagePaths) {
                    imagePaths.clear()
                    imagePaths.addAll(finalFileNames)
                }

                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    if (imagePaths.isNotEmpty()) {
                        showNextImage(isInitialImage = true)
                        startSlideshow()
                    }
                    startLongPollingForChanges()
                }
            } catch (e: InvalidAccessTokenException) {
                Log.e(TAG, "Invalid Access Token during sync.", e)
                withContext(Dispatchers.Main) { handleReAuthentication() }
            } catch (e: ListFolderErrorException) {
                // ★修正箇所: フォルダが見つからない場合のエラーを具体的に表示する
                Log.e(TAG, "ListFolderError: Folder not found or access error.", e)
                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    val folderPath = getString(R.string.dropbox_folder_path)
                    val errorMessage = if (e.errorValue.isPath && e.errorValue.pathValue.isNotFound) {
                        "フォルダが見つかりません:\n$folderPath"
                    } else {
                        "フォルダにアクセスできません:\n$folderPath"
                    }
                    showCustomSnackbar(errorMessage)
                }
            } catch (e: DbxException) {
                Log.e(TAG, "Generic DbxException during sync.", e)
                withContext(Dispatchers.Main) {
                    binding.progressLayout.visibility = View.GONE
                    showCustomSnackbar("キャッシュ同期エラー: ${e.message}")
                }
            }
        }
    }

    private suspend fun downloadFile(fileMetadata: FileMetadata) {
        val cacheDir = externalCacheDir ?: return
        val file = File(cacheDir, fileMetadata.name)
        try {
            FileOutputStream(file).use { dbxClient!!.files().download(fileMetadata.pathLower, fileMetadata.rev).download(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${fileMetadata.name}", e)
        }
    }

    private fun startLongPollingForChanges() {
        if (longpollJob?.isActive == true) return
        longpollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (folderCursor == null) {
                        syncLocalCache() // カーソルがない場合は再同期
                        continue
                    }
                    // dbxClientがnullでないことを確認
                    dbxClient?.let { client ->
                        if (client.files().listFolderLongpoll(folderCursor).changes) {
                            fetchLatestChanges()
                        }
                    } ?: run {
                        // dbxClientがnullの場合は認証がまだか、切れている可能性がある
                        Log.w(TAG, "DbxClient is null in longpoll. Attempting to re-handle auth.")
                        withContext(Dispatchers.Main) { handleAuth() } // 再度認証フローを試みる
                        delay(5000) // 少し待ってからリトライ
                    }
                } catch (e: InvalidAccessTokenException) {
                    withContext(Dispatchers.Main) { handleReAuthentication() }
                    break // ループを抜けて再認証を待つ
                } catch (e: DbxException) {
                    Log.e(TAG, "DbxException in longpoll, resetting cursor.", e)
                    folderCursor = null // カーソルをリセットして再同期を促す
                }  catch (e: Exception) { // その他の予期せぬエラー
                    Log.e(TAG, "Unexpected error in longpoll, resetting cursor.", e)
                    folderCursor = null
                    delay(10000) // 少し長めに待ってリトライ
                }
            }
        }
    }

    private suspend fun fetchLatestChanges() {
        try {
            // dbxClientがnullでないことを確認
            dbxClient?.let { client ->
                val result = client.files().listFolderContinue(folderCursor)
                folderCursor = result.cursor

                val allAddedMetadata = result.entries.filterIsInstance<FileMetadata>()
                val deletedMetadataList = result.entries.filterIsInstance<DeletedMetadata>()
                // ここではuploaderNameのパースは行わない想定
                val finalAddedMetadata = allAddedMetadata//.filter { parseUploaderName(it.name) != null }
                val suppressDeleteNotification = finalAddedMetadata.isNotEmpty()

                deletedMetadataList.forEach { metadata ->
                    val fileName = File(metadata.pathLower!!).name // pathLowerがnullでないことを想定
                    File(externalCacheDir, fileName).delete()
                    synchronized(imagePaths) { imagePaths.remove(fileName) }
                    if (!suppressDeleteNotification) {
                        withContext(Dispatchers.Main) { showCustomSnackbar("$fileName を削除しました") }
                    }
                }

                if (finalAddedMetadata.isNotEmpty()) {
                    val listWasEmpty = imagePaths.isEmpty()
                    finalAddedMetadata.forEach { downloadFile(it) }
                    val sortedNewNames = finalAddedMetadata.map { it.name }.sorted()
                    synchronized(imagePaths) {
                        val insertionPoint = if (listWasEmpty) 0 else currentImageIndex + 1
                        imagePaths.addAll(insertionPoint, sortedNewNames)
                    }
                    withContext(Dispatchers.Main) {
                        showNewPhotoNotification(sortedNewNames.last())
                        if (listWasEmpty) {
                            showNextImage(isInitialImage = true)
                            startSlideshow()
                        }
                    }
                }
                withContext(Dispatchers.Main) { updateCounter() }
            } ?: Log.w(TAG, "DbxClient is null in fetchLatestChanges. Skipping.")
        } catch (e: InvalidAccessTokenException) {
            withContext(Dispatchers.Main) { handleReAuthentication() }
        } catch (e: Exception) {
            when (e) {
                is ListFolderContinueErrorException -> if (e.errorValue.isReset) folderCursor = null
                is DbxException -> Log.e(TAG, "Error fetching latest changes", e)
                else -> Log.e(TAG, "Unexpected error fetching latest changes", e)
            }
        }
    }

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

    private fun stopAllJobs() {
        longpollJob?.cancel()
        slideshowJob?.cancel()
    }

    private fun showNextImage(isInitialImage: Boolean = false) {
        if (imagePaths.isEmpty()) return
        if (!isInitialImage) currentImageIndex = (currentImageIndex + 1) % imagePaths.size
        loadImageFromCache(imagePaths[currentImageIndex])
    }

    private fun loadImageFromCache(fileName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(externalCacheDir, fileName)
                if (!file.exists()) {
                    Log.w(TAG, "Image file not found in cache: $fileName. Attempting to re-sync.")
                    // ファイルが存在しない場合は、再度同期を試みるか、リストから削除するなどの対応が必要
                    // ここでは一旦ログ出力のみ
                    // 必要であれば syncLocalCache() を呼ぶか、imagePaths から該当要素を削除
                    synchronized(imagePaths) {
                        imagePaths.remove(fileName)
                    }
                    withContext(Dispatchers.Main) {
                        updateCounter() // UI更新
                        if (imagePaths.isEmpty()) {
                            // 画像がなくなった場合の処理
                        } else {
                           // showNextImage() // 次の画像を表示しようとすると無限ループの可能性あり
                        }
                    }
                    return@launch
                }
                val bitmap: Bitmap = Glide.with(this@MainActivity).asBitmap().load(file).submit().get()

                withContext(Dispatchers.Main) {
                    val activeImageView = if (isImageView1Active) binding.slideshowImageView1 else binding.slideshowImageView2
                    val inactiveImageView = if (isImageView1Active) binding.slideshowImageView2 else binding.slideshowImageView1
                    val activeBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView1 else binding.backgroundImageView2
                    val inactiveBackgroundView = if (isBackgroundView1Active) binding.backgroundImageView2 else binding.backgroundImageView1

                    inactiveImageView.setImageBitmap(bitmap)
                    Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(inactiveBackgroundView)

                    if (!isFirstLoadComplete) {
                        activeImageView.setImageBitmap(bitmap)
                        Glide.with(this@MainActivity).load(bitmap).apply(RequestOptions.bitmapTransform(BlurTransformation(25, 3))).into(activeBackgroundView)
                        isFirstLoadComplete = true
                    } else {
                        activeImageView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                        inactiveImageView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction { isImageView1Active = !isImageView1Active }.start()
                        activeBackgroundView.animate().alpha(0f).setDuration(FADE_DURATION_MS).start()
                        inactiveBackgroundView.animate().alpha(1f).setDuration(FADE_DURATION_MS).withEndAction { isBackgroundView1Active = !isBackgroundView1Active }.start()
                    }
                    updateCounter()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading image from cache: $fileName", e)
                // エラー発生時もファイルリストから削除するなどのフォールバックを検討
                synchronized(imagePaths) {
                    imagePaths.remove(fileName)
                }
                 withContext(Dispatchers.Main) {
                    updateCounter()
                }
            }
        }
    }

    private fun updateCounter() {
        if (imagePaths.isEmpty()) {
            binding.counterTextView.visibility = View.GONE
        } else {
            binding.counterTextView.visibility = View.VISIBLE
            binding.counterTextView.text = getString(R.string.image_counter_format, currentImageIndex + 1, imagePaths.size)
        }
    }

    private fun parseUploaderName(fileName: String): String? {
        val namePart = fileName.substringBeforeLast('.', fileName)
        // タイムスタンプ形式 "YYYYMMDD_HHMMSS" を除外するロジックを修正
        // 例: "名前 - 20230101_120000.jpg" のようなファイル名で "名前" のみを抽出
        val regexTimestamp = Regex(" - \\d{8}_\\d{6}$")
        val nameWithoutTimestamp = namePart.replace(regexTimestamp, "")

        // "名前 重光絵美子" のような形式で最後のスペース以降を名前とする
        val lastSpaceIndex = nameWithoutTimestamp.lastIndexOf(' ')
        if (lastSpaceIndex != -1 && lastSpaceIndex < nameWithoutTimestamp.length -1) {
            val potentialName = nameWithoutTimestamp.substring(lastSpaceIndex + 1).trim()
            if (potentialName.isNotEmpty() && !potentialName.matches(Regex("^\\d+$"))) { // 数字のみの名前は除外
                 return potentialName
            }
        }
        // "重光絵美子" のようにスペースなしで、かつタイムスタンプ形式でもない場合
        if (!nameWithoutTimestamp.contains(" ") && !nameWithoutTimestamp.matches(Regex("^\\d{8}_\\d{6}$"))) {
            if (nameWithoutTimestamp.isNotEmpty() && !nameWithoutTimestamp.matches(Regex("^\\d+$"))) {
                return nameWithoutTimestamp
            }
        }
        return null // 適切な名前が見つからない場合
    }

    private fun showCustomSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.rootLayout, message, Snackbar.LENGTH_LONG)
        val textView = snackbar.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
        textView.textSize = SNACKBAR_TEXT_SIZE_SP
        snackbar.show()
    }

    private fun showNewPhotoNotification(fileName: String) {
        val uploaderName = parseUploaderName(fileName)
        val message = uploaderName?.let { "$it さんが新しい画像を投稿しました！" } ?: getString(R.string.notification_new_photo_added)
        showCustomSnackbar(message)

        lifecycleScope.launch {
            try {
                val file = File(externalCacheDir, fileName)
                if (!file.exists()) return@launch
                val bitmap: Bitmap = withContext(Dispatchers.IO) { Glide.with(this@MainActivity).asBitmap().load(file).submit().get() }
                binding.thumbnailImageView.setImageBitmap(bitmap)
                binding.thumbnailCardView.alpha = 1f
                binding.thumbnailCardView.visibility = View.VISIBLE
                delay(THUMBNAIL_DISPLAY_DURATION_MS)
                binding.thumbnailCardView.animate().alpha(0f).setDuration(500).withEndAction { binding.thumbnailCardView.visibility = View.GONE }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing thumbnail for $fileName", e)
            }
        }
    }

    // QRコード関連の関数群
    private fun showUrlInputDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.url_input_hint)
            // EditTextに適切なパディングを設定 (例: 16dp)
            val paddingInDp = 16
            val scale = resources.displayMetrics.density
            val paddingInPx = (paddingInDp * scale + 0.5f).toInt()
            setPadding(paddingInPx, paddingInPx, paddingInPx, paddingInPx)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.enter_url_dialog_title))
            .setMessage(getString(R.string.enter_url_dialog_message))
            .setView(editText) // EditTextをダイアログにセット
            .setPositiveButton(R.string.ok) { dialog, _ ->
                val url = editText.text.toString()
                if (url.isNotBlank()) {
                    generateAndShowQrCode(url)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .show()
    }

    private fun generateAndShowQrCode(url: String) {
        try {
            val bitMatrix = MultiFormatWriter().encode(
                url,
                BarcodeFormat.QR_CODE,
                400, // QRコードの幅 (ピクセル) - 必要に応じて調整
                400  // QRコードの高さ (ピクセル) - 必要に応じて調整
            )
            val bitmap = createBitmapFromBitMatrix(bitMatrix)
            binding.qrCodeImageView.setImageBitmap(bitmap)
            binding.qrCodeImageView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code for URL: $url", e)
            showCustomSnackbar("QRコードの生成に失敗しました。")
        }
    }

    private fun createBitmapFromBitMatrix(bitMatrix: BitMatrix): Bitmap {
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565) // ARGB_8888の方が高品質
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
