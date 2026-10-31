// SPDX-License-Identifier: GPL-3.0-or-later
package org.fcitx.fcitx5.android.completion

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.core.data.DataManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Read-only source text lookup. A hit never calls the language model. */
object FixedCompletionEngine {
    private const val PUNCT = "，。！？；：、,.!?;:\n"
    private val mutex = Mutex()
    private var database: SQLiteDatabase? = null
    private var dictionaryId = ""
    private fun han(c: Int) = c in 0x3400..0x9fff || c in 0x20000..0x3134f
    private fun codePoints(text: String): List<Int> {
        val result = ArrayList<Int>(); var at = 0
        while (at < text.length) { val code = text.codePointAt(at); result.add(code); at += Character.charCount(code) }
        return result
    }
    private fun convert(text: String, config: String): String = FixedDictionaryNative.convert(
        text.toByteArray(Charsets.UTF_8), File(DataManager.dataDir, "usr/share/opencc/$config.json").absolutePath).toString(Charsets.UTF_8)
    private fun normalize(text: String): String {
        val simple = convert(Normalizer.normalize(text, Normalizer.Form.NFKC), "t2s")
        return buildString { codePoints(simple).filter { han(it) }.forEach { appendCodePoint(it) } }
    }
    suspend fun prepare(context: Context) = withContext(Dispatchers.IO) { mutex.withLock { open(context) }; Unit }
    private fun open(context: Context): SQLiteDatabase {
        database?.let { return it }
        val manifest = JSONObject(context.assets.open("dictionaries/manifest.json").bufferedReader().use { it.readText() })
        dictionaryId = manifest.getString("id")
        require(dictionaryId.matches(Regex("[a-zA-Z0-9_-]+")))
        val folder = File(context.noBackupFilesDir, "completion-dictionaries").apply { mkdirs() }
        val target = File(folder, "$dictionaryId.sqlite")
        if (!target.isFile || target.length() != manifest.getLong("bytes")) {
            val temporary = File(folder, "$dictionaryId.tmp")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                GZIPInputStream(context.assets.open("dictionaries/" + manifest.getString("database"))).use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val bytes = ByteArray(65536)
                        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n); output.write(bytes, 0, n) }
                    }
                }
                check(temporary.length() == manifest.getLong("bytes"))
                check(digest.digest().joinToString("") { "%02x".format(it) } == manifest.getString("sha256"))
                check(temporary.renameTo(target)) { "Cannot install completion dictionary" }
            } finally { temporary.delete() }
        }
        val opened = SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        check(opened.version == 1)
        database = opened
        return opened
    }
    suspend fun lookup(context: Context, prefix: String, requestId: Long): JSONObject = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        val result = try { mutex.withLock { search(open(context), prefix) } }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            JSONObject().put("matched", false).put("state", "dictionary_error").put("candidates", JSONArray()).put("error_type", error.javaClass.simpleName)
        }
        result.put("source", "dictionary").put("dictionary_id", dictionaryId).put("confidence_policy", "exact_dictionary")
        val timing = JSONObject(result.toString()).apply { remove("candidates"); remove("references") }
            .put("request_id", requestId).put("phase", "dictionary").put("lookup_ms", SystemClock.elapsedRealtime() - start)
            .put("candidate_count", result.getJSONArray("candidates").length())
        CompletionPerf.record(context, timing)
        result
    }
    private fun search(db: SQLiteDatabase, prefix: String): JSONObject {
        fun result(matched: Boolean, state: String, texts: List<String> = emptyList(), references: List<JSONObject> = emptyList(), length: Int = 0) =
            JSONObject().put("matched", matched).put("state", state).put("candidates", JSONArray(texts))
                .put("references", JSONArray(references)).put("matched_chars", length)
        val input = prefix.trimEnd()
        if (input.isEmpty()) return result(false, "empty")
        val last = input.codePointBefore(input.length)
        if (!han(last) && (last > 0xffff || last.toChar() !in PUNCT)) return result(false, "unsupported_boundary")
        val query = normalize(CompletionText.suffix(input, 128))
        val traditional = convert(input, "t2s") != input
        val total = query.codePointCount(0, query.length)
        for (length in minOf(24, total) downTo 4) {
            val suffix = CompletionText.suffix(query, length)
            val texts = mutableListOf<String>(); val references = mutableListOf<JSONObject>(); val seen = hashSetOf<String>()
            var finishedPriority = -1; var bestPriority = -1
            db.rawQuery("SELECT p.title,p.author,p.body,f.start,p.priority FROM prefixes f JOIN poems p ON p.id=f.poem_id WHERE f.k>=? AND f.k<? ORDER BY p.priority DESC,p.id LIMIT 120", arrayOf(suffix, suffix + "\uDBFF\uDFFF")).use { rows ->
                while (rows.moveToNext()) {
                    val body = rows.getString(2); val priority = rows.getInt(4)
                    var offset = rows.getInt(3); var consumed = 0
                    while (offset < body.length && consumed < length) {
                        val code = body.codePointAt(offset); if (han(code)) consumed++; offset += Character.charCount(code)
                    }
                    if (consumed < length) continue
                    val tail = body.substring(offset)
                    if (codePoints(tail).none { han(it) }) { finishedPriority = maxOf(finishedPriority, priority); continue }
                    val remaining = if (han(last)) tail else tail.trimStart { it in PUNCT || it.isWhitespace() }
                    val completion = StringBuilder(); var hasHan = false
                    for (code in codePoints(remaining)) {
                        if (Character.isWhitespace(code)) continue
                        completion.appendCodePoint(code); hasHan = hasHan || han(code)
                        if (hasHan && code <= 0xffff && code.toChar() in PUNCT) break
                        if (completion.codePointCount(0, completion.length) > 32) break
                    }
                    val text = completion.toString()
                    if (!hasHan || text.codePointCount(0, text.length) > 32 || text.any { it in "□{}<>[]（）()" }) continue
                    if (!seen.add(normalize(text))) continue
                    texts.add(if (traditional) convert(text, "s2t") else text)
                    references.add(JSONObject().put("title", rows.getString(0)).put("author", rows.getString(1)))
                    bestPriority = maxOf(bestPriority, priority)
                }
            }
            if (texts.isNotEmpty()) {
                if (finishedPriority > bestPriority) return result(true, "finished", length = length)
                return result(true, "generated", texts.take(3), references.take(3), length)
            }
            if (finishedPriority >= 0) return result(true, "finished", length = length)
        }
        return result(false, "no_match")
    }
}
