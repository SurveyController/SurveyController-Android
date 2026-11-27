package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AnswerContext
import com.surveycontroller.android.core.questions.AiTextException
import com.surveycontroller.android.core.questions.AiTextProvider
import com.surveycontroller.android.core.questions.ReverseFillAnswer
import com.surveycontroller.android.core.questions.ReverseFillResolver
import com.surveycontroller.android.core.questions.SurveyAnswerBuilder
import com.surveycontroller.android.provider.tencent.TencentApi
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class SurveyAnswerBuilderTest {

    @Test
    fun samples_only_from_the_selected_options_candidates() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1, title = "测试题", typeCode = "3", options = 2,
            optionTexts = listOf("A", "B"), fillableOptions = listOf(0, 1), forcedOptionIndex = 1,
        )
        val config = ExecutionConfig(
            url = "https://www.wjx.cn/vm/demo.aspx",
            singleProb = listOf(listOf(50.0, 50.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            optionFillCandidates = mapOf(1 to mapOf(0 to listOf("未选"), 1 to listOf("甲", "乙"))),
        )
        val answer = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)
        val fill = answer?.optionFillTexts?.single()
        assertEquals(1, fill?.first)
        assertTrue(fill?.second in listOf("甲", "乙"))
    }

    @Test
    fun single_choice_generates_attached_select_choice_when_selected_option_has_child_select() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "城市",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("其他城市", "不方便透露"),
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(100.0, 0.0)),
            singleAttachedOptionSelects = listOf(
                listOf(
                    mapOf(
                        "option_index" to 0,
                        "option_text" to "其他城市",
                        "select_options" to listOf("北京", "上海"),
                        "select_values" to listOf("1", "2"),
                        "weights" to listOf(0.0, 100.0),
                    ),
                ),
            ),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(0), action?.selectedIndices)
        assertEquals(0, action?.attachedSelectChoices?.single()?.optionIndex)
        assertEquals(1, action?.attachedSelectChoices?.single()?.selectedIndex)
        assertEquals("2", action?.attachedSelectChoices?.single()?.value)
    }

    @Test
    fun multiple_choice_generates_attached_select_choices_for_selected_options() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "可多选城市",
            typeCode = "4",
            options = 3,
            optionTexts = listOf("北京", "其他城市", "暂不填写"),
        )
        val config = ExecutionConfig(
            multipleProb = listOf(listOf(100.0, 100.0, 0.0)),
            multipleAttachedOptionSelects = listOf(
                listOf(
                    mapOf(
                        "option_index" to 1,
                        "option_text" to "其他城市",
                        "select_options" to listOf("上海", "广州"),
                        "select_values" to listOf("sh", "gz"),
                        "weights" to listOf(0.0, 100.0),
                    ),
                ),
            ),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(0, 1), action?.selectedIndices)
        assertEquals(1, action?.attachedSelectChoices?.single()?.optionIndex)
        assertEquals(1, action?.attachedSelectChoices?.single()?.selectedIndex)
        assertEquals("gz", action?.attachedSelectChoices?.single()?.value)
    }

    @Test
    fun provider_question_key_falls_back_to_original_config_when_current_question_number_changes() = runBlocking {
        val runtimeQuestion = SurveyQuestionMeta(
            num = 7,
            title = "运行时重新解析题",
            typeCode = "3",
            provider = "credamo",
            providerPageId = "page-a",
            providerQuestionId = "qid-a",
            options = 2,
            optionTexts = listOf("A", "B"),
        )
        val config = ExecutionConfig(
            surveyProvider = "credamo",
            singleProb = listOf(listOf(100.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            providerQuestionConfigIndexMap = mapOf("credamo:page-a:qid-a" to ("single" to 0)),
            providerQuestionConfigNumMap = mapOf("credamo:page-a:qid-a" to 1),
            questionsMetadata = mapOf(7 to runtimeQuestion),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                if (questionNum == 1) ReverseFillAnswer(kind = "choice", choiceIndex = 1) else null
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(
                config = config,
                state = ExecutionState(config),
                reverseFill = reverse,
            ),
        ).buildAction(runtimeQuestion)

        assertEquals(7, action?.questionNum)
        assertEquals(1, action?.configQuestionNum)
        assertEquals("qid-a", action?.questionId)
        assertEquals(listOf(1), action?.selectedIndices)
    }

    @Test
    fun multiple_reverse_fill_choice_is_padded_to_runtime_minimum_limit() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 4,
            optionTexts = listOf("A", "B", "C", "D"),
            multiMinLimit = 2,
            multiMaxLimit = 3,
        )
        val config = ExecutionConfig(
            multipleProb = listOf(listOf(-1.0)),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                ReverseFillAnswer(kind = "choice", choiceIndex = 0)
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), reverseFill = reverse),
        ).buildAction(question)

        assertTrue(action?.selectedIndices?.contains(0) == true)
        assertEquals(2, action?.selectedIndices?.size)
    }

    @Test
    fun multiple_reverse_fill_replays_multiple_choice_indexes_with_runtime_max_limit() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 4,
            optionTexts = listOf("A", "B", "C", "D"),
            multiMinLimit = 1,
            multiMaxLimit = 2,
        )
        val config = ExecutionConfig(
            multipleProb = listOf(listOf(-1.0)),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                ReverseFillAnswer(kind = "choice", choiceIndexes = listOf(0, 2, 3))
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), reverseFill = reverse),
        ).buildAction(question)

        assertEquals(listOf(0, 2), action?.selectedIndices)
    }

    @Test
    fun tencent_checkbox_limits_from_parser_are_applied_to_multiple_answer_generation() = runBlocking {
        val question = TencentApi.standardize(
            JSONArray(
                """
                [
                  {
                    "id": "q-1",
                    "page_id": "p-1",
                    "type": "checkbox",
                    "title": "最多选两项",
                    "min_length": 2,
                    "max_length": 2,
                    "options": [
                      {"id": "o-1", "text": "A"},
                      {"id": "o-2", "text": "B"},
                      {"id": "o-3", "text": "C"},
                      {"id": "o-4", "text": "D"}
                    ]
                  }
                ]
                """.trimIndent(),
            ),
        ).single()
        val config = ExecutionConfig(
            surveyProvider = "qq",
            multipleProb = listOf(listOf(100.0, 100.0, 100.0, 100.0)),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(2, action?.selectedIndices?.size)
    }

    @Test
    fun tencent_fillblank_option_from_parser_generates_option_fill_text() = runBlocking {
        val question = TencentApi.standardize(
            JSONArray(
                """
                [
                  {
                    "id": "q-1",
                    "page_id": "p-1",
                    "type": "radio",
                    "title": "其他说明",
                    "options": [
                      {"id": "o-1", "text": "固定"},
                      {"id": "o-2", "text": "其他___{fillblank-abc}"}
                    ]
                  }
                ]
                """.trimIndent(),
            ),
        ).single()
        val config = ExecutionConfig(
            surveyProvider = "qq",
            singleProb = listOf(listOf(0.0, 100.0)),
            singleOptionFillTexts = listOf(listOf(null, "补充说明")),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(1), action?.selectedIndices)
        assertEquals(listOf(1 to "补充说明"), action?.optionFillTexts)
    }

    @Test
    fun multiple_must_select_rule_survives_max_limit_when_optional_candidates_are_selected() = runBlocking {
        val trigger = SurveyQuestionMeta(
            num = 1,
            title = "条件题",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("触发", "不触发"),
        )
        val target = SurveyQuestionMeta(
            num = 2,
            title = "多选",
            typeCode = "4",
            options = 4,
            optionTexts = listOf("A", "B", "C", "D"),
            multiMinLimit = 1,
            multiMaxLimit = 2,
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(100.0, 0.0)),
            multipleProb = listOf(listOf(100.0, 100.0, 100.0, 100.0)),
            answerRules = listOf(
                mapOf(
                    "condition_question_num" to 1,
                    "condition_mode" to "selected",
                    "condition_option_indices" to listOf(0),
                    "target_question_num" to 2,
                    "action_mode" to "must_select",
                    "target_option_indices" to listOf(3),
                ),
            ),
            questionConfigIndexMap = mapOf(
                1 to ("single" to 0),
                2 to ("multiple" to 0),
            ),
            questionsMetadata = mapOf(1 to trigger, 2 to target),
        )
        val ctx = AnswerContext(config, ExecutionState(config))
        val builder = SurveyAnswerBuilder(ctx)

        builder.buildAction(trigger)?.let { ctx.answered.record(it) }
        val action = builder.buildAction(target)

        assertTrue(action?.selectedIndices?.contains(3) == true)
        assertTrue((action?.selectedIndices?.size ?: 0) <= 2)
    }

    @Test
    fun multiple_must_not_select_rule_applies_to_reverse_fill_choice() = runBlocking {
        val trigger = SurveyQuestionMeta(
            num = 1,
            title = "条件题",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("触发", "不触发"),
        )
        val target = SurveyQuestionMeta(
            num = 2,
            title = "多选",
            typeCode = "4",
            options = 3,
            optionTexts = listOf("A", "B", "C"),
            multiMinLimit = 1,
            multiMaxLimit = 2,
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(100.0, 0.0)),
            multipleProb = listOf(listOf(-1.0)),
            answerRules = listOf(
                mapOf(
                    "condition_question_num" to 1,
                    "condition_mode" to "selected",
                    "condition_option_indices" to listOf(0),
                    "target_question_num" to 2,
                    "action_mode" to "must_not_select",
                    "target_option_indices" to listOf(1),
                ),
            ),
            questionConfigIndexMap = mapOf(
                1 to ("single" to 0),
                2 to ("multiple" to 0),
            ),
            questionsMetadata = mapOf(1 to trigger, 2 to target),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                if (questionNum == 2) ReverseFillAnswer(kind = "choice", choiceIndex = 1) else null
        }
        val ctx = AnswerContext(config, ExecutionState(config), reverseFill = reverse)
        val builder = SurveyAnswerBuilder(ctx)

        builder.buildAction(trigger)?.let { ctx.answered.record(it) }
        val action = builder.buildAction(target)

        assertTrue(action?.selectedIndices?.contains(1) == false)
        assertEquals(1, action?.selectedIndices?.size)
    }

    @Test
    fun text_answer_uses_compiled_candidate_probabilities() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "文本",
            typeCode = "1",
            isTextLike = true,
            textInputs = 1,
        )
        val config = ExecutionConfig(
            texts = listOf(listOf("不会选", "必选")),
            textsProb = listOf(listOf(0.0, 1.0)),
            textEntryTypes = listOf("text"),
            questionConfigIndexMap = mapOf(1 to ("text" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val builder = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config)))

        repeat(20) {
            val action = builder.buildAction(question)
            assertEquals(listOf("必选"), action?.textValues)
        }
    }

    @Test
    fun multi_text_answer_splits_desktop_delimited_candidates() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "姓名和电话",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
        )
        val config = ExecutionConfig(
            texts = listOf(listOf("张三||13800000000")),
            textsProb = listOf(listOf(1.0)),
            textEntryTypes = listOf("multi_text"),
            questionConfigIndexMap = mapOf(1 to ("multi_text" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf("张三", "13800000000"), action?.textValues)
    }

    @Test
    fun option_fill_ai_placeholder_uses_ai_provider_result() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "城市",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("其他", "不填"),
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(100.0, 0.0)),
            singleOptionFillTexts = listOf(listOf("__AI_FILL__", null)),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        var promptTitle = ""
        val provider = object : AiTextProvider {
            override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> {
                promptTitle = question.title
                return listOf("AI补充")
            }
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), aiText = provider),
        ).buildAction(question)

        assertEquals(listOf(0 to "AI补充"), action?.optionFillTexts)
        assertTrue(promptTitle.contains("已选择的选项是：其他"))
    }

    @Test
    fun option_fill_ai_placeholder_falls_back_without_submitting_literal_token() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "城市",
            typeCode = "3",
            options = 1,
            optionTexts = listOf("其他"),
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(1.0)),
            singleOptionFillTexts = listOf(listOf("__AI_FILL__")),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(0 to "已填写"), action?.optionFillTexts)
    }

    @Test
    fun enabled_ai_text_throws_when_provider_returns_no_content() {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "开放题",
            typeCode = "1",
            isTextLike = true,
            textInputs = 1,
        )
        val config = ExecutionConfig(
            textEntryTypes = listOf("text"),
            textAiFlags = listOf(true),
            questionConfigIndexMap = mapOf(1 to ("text" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val provider = object : AiTextProvider {
            override suspend fun generate(question: SurveyQuestionMeta, blankCount: Int, threadName: String): List<String> = emptyList()
        }

        assertThrows(AiTextException::class.java) {
            runBlocking {
                SurveyAnswerBuilder(
                    AnswerContext(config, ExecutionState(config), aiText = provider),
                ).buildAction(question)
            }
        }
    }

    @Test
    fun slider_random_mode_samples_value_within_bounds_and_step() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "滑块",
            typeCode = "8",
            sliderMin = 10.0,
            sliderMax = 20.0,
            sliderStep = 2.0,
        )
        val config = ExecutionConfig(
            sliderTargets = listOf(Double.NaN),
            questionConfigIndexMap = mapOf(1 to ("slider" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val builder = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config)))

        repeat(40) {
            val value = builder.buildAction(question)?.sliderValue ?: error("slider action missing")
            assertTrue("value $value should stay within slider bounds", value in 10.0..20.0)
            val stepCount = (value - 10.0) / 2.0
            assertEquals(stepCount.roundToInt().toDouble(), stepCount, 0.000001)
        }
    }

    @Test
    fun slider_custom_target_is_clamped_to_runtime_bounds() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "滑块",
            typeCode = "8",
            sliderMin = 10.0,
            sliderMax = 20.0,
        )
        val config = ExecutionConfig(
            sliderTargets = listOf(99.0),
            questionConfigIndexMap = mapOf(1 to ("slider" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(20.0, action?.sliderValue ?: -1.0, 0.000001)
    }

    @Test
    fun slider_reverse_fill_value_overrides_config_and_is_clamped_to_runtime_bounds() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "滑块",
            typeCode = "8",
            sliderMin = 10.0,
            sliderMax = 20.0,
        )
        val config = ExecutionConfig(
            sliderTargets = listOf(12.0),
            questionConfigIndexMap = mapOf(1 to ("slider" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                ReverseFillAnswer(kind = "slider", sliderValue = 99.0)
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), reverseFill = reverse),
        ).buildAction(question)

        assertEquals(20.0, action?.sliderValue ?: -1.0, 0.000001)
    }

    @Test
    fun location_reverse_fill_parts_override_configured_default_parts() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "地区",
            typeCode = "1",
            isTextLike = true,
            isLocation = true,
        )
        val config = ExecutionConfig(
            locationParts = mapOf(1 to listOf("上海", "上海", "浦东新区")),
            questionConfigIndexMap = mapOf(1 to ("location" to -1)),
            questionsMetadata = mapOf(1 to question),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                ReverseFillAnswer(kind = "location", locationParts = listOf("北京", "北京", "东城区"))
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), reverseFill = reverse),
        ).buildAction(question)

        assertEquals(listOf("北京", "北京", "东城区"), action?.textValues)
    }

    @Test
    fun incomplete_location_reverse_fill_parts_do_not_override_configured_parts() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "地区",
            typeCode = "1",
            isTextLike = true,
            isLocation = true,
        )
        val config = ExecutionConfig(
            locationParts = mapOf(1 to listOf("上海", "上海", "浦东新区")),
            questionConfigIndexMap = mapOf(1 to ("location" to -1)),
            questionsMetadata = mapOf(1 to question),
        )
        val reverse = object : ReverseFillResolver {
            override fun resolve(questionNum: Int, threadName: String): ReverseFillAnswer? =
                ReverseFillAnswer(kind = "location", locationParts = listOf("北京", "北京"))
        }

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config), reverseFill = reverse),
        ).buildAction(question)

        assertEquals(listOf("上海", "上海", "浦东新区"), action?.textValues)
    }

    @Test
    fun incomplete_configured_location_parts_fall_back_to_default_parts() = runBlocking {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "地区",
            typeCode = "1",
            isTextLike = true,
            isLocation = true,
        )
        val config = ExecutionConfig(
            locationParts = mapOf(1 to listOf("上海", "上海")),
            questionConfigIndexMap = mapOf(1 to ("location" to -1)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(
            AnswerContext(config, ExecutionState(config)),
        ).buildAction(question)

        assertEquals(listOf("北京", "北京", "东城区"), action?.textValues)
    }

    @Test
    fun fillable_option_with_empty_fill_falls_back_to_default_fill_text() = runBlocking {
        // v4.0.3：含填空选项单选无法作答修复 —— fillableOptions 中的选项即使配置填空为空也返回 DEFAULT_FILL_TEXT
        val question = SurveyQuestionMeta(
            num = 1,
            title = "城市",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("北京", "其他"),
            fillableOptions = listOf(1),
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(0.0, 100.0)),
            // 第二选项填空配置为空字符串
            singleOptionFillTexts = listOf(listOf(null, "")),
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(1), action?.selectedIndices)
        assertEquals(listOf(1 to "已填写"), action?.optionFillTexts)
    }

    @Test
    fun fillable_option_without_config_entry_falls_back_to_default_fill_text() = runBlocking {
        // v4.0.3：fillableOptions 中的选项未配置填空条目时也返回 DEFAULT_FILL_TEXT
        val question = SurveyQuestionMeta(
            num = 1,
            title = "城市",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("北京", "其他"),
            fillableOptions = listOf(1),
        )
        val config = ExecutionConfig(
            singleProb = listOf(listOf(0.0, 100.0)),
            // singleOptionFillTexts 完全缺失
            questionConfigIndexMap = mapOf(1 to ("single" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(listOf(1 to "已填写"), action?.optionFillTexts)
    }

    @Test
    fun multiple_choice_with_all_zero_probs_and_no_min_limit_skips_question() = runBlocking {
        // v4.0.3 credamo：所有选项概率为 0、无必选项且无显式"至少选N项"时跳过该题
        val question = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 3,
            optionTexts = listOf("A", "B", "C"),
        )
        val config = ExecutionConfig(
            multipleProb = listOf(listOf(0.0, 0.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertEquals(null, action)
    }

    @Test
    fun multiple_choice_with_all_zero_probs_but_explicit_min_limit_still_selects() = runBlocking {
        // 有显式 multiMinLimit 时仍走 normalizeMultipleSelection 补足，不跳过
        val question = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 3,
            optionTexts = listOf("A", "B", "C"),
            multiMinLimit = 1,
        )
        val config = ExecutionConfig(
            multipleProb = listOf(listOf(0.0, 0.0, 0.0)),
            questionConfigIndexMap = mapOf(1 to ("multiple" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        val action = SurveyAnswerBuilder(AnswerContext(config, ExecutionState(config))).buildAction(question)

        assertTrue(action != null)
        assertEquals(1, action?.selectedIndices?.size)
    }

    @Test
    fun ai_text_without_provider_throws_instead_of_filling_default() {
        // v4.0.5+：resolveAiText 无 provider 改抛异常（停止提交而非静默提交默认）
        val question = SurveyQuestionMeta(
            num = 1,
            title = "开放题",
            typeCode = "1",
            isTextLike = true,
            textInputs = 1,
        )
        val config = ExecutionConfig(
            textEntryTypes = listOf("text"),
            textAiFlags = listOf(true),
            questionConfigIndexMap = mapOf(1 to ("text" to 0)),
            questionsMetadata = mapOf(1 to question),
        )

        assertThrows(AiTextException::class.java) {
            runBlocking {
                SurveyAnswerBuilder(
                    AnswerContext(config, ExecutionState(config)),
                ).buildAction(question)
            }
        }
    }

    @Test
    fun build_plan_rejects_unresolved_ai_placeholder_in_text_values() {
        // v4.0.5+：assertNoFreeAiPlaceholders 防御 —— __FREE_AI_TEXT__ 前缀未替换时抛异常
        val question = SurveyQuestionMeta(
            num = 1,
            title = "开放题",
            typeCode = "1",
            isTextLike = true,
            textInputs = 1,
        )
        val config = ExecutionConfig(
            questionConfigIndexMap = mapOf(1 to ("text" to 0)),
            questionsMetadata = mapOf(1 to question),
        )
        val ctx = AnswerContext(config, ExecutionState(config))
        val builder = SurveyAnswerBuilder(ctx)
        // 直接构造一个含未替换占位符的 action 验证防御逻辑
        val action = com.surveycontroller.android.core.model.AnswerAction(
            questionNum = 1,
            kind = "text",
            textValues = listOf("__FREE_AI_TEXT__1_0"),
            recordType = "text",
        )

        assertThrows(AiTextException::class.java) {
            builder.assertNoFreeAiPlaceholders(listOf(action))
        }
    }
}
