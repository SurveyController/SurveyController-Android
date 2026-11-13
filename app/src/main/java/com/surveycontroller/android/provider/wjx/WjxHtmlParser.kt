package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.model.QuestionMedia
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** 问卷已暂停/停止/未开放等状态异常。 */
class WjxPageStateException(message: String) : RuntimeException(message)

/**
 * 问卷星 HTML 解析。复刻 wjx/provider/html_parser*.py 的核心逻辑（Jsoup 版）。
 */
object WjxHtmlParser {

    private val displayNumRe = Regex("""^\*?\s*(\d+)[.．、]""")
    private val titleTagRe = Regex("""【[^】]*】""")
    private val leadingNumRe = Regex("""^\s*\d+\s*[.．、]\s*""")
    private val customSelectSeparatorRe = Regex("[,，\\n\\r|/]+")
    private val customSelectAttrNames = listOf("cusom", "custom", "data-custom", "data-cusom")
    private val forceSelectCommandRe = Regex("""请(?:务必|一定|必须|直接)?\s*选(?:择)?""")
    private val forceSelectIndexRe = Regex("""^第?\s*(\d{1,3})\s*(?:个|项|选项|分|星)?$""")
    private val forceSelectSentenceSplitRe = Regex("[。；;！？!\\n\\r]")
    private val forceSelectCleanRe = Regex("""[\s`'"“”‘’【】\[\]\(\)（）<>《》,，、。；;:：!?！？]""")
    private val forceSelectLabelTargetRe = Regex("""^([A-Za-z])(?:项|选项|答案)?$""")
    private val forceSelectOptionLabelRe = Regex("""^(?:第\s*)?[\(（【\[]?\s*([A-Za-z])\s*[\)）】\]]?(?=$|[.．、:：\-\s]|[\u4e00-\u9fff])""")
    private val pausedSurveyIdRe = Regex("""此问卷[（(]\d+[）)]已暂停""")
    private val notOpenTimeRe = Regex("""此问卷将于\s*(\d{4}[-/]\d{1,2}[-/]\d{1,2}\s+\d{1,2}:\d{2})\s*开放""")

    fun parse(html: String): Pair<List<SurveyQuestionMeta>, String> {
        raisePageStateErrors(html)
        val doc = Jsoup.parse(html)
        val title = extractTitle(doc)
        val questions = parseQuestions(doc)
        return questions to title
    }

    fun assertAnswerable(html: String) {
        raisePageStateErrors(html)
    }

    private fun extractTitle(doc: Document): String {
        val candidates = mutableListOf<String>()
        for (selector in listOf(
            "#divTitle h1",
            "#divTitle",
            ".surveytitle",
            ".survey-title",
            ".surveyTitle",
            ".wjdcTitle",
            ".htitle",
            ".topic_tit",
            "#htitle",
            "#lbTitle",
            ".survey_title",
        )) {
            doc.selectFirst(selector)?.let {
                val text = normalizeHtmlText(elementTextWithSpaces(it))
                if (text.isNotEmpty()) candidates.add(text)
            }
        }
        if (candidates.isEmpty()) {
            for (tagName in listOf("h1", "h2")) {
                doc.selectFirst(tagName)?.let {
                    val text = normalizeHtmlText(elementTextWithSpaces(it))
                    if (text.isNotEmpty()) candidates.add(text)
                }
                if (candidates.isNotEmpty()) break
            }
        }
        doc.title().trim().takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        return candidates.firstNotNullOfOrNull { cleanSurveyTitle(it).takeIf { title -> title.isNotEmpty() } }.orEmpty()
    }

    private fun cleanSurveyTitle(raw: String): String {
        val withoutSuffix = Regex("""(?:[-|]\s*)?(?:问卷星.*)$""", RegexOption.IGNORE_CASE)
            .replace(normalizeHtmlText(raw), "")
        return withoutSuffix.trim(' ', '-', '_', '|')
    }

    private fun raisePageStateErrors(html: String) {
        val doc = Jsoup.parse(html)
        val text = normalizeHtmlText(doc.text())
        if (text.isEmpty()) return
        val compact = text.replace(Regex("\\s+"), "")
        val hasQuestionContent = htmlHasQuestionContent(doc)
        if (isPausedSurveyText(text, compact)) {
            throw WjxPageStateException("该问卷已暂停，暂时无法填写")
        }
        if (isStoppedSurveyText(doc, compact, hasQuestionContent)) {
            throw WjxPageStateException("该问卷已停止，无法作答")
        }
        if (isEnterpriseUnavailableText(compact)) {
            throw WjxPageStateException("该企业版问卷暂时不能被填写")
        }
        buildNotOpenSurveyMessage(text, compact, hasQuestionContent)?.let { message ->
            throw WjxPageStateException(message)
        }
    }

    private fun htmlHasQuestionContent(doc: Document): Boolean {
        val container = doc.selectFirst("#divQuestion") ?: return false
        val fieldsets = container.select("fieldset")
        if (fieldsets.any { selectQuestionDivs(it).isNotEmpty() }) return true
        return selectQuestionDivs(container).isNotEmpty()
    }

    private fun isPausedSurveyText(text: String, compact: String): Boolean {
        if (!compact.contains("已暂停")) return false
        if (compact.contains("不能填写") || compact.contains("问卷已暂停")) return true
        return pausedSurveyIdRe.containsMatchIn(text)
    }

    private fun isStoppedSurveyText(doc: Document, compact: String, hasQuestionContent: Boolean): Boolean {
        if (!compact.contains("停止状态") || !compact.contains("无法作答")) return false
        for (container in doc.select("#divWorkError, #divTip")) {
            val errorText = normalizeHtmlText(container.text()).replace(Regex("\\s+"), "")
            if (errorText.contains("停止状态") && errorText.contains("无法作答")) return true
        }
        if (hasQuestionContent) return false
        return compact.contains("此问卷处于停止状态，无法作答")
    }

    private fun isEnterpriseUnavailableText(compact: String): Boolean =
        compact.contains("企业标准版") &&
            compact.contains("问卷发布者") &&
            (compact.contains("未购买") || compact.contains("已到期")) &&
            (compact.contains("暂时不能被填写") || compact.contains("暂时不能填写"))

    private fun buildNotOpenSurveyMessage(text: String, compact: String, hasQuestionContent: Boolean): String? {
        if (hasQuestionContent) return null
        val notOpenKeywords = listOf("此问卷将于", "请到时再进入此页面进行填写", "距离开始还有", "尚未开始", "未到开始时间", "未开放", "开放时间")
        if (notOpenKeywords.none { compact.contains(it) }) return null
        val openTime = notOpenTimeRe.find(text)?.groupValues?.getOrNull(1)?.replace("/", "-")?.trim()
        return if (!openTime.isNullOrBlank()) "该问卷尚未开放，开放时间：$openTime" else "该问卷尚未开放"
    }

    private fun parseQuestions(doc: Document): List<SurveyQuestionMeta> {
        val container = doc.selectFirst("#divQuestion") ?: return emptyList()
        var fieldsets = container.select("fieldset")
        if (fieldsets.isEmpty()) fieldsets = org.jsoup.select.Elements(container)
        val result = mutableListOf<SurveyQuestionMeta>()
        var pageIndex = 0
        for (fieldset in fieldsets) {
            pageIndex++
            val questionDivs = selectQuestionDivs(fieldset)
            var currentDisplayNum: Int? = null
            var visibleCounter = 0
            for (div in questionDivs) {
                val rawHeadingText = extractDisplayHeadingText(div)
                val headingNum = extractDisplayQuestionNumber(rawHeadingText)
                val num = extractQuestionNumber(div) ?: continue
                var typeCode = div.attr("type").trim().ifEmpty { "0" }
                if (typeCode != "11" && looksLikeReorder(div)) typeCode = "11"
                // 对标 _question_div_is_empty_gapfill_placeholder：隐藏且无输入框的填空占位节点直接跳过。
                if (isHiddenEmptyGapfillPlaceholder(div, typeCode)) continue
                // 对标 is_multilevel：type=1 且带「多级下拉」标记的输入框，按 type=7 处理。
                val multilevelInput = div.selectFirst("input[verify=多级下拉]")
                val isMultilevel = typeCode == "1" && multilevelInput != null
                if (isMultilevel) typeCode = "7"
                val isRating = typeCode == "5" && looksLikeRating(div)
                val required = isRequired(div)
                val title = extractTitle(div, num)

                val isSliderMatrix = looksLikeSliderMatrix(div)
                var (optionTexts, optionCount, rows, rowTexts, fillable, attachedSelects) =
                    extractMetadata(div, typeCode, isSliderMatrix)
                if (typeCode == "5") {
                    val ratingTexts = extractRatingOptionTexts(div)
                    if (ratingTexts.isNotEmpty()) {
                        optionTexts = ratingTexts
                        optionCount = ratingTexts.size
                    }
                    if (isRating) {
                        optionCount = maxOf(optionCount, extractRatingOptionCount(div), optionTexts.size)
                        if (optionCount > 0 && optionTexts.none { textLooksMeaningful(it) }) {
                            optionTexts = (1..optionCount).map { it.toString() }
                        }
                    }
                }

                val textInputs = if (isMultilevel) 0 else countTextInputs(div)
                val textInputLabels = if (textInputs > 1) extractTextInputLabels(div) else emptyList()
                val isLocation = typeCode in listOf("1", "2") && looksLikeLocation(div)
                val isTextLike = shouldTreatAsTextLike(typeCode, optionCount, textInputs, isLocation, isSliderMatrix)
                val isMultiText = shouldMarkMultiText(typeCode, optionCount, textInputs, isLocation, isSliderMatrix, div)
                val forced = if (typeCode in listOf("3", "5", "7")) extractForcedOption(div, title, optionTexts) else null
                val (multiMin, multiMax) = if (typeCode == "4") extractMultiLimits(div, title) else (null to null)
                val (hasJump, jumpRules) = extractJumpRules(div, optionTexts)
                val (hasDisplay, displayConditions) = extractDisplayConditions(div)
                val (sMin, sMax, sStep) = if (typeCode == "8" || isSliderMatrix) extractSliderRange(div, num) else Triple(null, null, null)
                val isDescription = looksLikeDescription(div, typeCode, optionCount, textInputs, isLocation)
                val questionMedia = collectQuestionMedia(div, rowTexts, optionTexts)
                var displayNum = headingNum ?: currentDisplayNum
                if (headingNum != null && headingNum > 0) currentDisplayNum = headingNum
                if (!questionOrAncestorsAreHidden(div) && !isDescription) {
                    visibleCounter++
                    if (displayNum == null || displayNum != visibleCounter) {
                        displayNum = visibleCounter
                        currentDisplayNum = displayNum
                    }
                }

                result.add(
                    SurveyQuestionMeta(
                        num = num,
                        displayNum = displayNum,
                        title = title,
                        typeCode = typeCode,
                        page = pageIndex,
                        options = optionCount,
                        rows = rows,
                        optionTexts = optionTexts,
                        rowTexts = rowTexts,
                        required = required,
                        providerType = if (isMultilevel) WjxMultilevel.PROVIDER_TYPE else typeCode,
                        providerPageRaw = if (isMultilevel) {
                            mapOf("cid" to (multilevelInput?.attr("cid")?.trim().orEmpty()))
                        } else {
                            null
                        },
                        isRating = isRating,
                        isDescription = isDescription,
                        ratingMax = if (isRating) maxOf(optionCount, extractRatingOptionCount(div)) else 0,
                        isLocation = isLocation,
                        isTextLike = isTextLike,
                        isMultiText = isMultiText,
                        isSliderMatrix = isSliderMatrix,
                        textInputs = textInputs,
                        textInputLabels = textInputLabels,
                        fillableOptions = fillable,
                        attachedOptionSelects = attachedSelects,
                        hasAttachedOptionSelect = attachedSelects.isNotEmpty(),
                        forcedOptionIndex = forced?.first,
                        forcedOptionText = forced?.second,
                        multiMinLimit = multiMin,
                        multiMaxLimit = multiMax,
                        hasJump = hasJump,
                        jumpRules = jumpRules,
                        hasDisplayCondition = hasDisplay,
                        displayConditions = displayConditions,
                        sliderMin = sMin,
                        sliderMax = sMax,
                        sliderStep = sStep,
                        questionMedia = questionMedia,
                        logicParseStatus = "complete",
                    ),
                )
            }
        }
        attachDisplayTargets(result)
        return result
    }

    // ===== 滑块范围 =====
    private fun extractSliderRange(div: Element, num: Int): Triple<Double?, Double?, Double?> {
        val input = div.selectFirst("input#q$num")
            ?: div.selectFirst("input[type=range]")
            ?: div.selectFirst("input.ui-slider-input")
            ?: return Triple(null, null, null)
        fun parse(attr: String) = input.attr(attr).trim().toDoubleOrNull()
        return Triple(parse("min"), parse("max"), parse("step"))
    }

    // ===== 跳题规则 =====
    private val terminateKeywords = listOf("结束作答", "结束答题", "结束填写", "终止作答", "停止作答")

    private fun parseJumpTarget(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.all { it.isDigit() }) return t.toIntOrNull()
        return Regex("(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun jumpTerminates(jumpto: Int, optionText: String?): Boolean {
        if (optionText != null && terminateKeywords.any { it in optionText }) return true
        return jumpto == 1 || jumpto == -1
    }

    private fun extractJumpRules(div: Element, optionTexts: List<String>): Pair<Boolean, List<com.surveycontroller.android.core.model.JumpRule>> {
        val hasJumpAttr = div.attr("hasjump").trim() == "1"
        val rules = mutableListOf<com.surveycontroller.android.core.model.JumpRule>()
        var selectable = div.select("input[type=radio], input[type=checkbox]").toList()
        if (selectable.isEmpty()) selectable = selectableJumpOptions(div)
        var optionIdx = 0
        for (node in selectable) {
            val jumptoRaw = node.attr("jumpto").ifEmpty { node.attr("data-jumpto") }
            if (jumptoRaw.isEmpty()) { optionIdx++; continue }
            val jumpto = parseJumpTarget(jumptoRaw)
            if (jumpto != null) {
                val optText = optionTexts.getOrNull(optionIdx)
                rules.add(
                    com.surveycontroller.android.core.model.JumpRule(
                        optionIndex = optionIdx, targetQuestion = jumpto, optionText = optText,
                        terminates = jumpTerminates(jumpto, optText),
                    ),
                )
            }
            optionIdx++
        }
        if (hasJumpAttr) {
            var unconditional: Int? = null
            for (attr in listOf("jumpto", "data-jumpto", "goto", "data-goto", "anyjump", "data-anyjump")) {
                unconditional = parseJumpTarget(div.attr(attr))
                if (unconditional != null) break
            }
            if (unconditional != null && rules.none { it.optionIndex < 0 && it.targetQuestion == unconditional }) {
                rules.add(com.surveycontroller.android.core.model.JumpRule(optionIndex = -1, targetQuestion = unconditional))
            }
        }
        return (hasJumpAttr || rules.isNotEmpty()) to rules
    }

    private fun selectableJumpOptions(div: Element): List<Element> =
        div.select("option").filterIndexed { index, option ->
            !selectOptionLooksLikePlaceholder(index, option)
        }

    // ===== 显隐条件（relation 属性）=====
    private fun extractDisplayConditions(div: Element): Pair<Boolean, List<com.surveycontroller.android.core.model.DisplayCondition>> {
        val relation = div.attr("relation").trim()
        if (relation.isEmpty()) return false to emptyList()
        val conditions = mutableListOf<com.surveycontroller.android.core.model.DisplayCondition>()
        val seen = HashSet<Pair<Int, List<Int>>>()
        for (chunk in relation.split(Regex("\\s*[|]\\s*"))) {
            val text = chunk.trim()
            if (text.isEmpty() || "," !in text) continue
            val sourceText = text.substringBefore(",")
            val optionText = text.substringAfter(",")
            val sourceNum = Regex("\\d+").find(sourceText)?.value?.toIntOrNull() ?: continue
            val optionIndices = mutableListOf<Int>()
            val seenIdx = HashSet<Int>()
            for (m in Regex("\\d+").findAll(optionText)) {
                val n = m.value.toIntOrNull() ?: continue
                if (n <= 0) continue
                val idx = n - 1
                if (seenIdx.add(idx)) optionIndices.add(idx)
            }
            if (sourceNum <= 0 || optionIndices.isEmpty()) continue
            val key = sourceNum to optionIndices.toList()
            if (!seen.add(key)) continue
            conditions.add(
                com.surveycontroller.android.core.model.DisplayCondition(
                    conditionQuestionNum = sourceNum, conditionMode = "selected", conditionOptionIndices = optionIndices,
                ),
            )
        }
        return conditions.isNotEmpty() to conditions
    }

    /** 回填"控制后续显示"目标，对应 _attach_display_condition_metadata。 */
    private fun attachDisplayTargets(questions: MutableList<SurveyQuestionMeta>) {
        val byNum = HashMap<Int, Int>() // num -> index in list
        questions.forEachIndexed { i, q -> if (q.num > 0) byNum.putIfAbsent(q.num, i) }
        val targetsBySource = HashMap<Int, MutableList<com.surveycontroller.android.core.model.DisplayTarget>>()
        for (q in questions) {
            if (q.displayConditions.isEmpty()) continue
            for (cond in q.displayConditions) {
                val src = cond.conditionQuestionNum
                if (src <= 0 || cond.conditionOptionIndices.isEmpty()) continue
                if (!byNum.containsKey(src)) continue
                targetsBySource.getOrPut(src) { mutableListOf() }.add(
                    com.surveycontroller.android.core.model.DisplayTarget(
                        targetQuestionNum = q.num, conditionMode = cond.conditionMode,
                        conditionOptionIndices = cond.conditionOptionIndices,
                    ),
                )
            }
        }
        for ((src, targets) in targetsBySource) {
            val idx = byNum[src] ?: continue
            questions[idx] = questions[idx].copy(
                hasDependentDisplayLogic = true,
                controlsDisplayTargets = targets,
            )
        }
    }

    // ===== 题图/选项图/矩阵行图 =====
    private fun collectQuestionMedia(div: Element, rowTexts: List<String>, optionTexts: List<String>): List<QuestionMedia> {
        val result = mutableListOf<QuestionMedia>()
        val seen = HashSet<String>()

        fun add(scope: String, index: Int?, sourceUrl: String?, label: String) {
            val url = normalizeMediaSourceUrl(sourceUrl)
            if (url.isEmpty()) return
            val key = "$scope\u0000${index ?: -1}\u0000$url"
            if (!seen.add(key)) return
            result.add(QuestionMedia(scope = scope, index = index, sourceUrl = url, label = label.trim()))
        }

        for (node in div.select(".topichtml, .field-label")) {
            for (image in node.select("img")) {
                add("title", null, imageSource(image), "题干图")
            }
        }

        choiceOptionElements(div).forEachIndexed { idx, item ->
            val label = optionTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "选项 ${idx + 1}"
            for (image in item.select("img")) {
                add("option", idx, imageSource(image), label)
            }
        }

        val rowNodes = mediaMatrixRowNodes(div)
        rowNodes.forEachIndexed { idx, row ->
            val label = rowTexts.getOrNull(idx)?.takeIf { it.isNotBlank() } ?: "第 ${idx + 1} 行"
            for (image in row.select("img")) {
                add("row", idx, imageSource(image), label)
            }
        }

        return result
    }

    private fun mediaMatrixRowNodes(div: Element): List<Element> {
        firstNonEmptySelection(div, "tr[rowindex]", "tr.rowtitletr").takeIf { it.isNotEmpty() }?.let {
            return it
        }
        val num = extractQuestionNumber(div)
        val table = num?.let { div.selectFirst("#divRefTab$it") } ?: div.selectFirst("table")
        if (table != null) {
            val rows = matrixDataRows(table, num)
            if (rows.isNotEmpty()) return rows
        }
        return div.select("tr[id^=drv]").toList()
    }

    private fun firstNonEmptySelection(div: Element, vararg selectors: String): List<Element> {
        for (selector in selectors) {
            val items = div.select(selector)
            if (items.isNotEmpty()) return items.toList()
        }
        return emptyList()
    }

    private fun imageSource(image: Element): String =
        image.attr("src").ifBlank { image.attr("data-src") }.ifBlank { image.attr("data-original") }

    private fun normalizeMediaSourceUrl(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return if (text.startsWith("//")) "https:$text" else text
    }

    private data class Meta(
        val optionTexts: List<String>,
        val optionCount: Int,
        val rows: Int,
        val rowTexts: List<String>,
        val fillable: List<Int>,
        val attachedSelects: List<Map<String, Any?>>,
    )

    private fun extractMetadata(div: Element, typeCode: String, isSliderMatrix: Boolean): Meta {
        return when (typeCode) {
            "3", "4", "11" -> {
                val choiceMeta = collectChoiceOptions(div)
                val attached = if (typeCode in listOf("3", "4")) collectAttachedOptionSelects(div) else emptyList()
                Meta(choiceMeta.first, choiceMeta.first.size, 1, emptyList(), choiceMeta.second, attached)
            }
            "5" -> {
                val texts = extractRatingOptionTexts(div)
                val count = maxOf(texts.size, extractRatingOptionCount(div))
                Meta(texts.ifEmpty { if (count > 0) (1..count).map { it.toString() } else emptyList() }, count, 1, emptyList(), emptyList(), emptyList())
            }
            "7" -> {
                val texts = collectSelectOptions(div)
                val fillable = if (texts.isNotEmpty() && questionHasSharedChoiceTextInput(div)) listOf(texts.lastIndex) else emptyList()
                Meta(texts, texts.size, 1, emptyList(), fillable, emptyList())
            }
            "6" -> {
                val (cols, rows) = collectMatrix(div)
                Meta(cols, cols.size, rows.size.coerceAtLeast(1), rows, emptyList(), emptyList())
            }
            "8" -> Meta(emptyList(), 1, 1, emptyList(), emptyList(), emptyList())
            else -> if (isSliderMatrix) {
                val (cols, rows) = collectSliderMatrixMetadata(div)
                Meta(cols, cols.size, rows.size.coerceAtLeast(1), rows, emptyList(), emptyList())
            } else {
                Meta(emptyList(), 0, 1, emptyList(), emptyList(), emptyList())
            }
        }
    }

    private fun choiceOptionElements(div: Element): List<Element> {
        var items = div.select(".ui-controlgroup > div")
        if (items.isEmpty()) items = div.select("ul > li")
        return items.toList()
    }

    private fun collectChoiceOptions(div: Element): Pair<List<String>, List<Int>> {
        val items = choiceOptionElements(div)
        val texts = mutableListOf<String>()
        val fillable = mutableListOf<Int>()
        items.forEach { item ->
            val label = elementTextWithSpaces(item.selectFirst(".label")).takeIf { it.isNotBlank() }
                ?: extractOptionTextFromAttrs(item).takeIf { it.isNotBlank() }
                ?: elementTextWithSpaces(item).trim()
            if (label.isBlank()) return@forEach
            val optionIndex = texts.size
            texts.add(label)
            if (elementContainsChoiceTextInput(item)) fillable.add(optionIndex)
        }
        if (texts.isEmpty()) {
            texts.addAll(collectFallbackChoiceOptionTexts(div))
        }
        if (fillable.isEmpty() && texts.isNotEmpty() && questionHasSharedChoiceTextInput(div)) {
            fillable.add(texts.lastIndex)
        }
        return texts to fillable
    }

    private fun collectFallbackChoiceOptionTexts(div: Element): List<String> {
        val texts = mutableListOf<String>()
        val seen = HashSet<String>()
        for (selector in listOf(".label", "li span", "li")) {
            for (element in div.select(selector)) {
                val text = elementTextWithSpaces(element).takeIf { it.isNotBlank() }
                    ?: extractOptionTextFromAttrs(element).takeIf { it.isNotBlank() }
                    ?: continue
                if (seen.add(text)) texts.add(text)
            }
            if (texts.isNotEmpty()) break
        }
        return texts
    }

    private fun elementContainsChoiceTextInput(element: Element): Boolean {
        if (elementLooksLikeChoiceTextInput(element)) return true
        return element.select("input, textarea").any { elementLooksLikeChoiceTextInput(it) }
    }

    private fun elementLooksLikeChoiceTextInput(element: Element): Boolean {
        val tag = element.tagName().lowercase()
        val type = element.attr("type").trim().lowercase()
        return tag == "textarea" || (tag == "input" && type in setOf("", "text", "search", "tel", "number"))
    }

    private fun questionHasSharedChoiceTextInput(div: Element): Boolean {
        if (div.select(".ui-other input, .ui-other textarea").any { elementContainsChoiceTextInput(it) }) return true
        return div.select("input[id*=other], input[name*=other], textarea[id*=other], textarea[name*=other]")
            .any { elementContainsChoiceTextInput(it) }
    }

    private fun collectAttachedOptionSelects(div: Element): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        choiceOptionElements(div).forEachIndexed { idx, item ->
            val select = item.selectFirst("select")
            val selectPairs = select?.let { collectSelectOptionPairs(it) }
                .orEmpty()
                .ifEmpty { firstCustomSelectOptionPairs(item.select("input")) }
            val selectOptions = selectPairs.map { it.first }
            if (selectOptions.isEmpty()) return@forEachIndexed
            val optionText = item.selectFirst(".label")?.text()?.trim()
                ?: extractOptionTextFromAttrs(item).takeIf { it.isNotBlank() }
                ?: item.clone().also { it.select("select").remove() }.text().trim()
            result.add(
                mapOf(
                    "option_index" to idx,
                    "option_text" to optionText,
                    "select_options" to selectOptions,
                    "select_values" to selectPairs.map { it.second },
                    "select_option_count" to selectOptions.size,
                ),
            )
        }
        return result
    }

    private fun collectSelectOptions(div: Element): List<String> {
        return collectSelectOptionPairs(div)
            .ifEmpty { collectCustomSelectOptionPairs(div) }
            .map { it.first }
    }

    private fun collectSelectOptionPairs(div: Element): List<Pair<String, String>> {
        val select = if (div.tagName().equals("select", ignoreCase = true)) div else div.selectFirst("select") ?: return emptyList()
        val options = select.select("option")
        val result = mutableListOf<Pair<String, String>>()
        options.forEachIndexed { idx, opt ->
            val value = opt.attr("value").trim()
            val text = opt.text().trim()
            if (!selectOptionLooksLikePlaceholder(idx, opt)) result.add(text to value)
        }
        return result
    }

    private fun selectOptionLooksLikePlaceholder(index: Int, option: Element): Boolean {
        if (index != 0) return false
        val value = option.attr("value").trim()
        val text = option.text().trim()
        return text.isBlank() || value in listOf("", "0", "-1", "-2") || textLooksLikeSelectPlaceholder(text)
    }

    private fun collectCustomSelectOptionPairs(element: Element): List<Pair<String, String>> {
        val candidates = mutableListOf<Element>()
        candidates.add(element)
        candidates.addAll(element.select("input"))
        return firstCustomSelectOptionPairs(candidates)
    }

    private fun firstCustomSelectOptionPairs(elements: Iterable<Element>): List<Pair<String, String>> {
        for (element in elements) {
            val pairs = customSelectOptionPairsFromElement(element)
            if (pairs.isNotEmpty()) return pairs
        }
        return emptyList()
    }

    private fun customSelectOptionPairsFromElement(element: Element): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val seen = HashSet<String>()
        for (attrName in customSelectAttrNames) {
            val raw = element.attr(attrName)
            if (raw.isBlank()) continue
            for (part in raw.split(customSelectSeparatorRe)) {
                val text = normalizeHtmlText(part)
                if (text.isEmpty() || textLooksLikeSelectPlaceholder(text) || !seen.add(text)) continue
                result.add(text to text)
            }
        }
        return result
    }

    private fun textLooksLikeSelectPlaceholder(value: String): Boolean {
        val compact = normalizeHtmlText(value).replace(" ", "")
        return compact.startsWith("请选择") || compact.startsWith("请先选择")
    }

    private fun collectMatrix(div: Element): Pair<List<String>, List<String>> {
        if (looksLikeSliderMatrix(div)) return collectSliderMatrixMetadata(div)

        val num = extractQuestionNumber(div)
        val table = num?.let { div.selectFirst("#divRefTab$it") } ?: div.selectFirst("table")
        var optionTexts = if (table != null) extractMatrixHeaderTexts(table) else emptyList()
        val rowTexts = mutableListOf<String>()

        if (table != null) {
            val indexedRows = table.select("tr[rowindex]")
            if (indexedRows.isNotEmpty()) {
                rowTexts.addAll(indexedRows.map { extractMatrixRowLabel(it) })
            } else {
                val dataRows = matrixDataRows(table, num)
                rowTexts.addAll(dataRows.map { extractMatrixRowLabel(it) })
                if (optionTexts.isEmpty()) {
                    val columns = dataRows.maxOfOrNull { maxOf(0, it.select("td, th").size - 1) } ?: 0
                    if (columns > 0) optionTexts = (1..columns).map { it.toString() }
                }
            }
        }

        if (rowTexts.isEmpty()) {
            val (rowsFromInputs, colsFromInputs) = inferMatrixSizeFromInputs(div, num)
            if (rowsFromInputs > 0) rowTexts.addAll(List(rowsFromInputs) { "" })
            if (optionTexts.isEmpty() && colsFromInputs > 0) {
                optionTexts = (1..colsFromInputs).map { it.toString() }
            }
        }

        mergeMatrixRowTitleCandidates(div, rowTexts)
        return postprocessMatrixOptionTexts(optionTexts) to rowTexts
    }

    private fun extractMatrixHeaderTexts(table: Element): List<String> {
        var best = emptyList<String>()
        var bestScore = 0
        for (row in table.select("tr")) {
            if (matrixRowLooksLikeTitleOnly(row)) continue
            if (row.select("input, select, textarea").isNotEmpty()) continue
            val cells = row.select("td, th")
            if (cells.size <= 1) continue
            val texts = cells.map { cleanLabel(it.text()).ifBlank { extractAttrText(it) } }.filter { it.isNotBlank() }
            if (texts.size < 2) continue
            if (texts.size > bestScore) {
                bestScore = texts.size
                best = texts
            }
        }
        return postprocessMatrixOptionTexts(best)
    }

    private fun postprocessMatrixOptionTexts(raw: List<String>): List<String> {
        val seen = HashSet<String>()
        val result = mutableListOf<String>()
        for (item in raw) {
            val text = cleanLabel(item)
            if (text.isEmpty() || !seen.add(text)) continue
            result.add(text)
        }
        return result
    }

    private fun matrixDataRows(table: Element, questionNumber: Int?): List<Element> {
        val result = mutableListOf<Element>()
        val headerId = questionNumber?.let { "drv${it}_1" }
        for (row in table.select("tr")) {
            if (headerId != null && row.id() == headerId) continue
            if (matrixRowLooksLikeTitleOnly(row)) continue
            val cells = row.select("td, th")
            if (cells.size <= 1) continue
            val firstText = extractMatrixRowLabel(row)
            val otherTexts = cells.drop(1).map { cleanLabel(it.text()) }
            if (firstText.isEmpty() && row.select("input, select, textarea").isEmpty() && otherTexts.any { it.isNotEmpty() }) {
                continue
            }
            result.add(row)
        }
        return result
    }

    private fun matrixRowLooksLikeTitleOnly(row: Element): Boolean {
        val id = row.id().trim().lowercase()
        val classes = row.classNames().joinToString(" ").lowercase()
        return id.endsWith("t") || "rowtitle" in classes || "rowtitletr" in classes
    }

    private fun extractMatrixRowLabel(row: Element): String {
        val cells = row.select("td, th")
        if (cells.isNotEmpty()) {
            cleanLabel(cells[0].text()).ifBlank { extractAttrText(cells[0]) }.takeIf { it.isNotBlank() }?.let {
                return it
            }
        }
        extractAttrText(row).takeIf { it.isNotBlank() }?.let { return it }
        for (selector in listOf(".label", ".row-title", ".rowtitle", ".row", ".item-title", ".itemTitle", ".itemTitleSpan", ".stitle")) {
            val text = cleanLabel(row.selectFirst(selector)?.text())
            if (text.isNotEmpty()) return text
        }
        for (child in row.select("label, span, div, p").take(10)) {
            extractAttrText(child).takeIf { it.isNotBlank() }?.let { return it }
            cleanLabel(child.text()).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun extractAttrText(node: Element): String {
        for (key in listOf("title", "data-title", "data-text", "data-label", "aria-label", "alt", "htitle", "data-original-title")) {
            val text = cleanLabel(node.attr(key))
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun inferMatrixSizeFromInputs(div: Element, questionNumber: Int?): Pair<Int, Int> {
        val pattern = if (questionNumber != null) {
            Regex("""q$questionNumber[_-](\d+)(?:[_-](\d+))?""")
        } else {
            Regex("""q\d+[_-](\d+)(?:[_-](\d+))?""")
        }
        var rows = 0
        var columns = 0
        for (input in div.select("input")) {
            val raw = input.attr("name").ifBlank { input.id() }
            val match = pattern.find(raw) ?: continue
            rows = maxOf(rows, match.groupValues.getOrNull(1)?.toIntOrNull() ?: 0)
            columns = maxOf(columns, match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0)
        }
        return rows to columns
    }

    private fun mergeMatrixRowTitleCandidates(div: Element, rowTexts: MutableList<String>) {
        val candidates = matrixRowTitleCandidates(div)
        if (candidates.isEmpty()) return
        if (rowTexts.isEmpty()) {
            rowTexts.addAll(candidates)
            return
        }
        for (idx in 0 until minOf(rowTexts.size, candidates.size)) {
            if (rowTexts[idx].isBlank()) rowTexts[idx] = candidates[idx]
        }
    }

    private fun matrixRowTitleCandidates(div: Element): List<String> {
        for (selector in listOf(".itemTitleSpan", ".itemTitle", ".item-title", ".row-title")) {
            val texts = div.select(selector).map { cleanLabel(it.text()) }.filter { it.isNotEmpty() }
            if (texts.isNotEmpty()) return texts
        }
        return emptyList()
    }

    private fun collectSliderMatrixMetadata(div: Element): Pair<List<String>, List<String>> {
        val rowTexts = sliderMatrixRowTitleTexts(div).toMutableList()
        val sliderInputs = div.select("input.ui-slider-input[rowid]")
        val drvRows = div.select("tr[id^=drv]")
        val rowCount = maxOf(rowTexts.size, sliderInputs.size, drvRows.size)
        while (rowTexts.size < rowCount) rowTexts.add("")

        var optionTexts = div.select(".ruler .cm[data-value]")
            .map { cleanLabel(it.attr("data-value")) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (optionTexts.isEmpty()) {
            sliderInputs.firstOrNull()?.let { optionTexts = buildSliderMatrixOptionTextsFromInput(it) }
        }
        return optionTexts to rowTexts
    }

    private fun sliderMatrixRowTitleTexts(div: Element): List<String> {
        for (selector in listOf("tr.rowtitletr .itemTitleSpan", "tr.rowtitletr td.title", "tr[id$=t] .itemTitleSpan, tr[id$=t] td.title")) {
            val texts = div.select(selector).map { cleanLabel(it.text()) }.filter { it.isNotEmpty() }
            if (texts.isNotEmpty()) return texts
        }
        val sliderInputs = sliderMatrixInputs(div)
        if (sliderInputs.isNotEmpty()) {
            val labels = sliderInputs.map { sliderMatrixInputLabel(it) }
            if (labels.any { it.isNotEmpty() }) return labels
        }
        return emptyList()
    }

    private fun sliderMatrixInputs(div: Element): List<Element> =
        div.select("input.ui-slider-input[rowid]").toList()

    private fun sliderMatrixInputLabel(input: Element): String {
        for (attr in listOf("aria-label", "data-label", "title", "alt")) {
            cleanLabel(input.attr(attr)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        var current = input.previousElementSibling()
        while (current != null) {
            val classes = current.classNames().joinToString(" ").lowercase()
            if ("rangeslider" in classes || "range-slider" in classes || "wjx-slider" in classes || "ruler" in classes) {
                current = current.previousElementSibling()
                continue
            }
            cleanLabel(current.text()).takeIf { it.isNotEmpty() }?.let { return it }
            current = current.previousElementSibling()
        }
        return ""
    }

    private fun buildSliderMatrixOptionTextsFromInput(input: Element): List<String> {
        val min = input.attr("min").trim().toDoubleOrNull() ?: return emptyList()
        var max = input.attr("max").trim().toDoubleOrNull() ?: return emptyList()
        var lo = min
        if (max < lo) {
            val tmp = lo
            lo = max
            max = tmp
        }
        val step = input.attr("step").trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
        val values = mutableListOf<String>()
        var current = lo
        while (current <= max + 1e-9 && values.size < 200) {
            values.add(formatSliderMatrixValue(current))
            current += step
        }
        return values
    }

    private fun formatSliderMatrixValue(value: Double): String {
        val rounded = kotlin.math.round(value)
        return if (kotlin.math.abs(value - rounded) < 1e-6) {
            rounded.toInt().toString()
        } else {
            "%.6f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
        }
    }

    private fun selectQuestionDivs(fieldset: Element): List<Element> {
        val seen = LinkedHashSet<Element>()
        val result = mutableListOf<Element>()
        for (div in fieldset.select("div[topic], div[id^=div]")) {
            if (!looksLikeQuestionDiv(div) || hasQuestionAncestor(div, fieldset) || !seen.add(div)) continue
            result.add(div)
        }
        return result
    }

    private fun hasQuestionAncestor(item: Element, fieldset: Element): Boolean {
        var cur = item.parent()
        while (cur != null && cur !== fieldset) {
            if (cur.tagName() == "div" && looksLikeQuestionDiv(cur)) return true
            cur = cur.parent()
        }
        return false
    }

    private fun looksLikeQuestionDiv(div: Element): Boolean {
        if (div.hasAttr("topic")) return true
        return Regex("""^div\d+$""").matches(div.id())
    }

    private fun extractQuestionNumber(div: Element): Int? {
        div.attr("topic").trim().toIntOrNull()?.let { return it }
        val id = div.id()
        Regex("""div(\d+)""").find(id)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun extractDisplayHeadingText(div: Element): String {
        div.selectFirst(".field-label")?.let { fieldLabel ->
            val parts = mutableListOf<String>()
            for (className in listOf("topicnumber", "topichtml")) {
                val text = normalizeHtmlText(elementTextWithSpaces(fieldLabel.selectFirst(".$className")))
                if (text.isNotEmpty()) parts.add(text)
            }
            if (parts.isNotEmpty()) return normalizeHtmlText(parts.joinToString(" "))
        }
        for (selector in listOf(".topichtml", ".field-label", ".qtypetip", "blockquote")) {
            val text = normalizeHtmlText(elementTextWithSpaces(div.selectFirst(selector)))
            if (text.isNotEmpty()) return text
        }
        return normalizeHtmlText(elementTextWithSpaces(div))
    }

    private fun extractDisplayQuestionNumber(text: String): Int? {
        val match = displayNumRe.find(text.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun extractTitle(div: Element, num: Int): String {
        val raw = elementTextWithSpaces(div.selectFirst(".topichtml")).takeIf { it.isNotBlank() }
            ?: elementTextWithSpaces(div.selectFirst(".field-label")).takeIf { it.isNotBlank() }
            ?: elementTextWithSpaces(div)
        var t = raw.trim()
        t = leadingNumRe.replace(t, "")
        t = titleTagRe.replace(t, "")
        return t.trim()
    }

    private fun elementTextWithSpaces(element: Element?): String {
        if (element == null) return ""
        val parts = mutableListOf<String>()
        for (node in element.childNodes()) {
            val text = when (node) {
                is org.jsoup.nodes.TextNode -> node.text()
                is Element -> elementTextWithSpaces(node)
                else -> node.toString()
            }
            normalizeHtmlText(text).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        return normalizeHtmlText(parts.joinToString(" "))
    }

    private fun questionOrAncestorsAreHidden(div: Element): Boolean {
        var current: Element? = div
        while (current != null) {
            if (elementInitiallyHidden(current)) return true
            current = current.parent()
        }
        return false
    }

    /** 对标 _question_div_is_empty_gapfill_placeholder。 */
    private fun isHiddenEmptyGapfillPlaceholder(div: Element, typeCode: String): Boolean =
        typeCode == "9" &&
            div.attr("gapfill").trim() == "1" &&
            div.attr("relation").trim() in setOf("", "0") &&
            questionOrAncestorsAreHidden(div) &&
            countTextInputs(div) == 0

    private fun elementInitiallyHidden(element: Element): Boolean {
        val style = element.attr("style").lowercase()
        val compactStyle = style.replace(" ", "")
        val hiddenAttr = element.attr("hidden").trim().lowercase()
        val classText = element.classNames().joinToString(" ").lowercase()
        return "display:none" in compactStyle ||
            "visibility:hidden" in compactStyle ||
            hiddenAttr in setOf("hidden", "true", "1") ||
            "display-none" in classText
    }

    private fun elementStyleLooksHidden(element: Element): Boolean {
        val compactStyle = element.attr("style").lowercase().replace(" ", "")
        return "display:none" in compactStyle || "visibility:hidden" in compactStyle
    }

    private fun isRequired(div: Element): Boolean {
        for (attr in listOf("req", "required", "must", "wjxreq", "aria-required")) {
            val v = div.attr(attr).trim().lowercase()
            if (v in setOf("1", "true", "required")) return true
        }
        if (div.select(".req, .required, .must, .star, .red, .wjxreq, [aria-required=true]").isNotEmpty()) return true
        val t = div.text().trim()
        return t.startsWith("*") || t.contains("必答")
    }

    private fun countTextInputs(div: Element): Int =
        textInputElements(div).size

    private fun textInputElements(div: Element): List<Element> =
        div.select("input, textarea, span, div").filter { candidate ->
            candidateLooksLikeTextInput(candidate)
        }

    private fun candidateLooksLikeTextInput(candidate: Element): Boolean {
        val tag = candidate.tagName().lowercase()
        val type = candidate.attr("type").trim().lowercase()
        val classText = candidate.classNames().joinToString(" ").lowercase()
        val isTextCont = "textcont" in classText || "textedit" in classText
        if (type == "hidden" || elementStyleLooksHidden(candidate)) return false
        if (tag == "input" && inputLooksLikeLocation(candidate)) return false
        if (tag == "input") {
            val sibling = candidate.nextElementSibling()
            if (sibling != null && sibling.classNames().any { it.contains("textedit", ignoreCase = true) }) return false
        }
        if (tag == "textarea") return true
        if (tag == "input" && type in setOf("text", "tel", "email", "number", "search", "url", "password", "")) return true
        return tag in setOf("span", "div") && (candidate.attr("contenteditable").equals("true", ignoreCase = true) || isTextCont)
    }

    private fun extractTextInputLabels(div: Element): List<String> {
        val labels = mutableListOf<String>()
        for (candidate in textInputElements(div)) {
            val label = listOf(
                candidate.attr("placeholder"),
                candidate.attr("aria-label"),
                candidate.attr("data-label"),
            ).firstOrNull { it.trim().isNotEmpty() }
                ?: previousTextSibling(candidate)
                ?: labelBeforeNode(candidate)
                ?: if (candidateLooksLikeTextContainer(candidate)) {
                    candidate.parent()?.let { labelBeforeNode(it) }
                } else {
                    null
                }
                ?: "填空${labels.size + 1}"
            labels.add(cleanLabel(label).ifBlank { "填空${labels.size + 1}" })
        }
        return labels
    }

    private fun candidateLooksLikeTextContainer(candidate: Element): Boolean {
        val classText = candidate.classNames().joinToString(" ").lowercase()
        return candidate.tagName().lowercase() in setOf("span", "div") &&
            ("textcont" in classText || "textedit" in classText || candidate.attr("contenteditable").equals("true", ignoreCase = true))
    }

    private fun previousTextSibling(candidate: Element): String? {
        var node = candidate.previousSibling()
        while (node != null) {
            val text = cleanLabel(
                if (node is org.jsoup.nodes.TextNode) node.text()
                else if (node is Element) node.text()
                else node.toString(),
            )
            if (text.isNotEmpty()) return text
            node = node.previousSibling()
        }
        return null
    }

    private fun labelBeforeNode(node: Element): String? {
        val parts = mutableListOf<String>()
        var current = node.previousSibling()
        while (current != null) {
            if (current is Element) {
                val tag = current.tagName().lowercase()
                if (parts.isNotEmpty() && tag in setOf("div", "p", "section", "fieldset", "table", "ul", "ol")) break
                if (tag in setOf("input", "textarea", "label", "span")) {
                    if (tag == "input") {
                        current = current.previousSibling()
                        continue
                    }
                    break
                }
                if (tag == "br") break
                cleanLabel(current.text()).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            } else {
                cleanLabel(current.toString()).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
            }
            current = current.previousSibling()
        }
        return parts.asReversed().joinToString(" ").let { cleanLabel(it) }.takeIf { it.isNotEmpty() }
    }

    private fun cleanLabel(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim().trimEnd('：', ':').trim()

    private fun normalizeHtmlText(raw: String?): String =
        raw.orEmpty().replace(Regex("\\s+"), " ").trim()

    private fun looksLikeReorder(div: Element): Boolean {
        if (div.select(".sortnum, .sortnum-sel, .order-number, .order-index").isNotEmpty()) return true
        val hasListItems = div.select("ul li, ol li").isNotEmpty()
        if (!hasListItems) return false
        return div.select(".ui-sortable, .ui-sortable-handle, [class*=sort]").isNotEmpty()
    }

    private fun looksLikeNumericScale(div: Element): Boolean {
        val anchors = div.select("ul[tp=d] li a, .scale-rating ul li a, .scale-rating a[val]")
        if (anchors.isEmpty()) return false
        val texts = anchors.mapNotNull { anchor ->
            ratingAnchorText(anchor)
                .ifBlank { normalizeHtmlText(elementTextWithSpaces(anchor)) }
                .takeIf { it.isNotEmpty() }
        }
        if (texts.isEmpty()) return false
        val numericCount = texts.count { Regex("""\d{1,2}""").matches(it) }
        val hasScaleTitle = div.selectFirst(".scaleTitle, .scaleTitle_frist, .scaleTitle_last, .scaleTitleFirst, .scaleTitleLast") != null
        return texts.size >= 5 && numericCount >= maxOf(3, (texts.size * 0.7).toInt()) && (texts.size >= 9 || hasScaleTitle)
    }

    private fun looksLikeRating(div: Element): Boolean {
        if (looksLikeNumericScale(div)) return false
        val hasRateIcon = div.selectFirst("a.rate-off, a.rate-on, .rate-off, .rate-on") != null
        val hasTagWrap = div.selectFirst(".evaluateTagWrap") != null
        val hasIconFont = div.selectFirst(".scale-rating .iconfontNew, .iconfontNew") != null
        return hasTagWrap || hasRateIcon || hasIconFont
    }

    private fun extractRatingOptionTexts(div: Element): List<String> {
        val anchors = firstNonEmptySelection(
            div,
            ".scale-rating ul li a",
            ".scale-rating a[val]",
            "ul[tp=d] li a",
            "ul[class*=modlen] li a",
        )
        if (anchors.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val texts = mutableListOf<String>()
        anchors.forEachIndexed { idx, anchor ->
            var text = ratingAnchorText(anchor)
            if (!textLooksMeaningful(text)) text = normalizeHtmlText(elementTextWithSpaces(anchor))
            if (!textLooksMeaningful(text)) text = normalizeHtmlText(anchor.attr("title"))
            if (!textLooksMeaningful(text)) text = normalizeHtmlText(anchor.attr("val"))
            if (!textLooksMeaningful(text)) text = (idx + 1).toString()
            if (seen.add(text)) texts.add(text)
        }
        return texts
    }

    private fun extractRatingOptionCount(div: Element): Int {
        div.selectFirst("ul[class*=modlen]")?.classNames()?.forEach { cls ->
            Regex("""modlen(\d+)""").find(cls)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        div.select(".scale-rating ul li").takeIf { it.isNotEmpty() }?.let { return it.size }
        div.select("a.rate-off, a.rate-on").takeIf { it.isNotEmpty() }?.let { return it.size }
        return 0
    }

    private fun ratingAnchorText(anchor: Element): String {
        extractOptionTextFromAttrs(anchor).takeIf { it.isNotEmpty() }?.let { return it }
        for (child in anchor.select("a, span, label").take(4)) {
            extractOptionTextFromAttrs(child).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun extractOptionTextFromAttrs(element: Element): String {
        for (key in listOf("title", "data-title", "data-text", "data-label", "aria-label", "alt", "htitle", "data-original-title")) {
            normalizeHtmlText(element.attr(key)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (child in element.select("a, span, label").take(4)) {
            for (key in listOf("title", "data-title", "data-text", "data-label", "aria-label", "alt", "htitle", "data-original-title")) {
                normalizeHtmlText(child.attr(key)).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        for (key in listOf("val", "value", "data-value", "data-val", "dval")) {
            normalizeHtmlText(element.attr(key)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (child in element.select("a, span, label").take(4)) {
            for (key in listOf("val", "value", "data-value", "data-val", "dval")) {
                normalizeHtmlText(child.attr(key)).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }

    private fun textLooksMeaningful(text: String): Boolean =
        Regex("""[A-Za-z0-9\u4e00-\u9fff]""").containsMatchIn(text)

    private fun inputLooksLikeLocation(input: Element): Boolean {
        val verify = input.attr("verify").trim()
        val onclick = input.attr("onclick").trim().lowercase()
        val markers = listOf("地图", "省市", "省份", "城市", "地区")
        return markers.any { verify.contains(it) } || onclick.contains("opencitybox")
    }

    private fun looksLikeLocation(div: Element): Boolean =
        div.select(".get_Local").isNotEmpty() ||
            div.select("input").any { inputLooksLikeLocation(it) }

    private fun looksLikeDescription(
        div: Element,
        typeCode: String,
        optionCount: Int,
        textInputs: Int,
        isLocation: Boolean,
    ): Boolean {
        val relation = div.attr("relation").trim()
        val style = div.attr("style").lowercase().replace(" ", "")
        if (relation == "-1" && "display:none" in style && !isRequired(div)) return true
        if (isLocation || optionCount > 0 || textInputs > 0) return false
        if (typeCode !in setOf("3", "4")) return false
        if (div.select("input[type=radio], input[type=checkbox]").isNotEmpty()) return false
        if (div.select(".ui-controlgroup, .jqradio, .jqcheck").isNotEmpty()) return false
        return div.select("input, textarea, select").isEmpty()
    }

    private fun looksLikeSliderMatrix(div: Element): Boolean {
        val sliderInputs = sliderMatrixInputs(div)
        if (sliderInputs.size >= 2) {
            val tracks = div.select(".rangeslider, .range-slider, .wjx-slider")
            if (tracks.size >= sliderInputs.size) return true
        }
        return div.select("tr.rowtitletr").isNotEmpty() && div.select("input[type=range], .ruler").isNotEmpty()
    }

    private val knownNonText = setOf("3", "4", "5", "6", "7", "8", "11")

    private fun shouldTreatAsTextLike(
        typeCode: String,
        optionCount: Int,
        textInputs: Int,
        isLocation: Boolean,
        isSliderMatrix: Boolean,
    ): Boolean {
        if (isLocation) return false
        if (isSliderMatrix) return false
        if (typeCode in listOf("1", "2", "9")) return textInputs > 0
        if (typeCode in knownNonText) return false
        return optionCount <= 1 && textInputs > 0
    }

    private fun shouldMarkMultiText(
        typeCode: String,
        optionCount: Int,
        textInputs: Int,
        isLocation: Boolean,
        isSliderMatrix: Boolean,
        div: Element,
    ): Boolean {
        if (isLocation) return false
        if (isSliderMatrix) return false
        val hasGapfill = div.attr("gapfill").trim() == "1"
        if (typeCode == "9" && hasGapfill) return true
        if (hasGapfill && textInputs > 1) return true
        return typeCode in listOf("1", "2", "9") && textInputs > 1
    }

    private fun extractForcedOption(div: Element, title: String, optionTexts: List<String>): Pair<Int, String>? {
        if (optionTexts.isEmpty()) return null
        val normalizedOptions = optionTexts.mapIndexedNotNull { idx, optionText ->
            val normalized = normalizeForceSelectText(optionText)
            if (normalized.isEmpty()) null else Triple(idx, optionText.trim(), normalized)
        }
        if (normalizedOptions.isEmpty()) return null

        for (fragment in collectForceSelectFragments(div, title)) {
            for (match in forceSelectCommandRe.findAll(fragment)) {
                val tail = fragment.substring(match.range.last + 1)
                if (tail.isEmpty()) continue
                val sentence = forceSelectSentenceSplitRe.split(tail, limit = 2)
                    .firstOrNull()
                    .orEmpty()
                    .trim(' ', '：', ':', '，', ',', '、')
                if (sentence.isEmpty()) continue
                val compact = normalizeForceSelectText(sentence)
                if (compact.isEmpty()) continue

                var best: Pair<Int, String>? = null
                var bestLength = -1
                for ((idx, rawText, normalizedText) in normalizedOptions) {
                    if (normalizedText.all { it.isDigit() }) continue
                    if (compact.contains(normalizedText) && normalizedText.length > bestLength) {
                        bestLength = normalizedText.length
                        best = idx to rawText
                    }
                }
                if (best != null) return best

                val label = forceSelectLabelTargetRe.matchEntire(compact)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.uppercase()
                if (!label.isNullOrEmpty()) {
                    normalizedOptions.firstOrNull { (_, rawText, _) ->
                        extractForceSelectOptionLabel(rawText) == label
                    }?.let { return it.first to it.second }
                }

                val index = forceSelectIndexRe.matchEntire(sentence)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.minus(1)
                if (index != null && index in optionTexts.indices) {
                    val selected = optionTexts[index].trim()
                    if (selected.isNotEmpty()) return index to selected
                }
            }
        }
        return null
    }

    private fun collectForceSelectFragments(div: Element, title: String): List<String> {
        val fragments = mutableListOf<String>()
        normalizeHtmlText(title).takeIf { it.isNotEmpty() }?.let { fragments.add(it) }
        for (selector in listOf(".qtypetip", ".topichtml", ".field-label")) {
            val text = normalizeHtmlText(elementTextWithSpaces(div.selectFirst(selector)))
            if (text.isNotEmpty()) fragments.add(text)
        }
        val result = mutableListOf<String>()
        val seen = HashSet<String>()
        for (fragment in fragments) {
            if (seen.add(fragment)) result.add(fragment)
        }
        return result
    }

    private fun normalizeForceSelectText(value: String): String {
        val text = normalizeHtmlText(value)
        if (text.isEmpty()) return ""
        return forceSelectCleanRe.replace(text, "").lowercase()
    }

    private fun extractForceSelectOptionLabel(optionText: String): String? {
        val text = normalizeHtmlText(optionText)
        if (text.isEmpty()) return null
        return forceSelectOptionLabelRe.find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractMultiLimits(div: Element, title: String): Pair<Int?, Int?> {
        var min: Int? = null
        var max: Int? = null

        val (attrMin, attrMax) = extractMultiLimitsFromAttributes(div)
        if (attrMin != null) min = attrMin
        if (attrMax != null) max = attrMax

        if (min == null || max == null) {
            for (attrName in listOf("data", "data-setting", "data-validate")) {
                val (jsonMin, jsonMax) = extractMultiLimitsFromPossibleJson(div.attr(attrName))
                if (min == null && jsonMin != null) min = jsonMin
                if (max == null && jsonMax != null) max = jsonMax
                if (min != null && max != null) break
            }
        }

        if (min == null || max == null) {
            for (fragment in collectMultiLimitTextFragments(div, title)) {
                val (textMin, textMax) = extractMultiLimitRangeFromText(fragment)
                if (min == null && textMin != null) min = textMin
                if (max == null && textMax != null) max = textMax
                if (min != null && max != null) break
            }
        }

        if (min != null && max != null && min!! > max!!) {
            val t = min; min = max; max = t
        }
        return min to max
    }

    private val multiMaxAttributeNames = listOf(
        "max", "maxvalue", "maxValue", "maxcount", "maxCount", "maxchoice", "maxChoice",
        "maxselect", "maxSelect", "selectmax", "selectMax", "maxsel", "maxSel",
        "maxnum", "maxNum", "maxlimit", "maxLimit", "data-max", "data-maxvalue",
        "data-maxcount", "data-maxchoice", "data-maxselect", "data-selectmax",
    )

    private val multiMinAttributeNames = listOf(
        "min", "minvalue", "minValue", "mincount", "minCount", "minchoice", "minChoice",
        "minselect", "minSelect", "selectmin", "selectMin", "minsel", "minSel",
        "minnum", "minNum", "minlimit", "minLimit", "data-min", "data-minvalue",
        "data-mincount", "data-minchoice", "data-minselect", "data-selectmin",
    )

    private val multiMaxValueKeys = setOf("max", "maxvalue", "maxcount", "maxchoice", "maxselect", "selectmax")
    private val multiMinValueKeys = setOf("min", "minvalue", "mincount", "minchoice", "minselect", "selectmin", "minlimit")

    private fun extractMultiLimitsFromAttributes(element: Element): Pair<Int?, Int?> {
        val min = multiMinAttributeNames.firstNotNullOfOrNull { safePositiveInt(element.attr(it)) }
        val max = multiMaxAttributeNames.firstNotNullOfOrNull { safePositiveInt(element.attr(it)) }
        return min to max
    }

    private fun extractMultiLimitsFromPossibleJson(raw: String?): Pair<Int?, Int?> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null to null
        for (candidate in listOf(text, if (text.startsWith("{") && "'" in text && "\"" !in text) text.replace('\'', '"') else "")) {
            if (candidate.isBlank()) continue
            try {
                val parsed: Any = if (candidate.startsWith("[")) JSONArray(candidate) else JSONObject(candidate)
                val pair = extractMultiLimitsFromJsonValue(parsed)
                if (pair.first != null || pair.second != null) return pair
            } catch (_: Exception) {
            }
        }
        var min: Int? = null
        var max: Int? = null
        for (key in multiMinValueKeys) {
            Regex("""${Regex.escape(key)}\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
                min = safePositiveInt(it.groupValues[1]) ?: min
            }
            if (min != null) break
        }
        for (key in multiMaxValueKeys) {
            Regex("""${Regex.escape(key)}\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)?.let {
                max = safePositiveInt(it.groupValues[1]) ?: max
            }
            if (max != null) break
        }
        return min to max
    }

    private fun extractMultiLimitsFromJsonValue(value: Any?): Pair<Int?, Int?> {
        var min: Int? = null
        var max: Int? = null
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val normalized = key.lowercase()
                    val item = value.opt(key)
                    if (normalized in multiMinValueKeys) safePositiveInt(item)?.let { if (min == null) min = it }
                    if (normalized in multiMaxValueKeys) safePositiveInt(item)?.let { if (max == null) max = it }
                    val (nestedMin, nestedMax) = extractMultiLimitsFromJsonValue(item)
                    if (min == null && nestedMin != null) min = nestedMin
                    if (max == null && nestedMax != null) max = nestedMax
                    if (min != null && max != null) break
                }
            }
            is JSONArray -> {
                for (idx in 0 until value.length()) {
                    val (nestedMin, nestedMax) = extractMultiLimitsFromJsonValue(value.opt(idx))
                    if (min == null && nestedMin != null) min = nestedMin
                    if (max == null && nestedMax != null) max = nestedMax
                    if (min != null && max != null) break
                }
            }
        }
        return min to max
    }

    private fun collectMultiLimitTextFragments(div: Element, title: String): List<String> {
        val result = mutableListOf<String>()
        fun add(raw: String?) {
            val text = normalizeHtmlText(raw)
            if (text.isNotEmpty() && text !in result) result.add(text)
        }
        add(title)
        for (selector in listOf(".qtypetip", ".topichtml", ".field-label", ".field-desc", ".question-desc", ".question-tip", ".qtip", ".qnotice", ".question-hint")) {
            for (node in div.select(selector)) add(elementTextWithSpaces(node))
        }
        val clone = div.clone()
        clone.select(".ui-controlgroup, ul, ol, table, textarea, select, .slider, .rangeslider, .range-slider, .errorMessage").remove()
        add(elementTextWithSpaces(clone))
        return result
    }

    private fun extractMultiLimitRangeFromText(raw: String?): Pair<Int?, Int?> {
        val text = normalizeHtmlText(raw)
        if (text.isEmpty()) return null to null
        val lower = text.lowercase()
        val containsCnKeyword = listOf("选", "選", "选择", "多选", "复选").any { it in text }
        val containsEnKeyword = listOf("option", "options", "choice", "choices", "select", "choose").any { it in lower }
        val containsCnMinHint = listOf("至少", "最少", "不少于").any { it in text }
        val containsCnMaxHint = listOf("最多", "至多", "不超过", "不超過", "限选", "限選").any { it in text }
        val containsEnMinHint = listOf("at least", "minimum").any { it in lower }
        val containsEnMaxHint = listOf("up to", "at most", "no more than").any { it in lower }
        var min: Int? = null
        var max: Int? = null

        fun applyRange(match: MatchResult?) {
            if (match == null) return
            val first = safePositiveInt(match.groupValues.getOrNull(1))
            val second = safePositiveInt(match.groupValues.getOrNull(2))
            if (first != null && second != null) {
                min = minOf(first, second)
                max = maxOf(first, second)
            }
        }
        fun firstNumber(patterns: List<Regex>, source: String = text): Int? =
            patterns.firstNotNullOfOrNull { pattern -> pattern.find(source)?.groupValues?.getOrNull(1)?.let { safePositiveInt(it) } }

        if (containsCnKeyword) {
            applyRange(Regex("""(?:请[选選择擇]?|可选|可選|需选|需選|选择|選擇|勾选|勾選)\s*(\d+)\s*(?:-|－|—|–|~|～|至|到)\s*(\d+)(?:\s*[个項项条])?""").find(text))
            if (min == null && max == null) applyRange(Regex("""至少\s*(\d+)\s*[个項项条]?(?:[^0-9]{0,6})(?:最多|至多|不超过|不超過)\s*(\d+)\s*[个項项条]?""").find(text))
            if (min == null && max == null) applyRange(Regex("""(?:限选|限選)\s*(\d+)\s*(?:-|－|—|–|~|～|至|到)\s*(\d+)(?:\s*[个項项条])?""").find(text))
        }
        if (min == null && max == null && containsCnKeyword && !containsCnMinHint && !containsCnMaxHint) {
            firstNumber(
                listOf(
                    Regex("""(?:请)?(?:选|選|选择|選擇|勾选|勾選)\s*(\d+)\s*[个項项条]"""),
                    Regex("""(?:必须|需|需要)\s*(?:选|選|选择|選擇|勾选|勾選)\s*(\d+)\s*[个項项条]"""),
                ),
            )?.let { min = it; max = it }
        }
        if (min == null && max == null && containsEnKeyword) {
            applyRange(Regex("""(?:select|choose|pick)\s*(\d+)\s*(?:-|–|—|~|～|to)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text))
            if (min == null && max == null) applyRange(Regex("""(?:select|choose)\s+between\s+(\d+)\s+and\s+(\d+)""", RegexOption.IGNORE_CASE).find(text))
        }
        if (min == null && max == null && containsEnKeyword && !containsEnMinHint && !containsEnMaxHint) {
            firstNumber(
                listOf(
                    Regex("""(?:select|choose|pick)\s+(\d+)\s+(?:options?|choices?|items?)""", RegexOption.IGNORE_CASE),
                    Regex("""(?:must|need\s+to|please)\s+(?:select|choose|pick)\s+(\d+)""", RegexOption.IGNORE_CASE),
                ),
                lower,
            )?.let { min = it; max = it }
        }
        if (min == null && containsCnKeyword) {
            min = firstNumber(listOf(Regex("""(?:至少|最少|不少于)\s*(?:选|選|选择|選擇)?\s*(\d+)\s*[个項项条]""")))
        }
        if (max == null && containsCnKeyword) {
            max = firstNumber(
                listOf(
                    Regex("""(?:最多|至多|不超过|不超過)\s*(?:选|選|选择|選擇)?\s*(\d+)\s*[个項项]?"""),
                    Regex("""(?:限选|限選)\s*(\d+)\s*[个項项条]?"""),
                ),
            )
        }
        if (min == null && containsEnKeyword) {
            min = firstNumber(listOf(Regex("""(?:at\s+least|min(?:imum)?\s*)\s*(\d+)""", RegexOption.IGNORE_CASE)), lower)
        }
        if (max == null && containsEnKeyword) {
            max = firstNumber(
                listOf(
                    Regex("""(?:select|choose|pick)\s+(?:up\s+to|at\s+most|no\s+more\s+than)\s+(\d+)""", RegexOption.IGNORE_CASE),
                    Regex("""(?:up\s+to|at\s+most|no\s+more\s+than)\s+(\d+)\s+(?:options?|choices?|items?)""", RegexOption.IGNORE_CASE),
                ),
                lower,
            )
        }
        if (min != null && max != null && min!! > max!!) {
            val t = min; min = max; max = t
        }
        return min to max
    }

    private fun safePositiveInt(raw: Any?): Int? {
        if (raw == null || raw is Boolean) return null
        val text = raw.toString().trim()
        if (text.isEmpty()) return null
        val n = text.toIntOrNull() ?: Regex("""\d+""").find(text)?.value?.toIntOrNull()
        return n?.takeIf { it > 0 }
    }
}
