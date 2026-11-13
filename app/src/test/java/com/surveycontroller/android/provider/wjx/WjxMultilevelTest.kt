package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WjxMultilevelTest {

    private val levelData = """
        华南地区
        华北地区|---华南地区
        湖南省
        广东省
        ---华北地区
        河北省
        山东省|---湖南省
        长沙市
        株洲市
        ---广东省
        广州市
        深圳市
        ---河北省
        石家庄市
        保定区
        ---山东省
        济南市
        青岛市|---长沙市
        天心区
        岳麓区
        ---株洲市
        荷塘区
        芦淞区
        ---广州市
        天河区
        白云区
        ---深圳市
        罗湖区
        龙华区
        ---石家庄市
        长安区
        新华区
        ---保定区
        竞秀区
        莲池区
        ---济南市
        历下区
        章丘区
        ---青岛市
        黄岛区
        崂山区〒大区|省份|城市|区县
    """.trimIndent()

    private fun menuHtml(): String =
        "<input id=\"hfLevelData\" value=\"" +
            levelData.replace("&", "&amp;").replace("\"", "&quot;") +
            "\">"

    private fun pageHtml(): String {
        val questions = (1..10).joinToString("") {
            "<div topic=\"$it\" type=\"1\"><input id=\"q$it\" type=\"text\"></div>"
        }
        return """
            <html><head><title>多级下拉样本 - 问卷星</title></head><body>
            <div id="divQuestion"><fieldset>
            $questions
            <div topic="11" id="div11" type="9" gapfill="1" relation="0" style="display:none;">
              <div class="field-label"><div class="topicnumber">11.</div><div class="topichtml">题目</div></div>
            </div>
            <div topic="12" id="div12" type="1" req="1">
              <div class="field-label"><div class="topicnumber">11.</div><div class="topichtml">请选择：</div></div>
              <input type="text" id="q12" verify="多级下拉" readonly="readonly"
                onclick="openCityBox(this,5,event,12);">
            </div>
            </fieldset></div>
            <script>function DecodeId(input) { return input ^ 2130030173; }
            var activityId = DecodeId(1797297031);</script>
            </body></html>
        """.trimIndent()
    }

    @Test
    fun paths_follow_parents_and_use_wjx_separator() {
        val options = WjxMultilevel.parseOptions(menuHtml())
        assertEquals(16, options.optionTexts.size)
        assertEquals("华南地区‐湖南省‐长沙市‐天心区", options.optionTexts.first())
        assertEquals("华北地区‐山东省‐青岛市‐崂山区", options.optionTexts.last())
        assertFalse(options.optionTexts.contains("华南地区‐河北省‐长沙市‐天心区"))
        assertEquals(options.optionTexts, options.submitValues)
        assertEquals(listOf("大区", "省份", "城市", "区县"), options.levelLabels)
    }

    @Test
    fun preserves_submission_markers_and_decodes_cid_data() {
        val data = "总部╰1╯┌|---总部╰1╯┌\n研发部〒公司|部门"
        val encoded = java.net.URLEncoder.encode(data, Charsets.UTF_8.name())
        val options = WjxMultilevel.parseOptions(
            "<input id=\"hfLevelData\" value=\"$encoded\">",
            encoded = true,
        )
        assertEquals(listOf("总部‐研发部"), options.optionTexts)
        assertEquals(listOf("总部┌‐研发部"), options.submitValues)
    }

    @Test
    fun stops_at_hidden_child_levels() {
        val data = "甲\n乙|---甲\n无下级々\n---乙\n分部|---无下级々\n占位々\n---分部\n研发部"
        val options = WjxMultilevel.parseOptions("<input id=\"hfLevelData\" value=\"$data\">")
        assertEquals(listOf("甲", "乙‐分部‐研发部"), options.submitValues)
    }

    @Test
    fun parser_detects_multilevel_and_skips_hidden_empty_gapfill() {
        val (questions, _) = WjxHtmlParser.parse(pageHtml())
        assertEquals(11, questions.size)
        assertFalse(questions.any { it.num == 11 })
        val question = questions.last()
        assertEquals(12, question.num)
        assertEquals(11, question.displayNum)
        assertEquals("7", question.typeCode)
        assertEquals(WjxMultilevel.PROVIDER_TYPE, question.providerType)
        assertEquals(0, question.textInputs)
        assertFalse(question.isTextLike)
        assertEquals("", question.providerPageRaw?.get("cid"))
    }

    @Test
    fun answer_action_submits_path_instead_of_index() {
        val options = WjxMultilevel.parseOptions(menuHtml())
        val question = SurveyQuestionMeta(
            num = 12,
            title = "多级下拉",
            typeCode = "7",
            options = options.optionTexts.size,
            optionTexts = options.optionTexts,
            providerType = WjxMultilevel.PROVIDER_TYPE,
            providerPageRaw = mapOf(
                "submit_values" to options.submitValues,
                "level_labels" to options.levelLabels,
            ),
        )
        val action = AnswerAction(
            questionNum = 12,
            kind = "choice",
            inputType = "radio",
            selectedIndices = listOf(15),
            selectedTexts = listOf(options.optionTexts[15]),
            recordType = "dropdown",
        )
        val mapped = WjxMultilevel.applyAnswer(action, question)
        assertEquals("text", mapped.kind)
        assertEquals(listOf("华北地区‐山东省‐青岛市‐崂山区"), mapped.textValues)
        assertEquals(
            "12\$华北地区‐山东省‐青岛市‐崂山区",
            WjxSubmitCodec.buildSubmitData(listOf(mapped), listOf(question)),
        )
    }

    @Test
    fun config_round_trip_preserves_provider_page_raw() {
        val options = WjxMultilevel.parseOptions(menuHtml())
        val question = SurveyQuestionMeta(
            num = 12,
            title = "多级下拉",
            typeCode = "7",
            options = options.optionTexts.size,
            optionTexts = options.optionTexts,
            providerType = WjxMultilevel.PROVIDER_TYPE,
            providerPageRaw = mapOf(
                "cid" to "abc",
                "submit_values" to options.submitValues,
                "level_labels" to options.levelLabels,
            ),
        )
        val definition = com.surveycontroller.android.provider.SurveyDefinition(
            com.surveycontroller.android.provider.SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/sample.aspx",
            "多级下拉问卷",
            listOf(question),
        )
        val draft = com.surveycontroller.android.data.SurveyConfigDraft.fromDefinition(definition)
        val restored = com.surveycontroller.android.data.ConfigCodec.deserialize(
            com.surveycontroller.android.data.ConfigCodec.serialize(draft),
        )
        val restoredMeta = restored.definition.questions.single()
        assertEquals(WjxMultilevel.PROVIDER_TYPE, restoredMeta.providerType)
        assertEquals(16, (restoredMeta.providerPageRaw?.get("submit_values") as List<*>).size)
        assertEquals("abc", restoredMeta.providerPageRaw?.get("cid"))
    }

    @Test
    fun loads_options_with_decoded_activity_id_and_cid() = runBlocking {
        val requested = mutableListOf<String>()
        val http = object : HttpClient() {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
                timeoutSeconds: Long,
                proxyAddress: String?,
            ): HttpResponse {
                requested.add(url)
                assertEquals("https://v.wjx.cn/vm/sample.aspx", headers["Referer"])
                assertEquals(12L, timeoutSeconds)
                return HttpResponse(200, menuHtml(), url)
            }
        }
        val question = SurveyQuestionMeta(
            num = 12,
            title = "多级下拉",
            typeCode = "7",
            providerType = WjxMultilevel.PROVIDER_TYPE,
            providerPageRaw = mapOf("cid" to "abc def"),
        )
        val loaded = WjxMultilevel.loadOptions(
            listOf(question), pageHtml(), "https://v.wjx.cn/vm/sample.aspx", http,
        ).single()

        assertEquals(16, loaded.options)
        assertEquals("华南地区‐湖南省‐长沙市‐天心区", loaded.optionTexts.first())
        assertTrue(requested.single().contains("activityid=366296538"))
        assertTrue(requested.single().contains("pos=12"))
        assertTrue(requested.single().contains("cid=abc+def"))
        val raw = loaded.providerPageRaw
        assertEquals(16, (raw?.get("submit_values") as List<*>).size)
    }
}
