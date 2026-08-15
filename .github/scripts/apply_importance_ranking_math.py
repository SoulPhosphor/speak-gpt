from pathlib import Path


def nl_for(data: bytes) -> bytes:
    return b"\r\n" if b"\r\n" in data else b"\n"


def rep(path: str, old: str, new: str, count: int = 1):
    p = Path(path)
    data = p.read_bytes()
    nl = nl_for(data)
    old_b = old.replace("\n", nl.decode()).encode()
    new_b = new.replace("\n", nl.decode()).encode()
    found = data.count(old_b)
    if found != count:
        raise RuntimeError(f"{path}: expected {count} match(es), found {found}: {old[:100]!r}")
    p.write_bytes(data.replace(old_b, new_b, count))


lib = "app/src/main/java/org/teslasoft/assistant/preferences/memory/librarian/Librarian.kt"

rep(
    lib,
    '''* first. score = w_sim·cosine + w_imp·(importance/5) + w_rec·recency +''',
    '''* first. score = w_sim·cosine + w_imp·signedImportance + w_rec·recency +''',
)

rep(
    lib,
    '''topK: Int,
            minSimilarity: Float = 0f
        ): List<ScoredMemory> {''',
    '''topK: Int,
            minSimilarity: Float = 0f,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true
        ): List<ScoredMemory> {''',
)

rep(
    lib,
    '''var s = weights.similarity * sim +
                    weights.importance * (c.memory.importance / 5.0) +
                    weights.recency * c.recency''',
    '''val importance = if (useImportanceRatings) {
                    ImportanceRanking.normalizedRankingImportance(c.memory.importance.toDouble())
                } else 0.0
                var s = weights.similarity * sim +
                    weights.importance * importance +
                    weights.recency * c.recency''',
)

rep(
    lib,
    '''return scored.sortedByDescending { it.score }.take(topK)
        }

        /**
         * Pure lexical ranking''',
    '''val ranked = scored.sortedByDescending { it.score }
            return ImportanceRanking.includeMandatory(ranked, topK) { hit ->
                includeMandatoryImportance &&
                    ImportanceRanking.isMandatory(hit.memory.importance.toDouble(), useImportanceRatings)
            }
        }

        /**
         * Pure lexical ranking''',
)

rep(
    lib,
    '''weights: Weights,
            topK: Int
        ): List<ScoredMemory> {''',
    '''weights: Weights,
            topK: Int,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true
        ): List<ScoredMemory> {''',
)

rep(
    lib,
    '''var s = weights.similarity * relevance +
                    weights.importance * (mem.importance / 5.0) +
                    weights.recency * c.recency''',
    '''val importance = if (useImportanceRatings) {
                    ImportanceRanking.normalizedRankingImportance(mem.importance.toDouble())
                } else 0.0
                var s = weights.similarity * relevance +
                    weights.importance * importance +
                    weights.recency * c.recency''',
)

rep(
    lib,
    '''// No title bonus (§3.1): retrieval never rewards a title.
                ScoredMemory(mem, relevance, s.toFloat())
            }
            return scored.sortedByDescending { it.score }.take(topK)
        }''',
    '''// No title bonus (§3.1): retrieval never rewards a title.
                ScoredMemory(mem, relevance, s.toFloat())
            }
            val ranked = scored.sortedByDescending { it.score }
            return ImportanceRanking.includeMandatory(ranked, topK) { hit ->
                includeMandatoryImportance &&
                    ImportanceRanking.isMandatory(hit.memory.importance.toDouble(), useImportanceRatings)
            }
        }''',
)

rep(
    lib,
    '''topK: Int,
            onSemantic: ((Boolean) -> Unit)? = null
        ): List<ScoredMemory> {''',
    '''topK: Int,
            useImportanceRatings: Boolean = true,
            includeMandatoryImportance: Boolean = true,
            onSemantic: ((Boolean) -> Unit)? = null
        ): List<ScoredMemory> {''',
)

rep(
    lib,
    '''weights, topK, MIN_SIMILARITY
                    )''',
    '''weights, topK, MIN_SIMILARITY,
                        useImportanceRatings, includeMandatoryImportance
                    )''',
)

rep(
    lib,
    '''weights, topK
            )
        }

        /**
         * Freshness''',
    '''weights, topK,
                useImportanceRatings, includeMandatoryImportance
            )
        }

        /**
         * Freshness''',
)

rep(
    lib,
    '''val result = searchCore(
            query, embedQuery, corpus, rankingWeights ?: weights(store), topK
        ) { semantic ->''',
    '''val useImportanceRatings = try {
            Preferences.getPreferences(appContext, "").getUseImportanceRatings()
        } catch (_: Exception) { true }
        val result = searchCore(
            query = query,
            embedQuery = embedQuery,
            corpus = corpus,
            weights = rankingWeights ?: weights(store),
            topK = topK,
            useImportanceRatings = useImportanceRatings,
            // +3's overflow rule is for live prompt delivery, not the
            // Archivist's bounded reconciliation candidate pool.
            includeMandatoryImportance = useImportanceRatings && !reconciliation
        ) { semantic ->''',
)

# Update stale ranking tests from the abandoned 0..5 scale and explicitly
# prove +3 overflow and toggle-off behavior at the relevance-first ranking seam.
test = "app/src/test/java/org/teslasoft/assistant/preferences/memory/librarian/LibrarianRankingTest.kt"
rep(test, "importance: Int = 3", "importance: Int = 0")
rep(test, 'mem("low", importance = 1)', 'mem("low", importance = -1)')
rep(test, 'mem("high", importance = 5)', 'mem("high", importance = 2)')
rep(test, "// cosine ≈ 0.25 — below the 0.3 floor, but importance 5 and", "// cosine ≈ 0.25 — below the 0.3 floor, but mandatory +3 and")
rep(test, 'mem("irrelevant-important", importance = 5)', 'mem("irrelevant-important", importance = 3)')
rep(test, 'mem("relevant-strong", importance = 1)', 'mem("relevant-strong", importance = 0)')
rep(test, 'mem("relevant-weak", importance = 1)', 'mem("relevant-weak", importance = 0)')
rep(test, 'mem("weaker", importance = 5, scope = "campaign")', 'mem("weaker", importance = 2, scope = "campaign")')

rep(
    test,
    '''    @Test
    fun topKLimitsResults() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = (1..10).map {
            cand(mem("m$it"), floatArrayOf(1f, 0f, 0f))
        }
        assertEquals(3, Librarian.rank(query, candidates, weights, topK = 3).size)
    }

    /* -------- Stage 3.2''',
    '''    @Test
    fun topKLimitsResults() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = (1..10).map {
            cand(mem("m$it"), floatArrayOf(1f, 0f, 0f))
        }
        assertEquals(3, Librarian.rank(query, candidates, weights, topK = 3).size)
    }

    @Test
    fun plusThreeExtendsPastTopKOnlyWhenEnabled() {
        val query = floatArrayOf(1f, 0f, 0f)
        val candidates = listOf(
            cand(mem("normal", importance = 0), floatArrayOf(1f, 0f, 0f)),
            // Relevant but intentionally weaker, so it would fall outside topK
            // without the explicit +3 inclusion pass.
            cand(mem("mandatory", importance = 3), floatArrayOf(0.31f, 0.9507f, 0f))
        )

        val enabled = Librarian.rank(
            query, candidates, weights, topK = 1, minSimilarity = 0.3f,
            useImportanceRatings = true, includeMandatoryImportance = true
        )
        assertEquals(listOf("normal", "mandatory"), enabled.map { it.memory.memoryId })

        val disabled = Librarian.rank(
            query, candidates, weights, topK = 1, minSimilarity = 0.3f,
            useImportanceRatings = false, includeMandatoryImportance = false
        )
        assertEquals(listOf("normal"), disabled.map { it.memory.memoryId })
    }

    /* -------- Stage 3.2''',
)

# Self-clean temporary patching machinery from the resulting feature commit.
Path(".github/scripts/apply_importance_ranking_math.py").unlink(missing_ok=True)
Path(".github/workflows/apply-importance-ranking-math.yml").unlink(missing_ok=True)
