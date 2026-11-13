package com.surveycontroller.android.core.reverse_fill

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.ReverseFillAnswer
import com.surveycontroller.android.core.questions.ReverseFillResolver
import java.util.concurrent.ConcurrentHashMap

const val REVERSE_FILL_FORMAT_AUTO = "auto"
const val REVERSE_FILL_FORMAT_WJX_SEQUENCE = "wjx_sequence"
const val REVERSE_FILL_FORMAT_WJX_SCORE = "wjx_score"
const val REVERSE_FILL_FORMAT_WJX_TEXT = "wjx_text"
const val REVERSE_FILL_STATUS_REVERSE = "reverse_fill"
const val REVERSE_FILL_STATUS_FALLBACK = "fallback_config"
const val REVERSE_FILL_STATUS_BLOCKED = "blocked"

data class ReverseFillSample(val rowNumber: Int, val answers: Map<Int, ReverseFillAnswer>)

data class ReverseFillQuestionPlan(
    val questionNum: Int,
    val title: String,
    val entryType: QuestionEntryType,
    val columnIndexes: List<Int>,
    val columnHeaders: List<String>,
    val matchedByHeader: Boolean,
    val status: String = REVERSE_FILL_STATUS_REVERSE,
    val detail: String = "",
    val sampleRows: List<Int> = emptyList(),
)

data class ReverseFillPreviewRow(
    val rowNumber: Int,
    val answeredQuestions: Int,
    val totalQuestions: Int,
)

data class ReverseFillSpec(
    val format: String,
    val totalSamples: Int,
    val samples: List<ReverseFillSample>,
    val questionPlans: List<ReverseFillQuestionPlan> = emptyList(),
    val previewRows: List<ReverseFillPreviewRow> = emptyList(),
)

/**
 * 反向填充：把已有问卷导出的 xlsx 回放为作答。复刻 software/core/reverse_fill。
 */
object ReverseFillBuilder {

    private sealed class ParseResult {
        data class Answer(val value: ReverseFillAnswer) : ParseResult()
        data class Invalid(val reason: String) : ParseResult()
        data object Blank : ParseResult()
    }

    private data class PlanIssue(
        val status: String,
        val detail: String,
        val sampleRows: List<Int> = emptyList(),
    )

    private fun normalizeText(value: String?): String =
        value?.trim()?.replace(Regex("\\s+"), "") ?: ""

    private fun normalizeKey(value: String?): String {
        var t = normalizeText(value)
        t = t.replace("（", "(").replace("）", ")").replace("【", "[").replace("】", "]")
            .replace("—", "-").replace("–", "-").replace("－", "-").replace("：", ":")
        return t.lowercase()
    }

    private val leadingIndex = Regex("^(?:第\\s*)?[\\(\\[（【]?\\s*\\d+\\s*(?:题)?\\s*[\\)\\]）】]?[\\.。．、:：\\-\\s]*")
    private val questionHeaderNumber = Regex("^\\s*(?:第\\s*)?[\\(\\[（【]?\\s*(\\d+)\\s*(?:题)?\\s*(?:[\\)\\]）】]|[\\.。．、,，:：\\-])")
    private val questionHeaderNumberWithTitle = Regex("^\\s*第\\s*(\\d+)\\s*题")

    private fun labelVariants(value: String?): List<String> {
        val text = value?.trim() ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        val variants = LinkedHashSet<String>()
        normalizeKey(text).let { if (it.isNotEmpty()) variants.add(it) }
        val stripped = leadingIndex.replace(text, "").trim().trim('_')
        normalizeKey(stripped).let { if (it.isNotEmpty()) variants.add(it) }
        val normalizedStripped = stripped.replace("—", "-").replace("–", "-").replace("－", "-")
        for (sep in listOf("-", ":", "丨", "|", "/", "／")) {
            if (sep in normalizedStripped) {
                normalizeKey(normalizedStripped.substringAfterLast(sep).trim().trim('_')).let {
                    if (it.isNotEmpty()) variants.add(it)
                }
            }
        }
        return variants.toList()
    }

    private fun explicitQuestionNumber(value: String?): Int? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        questionHeaderNumber.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return questionHeaderNumberWithTitle.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /**
     * 构建规格：按表头把列匹配到题目，逐行解析答案。
     * @param rows XlsxReader 输出（含表头行）
     */
    fun build(
        rows: List<Map<Int, String>>,
        questions: List<SurveyQuestionMeta>,
        startRow: Int,
        format: String,
        targetNum: Int,
    ): ReverseFillSpec {
        if (rows.isEmpty()) return ReverseFillSpec(format, 0, emptyList())
        val header = rows.first()
        val maxCol = (rows.flatMap { it.keys } + header.keys).maxOrNull() ?: 0

        // 列匹配：为每个题目分配连续列
        val questionColumns = matchColumns(header, maxCol, questions)
        val activeQuestions = questions.filter { it.num > 0 && !it.isDescription && !it.unsupported }
        val normalizedStartRow = maxOf(1, startRow)
        val dataRows = rows.drop(normalizedStartRow)
        val answersByRow = List(dataRows.size) { HashMap<Int, ReverseFillAnswer>() }
        val planIssues = activeQuestions.mapNotNull { q ->
            unsupportedPlanIssue(q)?.let { q.num to it }
        }.toMap(HashMap())
        activeQuestions.forEach { q ->
            if (planIssues.containsKey(q.num)) return@forEach
            val match = questionColumns[q.num] ?: return@forEach
            columnCountIssue(q, match.columns, header, format)?.let { planIssues[q.num] = it }
        }

        dataRows.forEachIndexed { idx, row ->
            val dataRowNumber = normalizedStartRow + idx
            for (q in activeQuestions) {
                if (planIssues.containsKey(q.num)) continue
                val match = questionColumns[q.num] ?: continue
                when (val result = parseAnswer(q, match.columns, row, format, header)) {
                    is ParseResult.Answer -> answersByRow[idx][q.num] = result.value
                    ParseResult.Blank -> Unit
                    is ParseResult.Invalid -> {
                        for (rowAnswers in answersByRow) rowAnswers.remove(q.num)
                        planIssues[q.num] = PlanIssue(
                            status = REVERSE_FILL_STATUS_FALLBACK,
                            detail = result.reason,
                            sampleRows = listOf(dataRowNumber),
                        )
                    }
                }
            }
        }

        val questionPlans = activeQuestions.mapNotNull { q ->
            val match = questionColumns[q.num] ?: return@mapNotNull null
            val issue = planIssues[q.num]
            ReverseFillQuestionPlan(
                questionNum = q.num,
                title = q.title.ifBlank { "第${q.num}题" },
                entryType = q.entryType,
                columnIndexes = match.columns,
                columnHeaders = match.columns.map { header[it]?.trim().orEmpty() },
                matchedByHeader = match.matchedByHeader,
                status = issue?.status ?: REVERSE_FILL_STATUS_REVERSE,
                detail = issue?.detail ?: "",
                sampleRows = issue?.sampleRows ?: emptyList(),
            )
        }

        val samples = mutableListOf<ReverseFillSample>()
        val previewRows = mutableListOf<ReverseFillPreviewRow>()
        val replayQuestionCount = activeQuestions.count { q ->
            planIssues[q.num]?.status == null || planIssues[q.num]?.status == REVERSE_FILL_STATUS_REVERSE
        }
        answersByRow.forEachIndexed { idx, answers ->
            val dataRowNumber = normalizedStartRow + idx
            if (idx < 5) {
                previewRows.add(
                    ReverseFillPreviewRow(
                        rowNumber = dataRowNumber,
                        answeredQuestions = answers.size,
                        totalQuestions = replayQuestionCount,
                    ),
                )
            }
            samples.add(ReverseFillSample(dataRowNumber, answers))
        }
        return ReverseFillSpec(format, dataRows.size, samples, questionPlans, previewRows)
    }

    private fun unsupportedPlanIssue(q: SurveyQuestionMeta): PlanIssue? {
        return when (q.entryType) {
            QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN -> {
                if (q.fillableOptions.isNotEmpty() || q.attachedOptionSelects.isNotEmpty()) {
                    PlanIssue(
                        status = REVERSE_FILL_STATUS_FALLBACK,
                        detail = "选项附加填空或内嵌下拉暂不参与反填回放，已使用常规配置",
                    )
                } else {
                    null
                }
            }
            QuestionEntryType.SCALE, QuestionEntryType.SCORE,
            QuestionEntryType.TEXT, QuestionEntryType.MULTI_TEXT,
            QuestionEntryType.MATRIX -> null
            QuestionEntryType.MULTIPLE -> {
                if (q.fillableOptions.isNotEmpty() || q.attachedOptionSelects.isNotEmpty()) {
                    PlanIssue(
                        status = REVERSE_FILL_STATUS_FALLBACK,
                        detail = "多选题包含选项附加填空或内嵌下拉，已使用常规配置",
                    )
                } else {
                    null
                }
            }
            QuestionEntryType.ORDER -> PlanIssue(
                status = REVERSE_FILL_STATUS_BLOCKED,
                detail = "排序题目前不参与反填覆盖，运行时按常规排序逻辑处理",
            )
            QuestionEntryType.SLIDER, QuestionEntryType.LOCATION -> null
        }
    }

    private val multipleColumnIndexRe = Regex("""[（(]\s*选项\s*(\d+)\s*[)）]\s*$""")
    private val questionHeaderPrefixRe = Regex("""^\s*(\d+)\s*[、,.，．]\s*(.*?)\s*$""")

    private fun columnSuffix(header: String?): String =
        questionHeaderPrefixRe.find(header.orEmpty())?.groupValues?.getOrNull(2)?.trim().orEmpty()

    /** 对齐 load_wjx_excel_export 的格式检测：表头带「(选项N)」即视为序号导出。 */
    private fun effectiveFormat(columns: List<Int>, header: Map<Int, String>, format: String): String {
        if (format != REVERSE_FILL_FORMAT_AUTO) return format
        val hasSequenceColumn = columns.any { multipleColumnIndexRe.containsMatchIn(columnSuffix(header[it])) }
        return if (hasSequenceColumn) REVERSE_FILL_FORMAT_WJX_SEQUENCE else REVERSE_FILL_FORMAT_WJX_TEXT
    }

    /** 多选题是否按「每题 N 列、每列 0/1」解析（对标 parse_multiple_row_answer）。 */
    private fun shouldUseMultipleColumns(
        q: SurveyQuestionMeta,
        columns: List<Int>,
        header: Map<Int, String>,
        format: String,
    ): Boolean {
        if (columns.size != 1) return true
        val resolvedFormat = effectiveFormat(columns, header, format)
        return resolvedFormat == REVERSE_FILL_FORMAT_WJX_SEQUENCE &&
            multipleColumnIndexRe.containsMatchIn(columnSuffix(header[columns[0]]))
    }

    private fun expectedColumnCount(
        q: SurveyQuestionMeta,
        columns: List<Int>,
        header: Map<Int, String>,
        format: String,
    ): Int = when (q.entryType) {
        QuestionEntryType.MATRIX -> maxOf(1, q.rows)
        QuestionEntryType.MULTI_TEXT -> maxOf(1, q.textInputs)
        QuestionEntryType.MULTIPLE ->
            if (shouldUseMultipleColumns(q, columns, header, format)) maxOf(1, q.optionTexts.size) else 1
        else -> 1
    }

    private fun columnCountIssue(
        q: SurveyQuestionMeta,
        columns: List<Int>,
        header: Map<Int, String>,
        format: String,
    ): PlanIssue? {
        val expected = expectedColumnCount(q, columns, header, format)
        val actual = columns.size
        if (actual == expected) return null
        val detail = when (q.entryType) {
            QuestionEntryType.MATRIX ->
                "矩阵题解析出 $expected 行，但 Excel 中对应了 $actual 列，已使用常规配置"
            QuestionEntryType.MULTI_TEXT ->
                "多项填空解析出 $expected 个空，但 Excel 中对应了 $actual 列，已使用常规配置"
            QuestionEntryType.MULTIPLE ->
                "多选题解析出 $expected 个选项，但 Excel 中对应了 $actual 列，已使用常规配置"
            else ->
                "这道题在 Excel 中对应了 $actual 列，无法确认唯一答案列，已使用常规配置"
        }
        return PlanIssue(status = REVERSE_FILL_STATUS_FALLBACK, detail = detail)
    }

    private data class ColumnMatch(
        val columns: List<Int>,
        val matchedByHeader: Boolean,
    )

    private fun matchColumns(
        header: Map<Int, String>,
        maxCol: Int,
        questions: List<SurveyQuestionMeta>,
    ): Map<Int, ColumnMatch> {
        val result = HashMap<Int, ColumnMatch>()
        val used = HashSet<Int>()
        val explicitColumnNums = HashMap<Int, Int>()
        val explicitByQuestion = HashMap<Int, MutableList<Int>>()
        for (c in 0..maxCol) {
            val questionNum = explicitQuestionNumber(header[c]) ?: continue
            explicitColumnNums[c] = questionNum
            explicitByQuestion.getOrPut(questionNum) { mutableListOf() }.add(c)
        }
        // 按题目顺序，从左到右分配列
        var cursor = 0
        for (q in questions) {
            if (q.num <= 0 || q.isDescription || q.unsupported) continue
            val needed = when (q.entryType) {
                QuestionEntryType.MATRIX -> maxOf(1, q.rows)
                QuestionEntryType.MULTI_TEXT -> maxOf(1, q.textInputs)
                else -> 1
            }
            val explicitCols = explicitByQuestion[q.num]
                ?.filter { it !in used }
                ?.sorted()
                ?.takeIf { it.isNotEmpty() }
            if (explicitCols != null) {
                val cols = explicitCols
                cols.forEach { used.add(it) }
                result[q.num] = ColumnMatch(orderColumnsForQuestion(q, cols, header), true)
                cursor = (cols.maxOrNull() ?: cursor) + 1
                continue
            }
            // 优先按表头文本匹配首列
            var startCol = -1
            var matchedByHeader = false
            val titleVariants = labelVariants(q.title)
            for (c in 0..maxCol) {
                if (c in used) continue
                if (explicitColumnNums[c] != null) continue
                val h = header[c] ?: continue
                if (labelVariants(h).any { v -> titleVariants.any { it == v || v.contains(it) || it.contains(v) } }) {
                    startCol = c
                    matchedByHeader = true
                    break
                }
            }
            if (startCol < 0) {
                // 回退：顺序分配下一批未用列
                while (cursor <= maxCol && (cursor in used || explicitColumnNums[cursor] != null)) cursor++
                startCol = cursor
            }
            val cols = mutableListOf<Int>()
            var c = startCol
            while (cols.size < needed && c <= maxCol) {
                if (c !in used && explicitColumnNums[c] == null) { cols.add(c); used.add(c) }
                c++
            }
            if (cols.isNotEmpty()) {
                result[q.num] = ColumnMatch(orderColumnsForQuestion(q, cols, header), matchedByHeader)
            }
            cursor = (cols.maxOrNull() ?: startCol) + 1
        }
        return result
    }

    private fun orderColumnsForQuestion(
        q: SurveyQuestionMeta,
        cols: List<Int>,
        header: Map<Int, String>,
    ): List<Int> {
        val ordered = cols.sorted()
        val labels = when (q.entryType) {
            QuestionEntryType.MATRIX -> q.rowTexts
            QuestionEntryType.MULTI_TEXT -> q.textInputLabels
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }
        if (ordered.isEmpty() || labels.isEmpty()) return ordered

        val labelIndexMap = HashMap<String, Int>()
        labels.forEachIndexed { index, label ->
            labelVariants(label).forEach { variant ->
                labelIndexMap.putIfAbsent(variant, index)
            }
        }
        val resolved = MutableList<Int?>(labels.size) { null }
        val usedIndexes = HashSet<Int>()
        for (col in ordered) {
            val headerText = header[col]?.trim().orEmpty()
            val matches = labelVariants(headerText)
                .mapNotNull { labelIndexMap[it] }
                .distinct()
            if (matches.isEmpty()) {
                if (ordered.size == labels.size) return ordered
                continue
            }
            if (matches.size != 1) return ordered
            val targetIndex = matches.first()
            if (targetIndex in usedIndexes) return ordered
            resolved[targetIndex] = col
            usedIndexes.add(targetIndex)
        }
        if (resolved.any { it == null }) return ordered
        return resolved.filterNotNull()
    }

    private fun parseAnswer(
        q: SurveyQuestionMeta,
        cols: List<Int>,
        row: Map<Int, String>,
        format: String,
        header: Map<Int, String>,
    ): ParseResult {
        return when (q.entryType) {
            QuestionEntryType.SINGLE, QuestionEntryType.DROPDOWN,
            QuestionEntryType.SCALE, QuestionEntryType.SCORE -> {
                val raw = row[cols.first()]?.trim().orEmpty()
                if (raw.isEmpty()) return ParseResult.Blank
                val idx = resolveChoiceIndex(raw, q.optionTexts, format)
                    ?: return ParseResult.Invalid(choiceParseFailure(raw, q.optionTexts, format))
                ParseResult.Answer(ReverseFillAnswer(kind = "choice", choiceIndex = idx))
            }
            QuestionEntryType.MULTIPLE -> {
                if (shouldUseMultipleColumns(q, cols, header, format)) {
                    parseMultipleRowAnswer(q, cols, row, format, header)
                } else {
                    val raw = row[cols.first()]?.trim().orEmpty()
                    if (raw.isEmpty()) return ParseResult.Blank
                    val indexes = resolveMultipleChoiceIndexes(raw, q.optionTexts, format)
                        ?: return ParseResult.Invalid(multipleChoiceParseFailure(raw, q.optionTexts, format))
                    if (indexes.isEmpty()) {
                        return ParseResult.Invalid("多选题答案为空，无法稳定回放")
                    }
                    validateMultipleCount(q, indexes.size)?.let { return ParseResult.Invalid(it) }
                    ParseResult.Answer(ReverseFillAnswer(kind = "choice", choiceIndexes = indexes))
                }
            }
            QuestionEntryType.TEXT -> {
                val raw = row[cols.first()]?.trim().orEmpty()
                if (raw.isEmpty()) return ParseResult.Blank
                ParseResult.Answer(ReverseFillAnswer(kind = "text", textValue = raw))
            }
            QuestionEntryType.MULTI_TEXT -> {
                val values = cols.map { row[it]?.trim().orEmpty() }
                if (values.all { it.isEmpty() }) return ParseResult.Blank
                ParseResult.Answer(ReverseFillAnswer(kind = "multi_text", textValues = values))
            }
            QuestionEntryType.MATRIX -> {
                val raws = cols.map { row[it]?.trim().orEmpty() }
                if (raws.all { it.isEmpty() }) return ParseResult.Blank
                if (raws.any { it.isEmpty() }) return ParseResult.Invalid("矩阵题存在部分行为空，无法稳定回放")
                val indexes = raws.map { raw ->
                    resolveChoiceIndex(raw, q.optionTexts, format)
                        ?: return ParseResult.Invalid(choiceParseFailure(raw, q.optionTexts, format))
                }
                ParseResult.Answer(ReverseFillAnswer(kind = "matrix", matrixChoiceIndexes = indexes))
            }
            QuestionEntryType.SLIDER -> {
                val raw = row[cols.first()]?.trim().orEmpty()
                if (raw.isEmpty()) return ParseResult.Blank
                val value = parseSliderValue(raw) ?: return ParseResult.Invalid("无法把“$raw”解析为滑块数值")
                ParseResult.Answer(ReverseFillAnswer(kind = "slider", sliderValue = value))
            }
            QuestionEntryType.LOCATION -> {
                val raw = row[cols.first()]?.trim().orEmpty()
                if (raw.isEmpty()) return ParseResult.Blank
                val parts = parseLocationParts(raw)
                if (parts.size < 3) return ParseResult.Invalid("无法把“$raw”解析为省/市/区县三段")
                ParseResult.Answer(ReverseFillAnswer(kind = "location", locationParts = parts))
            }
            else -> ParseResult.Blank
        }
    }

    private val numberText = Regex("^\\d+(?:\\.0+)?$")

    private fun unsupportedChoiceReason(raw: String): String? {
        return when {
            "┋" in raw -> "检测到多选串（┋），反填暂不支持该复合值"
            "→" in raw -> "检测到排序串（→），反填暂不支持该复合值"
            "〖" in raw && "〗" in raw -> "检测到“选项+附加填空”复合值，反填暂不支持"
            else -> null
        }
    }

    private fun choiceParseFailure(raw: String, optionTexts: List<String>, format: String): String {
        unsupportedChoiceReason(raw)?.let { return it }
        if (format == REVERSE_FILL_FORMAT_WJX_SEQUENCE) {
            val oneBased = raw.substringBefore(".").toIntOrNull()
            return if (oneBased == null) {
                "无法把“$raw”解析为选项序号"
            } else {
                "选项序号 $oneBased 超出范围（共 ${optionTexts.size} 个选项）"
            }
        }
        return "无法把“$raw”匹配到题目选项"
    }

    private fun multipleChoiceParseFailure(raw: String, optionTexts: List<String>, format: String): String {
        if ("〖" in raw && "〗" in raw) return "检测到“选项+附加填空”复合值，反填暂不支持"
        if ("→" in raw) return "检测到排序串（→），反填暂不支持该复合值"
        if (format == REVERSE_FILL_FORMAT_WJX_SEQUENCE) {
            return "无法把“$raw”解析为多选选项序号"
        }
        return "无法把“$raw”匹配到多选题选项"
    }

    /** 选项文本 → 索引；重复文本（歧义）记为 null，避免静默选错。 */
    private fun optionIndexMap(optionTexts: List<String>): Map<String, Int?> {
        val mapping = HashMap<String, Int?>()
        optionTexts.forEachIndexed { index, text ->
            for (variant in labelVariants(text)) {
                if (mapping.containsKey(variant) && mapping[variant] != index) {
                    mapping[variant] = null
                } else {
                    mapping[variant] = index
                }
            }
        }
        return mapping
    }

    private fun exactOptionMatches(raw: String, optionTexts: List<String>): List<Int> {
        val key = normalizeKey(raw)
        return optionTexts.indices.filter { normalizeKey(optionTexts[it]) == key }
    }

    private fun resolveChoiceIndex(raw: String, optionTexts: List<String>, format: String): Int? {
        if (unsupportedChoiceReason(raw) != null) return null
        // 序号导出只按序号解析，避免选项文本恰好是数字时误判。
        if (format == REVERSE_FILL_FORMAT_WJX_SEQUENCE) {
            if (!numberText.matches(raw)) return null
            val oneBased = raw.substringBefore(".").toIntOrNull() ?: return null
            return (oneBased - 1).takeIf { it in optionTexts.indices }
        }
        val exact = exactOptionMatches(raw, optionTexts)
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) return null
        // 文本匹配
        val variants = labelVariants(raw)
        val optionMap = optionIndexMap(optionTexts)
        for (v in variants) {
            if (!optionMap.containsKey(v)) continue
            return optionMap[v]
        }
        // 序号兜底（分数/文本导出允许纯数字）
        if (numberText.matches(raw)) {
            val oneBased = raw.substringBefore(".").toIntOrNull()
            if (oneBased != null && oneBased in 1..optionTexts.size) return oneBased - 1
        }
        for (v in variants) {
            if (!optionMap.containsKey(v)) continue
            return optionMap[v]
        }
        return null
    }

    private fun resolveMultipleChoiceIndexes(raw: String, optionTexts: List<String>, format: String): List<Int>? {
        if ("〖" in raw && "〗" in raw) return null
        if ("→" in raw) return null
        val parts = splitMultipleValue(raw)
        if (parts.isEmpty()) return null
        val indexes = LinkedHashSet<Int>()
        for (part in parts) {
            val idx = resolvePlainChoiceIndex(part, optionTexts, format) ?: return null
            indexes.add(idx)
        }
        return indexes.toList().takeIf { it.isNotEmpty() }?.sorted()
    }

    /** 对标 parse_multiple_row_answer：多选答案按「每题 N 列、每列 0/1」逐列回放。 */
    private fun parseMultipleRowAnswer(
        q: SurveyQuestionMeta,
        cols: List<Int>,
        row: Map<Int, String>,
        format: String,
        header: Map<Int, String>,
    ): ParseResult {
        val optionTexts = q.optionTexts
        val resolvedFormat = effectiveFormat(cols, header, format)
        if (optionTexts.isEmpty() || cols.size != optionTexts.size) {
            return ParseResult.Invalid("多选题的选项数量与 Excel 列数不匹配")
        }

        val indexesByColumn = LinkedHashMap<Int, Int>()
        val usedIndexes = HashSet<Int>()
        for (col in cols) {
            val suffix = columnSuffix(header[col])
            val index = if (resolvedFormat == REVERSE_FILL_FORMAT_WJX_SEQUENCE) {
                val match = multipleColumnIndexRe.find(suffix)
                    ?: return ParseResult.Invalid("多选题列“${header[col].orEmpty()}”缺少选项序号")
                match.groupValues[1].toIntOrNull()?.minus(1)
                    ?: return ParseResult.Invalid("多选题列“${header[col].orEmpty()}”的选项序号无效")
            } else {
                val suffixKey = normalizeKey(suffix)
                val matches = optionTexts.indices.filter { optionIndex ->
                    val labelKey = normalizeKey(optionTexts[optionIndex])
                    suffixKey == labelKey || suffixKey.endsWith("($labelKey)")
                }
                if (matches.size != 1) {
                    return ParseResult.Invalid("多选题列“${header[col].orEmpty()}”无法唯一匹配选项")
                }
                matches[0]
            }
            if (index !in optionTexts.indices || !usedIndexes.add(index)) {
                return ParseResult.Invalid("多选题列“${header[col].orEmpty()}”的选项超出范围或重复")
            }
            indexesByColumn[col] = index
        }

        val values = cols.map { row[it]?.trim().orEmpty() }
        if (values.all { it.isEmpty() }) return ParseResult.Blank
        val selected = mutableListOf<Int>()
        for ((col, value) in cols.zip(values)) {
            if (value !in setOf("0", "1")) {
                return ParseResult.Invalid("多选题列“${header[col].orEmpty()}”的值必须为 0 或 1")
            }
            if (value == "1") selected.add(indexesByColumn.getValue(col))
        }
        if (selected.isEmpty()) {
            return ParseResult.Invalid("多选题答案为空，无法稳定回放")
        }
        validateMultipleCount(q, selected.size)?.let { return ParseResult.Invalid(it) }
        return ParseResult.Answer(ReverseFillAnswer(kind = "choice", choiceIndexes = selected.sorted()))
    }

    private fun validateMultipleCount(q: SurveyQuestionMeta, count: Int): String? {
        val minimum = q.multiMinLimit?.takeIf { it > 0 }
        val maximum = q.multiMaxLimit?.takeIf { it > 0 }
        if (minimum != null && count < minimum) {
            return "多选题选中 $count 项，少于问卷要求的至少 $minimum 项"
        }
        if (maximum != null && count > maximum) {
            return "多选题选中 $count 项，多于问卷要求的至多 $maximum 项"
        }
        return null
    }

    private fun splitMultipleValue(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return text.split("┋").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun resolvePlainChoiceIndex(raw: String, optionTexts: List<String>, format: String): Int? {
        if (format == REVERSE_FILL_FORMAT_WJX_SEQUENCE) {
            if (!numberText.matches(raw)) return null
            val oneBased = raw.substringBefore(".").toIntOrNull() ?: return null
            return (oneBased - 1).takeIf { it in optionTexts.indices }
        }
        val exact = exactOptionMatches(raw, optionTexts)
        if (exact.size == 1) return exact[0]
        if (exact.size > 1) return null
        val variants = labelVariants(raw)
        val optionMap = optionIndexMap(optionTexts)
        for (v in variants) {
            if (!optionMap.containsKey(v)) continue
            return optionMap[v]
        }
        if (numberText.matches(raw)) {
            val oneBased = raw.substringBefore(".").toIntOrNull()
            if (oneBased != null && oneBased in 1..optionTexts.size) return oneBased - 1
        }
        for (v in variants) {
            if (!optionMap.containsKey(v)) continue
            return optionMap[v]
        }
        return null
    }

    private fun parseSliderValue(raw: String): Double? {
        val text = raw.trim()
            .removeSuffix("分")
            .removeSuffix("滑块")
            .trim()
            .replace("，", ".")
        val match = Regex("""[-+]?\d+(?:\.\d+)?""").find(text) ?: return null
        return match.value.toDoubleOrNull()?.takeUnless { it.isNaN() || it.isInfinite() }
    }

    private fun parseLocationParts(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val normalized = text
            .replace("，", ",")
            .replace("、", ",")
            .replace("/", ",")
            .replace("／", ",")
            .replace("\\", ",")
            .replace("|", ",")
            .replace("｜", ",")
            .replace(";", ",")
            .replace("；", ",")
            .replace("-", ",")
            .replace("—", ",")
            .replace("–", ",")
            .replace("－", ",")
        val explicit = normalized.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(3)
        return explicit
    }
}

/**
 * 反向填充运行时：按线程预约样本行，提交成功提交、失败可回收。
 */
class ReverseFillRuntime(spec: ReverseFillSpec) : ReverseFillResolver {
    private val queue = ArrayDeque(spec.samples)
    private val reservedByThread = ConcurrentHashMap<String, ReverseFillSample>()
    private val failureCountByRow = HashMap<Int, Int>()
    private val discardedRows = HashSet<Int>()
    private val committedRows = HashSet<Int>()
    private val lock = Any()

    fun reserve(threadName: String): Boolean {
        synchronized(lock) {
            reservedByThread[threadName]?.let { return true }
            val next = queue.removeFirstOrNull() ?: return false
            reservedByThread[threadName] = next
            return true
        }
    }

    fun commit(threadName: String) {
        synchronized(lock) {
            val sample = reservedByThread.remove(threadName) ?: return
            committedRows.add(sample.rowNumber)
            failureCountByRow.remove(sample.rowNumber)
        }
    }

    fun discard(threadName: String, requeue: Boolean) {
        synchronized(lock) {
            val sample = reservedByThread.remove(threadName)
            if (requeue && sample != null && sample.rowNumber !in committedRows && sample.rowNumber !in discardedRows) {
                queue.addFirst(sample)
            }
        }
    }

    fun markFailed(threadName: String, maxRetries: Int = 1): Boolean {
        synchronized(lock) {
            val sample = reservedByThread.remove(threadName) ?: return false
            val rowNumber = sample.rowNumber
            val nextCount = (failureCountByRow[rowNumber] ?: 0) + 1
            failureCountByRow[rowNumber] = nextCount
            return if (nextCount <= maxOf(0, maxRetries)) {
                queue.addFirst(sample)
                false
            } else {
                discardedRows.add(rowNumber)
                true
            }
        }
    }

    val remaining: Int get() = synchronized(lock) { queue.size }

    fun canReachTarget(successCount: Int, target: Int): Boolean =
        synchronized(lock) {
            if (target <= 0) return@synchronized true
            successCount + queue.size + reservedByThread.size >= target
        }

    override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
        reservedByThread[threadName]?.answers?.get(questionNum)
}
