package app.umuo.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.umuo.core.model.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** 备份关闭；数据库仅存连接描述，口令和 token 只以 Keystore 密钥加密后存储。 */
class SecureCredentials(context: Context) : CredentialStore {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "umuo.credentials.v1"
    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized override fun read(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        return try {
            val parts = stored.split(':')
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
                doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
            }
        } catch (_: Exception) { throw IllegalStateException("设备凭据已失效，请重新登录") }
    }
    @Synchronized override fun write(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val stored = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(key, stored).commit()) { "凭据保存失败" }
    }
    @Synchronized override fun remove(key: String) { check(preferences.edit().remove(key).commit()) }
}
