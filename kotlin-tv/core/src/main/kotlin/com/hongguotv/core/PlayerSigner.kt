// SPDX-License-Identifier: GPL-3.0-only
// Playback-only x-argus / x-ladon; adapted from N3urda/hongguoTV 7444bb01.
// Kotlin ports of fqapp
// (github.com/ch6vip/fqapp) rust/src/sign/{argus,simon,ladon,protobuf}.rs.
// GPL-3.0-only, kept with attribution as required by the source license.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.security.SecureRandom

/** Encode-only protobuf writer used by x-argus; varints truncate to 32 bits. */
internal class ProtoBuf {
    private class Field(val tag: Int, val type: Int, val value: Long, val blobIndex: Int)
    private val fields = mutableListOf<Field>()
    private val blobs = mutableListOf<ByteArray>()
    fun putVarint(idx: Int, v: Long): ProtoBuf { fields += Field(idx, 0, v, -1); return this }
    private fun putBlob(idx: Int, data: ByteArray): ProtoBuf { blobs += data; fields += Field(idx, 2, 0, blobs.size - 1); return this }
    fun putUtf8(idx: Int, s: String) = putBlob(idx, s.toByteArray(Charsets.UTF_8))
    fun putBytes(idx: Int, data: ByteArray) = putBlob(idx, data)
    fun putProtobuf(idx: Int, nested: ProtoBuf) = putBlob(idx, nested.toBuf())
    fun toBuf(): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            writeVarint(out, (f.tag.toLong() shl 3) or f.type.toLong())
            when (f.type) {
                0 -> writeVarint(out, f.value)
                2 -> { val b = blobs[f.blobIndex]; writeVarint(out, b.size.toLong()); out.write(b) }
            }
        }
        return out.toByteArray()
    }
    companion object {
        private fun writeVarint(dst: ByteArrayOutputStream, v: Long) {
            var n = v and 0xffffffffL
            while (n >= 128) { dst.write(((n and 127) or 128).toInt()); n = n ushr 7 }
            dst.write((n and 127).toInt())
        }
    }
}

/** Simon block cipher (four 64-bit key words, two 64-bit block words, 72 rounds) used by x-argus. */
internal object Simon {
    private const val Z = 0x3DC94C3A046D678BL
    fun keyExpansion(k: LongArray): LongArray {
        val key = LongArray(72)
        System.arraycopy(k, 0, key, 0, 4)
        for (i in 4 until 72) {
            var tmp = java.lang.Long.rotateRight(key[i - 1], 3)
            tmp = tmp xor key[i - 3]
            tmp = tmp xor java.lang.Long.rotateRight(tmp, 1)
            val bit = (Z shr ((i - 4) % 62)) and 1
            key[i] = key[i - 4].inv() xor tmp xor bit xor 3
        }
        return key
    }
    fun encrypt(xInput: Long, x1Input: Long, k: LongArray): LongArray {
        val key = keyExpansion(k)
        var xi = xInput
        var xi1 = x1Input
        for (roundKey in key) {
            val tmp = xi1
            val f = java.lang.Long.rotateLeft(xi1, 1) and java.lang.Long.rotateLeft(xi1, 8)
            xi1 = xi xor f xor java.lang.Long.rotateLeft(xi1, 2) xor roundKey
            xi = tmp
        }
        return longArrayOf(xi, xi1)
    }
}

/** x-argus: ordered protobuf bean -> PKCS7 -> Simon -> 551 framing -> AES-CBC. */
internal object Argus {
    private val SIGN_KEY = hex("fc78e0a9657a0c748ce51559903ccf03510e51d3cff232d71343e88a321c5304")
    private val AES_KEY = hex("d31e3718288a1027baab59f146a09a9c")
    private val AES_IV = hex("ea180a0336ed352fcd24e4d50018ae54")
    private val MAGIC8 = byteArrayOf(0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(), 0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte())
    private val TAIL9 = byteArrayOf(0xea.toByte(), 0xfb.toByte(), 0x2c, 0xfe.toByte(), 0x85.toByte(), 0x68, 0x51, 0x91.toByte(), 0x54)

    fun bodyHash(stubHex: String): ByteArray =
        if (stubHex.isEmpty()) sm3(ByteArray(16)).copyOf(6)
        else runCatching { sm3(hex(stubHex)).copyOf(6) }.getOrDefault(sm3(ByteArray(0)).copyOf(6))

    fun queryHash(query: String): ByteArray =
        if (query.isEmpty()) sm3(ByteArray(16)).copyOf(6) else sm3(query.toByteArray()).copyOf(6)

    private fun pkcs7Align(data: MutableList<Byte>) {
        val pad = 16 - data.size % 16
        repeat(pad) { data.add(pad.toByte()) }
    }

    private fun simonEncryptPadded(protobuf: ByteArray): ByteArray {
        val key = LongArray(4) { SIGN_KEY.i64(it * 8, true) }
        val out = ByteArray(protobuf.size / 16 * 16)
        for (i in 0 until protobuf.size / 16) {
            val block = protobuf.copyOfRange(i * 16, i * 16 + 16)
            val ct = Simon.encrypt(block.i64(0, true), block.i64(8, true), key)
            le64(ct[0]).copyInto(out, i * 16)
            le64(ct[1]).copyInto(out, i * 16 + 8)
        }
        return out
    }

    /** The x-argus wrapper accepted by the current upstream (ArgusBean551 layout). */
    fun sign(query: String, stubHex: String, ts: Long, aid: Long, licenseId: Long, deviceId: String, installId: String, randField: Int): String {
        val bean = ProtoBuf()
        bean.putVarint(1, 1_077_940_818)
        bean.putVarint(2, 2)
        bean.putVarint(3, randField.toLong() and 0xffffffffL)
        bean.putUtf8(4, (if (aid == 0L) 1967 else aid).toString())
        bean.putUtf8(5, deviceId)
        bean.putUtf8(6, (if (licenseId == 0L) 1_611_921_764 else licenseId).toString())
        bean.putUtf8(7, queryParam(query, "version_name").orEmpty())
        bean.putUtf8(8, "v04.04.05-ov-android")
        bean.putVarint(9, 134_744_640)
        bean.putBytes(10, ByteArray(8))
        bean.putVarint(11, 0)
        bean.putVarint(12, (ts shl 1) and 0xffffffffL)
        bean.putBytes(13, bodyHash(stubHex))
        bean.putBytes(14, queryHash(query))
        val sub15 = ProtoBuf()
        sub15.putVarint(1, 1); sub15.putVarint(2, 1); sub15.putVarint(3, 1); sub15.putVarint(7, 0xC792ECCC)
        bean.putProtobuf(15, sub15)
        bean.putUtf8(16, installId)
        bean.putUtf8(20, "none")
        bean.putVarint(21, 738)
        val sub23 = ProtoBuf()
        sub23.putUtf8(1, "NX551J"); sub23.putVarint(2, 8196); sub23.putVarint(4, 0x80E0D800L)
        bean.putProtobuf(23, sub23)
        bean.putVarint(25, 2)

        val protobuf = bean.toBuf().toMutableList()
        pkcs7Align(protobuf)
        val encPb = simonEncryptPadded(protobuf.toByteArray())

        val un = MAGIC8 + encPb + TAIL9
        val buf = ByteArray(un.size)
        MAGIC8.copyInto(buf, 0)
        for (i in 8 until un.size) buf[i] = (un[i].u() xor MAGIC8[i % 8].u()).toByte()
        buf.reverse()
        val padded = (buf + byteArrayOf('a'.code.toByte(), 'o'.code.toByte())).toMutableList()
        pkcs7Align(padded)
        val encrypted = aesEncrypt(padded.toByteArray(), AES_KEY, AES_IV)
        return (byteArrayOf(0xf2.toByte(), 0x81.toByte()) + encrypted).base64()
    }

}

/** x-ladon: a custom 34-round ARX block cipher over u64. */
internal object Ladon {
    fun encrypt(khronos: Long, lcId: Long, aid: Long, randomBytes: ByteArray): String {
        val data = "$khronos-$lcId-$aid"
        val md5Hex = digest("MD5", randomBytes + aid.toString().toByteArray()).hex()
        val ht = expandTable(md5Hex)
        val size = data.length
        val newSize = size + (16 - size % 16)
        val input = ByteArray(newSize) { (newSize - size).toByte() }
        data.toByteArray().copyInto(input, 0)
        val out = ByteArray(newSize)
        for (i in 0 until newSize / 16) encryptBlock(ht, input, i * 16).copyInto(out, i * 16)
        return (randomBytes + out).base64()
    }
    private fun expandTable(md5Hex: String): LongArray {
        val ht = ByteArray(288)
        val src = md5Hex.toByteArray()
        src.copyInto(ht, 0, 0, minOf(32, src.size))
        var b0 = ht.i64(0, true)
        var b8 = ht.i64(8, true)
        // Rust starts with temp = [word2, word3] and pushes before each pop.
        val temp = ArrayDeque(listOf(ht.i64(16, true), ht.i64(24, true)))
        for (i in 0 until 34) {
            var x8 = java.lang.Long.rotateRight(b8, 8) + b0
            x8 = x8 xor i.toLong()
            temp.addLast(x8)
            x8 = x8 xor java.lang.Long.rotateLeft(b0, 3)
            for (j in 0 until 8) ht[(i + 1) * 8 + j] = ((x8 ushr (j * 8)) and 255).toByte()
            b0 = x8
            b8 = temp.removeFirst()
        }
        return LongArray(36) { ht.i64(it * 8, true) }
    }
    private fun encryptBlock(ht: LongArray, data: ByteArray, at: Int): ByteArray {
        var data0 = data.i64(at, true)
        var data1 = data.i64(at + 8, true)
        for (i in 0 until 34) {
            data1 = ht[i] xor (data0 + java.lang.Long.rotateRight(data1, 8))
            data0 = data1 xor java.lang.Long.rotateLeft(data0, 3)
        }
        return le64(data0) + le64(data1)
    }
}

/** Shared query-string parameter lookup (Go url.Values.Get semantics). */
internal fun queryParam(query: String, name: String): String? {
    for (part in query.split('&')) {
        val eq = part.indexOf('=')
        val key = if (eq < 0) part else part.substring(0, eq)
        if (decodeForm(key) == name) return decodeForm(if (eq < 0) "" else part.substring(eq + 1))
    }
    return null
}
private fun decodeForm(value: String): String = runCatching {
    URLDecoder.decode(value, "UTF-8")
}.getOrDefault(value)

/** Upstream request headers for the fanqie player endpoints. */
internal object PlayerSigner {
    const val LICENSE_ID = 1_611_921_764L
    private val random = SecureRandom()
    fun headers(query: String, body: ByteArray?, ts: Long = System.currentTimeMillis() / 1000): Map<String, String> {
        val aid = queryParam(query, "aid")?.toLongOrNull() ?: 1967L
        val stub = body?.takeIf { it.isNotEmpty() }?.let { digest("MD5", it).hex() }.orEmpty()
        val ladonRandom = ByteArray(4).also { random.nextBytes(it) }
        val headers = linkedMapOf(
            "x-ss-req-ticket" to (ts * 1000).toString(),
            "x-khronos" to ts.toString(),
            "x-ladon" to Ladon.encrypt(ts, LICENSE_ID, aid, ladonRandom),
            "x-argus" to Argus.sign(query, stub, ts, aid, LICENSE_ID, queryParam(query, "device_id").orEmpty(), "", random.nextInt()),
        )
        if (stub.isNotEmpty()) headers["x-ss-stub"] = stub
        return headers
    }
}

private fun aesEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
    javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
        init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(iv))
        doFinal(data)
    }
