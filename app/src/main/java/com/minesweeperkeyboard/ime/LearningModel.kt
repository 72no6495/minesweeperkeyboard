package com.minesweeperkeyboard.ime

import java.io.File

/**
 * 予測変換の学習（Android 非依存・スレッドセーフ）。
 * 候補から選んだ「よみ → 単語」の回数を覚えておき、次から同じよみ（またはその途中）で先頭に出す。
 * 保存形式は 1 行 1 件の「よみ \t 単語 \t 回数」。
 */
class LearningModel(private val maxEntries: Int = 3000) {

    private companion object {
        /** 「次の単語」の学習データのよみに付ける印 */
        const val NEXT_MARK = "\u0001"
    }

    private data class Entry(val reading: String, val word: String, var count: Int, var lastUsed: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var clock = 0L

    @Synchronized
    fun learn(reading: String, word: String) {
        if (reading.isEmpty() || word.isEmpty()) return
        val key = "$reading\t$word"
        val e = entries.getOrPut(key) { Entry(reading, word, 0, 0) }
        e.count++
        e.lastUsed = ++clock
        if (entries.size > maxEntries) {
            // いちばん長く使っていないものから消す
            entries.values.minByOrNull { it.lastUsed }?.let { entries.remove("${it.reading}\t${it.word}") }
        }
    }

    /** [previous] の次に [next] を入力したことを覚える（次の単語の予測用） */
    fun learnNext(previous: String, next: String) {
        if (previous.isNotEmpty()) learn(NEXT_MARK + previous, next)
    }

    /** [previous] の次によく入力した単語 */
    @Synchronized
    fun lookupNext(previous: String, limit: Int = 8): List<String> {
        if (previous.isEmpty()) return emptyList()
        val key = NEXT_MARK + previous
        return entries.values
            .filter { it.reading == key }
            .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
            .map { it.word }
            .take(limit)
    }

    /** よみが [prefix] で始まる学習済みの単語（よみが完全一致するもの → 回数 → 最近使った順） */
    @Synchronized
    fun lookup(prefix: String, limit: Int = 4): List<String> {
        if (prefix.isEmpty() || prefix.startsWith(NEXT_MARK)) return emptyList()
        return entries.values
            .filter { it.reading.startsWith(prefix, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Entry> { it.reading.equals(prefix, ignoreCase = true) }
                    .thenByDescending { it.count }
                    .thenByDescending { it.lastUsed },
            )
            .map { it.word }
            .distinct()
            .take(limit)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size() = entries.size

    @Synchronized
    fun serialize(): String = entries.values
        .sortedBy { it.lastUsed }
        .joinToString("\n") { "${it.reading}\t${it.word}\t${it.count}" }

    @Synchronized
    fun load(text: String) {
        entries.clear()
        clock = 0
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val count = parts[2].toIntOrNull() ?: continue
            entries["${parts[0]}\t${parts[1]}"] = Entry(parts[0], parts[1], count, ++clock)
        }
    }
}

/** アプリ内で共有する学習データ（キーボードと設定画面は同じプロセスで動く） */
object Learning {
    const val FILE_NAME = "learning.tsv"

    val model = LearningModel()
    @Volatile
    private var loaded = false

    /** まだなら保存ファイルから読み込む（別スレッドで呼ぶ） */
    fun ensureLoaded(dir: File) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { File(dir, FILE_NAME).takeIf { it.exists() }?.readText()?.let { model.load(it) } }
            loaded = true
        }
    }

    fun save(dir: File) {
        runCatching { File(dir, FILE_NAME).writeText(model.serialize()) }
    }

    fun reset(dir: File) {
        model.clear()
        File(dir, FILE_NAME).delete()
    }
}
