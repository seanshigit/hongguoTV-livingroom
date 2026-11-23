// SPDX-License-Identifier: GPL-3.0-only
// V5 playback signing adapted from N3urda/hongguoTV:
// 18c37c2afd0567520135abcae312352542f09083 (Signer.kt, AesV3.kt, V5Constants.kt).
// Empty-body GET behavior follows jackotom/hongguo-drama 92df757ed7be8ea658ee274a6a429b77f49e54e8,
// also integrated by N3urda/hongguoTV 1782dda (MobilePlayback.videoTabRequest).
// Public protocol constants only; no captured account credentials or native SDK.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Playback-only V5 signer. Catalog and account signing retain their existing protocol. */
internal class PlaybackV5Signer(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { entropy.nextDouble() },
    private val processNonce: String = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(18).also(entropy::nextBytes)),
    private val processStarted: Long = clock(),
) {
    private fun rand() = (random() * 4294967296.0).toLong().toInt()

    private fun unsigned(v: Int) = v.toLong() and 0xffffffffL

    private fun varint(value: Long): ByteArray {
        var n = value
        return ByteArrayOutputStream()
            .apply {
                while (n > 127) {
                    write(((n and 127) or 128).toInt())
                    n = n ushr 7
                }
                write(n.toInt())
            }
            .toByteArray()
    }

    private data class Field(val tag: Int, val value: Any, val type: String = "string")

    private fun f(tag: Int, value: Any, type: String = "string") = Field(tag, value, type)

    private fun proto(vararg fields: Field): ByteArray =
        ByteArrayOutputStream()
            .apply {
                for ((tag, value, type) in fields) {
                    when (type) {
                        "sint" -> {
                            val n = (value as Number).toLong()
                            write(varint(tag.toLong() shl 3))
                            write(varint(if (n < 0) -n * 2 - 1 else n * 2))
                        }
                        "float" -> {
                            write(varint((tag.toLong() shl 3) or 5))
                            write(
                                ByteBuffer.allocate(4)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                    .putFloat((value as Number).toFloat())
                                    .array()
                            )
                        }
                        else -> {
                            val bytes =
                                if (value is ByteArray) value else value.toString().toByteArray()
                            if (bytes.isNotEmpty()) {
                                write(varint((tag.toLong() shl 3) or 2))
                                write(varint(bytes.size.toLong()))
                                write(bytes)
                            }
                        }
                    }
                }
            }
            .toByteArray()

    private fun getIv(initial: Int, data: ByteArray): Int {
        var value = initial
        for (i in data.indices) value =
            if (i and 1 == 0) (value ushr 4) xor value xor (value shl 6) xor data[i].u()
            else ((value ushr 7) xor value xor (data[i].u() or (value shl 12))).inv()
        return value
    }

    private fun iv(query: ByteArray, body: ByteArray, ts: Int) =
        getIv(getIv(getIv(0x20230928, query), body), le32(ts))

    internal fun branch(query: String, ts: Int): Int =
        iv(sm3(query.toByteArray(Charsets.UTF_8)), digest("MD5", ByteArray(0)), ts) and 15

    private fun sumMd5(data: ByteArray): Int {
        var check = 0x20220420
        for (i in 0 until 12) {
            val temp = (check ushr (if (i and 1 == 0) 3 else 5)) xor check
            check =
                if (i and 1 == 0) data[i].u() xor (check shl 7)
                else (data[i].u() or (check shl 11)).inv()
            check = check xor temp
        }
        return (check or 4) xor 0x1000000
    }

    internal fun hashF13(query: ByteArray, body: ByteArray, ts: Int): ByteArray {
        require(query.size == 32 && body.size == 16 && (iv(query, body, ts) and 15) == 12) { "Unsupported V5 hash input" }
        val ivV1 = 0x9d4a97c2.toInt()
        val count = ivV1 + ts
        val tt = VendorConstants.hashTable.map { ror(it, (count + 1) and 255) }
        val shift = (count + 2) and 7
        val pad =
            intArrayOf(0xfa, 0x45, 0x61, 0xd7)
                .map { ((it or (it shl 8)) shr shift).toByte() }
                .toByteArray()
        val data = query + body + le32(ts) + pad + hex("00000000000001a0")
        val di = (data.indices step 4).map { data.i32(it) }.toMutableList()
        for (i in 0 until 112) {
            val x = di[i + 1]
            val y = di[i + 14]
            di.add(
                di[i] +
                    di[i + 9] +
                    (rol(x, 14) xor rol(x, 25) xor (x ushr 3)) +
                    (rol(y, 13) xor rol(y, 15) xor (y ushr 10))
            )
        }
        val initial =
            intArrayOf(
                    0x7aba4fc8,
                    0x67166507,
                    0x6403fa00,
                    0x340f512f,
                    984304912,
                    3005047866L.toInt(),
                    2874125293L.toInt(),
                    2152413264L.toInt(),
                )
                .map { ror(it, count) }
        val chosen = intArrayOf(96, 1, 3, 4, 0, 6, 5, 2, 0, 7, 7)
        val d = initial.toMutableList()
        for (i in 0 until chosen[0]) {
            val base = ivV1 + i
            val n1 = ((d[chosen[3]] xor d[chosen[4]]) and d[chosen[1]]) xor d[chosen[3]]
            val n2 = rol(d[chosen[1]], 26) xor rol(d[chosen[1]], 21) xor rol(d[chosen[1]], 7)
            val n4 = di[base and 127] + n1 + n2 + tt[base and 63] + d[chosen[5]]
            val n5 = rol(d[chosen[2]], 30) xor rol(d[chosen[2]], 19) xor rol(d[chosen[2]], 10)
            val n6 =
                (d[chosen[2]] and d[chosen[6]]) or ((d[chosen[2]] or d[chosen[6]]) and d[chosen[7]])
            val old = d[chosen[9]]
            d.add(0, d.removeAt(7))
            d[chosen[10]] = n5 + n6 + n4
            d[chosen[8]] = old + n4
        }
        val ret = (0..7).fold(byteArrayOf()) { a, i -> a + be32(d[i] + initial[i]) }
        val folded = xor(ret.copyOfRange(0, 16), ret.copyOfRange(16, 32))
        return folded + le32(sumMd5(folded) or 0x20)
    }

    private fun xmxor(data: ByteArray, key: ByteArray): ByteArray {
        val encoded = IntArray(data.size)
        for (i in data.indices) {
            val at = (i * 4) and 28
            val d0 = key[at].u()
            val d1 = key[at + 1].u()
            val v = data[i].u()
            var d2 = (((v shl 4) or (v ushr 4)) and 255) + d0
            d2 = d2.inv() xor d1
            d2 = (((d2 and 255) shl 3) or ((d2 and 255) ushr 5)) and 255
            d2 = (d2 + d1) and 255
            d2 = (d2 xor d0) and 255
            encoded[data.size - i - 1] = d2.inv() and 255
        }
        val last = encoded.last() xor encoded[encoded.size - 2]
        val first = encoded[0]
        encoded[0] = (last.inv() + first) and 255
        encoded[1] = ((encoded[0] xor encoded.last() xor 254) + encoded[1]) and 255
        encoded[2] =
            (encoded[2] +
                ((last - first) xor
                    (((encoded[1] shl 3) or (encoded[1] ushr 5)) and 255) xor
                    2)) and 255
        for (i in 0 until encoded.size - 4) {
            val temp =
                (((encoded[i + 2] shl 3) or (encoded[i + 2] ushr 5)) and 255) xor
                    encoded[i + 1] xor
                    (i + 3)
            encoded[i + 3] = (temp.inv() + encoded[i + 3]) and 255
        }
        encoded[encoded.size - 1] = encoded.last() xor encoded[encoded.size - 2]
        encoded[0] = ((encoded[0] xor encoded[1]) + encoded.drop(1).sum()) and 255
        return encoded.map { it.toByte() }.toByteArray()
    }

    private fun medusa(url: String, bodyHash: ByteArray, ts: Int, who: Map<String, String>): String {
        val query = sm3(url.substringAfter('?').toByteArray(Charsets.UTF_8))
        val f13 = hashF13(query, bodyHash, ts)
        val flags = ByteArray(8)
        val version = who.getValue("version_name")
        val deviceInfo = proto(
            f(1, 1, "sint"), f(2, 2, "sint"), f(3, "8662"), f(4, who.getValue("device_id")),
            f(5, processNonce), f(6, "!noperm!"), f(12, "Asia/Shanghai,8"), f(13, "zh_CN"),
            f(22, who.getValue("os_version")), f(25, processStarted, "sint"),
            f(30, who.getValue("device_type")), f(31, "HONOR"),
            f(32, who.getValue("device_type")), f(34, "HONOR"),
        )
        val env = proto(f(6, "v04.10.03.04-bugfix"), f(8, who.getValue("channel")),
            f(12, deviceInfo), f(14, version))
        val message = proto(
            f(1, PlaybackV5Constants.magic), f(2, 5, "sint"), f(3, unsigned(rand()), "sint"),
            f(4, "8662"), f(5, who.getValue("device_id")), f(6, PlaybackV5Constants.license),
            f(7, version), f(8, "v04.10.03-ml-android"), f(9, 0x040a0300, "sint"),
            f(10, flags), f(12, ts, "sint"), f(13, f13), f(14, query.copyOf(6)),
            f(15, proto(f(1, 1, "sint"), f(2, 1, "sint"), f(3, 1, "sint"), f(5, 1, "sint"))),
            // Verified against App/SDK 7.4.5.32 in N3urda/hongguoTV#107.
            f(17, ts, "sint"), f(20, "none"), f(21, 312, "sint"), f(23, env),
            f(24, """{"cmr":16777216,"cmr2":16777216,"un_h":11,"vpn":0,"kd":0,"dyn":"","do":1,"tk":true}"""),
        )
        val nonce = rand()
        val key = PlaybackV5Constants.signKey
        val hash = sm3(key + le32(nonce) + key)
        val high = (nonce ushr 16) and 255
        val seed = le32((((high shl 11) or (nonce ushr 24)) xor (high ushr 5) xor high).inv())
        val transformed = (flags + xmxor(message, hash)).reversedArray()
        for (i in transformed.indices) transformed[i] =
            (transformed[i].u() xor seed[i.inv() and 3].u()).toByte()
        val check = 0x18000001 or ((query[0].u() and 63) shl 14) or ((f13[0].u() and 63) shl 8)
        val packed = byteArrayOf(0xca.toByte()) + le32(rand()) + le32(check) + transformed +
            byteArrayOf((nonce ushr 16).toByte(), (nonce ushr 24).toByte())
        val encrypted = PlaybackV5Carrier(digest("MD5", key.copyOfRange(0, 16)), ts)
            .encrypt(packed, digest("MD5", key.copyOfRange(16, 32)))
        val prefix = le32(5) + PlaybackV5Constants.magic
        for (i in prefix.indices) prefix[i] = (prefix[i].u() xor le32(ts)[i and 3].u()).toByte()
        return (prefix + byteArrayOf(nonce.toByte(), (nonce ushr 8).toByte(), 0, 1) + encrypted).base64()
    }

    private fun helios(ts: Int): String {
        val random = rand()
        val ascii = digest("MD5", le32(random) + "8662".toByteArray()).hex().toByteArray()
        val words = (0..3).map { ascii.i64(it * 8, true) }.toMutableList()
        val table = mutableListOf(words[0])
        var b0 = words.removeAt(0)
        var b8 = words.removeAt(0)
        for (i in 0 until 34) {
            var x8 = (java.lang.Long.rotateRight(b8, 8) + b0) xor i.toLong()
            words.add(x8)
            x8 = x8 xor java.lang.Long.rotateRight(b0, 61)
            table.add(x8)
            b0 = x8
            b8 = words.removeAt(0)
        }
        val license = PlaybackV5Constants.license
        val text = "$ts-$license-8662".toByteArray()
        val padding = 16 - text.size % 16
        val data = text + ByteArray(padding) { padding.toByte() }
        var output = le32(random)
        for (at in data.indices step 16) {
            var a = data.i64(at, true)
            var b = data.i64(at + 8, true)
            for (i in 0 until 34) {
                b = table[i] xor (a + java.lang.Long.rotateRight(b, 8))
                a = b xor java.lang.Long.rotateRight(a, 61)
            }
            output += le64(a) + le64(b)
        }
        return output.base64()
    }

    /** Sign the final query once, using MD5(empty bytes), and send no GET body or POST headers. */
    fun signGet(base: String, values: Map<String, String>, now: Long = clock()): Signer.Request =
        sign(base, values, null, now)

    /** The caller compresses once; these exact bytes are signed and returned for transmission. */
    fun signPost(base: String, values: Map<String, String>, body: ByteArray, now: Long = clock()): Signer.Request {
        val compressed = body.copyOf()
        require(compressed.size in 18..2 * 1024 * 1024) { "Invalid playback gzip body" }
        try {
            java.util.zip.GZIPInputStream(compressed.inputStream()).use { input ->
                val buffer = ByteArray(8192)
                var size = 0
                while (true) {
                    RequestScope.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= 2 * 1024 * 1024) { "Playback body is too large" }
                }
            }
        } catch (error: java.io.IOException) {
            RequestScope.ensureActive()
            throw IllegalArgumentException("Invalid playback gzip body", error)
        }
        return sign(base, values, compressed, now)
    }

    private fun sign(base: String, values: Map<String, String>, body: ByteArray?, now: Long): Signer.Request {
        val uri = runCatching { URI(base) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.port in listOf(-1, 443)) { "Invalid playback endpoint" }
        require(now >= 0 && now / 1000 <= Int.MAX_VALUE) { "Invalid playback timestamp" }
        val who = LinkedHashMap(values)
        require(who.size <= 128 && who.all { (key, value) ->
            key.isNotEmpty() && key.length <= 128 && value.length <= 8192 &&
                (key + value).none { it.code < 32 || it.code in 127..159 }
        }) { "Invalid playback parameters" }
        for (key in listOf("device_id", "iid")) {
            require(who[key]?.matches(Regex("[1-9][0-9]{0,29}")) == true) { "Invalid playback identity" }
        }
        for (key in listOf("cdid", "version_name", "channel", "os_version", "device_type")) {
            require(who[key]?.let { it.isNotBlank() && it.length <= 128 } == true) { "Incomplete playback profile" }
        }
        require(who["aid"] == null || who["aid"] == "8662") { "Invalid playback application" }
        val ts = (now / 1000).toInt()
        val bodyHash = digest("MD5", body ?: ByteArray(0))
        if ("ts" in who) who["ts"] = ts.toString()
        for (offset in 0 until 1024) {
            RequestScope.ensureActive()
            val query = Signer.encode(who + ("_rticket" to (now + offset).toString()))
            if ((iv(sm3(query.toByteArray(Charsets.UTF_8)), bodyHash, ts) and 15) != 12) continue
            val url = "$base?$query"
            val headers = linkedMapOf(
                "x-khronos" to ts.toString(),
                "x-helios" to helios(ts),
                "x-medusa" to medusa(url, bodyHash, ts, who),
            )
            if (body != null) headers["x-ss-stub"] = bodyHash.hex().uppercase()
            return Signer.Request(url, headers, body)
        }
        throw java.io.IOException("无法生成播放签名")
    }

    companion object {
        private val entropy = SecureRandom()
    }
}

/** V5 protocol carrier; intentionally separate from the existing V3 carrier. */
internal class PlaybackV5Carrier(key: ByteArray, timestamp: Int) {
    private val word = timestamp and 3
    private val box = PlaybackV5Constants.sbox.copyOfRange(word * 256, (word + 1) * 256).map { it.u() }
    private val con =
        arrayOf(
            intArrayOf(2, 0, 3, 1), intArrayOf(1, 3, 2, 0),
            intArrayOf(3, 2, 1, 0), intArrayOf(2, 3, 1, 0),
        )[word]
    private val order =
        arrayOf(
            intArrayOf(0,9,14,15,4,13,2,7,8,1,6,3,12,5,10,11),
            intArrayOf(0,9,14,11,4,13,2,3,8,1,6,15,12,5,10,7),
            intArrayOf(0,9,14,3,4,13,2,15,8,1,6,11,12,5,10,7),
            intArrayOf(0,9,14,15,4,13,2,3,8,1,6,11,12,5,10,7),
        )[word]
    private val keys: Array<IntArray>

    init {
        val initial =
            intArrayOf(0xbbb5de72.toInt(), 0xd4638d5b.toInt(),
                0x92a5f73b.toInt(), 0x89f8a1c9.toInt())[word]
        val expanded =
            (xor(le32(initial) + le32(initial) + le32(initial) + le32(initial), key) +
                    ByteArray(32))
                .map { it.u() }
                .toIntArray()
        var rounds = 8
        for (i in 4 until 12) {
            val at = 4 * (i - 1)
            var a = expanded[at]
            var b = expanded[at + 1]
            var c = expanded[at + 2]
            var d = expanded[at + 3]
            if (i and 3 == 0) {
                val t = ((initial shr (rounds and 24)) xor box[b]) and 255
                b = box[c]
                c = box[d]
                d = box[a]
                a = t
            }
            rounds += 2
            expanded[at + 4] = a xor expanded[at - 12]
            expanded[at + 5] = b xor expanded[at - 11]
            expanded[at + 6] = c xor expanded[at - 10]
            expanded[at + 7] = d xor expanded[at - 9]
        }
        keys = Array(12) { expanded.copyOfRange(it * 4, it * 4 + 4) }
    }

    private fun block(value: ByteArray): ByteArray {
        val rows = Array(4) { i -> IntArray(4) { j -> value[i * 4 + j].u() } }
        fun addCon(offset: Int) {
            for (i in 0..3) for (j in 0..3) rows[i][j] = rows[i][j] xor keys[offset + i][con[j]]
        }
        addCon(0)
        for (round in 1..2) {
            for (i in 0..3) for (j in 0..3) rows[i][j] = box[rows[i][j]]
            val before = rows.map { it.copyOf() }
            for (i in 0..3) rows[i] = before[con[i]].copyOf()
            val flat = rows.flatMap { it.toList() }
            for (i in 0..15) rows[i / 4][i % 4] = flat[order[i]]
            if (round == 1) {
                for (i in 0..3) {
                    val old = rows[i].copyOf()
                    rows[i] = IntArray(4) { old[con[it]] }
                }
                fun xt(x: Int) = ((x shl 1) xor (if (x and 128 != 0) 0x1b else 0)) and 255
                for (i in 0..3) {
                    val t = rows[0][i] xor rows[1][i] xor rows[2][i] xor rows[3][i]
                    val u = rows[0][i]
                    rows[0][i] = rows[0][i] xor t xor xt(rows[0][i] xor rows[1][i])
                    rows[1][i] = rows[1][i] xor t xor xt(rows[1][i] xor rows[2][i])
                    rows[2][i] = rows[2][i] xor t xor xt(rows[2][i] xor rows[3][i])
                    rows[3][i] = rows[3][i] xor t xor xt(rows[3][i] xor u)
                }
            }
            addCon(round * 4)
        }
        for (i in 0..3) for (j in 0..3) rows[i][j] = rows[i][j] xor keys[4 + i][j]
        return rows.flatMap { it.toList() }.map { it.toByte() }.toByteArray()
    }

    fun encrypt(source: ByteArray, iv: ByteArray): ByteArray {
        require(source.size >= 248)
        val plaintext = ByteArray(32)
        val wire = intArrayOf(6,5,3,4,2,7,1,0)
        val compact = intArrayOf(1,7,3,6,2,0,4,5)
        for (i in 0 until 31) for (j in 0..7) {
            plaintext[i] = (plaintext[i].u() or
                (((source[i * 8 + j].u() ushr wire[j]) and 1) shl compact[j])).toByte()
        }
        plaintext[31] = 1
        val first = block(xor(plaintext.copyOfRange(0, 16), iv))
        val key = first + block(xor(plaintext.copyOfRange(16, 32), first))
        val out = source.copyOf()
        for (i in 0 until 31) for (j in 0..7) {
            val at = i * 8 + j
            out[at] = ((out[at].u() and (1 shl wire[j]).inv()) or
                (((key[i].u() ushr compact[j]) and 1) shl wire[j])).toByte()
        }
        return byteArrayOf(key.last()) + out
    }
}

/** v04.10.03 wire constants; see SIGNING_V5.md for derivation and verification. */
private object PlaybackV5Constants {
    val magic = hex("2d4b4fca49750d433fb5ae2c226dcc56")
    const val license = "646637794"
    val signKey = hex("b23c2a470a20d105dc3f8c4438f8272ed46d6c89ffdcdedd5108698d26e9489d")
    val sbox = hex(
        "53290fe9e51f316f90f74a7e034d367784ab49237cd0135cdc632c322fd3be0e2624a417ecd9151a7ff64f60730a87f9445ec6ada2f865d22b696c4188f24c0d" +
        "f37b75510b939f6bc2fe6883e68bc8c722181ba0bdb08c9871b50c9a16b46eea8eed20ba4b8a14e0fcf1468dbbef05a93e5a9d7dcf06898635c1fd99f5de287a" +
        "80c594daaab19b6279fbd75070cb52eb2555c456e39e7839853874e2f0013ac0b64742d8ca10e4b79c955de8b843ce4ed464a6a72ed508091c92e7b3eedd1e1d" +
        "d12afac321618fa3d65f9167c982f43d66a53c6a1997585b346dbf9672ff37cdbc073b2dae11453304df00815476b2a13fccdb30b957a8acafe1274002485912" +
        "38922563e36406d37a24100b7902a028044e218453c2a399199c9d13dc40c55ba67e502273850ebdc1a2e2188b8f2d9e3cb909b8c46a2a9f4a6dd93007eafa65" +
        "b7b3a881aa908cc74f0ad76bbbb58617c37012ca05a469fd44e62eecf1e4272ce860c0ae5eadf99b20d8f0234174006f3f110d7657d01645d4eff8f71471cbfe" +
        "1cb2f52648727bf3c9de55153e031b4d61757db63dbf7782eeafe9018a8891362fb14759ac32bab03b349aeb5a377c5156be4c8dfbe13a684bda39cf836697c8" +
        "94545fe0f2491acea9fc6252f4ff1e0fe72bd2df6e67a7f635e5bcc6d1a5dd951d89960887a18eb40c785cdb1f80ed42299333ab98cdd67f316cd54346585dcc" +
        "d759fb722789b08807cc0fda293f7e5e1819bb0353517020b31d3d00374952bea2c421e862040a9ec892a05b266b7a0d0cc316f483b81af39715657ffe32ad8d" +
        "e0c13077646035236ee5363e5f900e9a7cf850a81cd9960b6df73317226f99eaa6fff15cec34acb98246639b918ab2851466a7cdfc4ad1b52c2ae95a58d3734e" +
        "47ddd5b7560193f644ef9c408b7dee39b4d6aabf4de761e3252d4c7ba3843187745d55e6a5db05d42b45caed43c0c5d0b64bfa9d94a4a9d8f2f0e1a108dcfd28" +
        "8fd2bc1fc7bd675412138169cb09249875ba3b68cfb11180afae104fc9789571c6dfce02413886796ae21e3c488cc21b9f423a066c2eabf98e2fdeeb5776e4f5" +
        "d09a515ba58cbbab3e37b09fd4f6d9244df80512e84cd7956be301cf64f1429d58577cc241f3a2c655144f81aaf95a4e108da894b38970561ae9002619fbbac9" +
        "1fa922ed202bfc62cb08e0de2a7f3631435f8b47c8a3c563b4db4071ce74c46da6fa696a9e282d1e825d663b93bfd56c48dce1884bb10fccea063929f746443f" +
        "d830a4232e53da21a1f2d1bdfebc3a02c10a1b8ad31d04fd616590d272ac737e98eb33af60528716bee78ec0ee8699031177277d9cff32542c67caf5f4459168" +
        "1335ae0e178f3409a7803db83892dde5b9b7f0c750e24aef077badd678a09b850b84ec1597e4963cb5e675df1c0d6fb618496ecd76c35c798325590c2fb27a5e"
    )
}
