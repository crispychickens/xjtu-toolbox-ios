package com.xjtu.toolbox.shared.webvpn

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class XjtuWebVpnHostCodecJvmReferenceTest {
    @Test
    fun sharedCodecMatchesJvmAesReference() {
        val host = "rg.lib.xjtu.edu.cn"

        val expected = jvmReferenceEncode(host)

        assertEquals(expected, XjtuWebVpnHostCodec.encodeHost(host))
        assertEquals(host, XjtuWebVpnHostCodec.decodeHost(expected))
    }

    private fun jvmReferenceEncode(host: String): String {
        val encrypted = cfb128Encrypt(host.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }

    private fun cfb128Encrypt(plaintext: ByteArray): ByteArray {
        val key = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
        val iv = "wrdvpnisthebest!".toByteArray(Charsets.UTF_8)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

        val result = ByteArray(plaintext.size)
        var feedback = iv.copyOf()
        var offset = 0

        while (offset < plaintext.size) {
            val encryptedFeedback = ecb.doFinal(feedback)
            val blockLen = minOf(16, plaintext.size - offset)
            for (i in 0 until blockLen) {
                result[offset + i] = (plaintext[offset + i].toInt() xor encryptedFeedback[i].toInt()).toByte()
            }
            if (offset + 16 <= result.size) {
                feedback = result.copyOfRange(offset, offset + 16)
            }
            offset += 16
        }
        return result
    }
}
