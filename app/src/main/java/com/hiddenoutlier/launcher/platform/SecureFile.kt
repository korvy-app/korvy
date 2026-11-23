package com.hiddenoutlier.launcher.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A small text file encrypted with a key that never leaves the phone's keystore (AES-GCM). */
class SecureFile(context: Context, name: String, private val alias: String) {
    private val file = File(context.filesDir, name)

    fun read(): String? = if (!file.exists()) null else runCatching { String(decrypt(file.readBytes()), Charsets.UTF_8) }.getOrNull()

    fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(encrypt(text.toByteArray(Charsets.UTF_8)))
        tmp.renameTo(file)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return byteArrayOf(c.iv.size.toByte()) + c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val ivLen = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, ivLen))
        return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
    }
}
