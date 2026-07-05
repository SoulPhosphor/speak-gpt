/**************************************************************************
 * Copyright (c) 2023-2026 Dmytro Ostapenko. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **************************************************************************/

package org.teslasoft.assistant.preferences.memory.librarian

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.File
import java.io.Reader
import java.io.StringReader

/**
 * Pure-Kotlin encoder for Hugging Face `tokenizer.json` files — the librarian
 * downloads the model repo's REAL tokenizer file at runtime instead of a
 * generated/bundled ONNX tokenizer graph, so the app never redistributes
 * model-licensed artifacts (owner decision, July 2026) and a future embedding
 * model (e.g. BGE-M3) is a catalog entry, not new tokenizer code.
 *
 * Supports the two model types today's candidates use: **BPE with
 * byte-fallback** (Gemma-family SentencePiece conversions) and **Unigram**
 * (XLM-R family, e.g. BGE-M3). Encode-only — the librarian never decodes.
 *
 * Correctness posture: this class must never silently produce wrong ids.
 * Any construct in the JSON it does not fully implement (unknown normalizer /
 * pre-tokenizer / post-processor / model type) THROWS at load or encode time;
 * the Librarian catches, logs to MemoryLog, and degrades to keyword search.
 * On top of that, the Librarian runs a semantic self-check on first use of a
 * downloaded model (see Librarian.ensureModel), so even a tokenizer that
 * parses cleanly but tokenizes wrongly is caught before it can poison the
 * vector index.
 *
 * Deliberate simplifications, safe for our inputs (short prose memories and
 * queries, always prefixed by us): added tokens are not extracted from the
 * input text (no user text legitimately contains "<bos>"), and only the
 * single-sequence ("A") template is applied.
 *
 * Parsing streams the big sections (vocab/merges — Gemma's file is ~33 MB)
 * with Gson's JsonReader so the whole JSON is never held as one string/DOM.
 * No Android imports: unit-tested on the JVM (HfTokenizerTest).
 */
class HfTokenizer private constructor(
    private val normalizers: List<(String) -> String>,
    private val preTokenizer: PreTokenizer?,
    private val model: TokenModel,
    /** Template specials rendered before/after the encoded sequence. */
    private val prefixIds: LongArray,
    private val suffixIds: LongArray
) {

    /** maxId+1 across vocab and added tokens — for "ids in range" sanity checks. */
    val vocabSize: Int get() = model.vocabSize

    /**
     * Text -> int64 ids (ORT's expected type), truncated to [maxTokens] with
     * template specials preserved (content is cut, never the specials).
     */
    fun encode(text: String, maxTokens: Int): LongArray {
        var s = text
        for (n in normalizers) s = n(s)
        val pieces = preTokenizer?.split(s) ?: listOf(s)
        val content = ArrayList<Long>()
        for (p in pieces) if (p.isNotEmpty()) model.encodePiece(p, content)

        val budget = maxTokens - prefixIds.size - suffixIds.size
        val kept = if (budget in 0 until content.size) content.subList(0, budget) else content
        val out = LongArray(prefixIds.size + kept.size + suffixIds.size)
        var i = 0
        for (id in prefixIds) out[i++] = id
        for (id in kept) out[i++] = id
        for (id in suffixIds) out[i++] = id
        return out
    }

    /* ----------------------------- models ----------------------------- */

    private sealed interface TokenModel {
        val vocabSize: Int
        fun idOf(token: String): Int?
        fun encodePiece(piece: String, out: MutableList<Long>)
    }

    /**
     * SentencePiece-style BPE (Gemma/Llama conversions): symbols start as
     * codepoints, adjacent pairs merge in rank order, leftovers byte-fall-back
     * to `<0xNN>` tokens.
     */
    private class BpeModel(
        private val vocab: HashMap<String, Int>,
        /** "left right" pair -> merge priority (lower merges first). */
        private val ranks: HashMap<String, Int>,
        private val byteFallback: Boolean,
        private val unkId: Int?,
        private val fuseUnk: Boolean,
        private val ignoreMerges: Boolean,
        override val vocabSize: Int
    ) : TokenModel {

        override fun idOf(token: String): Int? = vocab[token]

        override fun encodePiece(piece: String, out: MutableList<Long>) {
            if (ignoreMerges) {
                val whole = vocab[piece]
                if (whole != null) { out.add(whole.toLong()); return }
            }
            val symbols = toCodepointStrings(piece)
            if (symbols.isEmpty()) return

            while (symbols.size > 1) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (i in 0 until symbols.size - 1) {
                    val rank = ranks[symbols[i] + " " + symbols[i + 1]] ?: continue
                    if (rank < bestRank) { bestRank = rank; bestIdx = i }
                }
                if (bestIdx < 0) break
                symbols[bestIdx] = symbols[bestIdx] + symbols[bestIdx + 1]
                symbols.removeAt(bestIdx + 1)
            }

            var lastWasUnk = false
            for (sym in symbols) {
                val id = vocab[sym]
                if (id != null) { out.add(id.toLong()); lastWasUnk = false; continue }
                if (byteFallback) {
                    for (b in sym.toByteArray(Charsets.UTF_8)) {
                        val byteTok = String.format("<0x%02X>", b.toInt() and 0xFF)
                        val byteId = vocab[byteTok]
                            ?: unkId
                            ?: throw IllegalStateException("tokenizer: no byte token $byteTok and no unk")
                        out.add(byteId.toLong())
                    }
                    lastWasUnk = false
                    continue
                }
                val unk = unkId ?: throw IllegalStateException("tokenizer: '$sym' not in vocab and no unk token")
                if (!(fuseUnk && lastWasUnk)) out.add(unk.toLong())
                lastWasUnk = true
            }
        }
    }

    /** Unigram (XLM-R family): Viterbi best segmentation by piece log-probs. */
    private class UnigramModel(
        /** token -> (id, logprob). */
        private val pieces: HashMap<String, Pair<Int, Double>>,
        private val maxPieceChars: Int,
        private val minScore: Double,
        private val byteFallback: Boolean,
        private val unkId: Int?,
        override val vocabSize: Int
    ) : TokenModel {

        override fun idOf(token: String): Int? = pieces[token]?.first

        override fun encodePiece(piece: String, out: MutableList<Long>) {
            val cps = toCodepointStrings(piece)
            val n = cps.size
            if (n == 0) return
            // SentencePiece's unknown penalty: worse than any real piece.
            val unkScore = minScore - 10.0

            val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
            val backLen = IntArray(n + 1)
            val backUnk = BooleanArray(n + 1)
            best[0] = 0.0
            for (i in 0 until n) {
                if (best[i] == Double.NEGATIVE_INFINITY) continue
                val sb = StringBuilder()
                var j = i
                while (j < n && j - i < maxPieceChars) {
                    sb.append(cps[j]); j++
                    val p = pieces[sb.toString()] ?: continue
                    val s = best[i] + p.second
                    if (s > best[j]) { best[j] = s; backLen[j] = j - i; backUnk[j] = false }
                }
                // Single-codepoint unknown transition keeps the lattice connected.
                if (pieces[cps[i]] == null) {
                    val s = best[i] + unkScore
                    if (s > best[i + 1]) { best[i + 1] = s; backLen[i + 1] = 1; backUnk[i + 1] = true }
                }
            }
            if (best[n] == Double.NEGATIVE_INFINITY)
                throw IllegalStateException("tokenizer: unigram lattice disconnected")

            // Backtrack, then emit in order.
            val segs = ArrayList<Pair<String, Boolean>>()
            var pos = n
            while (pos > 0) {
                val len = backLen[pos]
                val start = pos - len
                segs.add(cps.subList(start, pos).joinToString("") to backUnk[pos])
                pos = start
            }
            segs.reverse()
            for ((seg, isUnk) in segs) {
                if (!isUnk) { out.add(pieces[seg]!!.first.toLong()); continue }
                if (byteFallback) {
                    var covered = true
                    val byteIds = ArrayList<Long>()
                    for (b in seg.toByteArray(Charsets.UTF_8)) {
                        val p = pieces[String.format("<0x%02X>", b.toInt() and 0xFF)]
                        if (p == null) { covered = false; break }
                        byteIds.add(p.first.toLong())
                    }
                    if (covered) { out.addAll(byteIds); continue }
                }
                val unk = unkId ?: throw IllegalStateException("tokenizer: '$seg' unknown and no unk id")
                out.add(unk.toLong())
            }
        }
    }

    /* -------------------------- pre-tokenizers ------------------------ */

    private sealed interface PreTokenizer {
        fun split(text: String): List<String>
    }

    /** SentencePiece metaspace: spaces become the replacement char (default ▁),
     *  optionally prefixed, then split at word starts with ▁ kept attached. */
    private class Metaspace(
        private val replacement: Char,
        private val prepend: Boolean,
        private val splitPieces: Boolean
    ) : PreTokenizer {
        override fun split(text: String): List<String> {
            var s = text.replace(' ', replacement)
            if (prepend && s.isNotEmpty() && s[0] != replacement) s = replacement + s
            if (!splitPieces) return listOf(s)
            val out = ArrayList<String>()
            var start = 0
            for (i in 1 until s.length) {
                if (s[i] == replacement) { out.add(s.substring(start, i)); start = i }
            }
            if (start < s.length) out.add(s.substring(start))
            return out
        }
    }

    private class SplitPre(
        private val regex: Regex,
        /** "Isolated" keeps delimiters as own pieces; "Removed" drops them. */
        private val isolated: Boolean
    ) : PreTokenizer {
        override fun split(text: String): List<String> {
            val out = ArrayList<String>()
            var last = 0
            for (m in regex.findAll(text)) {
                if (m.range.first > last) out.add(text.substring(last, m.range.first))
                if (isolated && m.value.isNotEmpty()) out.add(m.value)
                last = m.range.last + 1
            }
            if (last < text.length) out.add(text.substring(last))
            return out
        }
    }

    /** Digits pre-tokenizer: isolate runs (or single digits) so numbers don't merge. */
    private class DigitsPre(private val individual: Boolean) : PreTokenizer {
        override fun split(text: String): List<String> {
            val out = ArrayList<String>()
            val sb = StringBuilder()
            for (c in text) {
                if (c.isDigit()) {
                    if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() }
                    if (individual) out.add(c.toString())
                    else {
                        if (out.isNotEmpty() && out.last().isNotEmpty() && out.last().last().isDigit())
                            out[out.size - 1] = out.last() + c
                        else out.add(c.toString())
                    }
                } else sb.append(c)
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }
    }

    private class SequencePre(private val parts: List<PreTokenizer>) : PreTokenizer {
        override fun split(text: String): List<String> {
            var pieces = listOf(text)
            for (p in parts) pieces = pieces.flatMap { p.split(it) }
            return pieces
        }
    }

    /* ----------------------------- loading ---------------------------- */

    companion object {

        fun load(file: File): HfTokenizer =
            file.bufferedReader(Charsets.UTF_8).use { fromReader(it) }

        /** For unit tests with inline fixtures. */
        fun fromJson(json: String): HfTokenizer = fromReader(StringReader(json))

        fun fromReader(reader: Reader): HfTokenizer {
            var normalizerEl: JsonElement? = null
            var preTokEl: JsonElement? = null
            var postEl: JsonElement? = null
            val addedTokens = HashMap<String, Int>()
            var model: TokenModel? = null

            val jr = JsonReader(reader)
            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "normalizer" -> normalizerEl = JsonParser.parseReader(jr)
                    "pre_tokenizer" -> preTokEl = JsonParser.parseReader(jr)
                    "post_processor" -> postEl = JsonParser.parseReader(jr)
                    "added_tokens" -> parseAddedTokens(jr, addedTokens)
                    "model" -> model = parseModel(jr, addedTokens)
                    else -> jr.skipValue()
                }
            }
            jr.endObject()

            val m = model ?: throw IllegalStateException("tokenizer: no model section")
            val (prefix, suffix) = parseTemplate(postEl, m, addedTokens)
            return HfTokenizer(
                buildNormalizers(normalizerEl),
                buildPreTokenizer(preTokEl),
                m, prefix, suffix
            )
        }

        private fun parseAddedTokens(jr: JsonReader, into: HashMap<String, Int>) {
            jr.beginArray()
            while (jr.hasNext()) {
                var id = -1
                var content: String? = null
                jr.beginObject()
                while (jr.hasNext()) {
                    when (jr.nextName()) {
                        "id" -> id = jr.nextInt()
                        "content" -> content = jr.nextString()
                        else -> jr.skipValue()
                    }
                }
                jr.endObject()
                if (id >= 0 && content != null) into[content] = id
            }
            jr.endArray()
        }

        private fun parseModel(jr: JsonReader, addedTokens: Map<String, Int>): TokenModel {
            var type: String? = null
            var unkToken: String? = null
            var unkIndex: Int? = null
            var byteFallback = false
            var fuseUnk = false
            var ignoreMerges = false
            val bpeVocab = HashMap<String, Int>()
            val ranks = HashMap<String, Int>()
            val uniPieces = HashMap<String, Pair<Int, Double>>()

            jr.beginObject()
            while (jr.hasNext()) {
                when (jr.nextName()) {
                    "type" -> type = jr.nextString()
                    "unk_token" -> unkToken = if (jr.peek() == JsonToken.NULL) { jr.nextNull(); null } else jr.nextString()
                    "unk_id" -> unkIndex = if (jr.peek() == JsonToken.NULL) { jr.nextNull(); null } else jr.nextInt()
                    "byte_fallback" -> byteFallback = jr.nextBoolean()
                    "fuse_unk" -> fuseUnk = jr.nextBoolean()
                    "ignore_merges" -> ignoreMerges = jr.nextBoolean()
                    "continuing_subword_prefix", "end_of_word_suffix" -> {
                        if (jr.peek() == JsonToken.NULL) jr.nextNull()
                        else {
                            val v = jr.nextString()
                            if (v.isNotEmpty()) throw IllegalStateException("tokenizer: subword affixes unsupported")
                        }
                    }
                    "vocab" -> when (jr.peek()) {
                        // BPE serializes vocab as {token: id}; Unigram as [[token, score]].
                        JsonToken.BEGIN_OBJECT -> {
                            jr.beginObject()
                            while (jr.hasNext()) bpeVocab[jr.nextName()] = jr.nextInt()
                            jr.endObject()
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            jr.beginArray()
                            var idx = 0
                            while (jr.hasNext()) {
                                jr.beginArray()
                                val tok = jr.nextString()
                                val score = jr.nextDouble()
                                jr.endArray()
                                uniPieces[tok] = idx to score
                                idx++
                            }
                            jr.endArray()
                        }
                        else -> jr.skipValue()
                    }
                    "merges" -> {
                        jr.beginArray()
                        var rank = 0
                        while (jr.hasNext()) {
                            // Two serializations exist: "left right" or ["left", "right"].
                            when (jr.peek()) {
                                JsonToken.STRING -> {
                                    val s = jr.nextString()
                                    val sp = s.indexOf(' ')
                                    if (sp <= 0) throw IllegalStateException("tokenizer: bad merge '$s'")
                                    ranks[s] = rank
                                }
                                JsonToken.BEGIN_ARRAY -> {
                                    jr.beginArray()
                                    val l = jr.nextString()
                                    val r = jr.nextString()
                                    jr.endArray()
                                    ranks["$l $r"] = rank
                                }
                                else -> throw IllegalStateException("tokenizer: bad merge entry")
                            }
                            rank++
                        }
                        jr.endArray()
                    }
                    else -> jr.skipValue()
                }
            }
            jr.endObject()

            return when (type) {
                "BPE" -> {
                    if (bpeVocab.isEmpty()) throw IllegalStateException("tokenizer: BPE with empty vocab")
                    var maxId = 0
                    for (v in bpeVocab.values) if (v > maxId) maxId = v
                    for (v in addedTokens.values) if (v > maxId) maxId = v
                    BpeModel(
                        bpeVocab, ranks, byteFallback,
                        unkToken?.let { bpeVocab[it] ?: addedTokens[it] },
                        fuseUnk, ignoreMerges, maxId + 1
                    )
                }
                "Unigram" -> {
                    if (uniPieces.isEmpty()) throw IllegalStateException("tokenizer: Unigram with empty vocab")
                    var maxLen = 1
                    var minScore = 0.0
                    for ((tok, p) in uniPieces) {
                        val len = tok.codePointCount(0, tok.length)
                        if (len > maxLen) maxLen = len
                        if (p.second < minScore) minScore = p.second
                    }
                    var maxId = uniPieces.size - 1
                    for (v in addedTokens.values) if (v > maxId) maxId = v
                    UnigramModel(uniPieces, maxLen, minScore, byteFallback, unkIndex, maxId + 1)
                }
                else -> throw IllegalStateException("tokenizer: unsupported model type '$type'")
            }
        }

        private fun buildNormalizers(el: JsonElement?): List<(String) -> String> {
            if (el == null || el.isJsonNull) return emptyList()
            val o = el.asJsonObject
            return when (val type = o.get("type")?.asString) {
                "Sequence" -> o.getAsJsonArray("normalizers").flatMap { buildNormalizers(it) }
                "Prepend" -> {
                    val p = o.get("prepend").asString
                    listOf { s -> p + s }
                }
                "Replace" -> {
                    val content = o.get("content").asString
                    val pattern = o.get("pattern")
                    when {
                        pattern.isJsonObject && pattern.asJsonObject.has("String") -> {
                            val lit = pattern.asJsonObject.get("String").asString
                            listOf { s -> s.replace(lit, content) }
                        }
                        pattern.isJsonObject && pattern.asJsonObject.has("Regex") -> {
                            val re = Regex(pattern.asJsonObject.get("Regex").asString)
                            listOf { s -> re.replace(s, content) }
                        }
                        else -> throw IllegalStateException("tokenizer: bad Replace pattern")
                    }
                }
                "NFC", "NFD", "NFKC", "NFKD" -> {
                    val form = java.text.Normalizer.Form.valueOf(type!!)
                    listOf { s -> java.text.Normalizer.normalize(s, form) }
                }
                "Lowercase" -> listOf { s -> s.lowercase() }
                "Strip" -> {
                    val left = o.get("strip_left")?.asBoolean ?: true
                    val right = o.get("strip_right")?.asBoolean ?: true
                    listOf { s ->
                        var r = s
                        if (left) r = r.trimStart()
                        if (right) r = r.trimEnd()
                        r
                    }
                }
                // SentencePiece's precompiled charsmap is essentially NFKC plus
                // whitespace cleanup. Approximating with NFKC is the accepted
                // porting shortcut; the Librarian's semantic self-check guards
                // against it mattering for a given model.
                "Precompiled" -> listOf { s -> java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC) }
                else -> throw IllegalStateException("tokenizer: unsupported normalizer '$type'")
            }
        }

        private fun buildPreTokenizer(el: JsonElement?): PreTokenizer? {
            if (el == null || el.isJsonNull) return null
            val o = el.asJsonObject
            return when (val type = o.get("type")?.asString) {
                "Sequence" -> SequencePre(
                    o.getAsJsonArray("pretokenizers").mapNotNull { buildPreTokenizer(it) }
                )
                "Metaspace" -> {
                    val replacement = o.get("replacement")?.asString ?: "▁"
                    // Older files use add_prefix_space; newer use prepend_scheme.
                    val scheme = o.get("prepend_scheme")?.asString
                        ?: if (o.get("add_prefix_space")?.asBoolean != false) "always" else "never"
                    val split = o.get("split")?.asBoolean ?: true
                    if (replacement.length != 1)
                        throw IllegalStateException("tokenizer: multi-char metaspace replacement")
                    Metaspace(replacement[0], scheme == "always" || scheme == "first", split)
                }
                "Split" -> {
                    val pattern = o.get("pattern")
                    val regex = when {
                        pattern.isJsonObject && pattern.asJsonObject.has("String") ->
                            Regex(Regex.escape(pattern.asJsonObject.get("String").asString))
                        pattern.isJsonObject && pattern.asJsonObject.has("Regex") ->
                            Regex(pattern.asJsonObject.get("Regex").asString)
                        else -> throw IllegalStateException("tokenizer: bad Split pattern")
                    }
                    if (o.get("invert")?.asBoolean == true)
                        throw IllegalStateException("tokenizer: inverted Split unsupported")
                    when (val behavior = o.get("behavior")?.asString) {
                        "Isolated" -> SplitPre(regex, isolated = true)
                        "Removed" -> SplitPre(regex, isolated = false)
                        else -> throw IllegalStateException("tokenizer: Split behavior '$behavior' unsupported")
                    }
                }
                "Digits" -> DigitsPre(o.get("individual_digits")?.asBoolean ?: false)
                "Whitespace", "WhitespaceSplit" -> SplitPre(Regex("\\s+"), isolated = false)
                else -> throw IllegalStateException("tokenizer: unsupported pre-tokenizer '$type'")
            }
        }

        /**
         * TemplateProcessing, single ("A") sequence only: specials before A form
         * the prefix, after A the suffix. Anything else (or a null processor)
         * yields empty affixes; unknown processor types throw.
         */
        private fun parseTemplate(
            el: JsonElement?,
            model: TokenModel,
            addedTokens: Map<String, Int>
        ): Pair<LongArray, LongArray> {
            if (el == null || el.isJsonNull) return LongArray(0) to LongArray(0)
            val o = el.asJsonObject
            when (o.get("type")?.asString) {
                "TemplateProcessing" -> { }
                else -> throw IllegalStateException(
                    "tokenizer: unsupported post-processor '${o.get("type")?.asString}'"
                )
            }
            val single = o.getAsJsonArray("single") ?: return LongArray(0) to LongArray(0)
            val specialIds = o.getAsJsonObject("special_tokens")

            val prefix = ArrayList<Long>()
            val suffix = ArrayList<Long>()
            var seenSequence = false
            for (item in single) {
                val io = item.asJsonObject
                when {
                    io.has("Sequence") -> {
                        // Only the primary sequence is encoded; a "B" slot never
                        // occurs for single-input embedding models.
                        val seqId = io.getAsJsonObject("Sequence").get("id")?.asString
                        if (seqId == "A") seenSequence = true
                    }
                    io.has("SpecialToken") -> {
                        val name = io.getAsJsonObject("SpecialToken").get("id").asString
                        val id = resolveSpecial(name, specialIds, model, addedTokens)
                        (if (seenSequence) suffix else prefix).add(id)
                    }
                    else -> throw IllegalStateException("tokenizer: unknown template item")
                }
            }
            return prefix.toLongArray() to suffix.toLongArray()
        }

        private fun resolveSpecial(
            name: String,
            specialIds: JsonObject?,
            model: TokenModel,
            addedTokens: Map<String, Int>
        ): Long {
            val fromTemplate = specialIds?.getAsJsonObject(name)
                ?.getAsJsonArray("ids")?.firstOrNull()?.asLong
            if (fromTemplate != null) return fromTemplate
            val id = addedTokens[name] ?: model.idOf(name)
                ?: throw IllegalStateException("tokenizer: special token '$name' has no id")
            return id.toLong()
        }

        /** Codepoint-safe symbol list (surrogate pairs stay whole). */
        private fun toCodepointStrings(s: String): MutableList<String> {
            val out = ArrayList<String>(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val cc = Character.charCount(cp)
                out.add(s.substring(i, i + cc))
                i += cc
            }
            return out
        }
    }
}
