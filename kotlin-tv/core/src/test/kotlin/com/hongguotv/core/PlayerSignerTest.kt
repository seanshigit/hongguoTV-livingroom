package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/** Independent fqapp vectors published by N3urda/hongguoTV 7444bb01 and hongguo-drama v7.1. */
class PlayerSignerTest {
    @Test fun argusMatchesExternal551Ciphertext() {
        val query = "aid=1967&device_id=X&iid=333"
        assertEquals("8oEOCRTb0LQf+M+MsliYu4M9kBzAlzXvFgL/Ub198hyoBGxYfNXLAENmH+K/10JQ+Rbhm2zRxNtNv5wVd885xcb6C6F10zp/9jVeGHGz8plz8W3UXHHNrFKjJptjQElGDbyaVLSKt56+HfwO7ENYMfjVv/5PNi1upVciiHqyOPDDAtwi/fZkwBoU1kC7RolMOuedn4lCg/s0CjnNvxFT0QpF/K0yoW3Zpu30tkadIET7WbZ3+7KT3c1yAA6RKWI4mmw=",
            Argus.sign(query, "", 1234567890, 1967, PlayerSigner.LICENSE_ID, "X", "", 554000487))
    }

    @Test fun ladonMatchesExternalFixedRandomVector() {
        assertEquals("AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH",
            Ladon.encrypt(1700000000, PlayerSigner.LICENSE_ID, 1967, hex("01020304")))
        assertEquals("08ffffffff0f1005", ProtoBuf().putVarint(1, -1).putVarint(2, 4294967301).toBuf().hex())
    }

    @Test fun hashesBindFinalQueryAndActualCompressedBody() {
        assertEquals("106e34a2b8c7", Argus.bodyHash("").hex())
        val body=hex("1f8b0800000000000003")
        val headers=PlayerSigner.headers("aid=8662&device_id=333&iid=444",body,1700000000)
        assertEquals(digest("MD5",body).hex(),headers["x-ss-stub"])
        assertEquals("1700000000000",headers["x-ss-req-ticket"])
        assertFalse(Argus.queryHash("x=%20").contentEquals(Argus.queryHash("x=+")))
    }
}
