package com.trevisolli.agendawidget.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chaves, tokens e senhas cifrados com uma chave AES do Android Keystore (não sai do aparelho).
 * Fica nas mesmas preferências, como "enc:<nome>"; o valor antigo em texto puro é migrado e apagado na 1ª leitura.
 * Se a chave não existir (ex.: backup restaurado em outro celular), o valor volta vazio e a pessoa conecta de novo.
 */
object Secrets {
    private const val ALIAS = "consigliere_secrets"
    private const val PREFIX = "enc:"
    /** Decifrar no Keystore custa alguns ms; os valores lidos ficam em memória. */
    private val cache = HashMap<String, String>()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(c.iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    @Synchronized
    fun get(prefs: SharedPreferences, name: String): String {
        cache[name]?.let { return it }
        prefs.getString(PREFIX + name, null)?.let { enc ->
            // Só guarda em memória o que decifrou (falha do Keystore tenta de novo na próxima leitura).
            return runCatching { decrypt(enc) }.onSuccess { cache[name] = it }.getOrDefault("")
        }
        // Migração: valor antigo em texto puro → cifrado.
        val plain = prefs.getString(name, null) ?: return ""
        if (plain.isNotEmpty()) runCatching { put(prefs, name, plain) }
        return plain
    }

    @Synchronized
    fun put(prefs: SharedPreferences, name: String, value: String) {
        cache[name] = value
        val e = prefs.edit().remove(name)
        if (value.isEmpty()) e.remove(PREFIX + name) else e.putString(PREFIX + name, encrypt(value))
        e.apply()
    }
}
