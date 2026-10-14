package com.minesweeperkeyboard.ime

import java.io.File

/**
 * クリップボードの履歴（Android 非依存・スレッドセーフ）。
 * - 新しくコピーしたものが先頭。同じ文字列をもう一度コピーしたら先頭に移動する
 * - ピン留めしたものは常に一番上に並び、件数の上限を超えても消えない
 */
class ClipboardHistory(private val maxUnpinned: Int = 30) {

    data class Entry(val text: String, val pinned: Boolean)

    private val entries = ArrayList<Entry>()

    /** ピン留め → それ以外 の順（それぞれ新しい順） */
    @Synchronized
    fun items(): List<Entry> = entries.filter { it.pinned } + entries.filter { !it.pinned }

    @Synchronized
    fun add(text: String): Boolean {
        if (text.isBlank()) return false
        val existing = entries.indexOfFirst { it.text == text }
        val pinned = existing >= 0 && entries[existing].pinned
        if (existing == 0) return false
        if (existing > 0) entries.removeAt(existing)
        entries.add(0, Entry(text, pinned))
        trim()
        return true
    }

    @Synchronized
    fun togglePin(text: String) {
        val i = entries.indexOfFirst { it.text == text }
        if (i >= 0) entries[i] = entries[i].copy(pinned = !entries[i].pinned)
        trim()
    }

    @Synchronized
    fun remove(text: String) {
        entries.removeAll { it.text == text }
    }

    /** ピン留めしていないものをすべて消す */
    @Synchronized
    fun clearUnpinned() {
        entries.removeAll { !it.pinned }
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
    }

    private fun trim() {
        var unpinned = 0
        val it = entries.listIterator()
        while (it.hasNext()) {
            if (!it.next().pinned && ++unpinned > maxUnpinned) it.remove()
        }
    }

    /** 1 行 1 件「p または - \t 文字列」。改行・タブ・\ はエスケープする */
    @Synchronized
    fun serialize(): String = entries.joinToString("\n") { (if (it.pinned) "p" else "-") + "\t" + escape(it.text) }

    @Synchronized
    fun load(data: String) {
        entries.clear()
        for (line in data.lineSequence()) {
            if (line.length < 2 || line[1] != '\t') continue
            entries += Entry(unescape(line.substring(2)), line[0] == 'p')
        }
        trim()
    }

    companion object {
        fun escape(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

        fun unescape(s: String): String {
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '\\' && i + 1 < s.length) {
                    sb.append(
                        when (s[i + 1]) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            else -> s[i + 1]
                        },
                    )
                    i += 2
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }
    }
}

/** アプリ内で共有するクリップボードの履歴（キーボードと設定画面は同じプロセスで動く） */
object Clipboard {
    private const val FILE_NAME = "clipboard.tsv"

    val history = ClipboardHistory()
    @Volatile
    private var loaded = false

    fun ensureLoaded(dir: File) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                File(dir, FILE_NAME).takeIf { it.exists() }?.readText()?.let { saved ->
                    // 読み込みが終わる前にコピーされたものは、保存済みの履歴の上に残す
                    val recent = history.items().map { it.text }
                    history.load(saved)
                    recent.asReversed().forEach { history.add(it) }
                }
            }
            loaded = true
        }
    }

    fun save(dir: File) {
        runCatching { File(dir, FILE_NAME).writeText(history.serialize()) }
    }

    fun reset(dir: File) {
        history.clearAll()
        File(dir, FILE_NAME).delete()
    }
}
