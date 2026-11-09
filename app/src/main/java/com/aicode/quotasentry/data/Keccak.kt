package com.aicode.quotasentry.data

/**
 * 纯 Kotlin 的 SHA3-512（Keccak-f[1600]）。
 *
 * 为什么不用 `MessageDigest("SHA3-512")`：Android 各版本/各厂商对 SHA-3 的支持不一致，
 * minSdk 26 上可能直接抛 NoSuchAlgorithmException；这里只有 ChatGPT 的 proof-of-work
 * 会用到，实现一份可单测（已知测试向量）的版本更稳。
 */
internal object Sha3 {

    private const val RATE_BYTES = 72 // SHA3-512：1600 - 2*512 = 576 bit
    private const val OUTPUT_BYTES = 64

    fun sha512(input: ByteArray): ByteArray {
        val state = LongArray(25)
        val rate = RATE_BYTES
        // 吸收完整块
        var offset = 0
        while (input.size - offset >= rate) {
            absorbBlock(state, input, offset)
            keccakF1600(state)
            offset += rate
        }
        // 末块 + SHA3 填充：0x06 ... 0x80
        val last = ByteArray(rate)
        val remaining = input.size - offset
        System.arraycopy(input, offset, last, 0, remaining)
        last[remaining] = 0x06
        last[rate - 1] = (last[rate - 1].toInt() or 0x80).toByte()
        absorbBlock(state, last, 0)
        keccakF1600(state)

        // 挤压
        val output = ByteArray(OUTPUT_BYTES)
        var written = 0
        var laneIndex = 0
        while (written < OUTPUT_BYTES) {
            val lane = state[laneIndex]
            for (byteIndex in 0 until 8) {
                if (written >= OUTPUT_BYTES) break
                output[written] = ((lane ushr (8 * byteIndex)) and 0xFFL).toByte()
                written++
            }
            laneIndex++
            if (laneIndex * 8 >= rate && written < OUTPUT_BYTES) {
                keccakF1600(state)
                laneIndex = 0
            }
        }
        return output
    }

    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0F]
        }
        return String(chars)
    }

    private fun absorbBlock(state: LongArray, source: ByteArray, offset: Int) {
        for (lane in 0 until RATE_BYTES / 8) {
            var value = 0L
            for (byteIndex in 0 until 8) {
                value = value or ((source[offset + lane * 8 + byteIndex].toLong() and 0xFFL) shl (8 * byteIndex))
            }
            state[lane] = state[lane] xor value
        }
    }

    // Keccak round constants。写成 ULong 再转 Long，避免 Kotlin 拒绝超过 Long.MAX 的十六进制字面量。
    private val ROUND_CONSTANTS = longArrayOf(
        0x0000000000000001uL.toLong(), 0x0000000000008082uL.toLong(),
        0x800000000000808AuL.toLong(), 0x8000000080008000uL.toLong(),
        0x000000000000808BuL.toLong(), 0x0000000080000001uL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008009uL.toLong(),
        0x000000000000008AuL.toLong(), 0x0000000000000088uL.toLong(),
        0x0000000080008009uL.toLong(), 0x000000008000000AuL.toLong(),
        0x000000008000808BuL.toLong(), 0x800000000000008BuL.toLong(),
        0x8000000000008089uL.toLong(), 0x8000000000008003uL.toLong(),
        0x8000000000008002uL.toLong(), 0x8000000000000080uL.toLong(),
        0x000000000000800AuL.toLong(), 0x800000008000000AuL.toLong(),
        0x8000000080008081uL.toLong(), 0x8000000000008080uL.toLong(),
        0x0000000080000001uL.toLong(), 0x8000000080008008uL.toLong()
    )

    /** 旋转偏移表 ROTATION[x][y]（Keccak 规范）。 */
    private val ROTATION = arrayOf(
        intArrayOf(0, 36, 3, 41, 18),
        intArrayOf(1, 44, 10, 45, 2),
        intArrayOf(62, 6, 43, 15, 61),
        intArrayOf(28, 55, 25, 21, 56),
        intArrayOf(27, 20, 39, 8, 14)
    )

    private fun keccakF1600(state: LongArray) {
        val column = LongArray(5)
        val delta = LongArray(5)
        val rotated = LongArray(25)
        for (round in 0 until 24) {
            // θ
            for (x in 0 until 5) {
                column[x] = state[x] xor state[x + 5] xor state[x + 10] xor state[x + 15] xor state[x + 20]
            }
            for (x in 0 until 5) {
                delta[x] = column[(x + 4) % 5] xor java.lang.Long.rotateLeft(column[(x + 1) % 5], 1)
            }
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    state[x + 5 * y] = state[x + 5 * y] xor delta[x]
                }
            }
            // ρ + π
            for (x in 0 until 5) {
                for (y in 0 until 5) {
                    val targetY = (2 * x + 3 * y) % 5
                    rotated[y + 5 * targetY] = java.lang.Long.rotateLeft(state[x + 5 * y], ROTATION[x][y])
                }
            }
            // χ
            for (y in 0 until 5) {
                for (x in 0 until 5) {
                    val current = rotated[x + 5 * y]
                    val notNext = rotated[(x + 1) % 5 + 5 * y].inv()
                    val nextNext = rotated[(x + 2) % 5 + 5 * y]
                    state[x + 5 * y] = current xor (notNext and nextNext)
                }
            }
            // ι
            state[0] = state[0] xor ROUND_CONSTANTS[round]
        }
    }
}
