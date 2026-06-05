package com.xjtu.toolbox.shared.webvpn

interface WebVpnHostCodec {
    fun encodeHost(host: String): String
    fun decodeHost(encoded: String): String
}

class HexHostCodec : WebVpnHostCodec {
    override fun encodeHost(host: String): String =
        host.encodeToByteArray().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    override fun decodeHost(encoded: String): String {
        require(encoded.length % 2 == 0) { "encoded host length must be even" }
        val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return bytes.decodeToString()
    }
}

object XjtuWebVpnHostCodec : WebVpnHostCodec {
    private val key = "wrdvpnisthebest!".encodeToByteArray()
    private val iv = "wrdvpnisthebest!".encodeToByteArray()

    override fun encodeHost(host: String): String {
        return cfb128Crypt(host.encodeToByteArray(), encrypt = true)
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    }

    override fun decodeHost(encoded: String): String {
        require(encoded.length % 2 == 0) { "encoded host length must be even" }
        val bytes = encoded.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return cfb128Crypt(bytes, encrypt = false).decodeToString()
    }

    private fun cfb128Crypt(input: ByteArray, encrypt: Boolean): ByteArray {
        val aes = Aes128(key)
        val result = ByteArray(input.size)
        var feedback = iv.copyOf()
        var offset = 0
        while (offset < input.size) {
            val encryptedFeedback = aes.encryptBlock(feedback)
            val blockLen = minOf(16, input.size - offset)
            for (i in 0 until blockLen) {
                result[offset + i] = (input[offset + i].toInt() xor encryptedFeedback[i].toInt()).toByte()
            }
            if (offset + 16 <= input.size) {
                feedback = if (encrypt) {
                    result.copyOfRange(offset, offset + 16)
                } else {
                    input.copyOfRange(offset, offset + 16)
                }
            }
            offset += 16
        }
        return result
    }
}

class WebVpnUrlCodec(
    private val hostCodec: WebVpnHostCodec = XjtuWebVpnHostCodec,
    private val institution: String = "webvpn.xjtu.edu.cn",
    private val ivHexPrefix: String = "77726476706e69737468656265737421",
) {
    fun toVpnUrl(rawUrl: String): String {
        if (isVpnUrl(rawUrl)) return rawUrl
        val parsed = parseUrl(rawUrl) ?: return rawUrl
        val portPart = parsed.port?.let { "-$it" }.orEmpty()
        val encodedHost = hostCodec.encodeHost(parsed.host)
        val path = parsed.path.removePrefix("/")
        return "https://$institution/${parsed.scheme}$portPart/$ivHexPrefix$encodedHost/$path"
    }

    fun fromVpnUrl(vpnUrl: String): String? {
        if (!isVpnUrl(vpnUrl)) return null
        val path = vpnUrl
            .removePrefix("https://$institution/")
            .removePrefix("http://$institution/")
        if (path.isBlank()) return null
        val parts = path.split("/", limit = 3)
        if (parts.size < 2) return null

        val protocolPort = parts[0]
        val hexPart = parts[1]
        if (!hexPart.startsWith(ivHexPrefix) || hexPart.length <= ivHexPrefix.length) return null

        val scheme = protocolPort.substringBefore("-")
        val port = protocolPort.substringAfter("-", missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { ":$it" }
            .orEmpty()
        val host = hostCodec.decodeHost(hexPart.removePrefix(ivHexPrefix))
        val restPath = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { "/$it" }.orEmpty()
        return "$scheme://$host$port$restPath"
    }

    fun isVpnUrl(url: String): Boolean =
        url.startsWith("https://$institution") || url.startsWith("http://$institution")

    private fun parseUrl(url: String): ParsedUrl? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isBlank()) return null
        val rest = url.substringAfter("://")
        val hostPort = rest.substringBefore("/")
        val path = rest.substringAfter(hostPort, missingDelimiterValue = "")
        val host = hostPort.substringBefore(":")
        val port = hostPort.substringAfter(":", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        if (host.isBlank()) return null
        return ParsedUrl(scheme = scheme, host = host, port = port, path = path)
    }

    private data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: String?,
        val path: String,
    )
}

private class Aes128(key: ByteArray) {
    private val expandedKey = expandKey(key)

    init {
        require(key.size == 16) { "AES-128 key must be 16 bytes" }
    }

    fun encryptBlock(block: ByteArray): ByteArray {
        require(block.size == 16) { "AES block must be 16 bytes" }
        val state = block.copyOf()
        addRoundKey(state, 0)
        for (round in 1..9) {
            subBytes(state)
            shiftRows(state)
            mixColumns(state)
            addRoundKey(state, round)
        }
        subBytes(state)
        shiftRows(state)
        addRoundKey(state, 10)
        return state
    }

    private fun addRoundKey(state: ByteArray, round: Int) {
        val start = round * 16
        for (i in 0 until 16) {
            state[i] = (state[i].toInt() xor expandedKey[start + i].toInt()).toByte()
        }
    }

    private fun subBytes(state: ByteArray) {
        for (i in state.indices) {
            state[i] = SBOX[state[i].toInt() and 0xff].toByte()
        }
    }

    private fun shiftRows(state: ByteArray) {
        val copy = state.copyOf()
        state[1] = copy[5]
        state[5] = copy[9]
        state[9] = copy[13]
        state[13] = copy[1]

        state[2] = copy[10]
        state[6] = copy[14]
        state[10] = copy[2]
        state[14] = copy[6]

        state[3] = copy[15]
        state[7] = copy[3]
        state[11] = copy[7]
        state[15] = copy[11]
    }

    private fun mixColumns(state: ByteArray) {
        for (col in 0 until 4) {
            val i = col * 4
            val a0 = state[i].toInt() and 0xff
            val a1 = state[i + 1].toInt() and 0xff
            val a2 = state[i + 2].toInt() and 0xff
            val a3 = state[i + 3].toInt() and 0xff
            state[i] = (gmul2(a0) xor gmul3(a1) xor a2 xor a3).toByte()
            state[i + 1] = (a0 xor gmul2(a1) xor gmul3(a2) xor a3).toByte()
            state[i + 2] = (a0 xor a1 xor gmul2(a2) xor gmul3(a3)).toByte()
            state[i + 3] = (gmul3(a0) xor a1 xor a2 xor gmul2(a3)).toByte()
        }
    }

    private fun gmul2(value: Int): Int {
        val shifted = value shl 1
        return if ((value and 0x80) != 0) (shifted xor 0x1b) and 0xff else shifted and 0xff
    }

    private fun gmul3(value: Int): Int = gmul2(value) xor value

    private companion object {
        private val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36)

        private val SBOX = intArrayOf(
            0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
            0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
            0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
            0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
            0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
            0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
            0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
            0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
            0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
            0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
            0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
            0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
            0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
            0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
            0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
            0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16,
        )

        private fun expandKey(key: ByteArray): ByteArray {
            val expanded = ByteArray(176)
            key.copyInto(expanded)
            var bytesGenerated = 16
            var rconIndex = 0
            val temp = ByteArray(4)
            while (bytesGenerated < expanded.size) {
                for (i in 0 until 4) temp[i] = expanded[bytesGenerated - 4 + i]
                if (bytesGenerated % 16 == 0) {
                    val first = temp[0]
                    temp[0] = SBOX[temp[1].toInt() and 0xff].toByte()
                    temp[1] = SBOX[temp[2].toInt() and 0xff].toByte()
                    temp[2] = SBOX[temp[3].toInt() and 0xff].toByte()
                    temp[3] = SBOX[first.toInt() and 0xff].toByte()
                    temp[0] = (temp[0].toInt() xor RCON[rconIndex++]).toByte()
                }
                for (i in 0 until 4) {
                    expanded[bytesGenerated] = (expanded[bytesGenerated - 16].toInt() xor temp[i].toInt()).toByte()
                    bytesGenerated++
                }
            }
            return expanded
        }
    }
}
