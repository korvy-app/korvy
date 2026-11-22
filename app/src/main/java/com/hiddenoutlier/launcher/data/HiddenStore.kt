package com.hiddenoutlier.launcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class HideLevel { HIDDEN, LOCKED }

/** Per-app privacy choices. Stored encrypted with a key that never leaves the phone's keystore. */
data class Privacy(
    val hidden: Map<String, HideLevel> = emptyMap(),
    val neverSuggest: Set<String> = emptySet(),
)

class HiddenStore(context: Context) {
    private val file = File(context.filesDir, "privacy.bin")
    private val alias = "launcher-privacy"

    fun load(): Privacy {
        if (!file.exists()) return Privacy()
        return runCatching { parse(String(decrypt(file.readBytes()), Charsets.UTF_8)) }.getOrElse { Privacy() }
    }

    fun save(p: Privacy) {
        val text = buildString {
            p.hidden.forEach { (k, v) -> append("H\t").append(v.name).append('\t').append(k).append('\n') }
            p.neverSuggest.forEach { append("N\t-\t").append(it).append('\n') }
        }
        val tmp = File(file.parentFile, "privacy.bin.tmp")
        tmp.writeBytes(encrypt(text.toByteArray(Charsets.UTF_8)))
        tmp.renameTo(file)
    }

    private fun parse(text: String): Privacy {
        val hidden = HashMap<String, HideLevel>()
        val never = HashSet<String>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t', limit = 3)
            if (parts.size != 3) continue
            when (parts[0]) {
                "H" -> runCatching { HideLevel.valueOf(parts[1]) }.getOrNull()?.let { hidden[parts[2]] = it }
                "N" -> never += parts[2]
            }
        }
        return Privacy(hidden, never)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val ivLen = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
        return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }
}
