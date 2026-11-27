package com.codetic.saoreo

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal object PairedBackup {
    private val magic = "SAOREO-CHAT-BACKUP\u0001".encodeToByteArray()
    private const val SALT_BYTES = 24

    fun write(output: OutputStream, password: CharArray, data: (DataOutputStream) -> Unit) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val header = magic + salt
        output.write(header)
        DataOutputStream(cipher(password, salt).newEncryptingStream(output, header).buffered()).use(data)
    }

    fun read(input: InputStream, password: CharArray, data: (DataInputStream) -> Unit) {
        val header = input.readNBytes(magic.size + SALT_BYTES)
        require(header.size == magic.size + SALT_BYTES && header.copyOf(magic.size).contentEquals(magic)) {
            "Unsupported SAOREO backup"
        }
        val salt = header.copyOfRange(magic.size, header.size)
        DataInputStream(cipher(password, salt).newDecryptingStream(input, header).buffered()).use(data)
    }

    private fun cipher(password: CharArray, salt: ByteArray): AesGcmHkdfStreaming {
        require(password.size in 12..128)
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                return AesGcmHkdfStreaming(key, "HmacSha256", 32, 256 * 1024, 0)
            } finally {
                key.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }
}
