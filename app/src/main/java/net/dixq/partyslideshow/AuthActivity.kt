package net.dixq.partyslideshow

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dropbox.core.android.Auth

class AuthActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // レイアウトファイルは現時点では空で良いので、setContentViewは一旦コメントアウトしておく
        // setContentView(R.layout.activity_auth)

        prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        if (!isDropboxLinked()) {
            showWelcomeDialog()
        } else {
            navigateToMainActivity()
        }
    }

    private fun isDropboxLinked(): Boolean {
        // MainActivityから認証状態を確認するロジックを移管・参考にする
        // ここでは仮にSharedPreferencesに認証トークンが保存されているかで判定
        val accessToken = prefs.getString("dropbox-token", null)
        return accessToken != null
    }

    private fun showWelcomeDialog() {
        val dropboxPath = getString(R.string.dropbox_folder_path)
        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_dialog_title)
            .setMessage(getString(R.string.welcome_dialog_message, dropboxPath))
            .setPositiveButton(R.string.ok) { dialog, _ ->
                // Dropbox認証を開始
                // MainActivityの認証処理を参考に実装する
                // Auth.startOAuth2PKCE(this, getString(R.string.dropbox_app_key), DbxRequestConfig("party-photo-slideshow/1.0"), listOf())
                startDropboxAuthorization()
                dialog.dismiss()
            }
            .setCancelable(false) // ユーザーがダイアログをキャンセルできないようにする
            .show()
    }

    private fun startDropboxAuthorization() {
        //  MainActivityの mDropboxAppKey や mDBXRequestConfig を参照・移管する
        //  ここでは仮の値を設定。実際のアプリキーと設定を使用する必要がある。
        //  getString(R.string.dropbox_app_key) は strings.xml に定義されている想定
        Auth.startOAuth2PKCE(this, getString(R.string.dropbox_app_key), com.dropbox.core.DbxRequestConfig("party-photo-slideshow/1.0"), listOf())
    }


    override fun onResume() {
        super.onResume()
        // Dropbox認証後の処理
        // MainActivityの onResume 内の認証処理を参考に実装する
        val accessToken = Auth.getOAuth2Token() // こちらはcom.dropbox.core.android.Authのメソッド
        if (accessToken != null) {
            prefs.edit().putString("dropbox-token", accessToken).apply()
            navigateToMainActivity()
        }
    }


    private fun navigateToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
