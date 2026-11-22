// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.Closeable

/** One cancellable speculative result. Claim transfers ownership; stale completions close themselves. */
class PreparedSlot<T: Closeable>(private val clock: ()->Long,private val ttl: Long=120_000) {
    private var revision=0L
    private var key=""
    private var readyAt=0L
    private var value: T?=null
    @Synchronized fun begin(next: String): Long { clear(); key=next; return revision }
    @Synchronized fun complete(ticket: Long,next: String,result: T): Boolean {
        if(ticket!=revision || key!=next || value!=null) { result.close(); return false }
        value=result; readyAt=clock(); return true
    }
    @Synchronized fun take(next: String): T? {
        val result=value?.takeIf { key==next && clock()-readyAt in 0..ttl }
        if(result!=null) value=null
        clear()
        return result
    }
    @Synchronized fun isFresh(next: String): Boolean = value!=null && key==next && clock()-readyAt in 0..ttl
    @Synchronized fun isFresh(ticket: Long,next: String): Boolean = ticket==revision && isFresh(next)
    /** A late failure may release only the preparation it belongs to, never a claimed/new result. */
    @Synchronized fun invalidate(ticket: Long,next: String): Boolean {
        if(ticket!=revision || key!=next) return false
        clear()
        return true
    }
    @Synchronized fun clear() { revision++; value?.close(); value=null; key="" }
}
