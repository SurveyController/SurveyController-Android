package com.surveycontroller.android.provider.tencent

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.HttpResponse
import com.surveycontroller.android.provider.SubmitResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TencentProviderTest {

    @Test
    fun merge_submit_questions_keeps_config_logic_and_updates_runtime_fields() {
        val provider = TencentProvider(QueueHttpClient())
        val existing = SurveyQuestionMeta(
            num = 8,
            displayNum = 8,
            title = "请选择",
            typeCode = "3",
            page = 1,
            provider = "qq",
            providerQuestionId = "old-qid",
            providerPageId = "old-page",
            providerType = "radio",
            options = 2,
            hasJump = true,
            jumpRules = listOf(JumpRule(optionIndex = 0, targetQuestion = 10)),
            logicParseStatus = "complete",
        )
        val current = SurveyQuestionMeta(
            num = 1,
            title = "请选择",
            typeCode = "3",
            page = 2,
            provider = "qq",
            providerQuestionId = "runtime-qid",
            providerPageId = "runtime-page",
            providerType = "radio",
            options = 3,
            optionTexts = listOf("A", "B", "C"),
        )

        val merged = provider.mergeSubmitQuestions(
            ExecutionConfig(questionsMetadata = mapOf(8 to existing)),
            listOf(current),
        ).single()

        assertEquals(8, merged.num)
        assertEquals("runtime-qid", merged.providerQuestionId)
        assertEquals("runtime-page", merged.providerPageId)
        assertEquals(3, merged.options)
        assertTrue(merged.hasJump)
        assertEquals(10, merged.jumpRules.single().targetQuestion)
        assertEquals("complete", merged.logicParseStatus)
    }

    @Test
    fun score_answer_uses_scalar_answer_field_for_nps() {
        val provider = TencentProvider(QueueHttpClient())
        val encoded = provider.encodeAnswer(
            JSONObject(
                """
                {
                  "id": "q-15-a899",
                  "type": "nps",
                  "star_begin_num": 0,
                  "star_num": 11
                }
                """.trimIndent(),
            ),
            AnswerAction(
                questionNum = 8,
                questionId = "q-15-a899",
                kind = "choice",
                selectedIndices = listOf(3),
                scalarValue = 3,
                recordType = "score",
            ),
        ).single()

        assertEquals("q-15-a899-3", encoded.getString("id"))
        assertEquals("nps", encoded.getString("type"))
        assertEquals("3", encoded.getString("answer"))
    }

    @Test
    fun score_answer_rejects_missing_question_id_before_submit() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "type": "nps",
                      "star_begin_num": 0,
                      "star_num": 11
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 8,
                    kind = "choice",
                    selectedIndices = listOf(3),
                    scalarValue = 3,
                    recordType = "score",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少题目 id"))
    }

    @Test
    fun matrix_star_answer_builds_star_options_without_raw_options() {
        val provider = TencentProvider(QueueHttpClient())
        val encoded = provider.encodeAnswer(
            JSONObject(
                """
                {
                  "id": "q-13-7d39",
                  "type": "matrix_star",
                  "star_begin_num": 1,
                  "star_num": 5,
                  "sub_titles": [{"id": "g-101-ae7c", "text": "薪资福利"}]
                }
                """.trimIndent(),
            ),
            AnswerAction(
                questionNum = 4,
                questionId = "q-13-7d39",
                kind = "matrix",
                matrixIndices = listOf(2),
                recordType = "matrix",
            ),
        ).single()

        assertEquals("q-13-7d39-g-101-ae7c-3", encoded.getString("id"))
        assertEquals("matrix_star", encoded.getString("type"))
        assertEquals("on", encoded.getString("answer"))
    }

    @Test
    fun matrix_radio_answer_uses_question_row_id_and_option_id() {
        val provider = TencentProvider(QueueHttpClient())
        val encoded = provider.encodeAnswer(
            JSONObject(
                """
                {
                  "id": "q-16-6ef8",
                  "type": "matrix_radio",
                  "options": [
                    {"id": "o-101-1d22", "text": "较弱"},
                    {"id": "o-102-98ff", "text": "一般"}
                  ],
                  "sub_titles": [
                    {"id": "g-101-f3a5", "text": "专业实践能力"},
                    {"id": "g-102-fefa", "text": "外语能力"}
                  ]
                }
                """.trimIndent(),
            ),
            AnswerAction(
                questionNum = 16,
                questionId = "q-16-6ef8",
                kind = "matrix",
                matrixIndices = listOf(1, 0),
                recordType = "matrix",
            ),
        )

        assertEquals("q-16-6ef8_g-101-f3a5_o-102-98ff", encoded[0].getString("id"))
        assertEquals("q-16-6ef8_g-102-fefa_o-101-1d22", encoded[1].getString("id"))
        assertTrue(encoded.all { it.getString("type") == "matrix_radio" && it.getString("answer") == "on" })
    }

    @Test
    fun matrix_radio_answer_rejects_missing_option_id() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q16",
                      "type": "matrix_radio",
                      "options": [{"text": "较弱"}, {"text": "一般"}],
                      "sub_titles": [{"id": "g-101-f3a5", "text": "专业实践能力"}]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 16,
                    questionId = "q-16-6ef8",
                    kind = "matrix",
                    matrixIndices = listOf(1),
                    recordType = "matrix",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少矩阵列 id"))
    }

    @Test
    fun matrix_radio_answer_rejects_missing_row_id() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q16",
                      "type": "matrix_radio",
                      "options": [{"id": "o-1", "text": "较弱"}, {"id": "o-2", "text": "一般"}],
                      "sub_titles": [{"text": "专业实践能力"}]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 16,
                    questionId = "q16",
                    kind = "matrix",
                    matrixIndices = listOf(1),
                    recordType = "matrix",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少矩阵行 id"))
    }

    @Test
    fun matrix_answer_rejects_empty_business_answer() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject("""{"id": "q4", "type": "matrix_radio", "sub_titles": [{"id": "g1", "text": "薪资福利"}]}"""),
                AnswerAction(questionNum = 4, questionId = "q4", kind = "matrix", matrixIndices = listOf(0), recordType = "matrix"),
            )
        }

        assertTrue(error.message.orEmpty().contains("没有可提交的矩阵列"))
    }

    @Test
    fun number_answer_is_encoded_as_text_payload() {
        val provider = TencentProvider(QueueHttpClient())

        val encoded = provider.encodeAnswer(
            JSONObject("""{"id": "q-age", "type": "number", "title": "年龄"}"""),
            AnswerAction(
                questionNum = 3,
                questionId = "q-age",
                kind = "text",
                textValues = listOf("28"),
                recordType = "text",
            ),
        ).single()

        assertEquals("q-age", encoded.getString("id"))
        assertEquals("number", encoded.getString("type"))
        assertEquals("28", encoded.getString("text"))
    }

    @Test
    fun text_answer_rejects_blank_answer_before_submit() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject("""{"id": "q-text", "type": "text", "title": "说明"}"""),
                AnswerAction(
                    questionNum = 3,
                    questionId = "q-text",
                    kind = "text",
                    textValues = listOf(" ", ""),
                    recordType = "text",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("没有生成填空答案"))
    }

    @Test
    fun choice_answer_encodes_option_fill_text_as_blanks_payload() {
        val provider = TencentProvider(QueueHttpClient())

        val encoded = provider.encodeAnswer(
            JSONObject(
                """
                {
                  "id": "q-1",
                  "type": "radio",
                  "options": [
                    {"id": "o-1", "text": "固定"},
                    {"id": "o-2", "text": "其他____{fillblank-abc}"}
                  ],
                  "blank_setting": [
                    {"id": "fillblank-abc", "type": "option", "attach_id": "o-2", "validate": "unlimited"}
                  ]
                }
                """.trimIndent(),
            ),
            AnswerAction(
                questionNum = 1,
                questionId = "q-1",
                kind = "choice",
                selectedIndices = listOf(1),
                optionFillTexts = listOf(1 to "补充说明"),
                recordType = "single",
            ),
        ).single()
        val blanks = encoded.getJSONArray("blanks")

        assertEquals(1, blanks.length())
        assertEquals("fillblank-abc", blanks.getJSONObject(0).getString("id"))
        assertEquals("补充说明", blanks.getJSONObject(0).getString("value"))
        assertEquals(1, encoded.getJSONArray("options").getJSONObject(1).getInt("checked"))
    }

    @Test
    fun choice_answer_rejects_missing_option_id_before_submit() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q-1",
                      "type": "radio",
                      "options": [
                        {"id": "o-1", "text": "固定"},
                        {"text": "缺 id"}
                      ]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 1,
                    questionId = "q-1",
                    kind = "choice",
                    selectedIndices = listOf(0),
                    recordType = "single",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少选项 id"))
    }

    @Test
    fun choice_answer_rejects_out_of_range_selected_option() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q-1",
                      "type": "checkbox",
                      "options": [
                        {"id": "o-1", "text": "A"},
                        {"id": "o-2", "text": "B"}
                      ]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 1,
                    questionId = "q-1",
                    kind = "choice",
                    selectedIndices = listOf(2),
                    recordType = "multiple",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("选项越界"))
    }

    @Test
    fun choice_answer_rejects_multiple_selected_options_for_radio() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q-1",
                      "type": "radio",
                      "options": [
                        {"id": "o-1", "text": "A"},
                        {"id": "o-2", "text": "B"}
                      ]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 1,
                    questionId = "q-1",
                    kind = "choice",
                    selectedIndices = listOf(0, 1),
                    recordType = "single",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("只能提交一个选项"))
    }

    @Test
    fun choice_answer_rejects_option_fill_text_when_blank_id_is_missing() {
        val provider = TencentProvider(QueueHttpClient())

        val error = assertThrows(IllegalStateException::class.java) {
            provider.encodeAnswer(
                JSONObject(
                    """
                    {
                      "id": "q-1",
                      "type": "radio",
                      "options": [
                        {"id": "o-1", "text": "固定"},
                        {"id": "o-2", "text": "其他"}
                      ]
                    }
                    """.trimIndent(),
                ),
                AnswerAction(
                    questionNum = 1,
                    questionId = "q-1",
                    kind = "choice",
                    selectedIndices = listOf(1),
                    optionFillTexts = listOf(1 to "补充说明"),
                    recordType = "single",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("缺少填空 id"))
    }

    @Test
    fun choice_answer_falls_back_to_fillblank_token_when_blank_setting_is_absent() {
        val provider = TencentProvider(QueueHttpClient())

        val encoded = provider.encodeAnswer(
            JSONObject(
                """
                {
                  "id": "q-1",
                  "type": "checkbox",
                  "options": [
                    {"id": "o-1", "text": "固定"},
                    {"id": "o-2", "text": "其他____{fillblank-token}"}
                  ]
                }
                """.trimIndent(),
            ),
            AnswerAction(
                questionNum = 1,
                questionId = "q-1",
                kind = "choice",
                selectedIndices = listOf(0, 1),
                optionFillTexts = listOf(1 to "补充说明"),
                recordType = "multiple",
            ),
        ).single()

        assertEquals("fillblank-token", encoded.getJSONArray("blanks").getJSONObject(0).getString("id"))
    }

    @Test
    fun fill_survey_confirms_submit_persisted_after_ok_response() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            questionsBody(),
            submitBody(answerHash = "answer-hash"),
            sessionBody(lastSubmittedAt = 101, lastAnswerId = 10),
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            singleProb = listOf(listOf(100.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Success)
        assertEquals(3, http.getUrls.size)
        assertTrue(http.getUrls[0].contains("/session"))
        assertTrue(http.getUrls[1].contains("/questions"))
        assertTrue(http.getUrls[2].contains("/session"))
        assertEquals(1, http.postUrls.size)
        assertTrue(http.postBodies.single().contains("q-1"))
    }

    @Test
    fun fill_survey_uses_fixed_answer_duration_without_defaulting_to_ninety_seconds() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            questionsBody(),
            submitBody(answerHash = "answer-hash"),
            sessionBody(lastSubmittedAt = 101, lastAnswerId = 10),
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            singleProb = listOf(listOf(100.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            answerDurationRangeSeconds = 120..120,
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Success)
        val duration = JSONObject(http.postBodies.single())
            .getJSONObject("answer_survey")
            .getInt("duration")
        assertEquals(120, duration)
    }

    @Test
    fun fill_survey_submits_tencent_score_and_matrix_star_questions() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            ratingQuestionsBody(),
            submitBody(answerHash = "answer-hash"),
            sessionBody(lastSubmittedAt = 101, lastAnswerId = 10),
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            scaleProb = listOf(listOf(0.0, 0.0, 0.0, 100.0, 0.0)),
            matrixProb = listOf(listOf(0.0, 0.0, 100.0, 0.0, 0.0)),
            questionConfigIndexMap = mapOf(
                1 to ("score" to 0),
                2 to ("matrix" to 0),
            ),
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Success)
        val submitted = JSONObject(http.postBodies.single())
        val answerSurvey = submitted.getJSONObject("answer_survey")
        val pageQuestions = answerSurvey.getJSONArray("pages")
            .getJSONObject(0)
            .getJSONArray("questions")
        assertEquals("q-nps-3", pageQuestions.getJSONObject(0).getString("id"))
        assertEquals("3", pageQuestions.getJSONObject(0).getString("answer"))
        assertEquals("q-matrix-row-3", pageQuestions.getJSONObject(1).getString("id"))
        assertEquals("on", pageQuestions.getJSONObject(1).getString("answer"))
    }

    @Test
    fun fill_survey_fails_when_submit_ok_but_persistence_is_not_confirmed() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            questionsBody(),
            submitBody(answerHash = "answer-hash"),
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            singleProb = listOf(listOf(100.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Failure)
        assertEquals("腾讯问卷提交后未确认到服务端已记录答案", (result as SubmitResult.Failure).message)
        assertEquals(5, http.getUrls.size)
    }

    @Test
    fun fill_survey_fails_before_submit_when_runtime_questions_include_unsupported_type() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            unsupportedQuestionsBody(),
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            singleProb = listOf(listOf(100.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Failure)
        assertTrue((result as SubmitResult.Failure).message.contains("暂不支持"))
        assertTrue(result.message.contains("upload"))
        assertEquals(0, http.postUrls.size)
    }

    @Test
    fun fill_survey_keeps_config_jump_logic_when_runtime_metadata_is_refreshed() = runBlocking {
        val http = QueueHttpClient(
            sessionBody(lastSubmittedAt = 100, lastAnswerId = 10),
            twoRadioQuestionsBody(),
            submitBody(answerHash = "answer-hash"),
            sessionBody(lastSubmittedAt = 101, lastAnswerId = 10),
        )
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "请选择",
            typeCode = "3",
            page = 1,
            provider = "qq",
            providerQuestionId = "old-q1",
            providerPageId = "old-p1",
            providerType = "radio",
            options = 2,
            hasJump = true,
            jumpRules = listOf(JumpRule(optionIndex = 0, targetQuestion = 3)),
            logicParseStatus = "complete",
        )
        val q2 = SurveyQuestionMeta(
            num = 2,
            title = "后续题",
            typeCode = "3",
            page = 1,
            provider = "qq",
            providerQuestionId = "old-q2",
            providerPageId = "old-p1",
            providerType = "radio",
            options = 2,
        )
        val config = ExecutionConfig(
            url = "https://wj.qq.com/s2/123456/abcDEF/",
            surveyProvider = "qq",
            singleProb = listOf(listOf(100.0, 0.0), listOf(100.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0), 2 to ("single" to 1)),
            questionsMetadata = mapOf(1 to q1, 2 to q2),
        )
        val state = ExecutionState(config)

        val result = TencentProvider(http).fillSurveyHttp(
            config = config,
            state = state,
            threadName = "T1",
            proxyAddress = null,
            userAgent = "ua-test",
        )

        assertTrue(result is SubmitResult.Success)
        val pageQuestions = JSONObject(http.postBodies.single())
            .getJSONObject("answer_survey")
            .getJSONArray("pages")
            .getJSONObject(0)
            .getJSONArray("questions")
        assertEquals(1, pageQuestions.length())
        assertEquals("q-1", pageQuestions.getJSONObject(0).getString("id"))
        assertTrue(!http.postBodies.single().contains("q-2"))
    }

    private class QueueHttpClient(vararg bodies: String) : HttpClient() {
        private val queue = ArrayDeque(bodies.toList())
        val getUrls = mutableListOf<String>()
        val postUrls = mutableListOf<String>()
        val postBodies = mutableListOf<String>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
            proxyAddress: String?,
        ): HttpResponse {
            getUrls.add(url)
            return HttpResponse(statusCode = 200, body = queue.removeFirstOrNull() ?: "{}", finalUrl = url)
        }

        override suspend fun postBody(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            timeoutSeconds: Long,
            proxyAddress: String?,
            surveySubmission: Boolean,
        ): HttpResponse {
            postUrls.add(url)
            postBodies.add(body)
            return HttpResponse(statusCode = 200, body = queue.removeFirstOrNull() ?: "{}", finalUrl = url)
        }
    }

    private fun sessionBody(lastSubmittedAt: Long, lastAnswerId: Long): String =
        """
        {
          "code": "OK",
          "data": {
            "answer_session_id": "session-1",
            "answer_session": {
              "last_submitted_at": $lastSubmittedAt,
              "last_answer_id": $lastAnswerId
            }
          }
        }
        """.trimIndent()

    private fun questionsBody(): String =
        """
        {
          "code": "OK",
          "data": {
            "questions": [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "请选择",
                "options": [
                  {"id": "o-1", "text": "A"},
                  {"id": "o-2", "text": "B"}
                ]
              }
            ]
          }
        }
        """.trimIndent()

    private fun ratingQuestionsBody(): String =
        """
        {
          "code": "OK",
          "data": {
            "questions": [
              {
                "id": "q-nps",
                "page_id": "p-1",
                "page": 1,
                "type": "nps",
                "title": "推荐度",
                "star_begin_num": 0,
                "star_num": 5
              },
              {
                "id": "q-matrix",
                "page_id": "p-1",
                "page": 1,
                "type": "matrix_star",
                "title": "满意度矩阵",
                "star_begin_num": 1,
                "star_num": 5,
                "sub_titles": [
                  {"id": "row", "text": "服务"}
                ]
              }
            ]
          }
        }
        """.trimIndent()

    private fun unsupportedQuestionsBody(): String =
        """
        {
          "code": "OK",
          "data": {
            "questions": [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "upload",
                "title": "上传文件"
              }
            ]
          }
        }
        """.trimIndent()

    private fun twoRadioQuestionsBody(): String =
        """
        {
          "code": "OK",
          "data": {
            "questions": [
              {
                "id": "q-1",
                "page_id": "p-1",
                "type": "radio",
                "title": "请选择",
                "options": [
                  {"id": "o-1", "text": "A"},
                  {"id": "o-2", "text": "B"}
                ]
              },
              {
                "id": "q-2",
                "page_id": "p-1",
                "type": "radio",
                "title": "后续题",
                "options": [
                  {"id": "o-3", "text": "C"},
                  {"id": "o-4", "text": "D"}
                ]
              }
            ]
          }
        }
        """.trimIndent()

    private fun submitBody(answerHash: String): String =
        """
        {
          "code": "OK",
          "data": {
            "answer_hash": "$answerHash"
          }
        }
        """.trimIndent()
}
