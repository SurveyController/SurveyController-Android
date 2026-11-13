package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.Jsoup

/**
 * 问卷星多级下拉题。对标 surveycore/wjx/provider/multilevel.py：
 * 解析 hfLevelData 层级数据、拉取选项、把作答索引转换为提交文本。
 */
object WjxMultilevel {

    const val PROVIDER_TYPE = "multilevel_dropdown"

    /** 问卷星多级下拉路径分隔符（U+2010，非普通连字符）。 */
    private const val SPLIT_CHAR = "‐"
    private const val DISPLAY_SUFFIXES = "┌┍┎┏┐┑┒┓└┕┖┗┘┙┚┛├┝┞┟┠┡┢┣┤┥┦┧┨┩┪┫┬┭┮┯┰┱┲┳┴┵┶┷┸┹┺┻┼┽┾┿╀╁╂╃╄╅╆╇"
    private val markerRe = Regex("""╰[0-9]+╯""")
    private val externalDataRe = Regex("""levelDataExternalUrl\s*=\s*['"][^'"]+""")
    private val activityIdRe = Regex("""\bvar\s+activityId\s*=\s*(?:DecodeId\((\d+)\)|(\d+))""")
    private val decodeIdRe = Regex("""function\s+DecodeId\([^)]*\)\s*\{\s*return\s+\w+\s*\^\s*(\d+)""")

    data class Options(
        val optionTexts: List<String>,
        val submitValues: List<String>,
        val levelLabels: List<String>,
    )

    // ===== 层级数据解析（对标 parse_multilevel_options） =====

    fun parseOptions(html: String, encoded: Boolean = false): Options {
        val doc = Jsoup.parse(html)
        val dataInput = doc.selectFirst("input#hfLevelData")
            ?: error("问卷星多级下拉未返回 hfLevelData")
        for (script in doc.select("script")) {
            if (externalDataRe.containsMatchIn(script.data())) {
                error("问卷星多级下拉暂不支持外部数据源")
            }
        }

        var data = dataInput.attr("value")
        if (encoded) data = percentDecode(data)
        val parts = data.split("〒", limit = 2)
        val levels = parts.getOrElse(0) { "" }
        val labels = parts.getOrElse(1) { "" }
        val columns = levels.split("|")
        require(levels.isNotEmpty() && columns.size in 1..6) {
            "问卷星多级下拉层级数据为空或超过六级"
        }

        var paths: MutableList<MutableList<String>> = mutableListOf()
        val terminalPaths = mutableListOf<MutableList<String>>()
        var previousOptions: Set<String> = emptySet()

        columns.forEachIndexed { levelIndex, column ->
            val lines = column.split(Regex("\r\n|\r|\n"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (levelIndex == 0) {
                require(lines.isNotEmpty() && lines.none { it.startsWith("---") }) {
                    "问卷星多级下拉首级选项无效"
                }
                paths = lines.map { mutableListOf(it) }.toMutableList()
                previousOptions = lines.toSet()
                return@forEachIndexed
            }

            val groups = LinkedHashMap<String, MutableList<String>>()
            var parent = ""
            for (line in lines) {
                if (line.startsWith("---")) {
                    parent = line.removePrefix("---")
                    require(parent in previousOptions) {
                        "问卷星多级下拉父选项不存在：$parent"
                    }
                    groups[parent] = mutableListOf()
                } else if (parent.isNotEmpty()) {
                    groups.getOrPut(parent) { mutableListOf() }.add(line)
                } else {
                    error("问卷星多级下拉子选项缺少父选项")
                }
            }

            val nextPaths = mutableListOf<MutableList<String>>()
            for (path in paths) {
                val children = groups[path.last()]
                    ?: error("问卷星多级下拉缺少子选项：${path.last()}")
                require(children.isNotEmpty()) {
                    "问卷星多级下拉缺少子选项：${path.last()}"
                }
                val hasTerminalChild = children.any { it.indexOf('々') > 0 }
                if (hasTerminalChild) {
                    terminalPaths.add(path)
                } else {
                    for (child in children) nextPaths.add((path + child).toMutableList())
                }
            }
            paths = nextPaths
            previousOptions = groups.values.flatten().toSet()
        }

        val finalPaths = terminalPaths + paths
        val submitValues = mutableListOf<String>()
        val optionTexts = mutableListOf<String>()
        for (path in finalPaths) {
            val values = path.map { markerRe.replace(it, "") }
            submitValues.add(values.joinToString(SPLIT_CHAR))
            optionTexts.add(values.joinToString(SPLIT_CHAR) { stripDisplaySuffix(it) })
        }
        return Options(
            optionTexts = optionTexts,
            submitValues = submitValues,
            levelLabels = if (labels.isEmpty()) emptyList() else labels.split("|"),
        )
    }

    // ===== 拉取选项（对标 load_multilevel_options） =====

    suspend fun loadOptions(
        questions: List<SurveyQuestionMeta>,
        html: String,
        url: String,
        http: HttpClient,
    ): List<SurveyQuestionMeta> {
        if (questions.none { it.providerType == PROVIDER_TYPE }) return questions

        val scripts = Jsoup.parse(html).select("script").joinToString("\n") { it.data() }
        val activityMatch = activityIdRe.find(scripts)
            ?: error("问卷星多级下拉缺少 activityId")
        val encodedActivityId = activityMatch.groupValues.getOrNull(1).orEmpty()
        val plainActivityId = activityMatch.groupValues.getOrNull(2).orEmpty()
        val activityId = if (encodedActivityId.isNotEmpty()) {
            val decodeMatch = decodeIdRe.find(scripts)
                ?: error("问卷星多级下拉无法解析 activityId")
            val xorValue = decodeMatch.groupValues[1].toIntOrNull()
                ?: error("问卷星多级下拉无法解析 activityId")
            (encodedActivityId.toIntOrNull()
                ?: error("问卷星多级下拉无法解析 activityId")) xor xorValue
        } else {
            plainActivityId.toIntOrNull()
                ?: error("问卷星多级下拉无法解析 activityId")
        }

        val base = URL(url)
        val menuUrl = URL(base, "/joinnew/setmenuselnew.aspx").toString()
        val headers = UserAgents.DEFAULT_HEADERS + ("Referer" to url)

        return questions.map { question ->
            if (question.providerType != PROVIDER_TYPE) return@map question
            val raw = question.providerPageRaw.orEmpty()
            val cid = (raw["cid"] as? String).orEmpty().trim()

            val query = buildString {
                append("?activityid=").append(activityId)
                append("&ct=5")
                append("&pos=").append(question.num)
                if (cid.isNotEmpty()) {
                    append("&cid=").append(URLEncoder.encode(cid, Charsets.UTF_8.name()))
                }
            }
            val response = http.get(menuUrl + query, headers = headers, timeoutSeconds = 12)
            if (response.statusCode !in 200..299) {
                error("问卷星多级下拉选项请求失败：HTTP ${response.statusCode}")
            }
            val options = parseOptions(response.body, encoded = cid.isNotEmpty())
            question.copy(
                optionTexts = options.optionTexts,
                options = options.optionTexts.size,
                providerPageRaw = raw + mapOf(
                    "submit_values" to options.submitValues,
                    "level_labels" to options.levelLabels,
                ),
            )
        }
    }

    // ===== 作答映射（对标 multilevel_answer_action） =====

    /** 多级下拉按文本路径提交，而不是选项序号。 */
    fun applyAnswer(action: AnswerAction, question: SurveyQuestionMeta): AnswerAction {
        if (question.providerType != PROVIDER_TYPE) return action
        val values = question.providerPageRaw?.get("submit_values") as? List<*>
            ?: error("问卷星第${question.num}题多级下拉缺少提交数据")
        val selectedIndex = action.selectedIndices.firstOrNull()
            ?: error("问卷星第${question.num}题多级下拉缺少选项答案")
        val value = values.getOrNull(selectedIndex) as? String
            ?: error("问卷星第${question.num}题多级下拉选项越界")
        return action.copy(kind = "text", textValues = listOf(value))
    }

    // ===== 私有工具 =====

    private fun stripDisplaySuffix(value: String): String =
        if (value.length > 1 && value.last() in DISPLAY_SUFFIXES) value.dropLast(1) else value

    /** 对齐 Python unquote：不把 '+' 当空格。 */
    private fun percentDecode(value: String): String =
        runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(value)
}
