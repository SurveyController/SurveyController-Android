package com.surveycontroller.android.core.questions

/**
 * 填空文本解析。1:1 复刻 software/core/questions/text_values.py:resolve_text_values_from_config。
 */
import kotlin.random.Random

object TextValues {
    const val MULTI_TEXT_DELIMITER = "\u0001"
    const val DESKTOP_MULTI_TEXT_DELIMITER = "||"
    const val MODE_RANDOM_NAME = "random_name"
    const val MODE_RANDOM_MOBILE = "random_mobile"
    const val MODE_RANDOM_ID_CARD = "random_id_card"
    const val MODE_RANDOM_INTEGER = "random_integer"

    fun resolve(
        candidates: List<String>,
        probs: List<Double>,
        blankCount: Int,
        entryType: String = "text",
        blankModes: List<String> = emptyList(),
        blankIntRanges: List<List<Int>> = emptyList(),
        gender: String? = null,
        random: Random = Random.Default,
        referenceYear: Int? = null,
    ): List<String> {
        val cands = candidates.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(RandomText.DEFAULT_FILL_TEXT) }
        var weights = probs.toMutableList()
        when {
            weights.size < cands.size -> weights.addAll(List(cands.size - weights.size) { 0.0 })
            weights.size > cands.size -> weights = weights.subList(0, cands.size)
        }
        val normalized = try {
            Probabilities.normalize(weights)
        } catch (e: Exception) {
            List(cands.size) { 1.0 / cands.size }
        }
        val selectedRaw = cands[Probabilities.weightedIndex(normalized, random)]
        val resolvedBlankCount = maxOf(1, blankCount)

        val textValues = if (entryType.trim() == "multi_text") {
            splitMultiTextCandidate(selectedRaw).map { RandomText.resolveDynamicToken(it, gender, random, referenceYear) }.toMutableList()
        } else {
            mutableListOf(RandomText.resolveDynamicToken(selectedRaw, gender, random, referenceYear))
        }
        if (textValues.isEmpty()) textValues.add(RandomText.DEFAULT_FILL_TEXT)
        while (textValues.size < resolvedBlankCount) textValues.add(textValues.last())
        while (textValues.size > resolvedBlankCount) textValues.removeAt(textValues.size - 1)

        for (i in 0 until resolvedBlankCount) {
            val mode = blankModes.getOrNull(i)?.trim()?.lowercase().orEmpty()
            when (mode) {
                MODE_RANDOM_NAME, "name" -> textValues[i] = RandomText.resolveDynamicToken("__RANDOM_NAME__", gender, random, referenceYear)
                MODE_RANDOM_MOBILE, "mobile" -> textValues[i] = RandomText.resolveDynamicToken("__RANDOM_MOBILE__", gender, random, referenceYear)
                MODE_RANDOM_ID_CARD, "id_card" -> textValues[i] = RandomText.resolveDynamicToken("__RANDOM_ID_CARD__", gender, random, referenceYear)
                MODE_RANDOM_INTEGER, "integer" -> {
                    val range = blankIntRanges.getOrNull(i)
                    if (range != null && range.size >= 2) {
                        val token = "${RandomText.RANDOM_INT_TOKEN_PREFIX}${minOf(range[0], range[1])}:${maxOf(range[0], range[1])}"
                        textValues[i] = RandomText.resolveDynamicToken(token, gender, random, referenceYear)
                    }
                }
            }
        }
        return textValues.map { it.trim().ifEmpty { RandomText.DEFAULT_FILL_TEXT } }
    }

    fun splitMultiTextCandidate(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val delimiter = when {
            MULTI_TEXT_DELIMITER in text -> MULTI_TEXT_DELIMITER
            DESKTOP_MULTI_TEXT_DELIMITER in text -> DESKTOP_MULTI_TEXT_DELIMITER
            else -> return listOf(text)
        }
        return text.split(delimiter).map { it.trim() }
    }
}
