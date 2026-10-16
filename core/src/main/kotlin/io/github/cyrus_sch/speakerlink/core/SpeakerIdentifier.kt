package io.github.cyrus_sch.speakerlink.core

import java.util.Locale

/** Where a speaker's model information came from. */
enum class IdentificationSource {
    /** The user picked the model by hand. */
    USER,

    /** The product ID in the speaker's Bluetooth LE advertisement. */
    ADVERTISEMENT,

    /** The speaker's Bluetooth name. */
    NAME,

    /** Nothing matched. */
    NONE,
}

data class Identification(
    val model: SpeakerModel?,
    val source: IdentificationSource,
    /** True when the device is (very likely) a JBL speaker, even if the exact model is unknown. */
    val isJbl: Boolean,
)

object SpeakerIdentifier {

    private val nonAlphanumeric = Regex("[^a-z0-9]+")
    private val letterDigit = Regex("(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])")
    private val whitespace = Regex("\\s+")
    private val jblWord = Regex("\\bjbl\\b")

    /**
     * Lower-cases a Bluetooth name, turns punctuation into spaces and separates letters from
     * digits, so "JBL Flip6", "jbl-flip-6" and "JBL FLIP 6" all become "jbl flip 6".
     */
    fun normalize(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        val spaced = nonAlphanumeric.replace(lower, " ")
        return whitespace.replace(letterDigit.replace(spaced, " "), " ").trim()
    }

    fun looksLikeJbl(name: String?): Boolean =
        name != null && jblWord.containsMatchIn(normalize(name))

    /**
     * True when two Bluetooth names plausibly belong to the same speaker. LE advertisements often
     * carry a truncated name ("JBL Fli"), so a prefix of at least six characters also counts.
     */
    fun sameSpeakerName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = normalize(a)
        val y = normalize(b)
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= 6 && long.startsWith(short)
    }

    /**
     * Finds the model whose name pattern matches the longest part of [name]. Longest-match keeps
     * "JBL PartyBox Encore Essential 2" from being read as "PartyBox Encore".
     */
    fun modelFromName(name: String?): SpeakerModel? {
        if (name.isNullOrBlank()) return null
        val normalized = normalize(name)
        var best: SpeakerModel? = null
        var bestLength = 0
        for (model in ModelCatalog.all) {
            for (pattern in model.namePatterns) {
                val match = pattern.find(normalized) ?: continue
                val length = match.value.length
                if (length > bestLength) {
                    best = model
                    bestLength = length
                }
            }
        }
        return best
    }

    /**
     * Identifies a speaker. A model the user picked wins over the advertised product ID, which
     * wins over the name (names can be changed by the user, product IDs cannot).
     */
    fun identify(name: String?, advertisedPid: Int? = null, userModelId: String? = null): Identification {
        ModelCatalog.byId(userModelId)?.let {
            return Identification(it, IdentificationSource.USER, isJbl = true)
        }
        ModelCatalog.byPid(advertisedPid)?.let {
            return Identification(it, IdentificationSource.ADVERTISEMENT, isJbl = true)
        }
        // Harman's company ID is shared with Harman Kardon and others, so an unknown product ID
        // alone does not make a device a JBL speaker.
        val jblName = looksLikeJbl(name)
        val fromName = modelFromName(name)
        if (fromName != null && jblName) {
            return Identification(fromName, IdentificationSource.NAME, isJbl = true)
        }
        return Identification(null, IdentificationSource.NONE, isJbl = jblName)
    }
}
