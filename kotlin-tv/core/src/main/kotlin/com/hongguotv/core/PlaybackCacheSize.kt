// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import kotlin.math.abs

object PlaybackCacheSize {
    val options = listOf(64L, 128L, 256L, 512L, 1024L)

    fun normalize(value: Long): Long = options.minBy { abs(it - value.coerceIn(options.first(), options.last())) }
}
