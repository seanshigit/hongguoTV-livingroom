// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject

/** One account's local history and durable pending writes. */
data class CloudPending(val sequence: Long, val progress: BackupProgress)
class CloudHistoryState(private val owner: String) {
    private val records = linkedMapOf<String, BackupProgress>()
    private val queued = linkedMapOf<String, CloudPending>()
    private val hidden = linkedSetOf<String>()
    private val watchedIds = linkedSetOf<String>()
    private var revision = 0L

    init { require(ID.matches(owner)) }

    fun checkpoint(progress: BackupProgress): Boolean {
        validate(progress, true)
        val old = records[progress.series.id]
        val next = progress.copy(cloudMetadata = progress.cloudMetadata ?: old?.cloudMetadata)
        if (old != null && old.copy(updatedAt = next.updatedAt) == next) return false
        revision++
        records[progress.series.id] = next
        queued[progress.series.id] = CloudPending(revision, next)
        hidden.remove(progress.series.id)
        return true
    }
    fun importLocal(progress: List<BackupProgress>): Int {
        progress.forEach { validate(it, true) }
        var count = 0
        progress.forEach { item ->
            if (item.updatedAt > (records[item.series.id]?.updatedAt ?: -1) && checkpoint(item)) count++
        }
        return count
    }

    /** Apply only a completely read snapshot; absence does not delete local records. */
    fun mergeRemote(progress: List<BackupProgress>) {
        progress.forEach { validate(it, false) }
        progress.forEach { remote ->
            val id = remote.series.id
            if (id !in hidden) {
                val local = records[id]
                if (local == null || remote.updatedAt > local.updatedAt) {
                    records[id] = remote.copy(cloudMetadata = remote.cloudMetadata ?: local?.cloudMetadata)
                    if (queued[id]?.progress?.updatedAt?.let { remote.updatedAt > it } == true) queued.remove(id)
                } else if (local.cloudMetadata == null && remote.cloudMetadata != null) {
                    val withMetadata = local.copy(cloudMetadata = remote.cloudMetadata)
                    records[id] = withMetadata
                    queued[id]?.let { queued[id] = it.copy(progress = withMetadata) }
                }
            }
        }
    }

    fun history(): List<BackupProgress> = records.values.sortedByDescending { it.updatedAt }
    fun pending(): List<CloudPending> = queued.values.sortedBy { it.sequence }
    fun watched(): Set<String> = watchedIds.toSet()
    fun setWatched(id: String, value: Boolean) {
        require(ID.matches(id))
        if (value) watchedIds.add(id) else watchedIds.remove(id)
    }
    fun acknowledge(sequence: Long) { queued.entries.removeAll { it.value.sequence == sequence } }
    fun remove(id: String) {
        require(ID.matches(id))
        records.remove(id); queued.remove(id); watchedIds.remove(id); hidden += id
    }
    fun clear() { records.keys.toList().forEach(::remove); watchedIds.clear() }

    fun encode(): String = JSONObject().put("version", 1).put("owner", owner).put("revision", revision)
        .put("history", JSONArray(history().map(::progressJson)))
        .put("pending", JSONArray(pending().map { JSONObject().put("sequence", it.sequence).put("progress", progressJson(it.progress)) }))
        .put("hidden", JSONArray(hidden.toList())).put("watched", JSONArray(watchedIds.toList())).toString()

    companion object {
        private val ID = Regex("[1-9][0-9]{0,29}")
        private fun validate(p: BackupProgress, pending: Boolean) {
            require(ID.matches(p.series.id) && (ID.matches(p.episodeId) || (!pending && p.episodeId.isEmpty())))
            require(p.series.title.isNotBlank() && p.series.title.length <= 200 && p.series.title.none(Char::isISOControl))
            require(p.episodeIndex in 0..9999 && p.position in 0..604_800_000 && p.duration in 0..604_800_000 && p.updatedAt > 0)
        }
        private fun progressJson(p: BackupProgress) = JSONObject().put("series", LibraryBackup.seriesJson(p.series))
            .put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex).put("position", p.position)
            .put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
            .apply { p.cloudMetadata?.let { put("cloudMetadata", JSONObject().put("episodeCount", it.episodeCount).put("seasonIndex", it.seasonIndex).put("multiSeason", it.multiSeason)) } }
        private fun progress(json: JSONObject, pending: Boolean): BackupProgress {
            val metadata = json.optJSONObject("cloudMetadata")?.let {
                CloudSeriesMetadata(it.getInt("episodeCount"), it.getInt("seasonIndex"), it.getBoolean("multiSeason"))
            }
            val p = BackupProgress(Series.fromJson(json.getJSONObject("series")), json.getString("episodeId"), json.getInt("episodeIndex"),
                json.getLong("position"), json.getLong("duration"), json.getBoolean("completed"), json.getLong("updatedAt"), metadata)
            validate(p, pending)
            return p
        }
        fun decode(owner: String, json: String): CloudHistoryState {
            require(json.toByteArray(Charsets.UTF_8).size <= 32 * 1024 * 1024)
            val value = JSONObject(json)
            require(value.getInt("version") == 1 && value.getString("owner") == owner)
            val state = CloudHistoryState(owner)
            state.revision = value.getLong("revision").also { require(it >= 0) }
            val records = value.getJSONArray("history")
            for (i in 0 until records.length()) {
                val p = progress(records.getJSONObject(i), false)
                require(state.records.put(p.series.id, p) == null)
            }
            val pending = value.getJSONArray("pending")
            val sequences = mutableSetOf<Long>()
            for (i in 0 until pending.length()) {
                val row = pending.getJSONObject(i)
                val seq = row.getLong("sequence")
                val p = progress(row.getJSONObject("progress"), true)
                require(seq in 1..state.revision && sequences.add(seq) && state.records[p.series.id] == p)
                require(state.queued.put(p.series.id, CloudPending(seq, p)) == null)
            }
            val hidden = value.getJSONArray("hidden")
            for (i in 0 until hidden.length()) {
                val id = hidden.getString(i)
                require(ID.matches(id) && id !in state.records && id !in state.queued)
                state.hidden += id
            }
            val watched = value.optJSONArray("watched") ?: JSONArray()
            for (i in 0 until watched.length()) {
                val id = watched.getString(i)
                require(ID.matches(id) && id !in state.hidden)
                state.watchedIds += id
            }
            return state
        }
    }
}
