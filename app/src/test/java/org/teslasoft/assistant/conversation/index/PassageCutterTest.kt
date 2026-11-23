package org.teslasoft.assistant.conversation.index

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory development plan G.5b, "With the cutter, in Step 7". */
class PassageCutterTest {

    /** A size in code points. */
    private val codePoints: (String) -> Int = { it.codePointCount(0, it.length) }

    /** A rough, non-additive token-like size: words, punctuation and long-word pieces. */
    private val tokenLike: (String) -> Int = { text ->
        Regex("""\p{L}{1,4}|\p{N}{1,3}|[^\s\p{L}\p{N}]""").findAll(text).count() + 1
    }

    private val samples: Map<String, String> = mapOf(
        "ordinary" to ordinaryText(),
        "very long" to ordinaryText().repeat(40),
        "no spaces" to "a".repeat(3_000) + "https://example.invalid/" + "b/c-d_e".repeat(400),
        "no punctuation" to (1..600).joinToString(" ") { "word$it" },
        "speech to text" to "so then I went to the store and I said. to her that we need milk " +
            "and bread and also eggs. because the. recipe calls for them ".repeat(60),
        "code" to buildString {
            append("```kotlin\n")
            repeat(120) { append("fun item$it(value: Int): Int {\n    return value * $it\n}\n\n") }
            append("```\n")
        },
        "table" to buildString {
            append("| Name | Value | Notes |\n|---|---|---|\n")
            repeat(200) { append("| row $it | ${it * 7} | some note about row $it |\n") }
        },
        "emoji" to "👨‍👩‍👧‍👦🏳️‍🌈👍🏽 family time 🎉🎉 ".repeat(150) + "🙂".repeat(500),
        "combining marks" to "éàö ".repeat(400) + "Z" + "̶".repeat(40),
        "Chinese" to "我们今天去公园散步然后在湖边吃午饭天气很好".repeat(80),
        "Japanese" to "今日はとても良い天気ですね。散歩に行きましょう。".repeat(80),
        "Thai" to "วันนี้อากาศดีมากเราไปเดินเล่นที่สวนสาธารณะกัน".repeat(80),
        "Arabic" to "ذهبنا إلى الحديقة اليوم وتناولنا الغداء بجانب البحيرة\n".repeat(80),
        "Russian" to "Сегодня мы пошли в парк и пообедали у озера.\n\n".repeat(80),
        "line endings" to "first line\r\nsecond line\r\n\r\nthird\rfourth fifth \n".repeat(100),
        "blank edges" to "\n\n   \n" + ordinaryText() + "\n\n\n   ",
        "lone surrogate" to "abc\uD83Ddef \uDE00 ghi".repeat(200)
    )

    private val targets = listOf(1, 2, 7, 16, 64, 256)

    /** Every sample at every target; the tiniest targets use the first 600 characters, which already exercise every level. */
    private fun cases(): List<Triple<String, String, Int>> = samples.flatMap { (name, text) ->
        targets.map { target -> Triple(name, if (target <= 2) text.take(600) else text, target) }
    }

    @Test
    fun joiningEveryPassageInOrderGivesBackTheMessageExactly() {
        for ((name, text, target) in cases()) for (measure in listOf(codePoints, tokenLike)) {
            val passages = PassageCutter.cut(text, target, measure).passages
            assertEquals("$name at $target", text, passages.joinToString("") { text.substring(it.start, it.end) })
        }
    }

    @Test
    fun noPassageIsLargerThanTheSizeItWasAskedFor() {
        for ((name, text, target) in cases()) for (measure in listOf(codePoints, tokenLike)) {
            for (passage in PassageCutter.cut(text, target, measure).passages) {
                val piece = text.substring(passage.start, passage.end)
                val singleCodePoint = piece.codePointCount(0, piece.length) == 1
                assertTrue(
                    "$name at $target: ${measure(piece)} for \"$piece\"",
                    measure(piece) <= target || singleCodePoint
                )
            }
        }
    }

    @Test
    fun passagesComeOutInOrderAndNeverOverlap() {
        for ((name, text, target) in cases()) {
            val passages = PassageCutter.cut(text, target, tokenLike).passages
            var expectedStart = 0
            for (passage in passages) {
                assertEquals(name, expectedStart, passage.start)
                assertTrue(name, passage.end > passage.start)
                expectedStart = passage.end
            }
            assertEquals(name, text.length, expectedStart)
        }
    }

    @Test
    fun shortNeighbouringParagraphsShareAPassage() {
        val text = "First short paragraph.\n\nSecond one.\n\nThird.\n\nFourth and last."
        val passages = PassageCutter.cut(text, 256, tokenLike).passages
        assertEquals(listOf(PassageSpan(0, text.length)), passages)

        val lines = "one\ntwo\nthree\nfour\n" + "x ".repeat(300)
        val first = PassageCutter.cut(lines, 64, codePoints).passages.first()
        assertTrue(lines.substring(first.start, first.end).startsWith("one\ntwo\nthree\nfour\n"))
    }

    @Test
    fun aDetailInTheMiddleOfALongMessageHasAPassageThatContainsIt() {
        val detail = "My sister's cat is called Biscuit"
        val text = ordinaryText().repeat(20) + detail + ". " + ordinaryText().repeat(20)
        val passages = PassageCutter.cut(text, 64, tokenLike).passages
        assertTrue(passages.size > 10)
        assertTrue(passages.any { text.substring(it.start, it.end).contains(detail) })
    }

    @Test
    fun theSameMessageAlwaysCutsTheSameWay() {
        for ((name, text) in samples) {
            val first = PassageCutter.cut(text, 64, tokenLike)
            repeat(3) { assertEquals(name, first, PassageCutter.cut(text, 64, tokenLike)) }
        }
    }

    @Test
    fun aMessageThatFitsIsOnePassageAndEmptyTextHasNone() {
        assertEquals(listOf(PassageSpan(0, 2)), PassageCutter.cut("ok", 256, tokenLike).passages)
        assertEquals(emptyList<PassageSpan>(), PassageCutter.cut("", 256, tokenLike).passages)
    }

    @Test
    fun passagesRecordTheCutterVersion() {
        assertEquals(PassageCutter.CUTTER_VERSION, PassageCutter.cut(ordinaryText(), 16, tokenLike).cutterVersion)
        assertEquals(PassageCutter.CUTTER_VERSION, PassageCutter.cut("", 16, tokenLike).cutterVersion)
    }

    @Test
    fun paragraphsThatFitAreNeverSplitAndCutsFallBetweenThem() {
        val first = "cat ".repeat(30).trim()
        val second = "dog ".repeat(30).trim()
        val text = "$first\n\n$second"
        val passages = PassageCutter.cut(text, 40, tokenLike).passages
        assertEquals(listOf(PassageSpan(0, first.length + 2), PassageSpan(first.length + 2, text.length)), passages)
    }

    @Test
    fun linesOfCodeStayWholeWhenAParagraphIsTooLarge() {
        val lines = (1..40).map { "    val number$it = compute($it, \"text\")\n" }
        val text = lines.joinToString("")
        val lineStarts = lines.runningFold(0) { at, line -> at + line.length }.toSet()
        for (passage in PassageCutter.cut(text, 64, tokenLike).passages) {
            assertTrue(passage.start in lineStarts && passage.end in lineStarts)
        }
    }

    @Test
    fun periodsAreNeverCutPointsWhenWordsAreAvailable() {
        val text = "we went out. and then we came back. and it was late.".repeat(30)
        for (passage in PassageCutter.cut(text, 16, tokenLike).passages.dropLast(1)) {
            assertEquals(' ', text[passage.end - 1])
        }
    }

    @Test
    fun spacesStayWithTheWordBeforeThem() {
        val text = "one two three four five six seven eight nine ten ".repeat(20)
        for (passage in PassageCutter.cut(text, 8, codePoints).passages.dropLast(1)) {
            val cutAt = passage.end
            assertTrue(text[cutAt - 1] == ' ' && text[cutAt] != ' ')
        }
    }

    @Test
    fun charactersAreNeverCutThroughTheMiddle() {
        val family = "👨‍👩‍👧‍👦"
        val text = family.repeat(50)
        for (passage in PassageCutter.cut(text, family.codePointCount(0, family.length), codePoints).passages) {
            assertEquals(family, text.substring(passage.start, passage.end))
        }
        val surrogates = "🙂".repeat(100)
        for (passage in PassageCutter.cut(surrogates, 1, codePoints).passages) {
            assertEquals("🙂", surrogates.substring(passage.start, passage.end))
        }
    }

    @Test
    fun aCharacterLargerThanTheTargetIsSplitOnlyBetweenCodePoints() {
        val text = "e" + "́".repeat(20)
        val passages = PassageCutter.cut(text, 4, codePoints).passages
        assertTrue(passages.all { codePoints(text.substring(it.start, it.end)) <= 4 })
        assertEquals(text, passages.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test
    fun aCodePointLargerThanTheTargetTravelsAlone() {
        val heavy: (String) -> Int = { text -> text.length * 10 }
        val passages = PassageCutter.cut("abc", 5, heavy).passages
        assertEquals(listOf(PassageSpan(0, 1), PassageSpan(1, 2), PassageSpan(2, 3)), passages)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTargetBelowOneIsRejected() {
        PassageCutter.cut("text", 0, tokenLike)
    }

    @Test
    fun randomTextKeepsEveryRule() {
        val alphabet = listOf("a", "b", "word", " ", " ", "\n", "\n\n", ".", "🙂", "我", "\r\n", "é", "\t")
        for (seed in 0 until 200) {
            val random = Random(seed)
            val text = buildString { repeat(random.nextInt(0, 400)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            val target = random.nextInt(1, 40)
            val measure = if (seed % 2 == 0) codePoints else tokenLike
            val cut = PassageCutter.cut(text, target, measure)
            assertEquals("seed $seed", text, cut.passages.joinToString("") { text.substring(it.start, it.end) })
            var at = 0
            for (passage in cut.passages) {
                assertEquals("seed $seed", at, passage.start)
                val piece = text.substring(passage.start, passage.end)
                assertTrue("seed $seed", piece.isNotEmpty())
                assertTrue("seed $seed", measure(piece) <= target || piece.codePointCount(0, piece.length) == 1)
                at = passage.end
            }
            assertEquals(cut, PassageCutter.cut(text, target, measure))
        }
    }

    private fun ordinaryText(): String =
        "We talked about the garden today and what to plant next spring. " +
            "The tomatoes did well, but the beans struggled in the shade.\n\n" +
            "Next week I want to build a second raised bed near the fence.\n"
}
