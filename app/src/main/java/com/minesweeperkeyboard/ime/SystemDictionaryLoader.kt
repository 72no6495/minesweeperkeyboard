package com.minesweeperkeyboard.ime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * APK の assets に入っている日本語辞書（数十 MB）を、初回だけアプリの保存領域へ展開して
 * メモリマップで開く。アップデートで APK が変わったら展開し直す。
 */
object SystemDictionaryLoader {

    private const val TAG = "SystemDictionary"
    private const val MAIN = "ja_dict.bin"
    private const val PREFIX = "ja_prefix.bin"
    const val LICENSE = "ja_dict_LICENSE.txt"

    @Volatile
    private var cached: JapaneseDictionary? = null
    @Volatile
    private var loading = false

    /** 読み込み済みなら返す。まだならバックグラウンドで読み込みを始めて null を返す */
    fun getOrLoadAsync(context: Context, onLoaded: () -> Unit = {}): JapaneseDictionary? {
        cached?.let { return it }
        synchronized(this) {
            if (loading) return null
            loading = true
        }
        val app = context.applicationContext
        Thread {
            try {
                cached = load(app)
                if (cached != null) onLoaded()
            } catch (e: Exception) {
                Log.w(TAG, "failed to load the Japanese dictionary", e)
            } finally {
                loading = false
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        return null
    }

    /** 辞書が APK に入っているか（ソースから辞書なしでビルドした場合は false） */
    fun isBundled(context: Context): Boolean =
        try {
            context.assets.open(MAIN).close()
            true
        } catch (e: FileNotFoundException) {
            false
        }

    private fun load(context: Context): JapaneseDictionary? {
        if (!isBundled(context)) return null
        val dir = File(context.noBackupFilesDir, "ja_dict").apply { mkdirs() }
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val stampFile = File(dir, "version")
        val main = File(dir, MAIN)
        val prefix = File(dir, PREFIX)
        if (!main.exists() || !prefix.exists() || stampFile.takeIf { it.exists() }?.readText() != stamp) {
            extract(context, MAIN, main)
            extract(context, PREFIX, prefix)
            stampFile.writeText(stamp)
        }
        return JapaneseDictionary(SortedRecordFile(map(main)), SortedRecordFile(map(prefix)))
    }

    private fun extract(context: Context, asset: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            check(tmp.renameTo(dest)) { "could not move $tmp" }
        }
    }

    private fun map(file: File) = RandomAccessFile(file, "r").use { raf ->
        raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
    }

    fun licenseText(context: Context): String? =
        try {
            context.assets.open(LICENSE).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
}
