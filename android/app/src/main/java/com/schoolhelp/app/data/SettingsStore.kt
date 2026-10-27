package com.schoolhelp.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.schoolhelp.app.core.ApiKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AiProvider { LOCAL, OPENROUTER }

data class AppSettings(
    val provider: AiProvider = AiProvider.LOCAL,
    val language: String = "en",
    val outputLanguage: String = "English",
    val context: String = "",
    val writingPauses: Boolean = false,
    val writingWpm: Int = 30,
    val explanations: Boolean = true,
    val modelId: String = "",
    val rate: Float = 1f,
    val tapGapMs: Long = 300L,
    val readySound: Boolean = true,
    val speechProvider: AiProvider = AiProvider.LOCAL,
    val speechModelId: String = "",
    val speechVoice: String = "",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("schoolhelp", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        provider = runCatching { AiProvider.valueOf(prefs.getString("provider", "LOCAL")!!) }.getOrDefault(AiProvider.LOCAL),
        language = prefs.getString("language", "en")!!.let { if (it == "pl") "pl" else "en" },
        writingPauses = prefs.getBoolean("writingPauses", false),
        writingWpm = prefs.getInt("writingWpm", 30).coerceIn(1,300),
        context = prefs.getString("answerContext", "")!!,
        outputLanguage = prefs.getString("outputLanguage", if (prefs.getString("language", "en") == "pl") "Polish" else "English")!!,
        explanations = prefs.getBoolean("explanations", true),
        modelId = prefs.getString("modelId", "")!!,
        rate = prefs.getFloat("rate", 1f).coerceIn(com.schoolhelp.app.core.SpeechSpeed.MIN, com.schoolhelp.app.core.SpeechSpeed.MAX),
        tapGapMs = prefs.getLong("tapGap", 300).coerceIn(150L, 700L),
        readySound = prefs.getBoolean("readySound", true),
        speechProvider = runCatching { AiProvider.valueOf(prefs.getString("speechProvider", "LOCAL")!!) }.getOrDefault(AiProvider.LOCAL),
        speechModelId = prefs.getString("speechModelId", "")!!,
        speechVoice = prefs.getString("speechVoice", "")!!,
    )

    fun save(settings: AppSettings) {
        prefs.edit().putBoolean("writingPauses",settings.writingPauses).putInt("writingWpm",settings.writingWpm).putString("provider", settings.provider.name)
            .putString("answerContext", settings.context).putString("outputLanguage", settings.outputLanguage).putString("language", settings.language).putBoolean("explanations", settings.explanations)
            .putString("modelId", settings.modelId).putFloat("rate", settings.rate)
            .putLong("tapGap", settings.tapGapMs).putBoolean("readySound", settings.readySound)
            .putString("speechProvider", settings.speechProvider.name).putString("speechModelId", settings.speechModelId)
            .putString("speechVoice", settings.speechVoice).apply()
    }

    fun hasApiKey() = prefs.contains("apiKey")

    fun apiKey(): String {
        val encrypted = prefs.getString("apiKey", null) ?: return ""
        val parts = encrypted.split(":")
        check(parts.size == 2) { "Please save your OpenRouter key again." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return ApiKey.normalize(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }

    fun saveApiKey(value: String) {
        if (value.isBlank()) { prefs.edit().remove("apiKey").apply(); return }
        val key = ApiKey.normalize(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("apiKey", "${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}").apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("schoolhelp.openrouter", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("schoolhelp.openrouter", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
