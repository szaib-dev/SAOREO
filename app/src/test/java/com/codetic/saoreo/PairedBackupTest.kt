package com.codetic.saoreo

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class PairedBackupTest {
    @Test fun verifiesEncryptedStreamAcrossSegmentsBeforeAcceptingItsEnd() {
        val password = "an example secure backup password".toCharArray()
        val data = ByteArray(410_000) { ((it / 29) % 229).toByte() }
        val archive = ByteArrayOutputStream().let { target ->
            PairedBackup.write(target, password) { it.write(data) }
            target.toByteArray()
        }
        fun restore(bytes: ByteArray, key: CharArray = password): ByteArray {
            var result = ByteArray(0)
            PairedBackup.read(ByteArrayInputStream(bytes), key) { input ->
                result = input.readBytes()
            }
            return result
        }
        assertArrayEquals(data, restore(archive))
        assertThrows(IOException::class.java) { restore(archive, "wrong backup password".toCharArray()) }
        assertThrows(IOException::class.java) { restore(archive.copyOf(archive.size - 1)) }
        archive[300_000] = (archive[300_000].toInt() xor 1).toByte()
        assertThrows(IOException::class.java) { restore(archive) }
    }
}
