package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.AnswerAction
import com.surveycontroller.android.core.model.DisplayCondition
import com.surveycontroller.android.core.model.DisplayTarget
import com.surveycontroller.android.core.model.JumpRule
import com.surveycontroller.android.core.model.QuestionMedia
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.HttpLogicPlanner
import com.surveycontroller.android.core.questions.TextValues
import com.surveycontroller.android.data.ConfigCodec
import com.surveycontroller.android.data.ConfigCompiler
import com.surveycontroller.android.data.ConfigPreservedFields
import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun round_trips_config_with_schema_v6() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val q2 = SurveyQuestionMeta(num = 2, title = "满意度", typeCode = "5", options = 5, optionTexts = listOf("1", "2", "3", "4", "5"))
        val def = SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/abc.aspx", "测试问卷", listOf(q1, q2))
        var draft = SurveyConfigDraft.fromDefinition(def)
        // 调整一些配置
        draft = draft.copy(
            params = draft.params.copy(
                targetNum = 42,
                numThreads = 3,
                submitIntervalMin = 5,
                submitIntervalMax = 15,
                randomUserAgentEnabled = true,
                randomUserAgentRatios = mapOf("wechat" to 20, "mobile" to 30, "pc" to 50),
                stopOnFailEnabled = true,
                failThreshold = 9,
                reliabilityModeEnabled = false,
                psychoTargetAlpha = 0.9,
                aiMode = "free",
            ),
        )
        draft.questions.first { it.num == 2 }.optionWeights.let { /* 默认权重 */ }

        val json = ConfigCodec.serialize(draft)
        assertTrue(json.contains("\"config_schema_version\": 6"))

        val restored = ConfigCodec.deserialize(json)
        assertEquals("https://www.wjx.cn/vm/abc.aspx", restored.definition.url)
        assertEquals("测试问卷", restored.definition.title)
        assertEquals(SurveyProviderType.WJX, restored.definition.provider)
        assertEquals(2, restored.definition.questions.size)
        assertEquals(42, restored.params.targetNum)
        assertEquals(3, restored.params.numThreads)
        assertEquals(5, restored.params.submitIntervalMin)
        assertEquals(15, restored.params.submitIntervalMax)
        assertEquals(mapOf("wechat" to 20, "mobile" to 30, "pc" to 50), restored.params.randomUserAgentRatios)
        assertEquals(9, restored.params.failThreshold)
        assertEquals(false, restored.params.reliabilityModeEnabled)
        assertEquals(0.9, restored.params.psychoTargetAlpha, 0.0001)
        assertEquals(2, restored.questions.size)
        assertEquals("满意度", restored.questions.first { it.num == 2 }.title)
        assertEquals(9, ConfigCompiler.compile(restored).failThreshold)
    }

    @Test
    fun round_trips_option_fill_multi_blank_and_location_config() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "选一个",
            typeCode = "3",
            options = 3,
            optionTexts = listOf("A", "B", "其他"),
            fillableOptions = listOf(2),
        )
        val q2 = SurveyQuestionMeta(
            num = 2,
            title = "资料",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
            textInputLabels = listOf("姓名", "年龄"),
        )
        val q3 = SurveyQuestionMeta(
            num = 3,
            title = "地区",
            typeCode = "1",
            isLocation = true,
            textInputs = 0,
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/abc.aspx",
            "扩展配置",
            listOf(q1, q2, q3),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map { q ->
                when (q.num) {
                    1 -> q.copy(optionFillTexts = mutableListOf(null, null, "补充说明"))
                    2 -> q.copy(
                        multiTextBlankModes = mutableListOf("name", "integer"),
                        multiTextBlankAiFlags = mutableListOf(false, true),
                        multiTextBlankIntRanges = mutableListOf(mutableListOf(0, 100), mutableListOf(18, 60)),
                    )
                    3 -> q.copy(locationParts = mutableListOf("北京", "北京", "东城区"))
                    else -> q
                }
            },
        )

        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(draft))
        assertEquals("补充说明", restored.questions.first { it.num == 1 }.optionFillTexts[2])
        assertEquals(listOf("name", "integer"), restored.questions.first { it.num == 2 }.multiTextBlankModes)
        assertEquals(listOf(false, true), restored.questions.first { it.num == 2 }.multiTextBlankAiFlags)
        assertEquals(listOf("北京", "北京", "东城区"), restored.questions.first { it.num == 3 }.locationParts)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf(null, null, "补充说明"), compiled.singleOptionFillTexts.first())
        assertEquals(listOf("random_name", "random_integer"), compiled.multiTextBlankModes.first())
        assertEquals(listOf(false, true), compiled.multiTextBlankAiFlags.first())
        assertEquals(listOf(listOf(0, 100), listOf(18, 60)), compiled.multiTextBlankIntRanges.first())
        assertEquals("location" to -1, compiled.questionConfigIndexMap[3])
        assertEquals(listOf("北京", "北京", "东城区"), compiled.locationParts[3])
    }

    @Test
    fun compiles_desktop_runtime_fields_and_slider_bounds() {
        val slider = SurveyQuestionMeta(
            num = 1,
            title = "滑块",
            typeCode = "8",
            sliderMin = 10.0,
            sliderMax = 20.0,
        )
        val def = SurveyDefinition(SurveyProviderType.CREDAMO, "https://www.credamo.com/answer.html#x", "见数", listOf(slider))
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            params = base.params.copy(
                answerDatetimeStart = "2026-06-01 10:00",
                answerDatetimeEnd = "2026-06-01 11:00",
                randomUserAgentEnabled = true,
                randomUserAgentRatios = mapOf("wechat" to 10, "mobile" to 20, "pc" to 70),
                psychoTargetAlpha = 0.92,
            ),
        )

        assertEquals(15.0, draft.questions.first().sliderTarget, 0.0001)
        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(draft))
        assertEquals(10.0, restored.definition.questions.first().sliderMin!!, 0.0001)
        assertEquals(20.0, restored.definition.questions.first().sliderMax!!, 0.0001)

        val compiled = ConfigCompiler.compile(restored)
        assertTrue(compiled.answerDatetimeWindowMs.first > 0L)
        assertTrue(compiled.answerDatetimeWindowMs.last > compiled.answerDatetimeWindowMs.first)
        assertEquals(mapOf("wechat" to 10, "mobile" to 20, "pc" to 70), compiled.userAgentRatios)
        assertEquals(0.92, compiled.psychoTargetAlpha, 0.0001)
    }

    @Test
    fun preserves_unsafe_runtime_values_during_import_for_preflight_and_clamps_at_compile() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/runtime.aspx",
              "survey_title": "运行参数",
              "survey_provider": "wjx",
              "target": 0,
              "threads": 99,
              "fail_threshold": 0,
              "psycho_target_alpha": 1.2,
              "proxy_source": "unknown",
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        assertEquals(0, restored.params.targetNum)
        assertEquals(0, restored.params.failThreshold)
        assertEquals(1.2, restored.params.psychoTargetAlpha, 0.0001)
        assertEquals("default", restored.params.proxySource)
        val preflightMessage = ConfigPreflight.validate(restored).blockingMessage()
        assertTrue(preflightMessage.contains("目标份数无效"))
        assertTrue(preflightMessage.contains("连续失败阈值无效"))
        assertTrue(preflightMessage.contains("信度目标 Alpha 无效"))

        val compiled = ConfigCompiler.compile(
            restored.copy(
                params = restored.params.copy(
                    targetNum = 0,
                    numThreads = 99,
                    failThreshold = 0,
                    psychoTargetAlpha = 1.2,
                    proxySource = " mystery ",
                ),
            ),
        )
        assertEquals(1, compiled.targetNum)
        assertEquals(16, compiled.numThreads)
        assertEquals(1, compiled.failThreshold)
        assertEquals(0.95, compiled.psychoTargetAlpha, 0.0001)
        assertEquals("default", compiled.proxySource)
    }

    @Test
    fun exports_unsafe_runtime_values_without_silent_clamping() {
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(
                SurveyProviderType.WJX,
                "https://www.wjx.cn/vm/runtime-export.aspx",
                "运行参数",
                listOf(SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B"))),
            ),
        ).let {
            it.copy(params = it.params.copy(targetNum = 0, failThreshold = 0, psychoTargetAlpha = 1.2))
        }

        val root = JSONObject(ConfigCodec.serialize(draft))

        assertEquals(0, root.getInt("target"))
        assertEquals(0, root.getInt("fail_threshold"))
        assertEquals(1.2, root.getDouble("psycho_target_alpha"), 0.0001)
    }

    @Test
    fun imports_answer_duration_defaults_and_legacy_single_value_like_desktop() {
        val base = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/duration.aspx",
              "survey_title": "时长配置",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}
              ]
            }
        """.trimIndent()

        val defaulted = ConfigCodec.deserialize(base)
        assertEquals(1, defaulted.params.targetNum)
        assertEquals(60, defaulted.params.answerDurationMin)
        assertEquals(120, defaulted.params.answerDurationMax)
        assertEquals(1, SurveyConfigDraft.fromDefinition(defaulted.definition).params.targetNum)

        val legacyNumber = ConfigCodec.deserialize(base.replace("\"questions_info\"", "\"answer_duration\": 90,\n              \"questions_info\""))
        assertEquals(81, legacyNumber.params.answerDurationMin)
        assertEquals(99, legacyNumber.params.answerDurationMax)

        val legacySingleItem = ConfigCodec.deserialize(base.replace("\"questions_info\"", "\"answer_duration\": [90],\n              \"questions_info\""))
        assertEquals(81, legacySingleItem.params.answerDurationMin)
        assertEquals(99, legacySingleItem.params.answerDurationMax)
    }

    @Test
    fun preserves_reversed_imported_answer_duration_range_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/duration-reversed.aspx",
              "survey_title": "时长配置",
              "survey_provider": "wjx",
              "answer_duration": [120, 60],
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(120, restored.params.answerDurationMin)
        assertEquals(60, restored.params.answerDurationMax)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("作答时长区间无效"))
    }

    @Test
    fun preserves_oversized_imported_answer_duration_range_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/duration-large.aspx",
              "survey_title": "时长配置",
              "survey_provider": "wjx",
              "answer_duration": [60, 1801],
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(60, restored.params.answerDurationMin)
        assertEquals(1801, restored.params.answerDurationMax)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("作答时长超出上限"))
    }

    @Test
    fun imports_legacy_logic_metadata_with_desktop_contract_normalization() = runBlocking {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/logic-legacy.aspx",
              "survey_title": "旧逻辑配置",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "结束作答",
                  "type_code": "3",
                  "options": 2,
                  "option_texts": ["继续", "没有（结束作答）"],
                  "has_jump": true,
                  "jump_rules": [{"option_index": 1, "jumpto": 1, "option_text": "没有（结束作答）"}]
                },
                {
                  "num": 2,
                  "title": "普通题",
                  "type_code": "3",
                  "options": 2,
                  "option_texts": ["A", "B"]
                },
                {
                  "num": 3,
                  "title": "未知跳题",
                  "type_code": "3",
                  "options": 2,
                  "has_jump": true
                },
                {
                  "num": 4,
                  "title": "坏状态",
                  "type_code": "3",
                  "options": 2,
                  "logic_parse_status": "bad-status"
                }
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "结束作答", "question_type": "single", "option_count": 2, "probabilities": [0, 100]},
                {"question_num": 2, "question_title": "普通题", "question_type": "single", "option_count": 2, "probabilities": [50, 50]},
                {"question_num": 3, "question_title": "未知跳题", "question_type": "single", "option_count": 2, "probabilities": [50, 50]},
                {"question_num": 4, "question_title": "坏状态", "question_type": "single", "option_count": 2, "probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val byNum = restored.definition.questions.associateBy { it.num }

        assertEquals("complete", byNum[1]?.logicParseStatus)
        assertEquals(true, byNum[1]?.jumpRules?.single()?.terminates)
        assertEquals("none", byNum[2]?.logicParseStatus)
        assertEquals("unknown", byNum[3]?.logicParseStatus)
        assertEquals("unknown", byNum[4]?.logicParseStatus)

        val terminatingPlan = HttpLogicPlanner.build(listOf(byNum.getValue(1), byNum.getValue(2))) { q ->
            AnswerAction(questionNum = q.num, kind = "choice", selectedIndices = listOf(1))
        }
        assertTrue(terminatingPlan.terminatedEarly)
        assertEquals(listOf(1), terminatingPlan.actions.map { it.questionNum })

        val reason = HttpLogicPlanner.fallbackReason(listOf(byNum.getValue(3)))
        assertEquals("第3题逻辑规则未完整解析", reason)
    }

    @Test
    fun imports_question_identity_and_support_flags_with_desktop_contract_normalization() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://wj.qq.com/s2/identity",
              "survey_title": "身份字段归一",
              "survey_provider": "qq",
              "questions_info": [
                {
                  "num": 0,
                  "page": 0,
                  "title": "  测试题  ",
                  "type_code": "radio",
                  "options": 2,
                  "provider": "unknown",
                  "provider_question_id": "",
                  "provider_page_id": ""
                },
                {
                  "num": 2,
                  "title": "上传",
                  "type_code": "upload",
                  "unsupported": true,
                  "unsupported_reason": ""
                },
                {
                  "num": 5,
                  "title": "说明文字",
                  "type_code": "0",
                  "provider_type": "description",
                  "unsupported": true,
                  "unsupported_reason": "暂不支持腾讯题型：description"
                }
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "测试题", "question_type": "single", "option_count": 2, "probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val first = restored.definition.questions.first { it.num == 1 }
        val unsupported = restored.definition.questions.first { it.num == 2 }
        val description = restored.definition.questions.first { it.num == 5 }

        assertEquals(1, first.page)
        assertEquals("测试题", first.title)
        assertEquals("qq", first.provider)
        assertEquals("1", first.providerQuestionId)
        assertEquals("1", first.providerPageId)
        assertEquals("radio", first.providerType)
        assertEquals(false, first.unsupported)
        assertTrue(unsupported.unsupported)
        assertEquals("当前平台暂不支持该题型", unsupported.unsupportedReason)

        assertTrue(description.isDescription)
        assertEquals(false, description.unsupported)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals("single" to 0, compiled.providerQuestionConfigIndexMap["qq:1:1"])
        assertEquals(1, compiled.providerQuestionConfigNumMap["qq:1:1"])
    }

    @Test
    fun import_drops_stale_entries_for_description_and_unsupported_questions() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/stale.aspx",
              "survey_title": "陈旧题目配置",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "可作答", "type_code": "3", "options": 2, "option_texts": ["A", "B"]},
                {"num": 2, "title": "说明", "type_code": "0", "is_description": true},
                {"num": 3, "title": "上传", "type_code": "99", "unsupported": true, "unsupported_reason": "上传题暂未支持"}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "可作答", "question_type": "single", "option_count": 2, "probabilities": [50, 50]},
                {"question_num": 2, "question_title": "说明", "question_type": "single", "option_count": 1, "probabilities": [100]},
                {"question_num": 3, "question_title": "上传", "question_type": "text", "texts": ["x"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(1), restored.questions.map { it.num })
        assertEquals(listOf(1, 2, 3), restored.definition.questions.map { it.num })
        assertTrue(restored.definition.questions.first { it.num == 3 }.unsupported)
    }

    @Test
    fun import_matches_question_entry_by_provider_identity_when_question_num_is_stale() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.credamo.com/answer.html#identity",
              "survey_title": "题号修复",
              "survey_provider": "credamo",
              "questions_info": [
                {
                  "num": 7,
                  "page": 3,
                  "title": "入口",
                  "type_code": "radio",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "provider": "credamo",
                  "provider_question_id": "qid-7",
                  "provider_page_id": "page-3"
                }
              ],
              "question_entries": [
                {
                  "question_num": 0,
                  "question_title": "",
                  "question_type": "single",
                  "option_count": 2,
                  "survey_provider": "credamo",
                  "provider_question_id": "qid-7",
                  "provider_page_id": "page-3",
                  "probabilities": [25, 75]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(7), restored.questions.map { it.num })
        assertEquals("入口", restored.questions.single().title)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals("single" to 0, compiled.questionConfigIndexMap[7])
        assertEquals("single" to 0, compiled.providerQuestionConfigIndexMap["credamo:page-3:qid-7"])
        assertEquals(7, compiled.providerQuestionConfigNumMap["credamo:page-3:qid-7"])
    }

    @Test
    fun import_rewrites_legacy_wjx_entry_provider_for_non_wjx_config() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.credamo.com/answer.html#legacy-provider",
              "survey_title": "旧 provider",
              "survey_provider": "credamo",
              "questions_info": [
                {
                  "num": 1,
                  "page": 2,
                  "title": "入口",
                  "type_code": "radio",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "provider": "credamo",
                  "provider_question_id": "qid-1",
                  "provider_page_id": "page-2"
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "入口",
                  "question_type": "single",
                  "option_count": 2,
                  "survey_provider": "wjx",
                  "provider_question_id": "qid-1",
                  "provider_page_id": "page-2",
                  "probabilities": [50, 50]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals("credamo", restored.questions.single().surveyProvider)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals("single" to 0, compiled.providerQuestionConfigIndexMap["credamo:page-2:qid-1"])
        assertEquals(false, compiled.providerQuestionConfigIndexMap.containsKey("wjx:page-2:qid-1"))
    }

    @Test
    fun import_matches_question_entry_by_unique_provider_question_id_when_page_id_is_missing() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://wj.qq.com/s2/no-page-id",
              "survey_title": "缺页标识",
              "survey_provider": "qq",
              "questions_info": [
                {
                  "num": 8,
                  "page": 2,
                  "title": "入口",
                  "type_code": "radio",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "provider": "qq",
                  "provider_question_id": "qid-8",
                  "provider_page_id": "page-2"
                }
              ],
              "question_entries": [
                {
                  "question_num": 0,
                  "question_title": "",
                  "question_type": "single",
                  "option_count": 2,
                  "survey_provider": "qq",
                  "provider_question_id": "qid-8",
                  "probabilities": [40, 60]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(8), restored.questions.map { it.num })
        assertEquals("page-2", restored.questions.single().providerPageId)
        assertEquals("入口", restored.questions.single().title)
    }

    @Test
    fun import_matches_legacy_wjx_entry_by_unique_provider_question_id_for_non_wjx_config() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.credamo.com/answer.html#legacy-no-page",
              "survey_title": "缺页旧 provider",
              "survey_provider": "credamo",
              "questions_info": [
                {
                  "num": 6,
                  "page": 4,
                  "title": "入口",
                  "type_code": "radio",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "provider": "credamo",
                  "provider_question_id": "qid-6",
                  "provider_page_id": "page-4"
                }
              ],
              "question_entries": [
                {
                  "question_num": 0,
                  "question_title": "",
                  "question_type": "single",
                  "option_count": 2,
                  "survey_provider": "wjx",
                  "provider_question_id": "qid-6",
                  "probabilities": [40, 60]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(6), restored.questions.map { it.num })
        assertEquals("credamo", restored.questions.single().surveyProvider)
        assertEquals("入口", restored.questions.single().title)
    }

    @Test
    fun export_normalizes_dirty_entry_provider_to_definition_provider() {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "入口",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("A", "B"),
            provider = "qq",
            providerQuestionId = "qid-1",
            providerPageId = "page-1",
        )
        val base = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(
                SurveyProviderType.QQ,
                "https://wj.qq.com/s2/provider-clean",
                "Provider 清理",
                listOf(question),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(surveyProvider = " UNKNOWN ") },
        )

        val entry = JSONObject(ConfigCodec.serialize(draft))
            .getJSONArray("question_entries")
            .getJSONObject(0)

        assertEquals("qq", entry.getString("survey_provider"))
    }

    @Test
    fun export_normalizes_dirty_question_metadata_provider_identity() {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "入口",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("A", "B"),
            provider = " UNKNOWN ",
            providerQuestionId = " qid-1 ",
            providerPageId = " page-1 ",
            providerType = " radio ",
        )
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(
                SurveyProviderType.QQ,
                "https://wj.qq.com/s2/meta-provider-clean",
                "Provider 元数据清理",
                listOf(question),
            ),
        )

        val meta = JSONObject(ConfigCodec.serialize(draft))
            .getJSONArray("questions_info")
            .getJSONObject(0)

        assertEquals("qq", meta.getString("provider"))
        assertEquals("qid-1", meta.getString("provider_question_id"))
        assertEquals("page-1", meta.getString("provider_page_id"))
        assertEquals("radio", meta.getString("provider_type"))
    }

    @Test
    fun export_normalizes_dirty_question_logic_parse_status() {
        val dirty = SurveyQuestionMeta(num = 1, title = "坏状态", typeCode = "3", options = 2, logicParseStatus = "bad-status")
        val uppercase = SurveyQuestionMeta(num = 2, title = "大写状态", typeCode = "3", options = 2, logicParseStatus = " COMPLETE ")
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(
                SurveyProviderType.WJX,
                "https://www.wjx.cn/vm/logic-status.aspx",
                "逻辑状态",
                listOf(dirty, uppercase),
            ),
        )

        val metas = JSONObject(ConfigCodec.serialize(draft)).getJSONArray("questions_info")

        assertEquals("unknown", metas.getJSONObject(0).getString("logic_parse_status"))
        assertEquals("complete", metas.getJSONObject(1).getString("logic_parse_status"))
    }

    @Test
    fun import_does_not_match_provider_question_id_without_page_id_when_ambiguous() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://wj.qq.com/s2/ambiguous",
              "survey_title": "重复题标识",
              "survey_provider": "qq",
              "questions_info": [
                {"num": 1, "page": 1, "title": "第一页", "type_code": "radio", "options": 2, "provider": "qq", "provider_question_id": "same", "provider_page_id": "page-1"},
                {"num": 2, "page": 2, "title": "第二页", "type_code": "radio", "options": 2, "provider": "qq", "provider_question_id": "same", "provider_page_id": "page-2"}
              ],
              "question_entries": [
                {"question_num": 0, "question_title": "旧题", "question_type": "single", "option_count": 2, "survey_provider": "qq", "provider_question_id": "same", "probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(0), restored.questions.map { it.num })
    }

    @Test
    fun round_trips_logic_metadata_and_preserves_http_plan() = runBlocking {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "入口",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("跳到第3题", "继续"),
            hasJump = true,
            jumpRules = listOf(JumpRule(optionIndex = 0, targetQuestion = 3, optionText = "跳到第3题")),
            hasDependentDisplayLogic = true,
            controlsDisplayTargets = listOf(
                DisplayTarget(targetQuestionNum = 4, conditionMode = "selected", conditionOptionIndices = listOf(1)),
            ),
            logicParseStatus = "complete",
        )
        val q2 = SurveyQuestionMeta(num = 2, title = "应跳过", typeCode = "3", options = 2)
        val q3 = SurveyQuestionMeta(num = 3, title = "中间题", typeCode = "3", options = 2)
        val q4 = SurveyQuestionMeta(
            num = 4,
            title = "条件显示",
            typeCode = "3",
            options = 2,
            hasDisplayCondition = true,
            displayConditions = listOf(
                DisplayCondition(
                    conditionQuestionNum = 3,
                    conditionMode = "selected",
                    conditionOptionIndices = listOf(1),
                ),
            ),
            logicParseStatus = "complete",
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/logic.aspx",
            "逻辑问卷",
            listOf(q1, q2, q3, q4),
        )

        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(SurveyConfigDraft.fromDefinition(def)))
        val restoredQ1 = restored.definition.questions.first { it.num == 1 }
        val restoredQ4 = restored.definition.questions.first { it.num == 4 }

        assertEquals(listOf(JumpRule(optionIndex = 0, targetQuestion = 3, optionText = "跳到第3题")), restoredQ1.jumpRules)
        assertEquals(1, restoredQ1.controlsDisplayTargets.size)
        assertEquals(listOf(DisplayCondition(conditionQuestionNum = 3, conditionMode = "selected", conditionOptionIndices = listOf(1))), restoredQ4.displayConditions)

        val plan = HttpLogicPlanner.build(restored.definition.questions) { q ->
            AnswerAction(questionNum = q.num, kind = "choice", selectedIndices = listOf(0))
        }

        assertEquals(listOf(1, 3), plan.actions.map { it.questionNum })
        assertTrue(2 in plan.skippedQuestionNums)
        assertTrue(4 in plan.skippedQuestionNums)
    }

    @Test
    fun round_trips_rich_question_metadata_without_loss() {
        val q = SurveyQuestionMeta(
            num = 1,
            displayNum = 7,
            title = "带图题",
            description = "说明文字",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("A", "B"),
            forcedTexts = listOf("固定文本"),
            attachedOptionSelects = listOf(
                mapOf(
                    "option_index" to 1,
                    "option_text" to "B",
                    "select_options" to listOf("x", "y"),
                    "weights" to listOf(20, 80),
                ),
            ),
            hasAttachedOptionSelect = true,
            questionMedia = listOf(
                QuestionMedia(scope = "title", sourceUrl = "https://example.com/title.png", label = "题干图"),
                QuestionMedia(scope = "option", index = 1, sourceUrl = "https://example.com/option.png", label = "选项图"),
            ),
            unsupported = true,
            unsupportedReason = "当前平台暂不支持上传题",
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/rich.aspx",
            "富元数据",
            listOf(q),
        )

        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(SurveyConfigDraft.fromDefinition(def)))
            .definition.questions.first()

        assertEquals(7, restored.displayNum)
        assertEquals("说明文字", restored.description)
        assertEquals(listOf("固定文本"), restored.forcedTexts)
        assertTrue(restored.hasAttachedOptionSelect)
        assertEquals("B", restored.attachedOptionSelects.first()["option_text"])
        assertEquals(2, restored.questionMedia.size)
        assertEquals("https://example.com/title.png", restored.questionMedia.first().sourceUrl)
        assertTrue(restored.unsupported)
        assertEquals("当前平台暂不支持上传题", restored.unsupportedReason)
    }

    @Test
    fun imports_desktop_schema_probability_and_text_range_fields() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/desktop.aspx",
              "survey_title": "桌面配置",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 2, "option_texts": ["A", "B"]},
                {"num": 2, "title": "多选", "type_code": "4", "options": 3, "option_texts": ["A", "B", "C"]},
                {"num": 3, "title": "矩阵", "type_code": "6", "rows": 2, "options": 3, "row_texts": ["R1", "R2"], "option_texts": ["1", "2", "3"]},
                {"num": 4, "title": "滑块", "type_code": "8", "options": 1},
                {"num": 5, "title": "随机整数", "type_code": "1", "is_text_like": true, "text_inputs": 1},
                {"num": 7, "title": "文本权重", "type_code": "1", "is_text_like": true, "text_inputs": 1},
                {"num": 6, "title": "随机滑块", "type_code": "8", "options": 1, "slider_min": 0, "slider_max": 10}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 2, "distribution_mode": "custom", "probabilities": -1, "custom_weights": [80, 20]},
                {"question_num": 2, "question_title": "多选", "question_type": "multiple", "option_count": 3, "distribution_mode": "custom", "probabilities": [10, 20, 30]},
                {"question_num": 3, "question_title": "矩阵", "question_type": "matrix", "rows": 2, "option_count": 3, "distribution_mode": "custom", "probabilities": [[1, 2, 3], [4, 5, 6]]},
                {"question_num": 4, "question_title": "滑块", "question_type": "slider", "option_count": 1, "distribution_mode": "custom", "probabilities": [72.5]},
                {"question_num": 5, "question_title": "随机整数", "question_type": "text", "texts": ["占位"], "text_random_mode": "integer", "text_random_int_range": ["5", "9"]},
                {"question_num": 7, "question_title": "文本权重", "question_type": "text", "texts": ["低", "高"], "probabilities": [0, 100]},
                {"question_num": 6, "question_title": "随机滑块", "question_type": "slider", "option_count": 1, "distribution_mode": "random", "probabilities": [9]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        assertEquals(listOf(80.0, 20.0), restored.questions.first { it.num == 1 }.optionWeights)
        assertEquals(listOf(10.0, 20.0, 30.0), restored.questions.first { it.num == 2 }.multiProbabilities)
        assertEquals(
            listOf(listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0)),
            restored.questions.first { it.num == 3 }.matrixRowWeights.map { it.toList() },
        )
        assertEquals(72.5, restored.questions.first { it.num == 4 }.sliderTarget, 0.0001)
        assertEquals("integer", restored.questions.first { it.num == 5 }.textMode)
        assertEquals(5, restored.questions.first { it.num == 5 }.textIntMin)
        assertEquals(9, restored.questions.first { it.num == 5 }.textIntMax)
        assertEquals(listOf("低", "高"), restored.questions.first { it.num == 7 }.textCandidates)
        assertEquals(listOf(0.0, 100.0), restored.questions.first { it.num == 7 }.optionWeights)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf(0.8, 0.2), compiled.singleProb.first())
        assertEquals(listOf(10.0, 20.0, 30.0), compiled.multipleProb.first())
        assertEquals(listOf(1.0 / 6.0, 2.0 / 6.0, 3.0 / 6.0), compiled.matrixProb[0])
        assertEquals(listOf(4.0 / 15.0, 5.0 / 15.0, 6.0 / 15.0), compiled.matrixProb[1])
        assertEquals(72.5, compiled.sliderTargets.first(), 0.0001)
        assertTrue(compiled.sliderTargets[1].isNaN())
        assertEquals(listOf("__RANDOM_INT__:5:9"), compiled.texts.first())
        assertEquals(listOf(0.0, 1.0), compiled.textsProb[1])
    }

    @Test
    fun preserves_imported_single_weight_count_mismatch_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/weights.aspx",
              "survey_title": "权重数量",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 3, "distribution_mode": "custom", "custom_weights": [80, 20]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(listOf(80.0, 20.0), q.optionWeights)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("配比数量不一致"))
    }

    @Test
    fun still_defaults_missing_single_weights_to_option_count() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/weights-default.aspx",
              "survey_title": "默认权重",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 3, "distribution_mode": "random"}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf(1.0, 1.0, 1.0), restored.questions.single().optionWeights)
        assertTrue(ConfigPreflight.validate(restored).canStart)
    }

    @Test
    fun keeps_legacy_weighted_single_short_array_padding() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/weights-weighted.aspx",
              "survey_title": "旧权重",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 3, "probabilities": [100]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals("weighted", restored.questions.single().distributionMode)
        assertEquals(listOf(100.0, 0.0, 0.0), restored.questions.single().optionWeights)
        assertTrue(ConfigPreflight.validate(restored).canStart)
    }

    @Test
    fun preserves_imported_multiple_probability_count_mismatch_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/multiple-probabilities.aspx",
              "survey_title": "多选概率",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "多选", "type_code": "4", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "多选", "question_type": "multiple", "option_count": 3, "distribution_mode": "custom", "multi_probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(listOf(50.0, 50.0), q.multiProbabilities)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("多选概率数量不一致"))
    }

    @Test
    fun preserves_desktop_multiple_probability_count_mismatch_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/multiple-desktop-probabilities.aspx",
              "survey_title": "桌面多选概率",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "多选", "type_code": "4", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "多选", "question_type": "multiple", "option_count": 3, "distribution_mode": "custom", "probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(listOf(50.0, 50.0), q.multiProbabilities)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("多选概率数量不一致"))
    }

    @Test
    fun ignores_imported_multiple_probability_count_mismatch_when_random_count_is_enabled() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/multiple-random-count.aspx",
              "survey_title": "随机多选数量",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "多选", "type_code": "4", "options": 3, "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "多选", "question_type": "multiple", "option_count": 3, "multi_random_count": true, "multi_probabilities": [0]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertTrue(q.multiRandomCount)
        assertEquals(listOf(0.0, 50.0, 50.0), q.multiProbabilities)
        assertTrue(ConfigPreflight.validate(restored).canStart)
    }

    @Test
    fun preserves_imported_matrix_row_count_mismatch_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/matrix-row-count.aspx",
              "survey_title": "矩阵行数",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "矩阵", "type_code": "6", "rows": 2, "options": 3, "row_texts": ["R1", "R2"], "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "矩阵", "question_type": "matrix", "rows": 2, "option_count": 3, "distribution_mode": "custom", "matrix_row_weights": [[1, 2, 3]]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(listOf(listOf(1.0, 2.0, 3.0)), q.matrixRowWeights.map { it.toList() })
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("矩阵行数不一致"))
    }

    @Test
    fun preserves_imported_matrix_column_count_mismatch_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/matrix-column-count.aspx",
              "survey_title": "矩阵列数",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "矩阵", "type_code": "6", "rows": 2, "options": 3, "row_texts": ["R1", "R2"], "option_texts": ["A", "B", "C"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "矩阵", "question_type": "matrix", "rows": 2, "option_count": 3, "distribution_mode": "custom", "probabilities": [[1, 2, 3], [4, 5]]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(
            listOf(listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0)),
            q.matrixRowWeights.map { it.toList() },
        )
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("矩阵列数不一致"))
    }

    @Test
    fun preserves_reversed_imported_integer_range_for_preflight_validation() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/integer-range.aspx",
              "survey_title": "随机整数",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "年龄", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "年龄", "question_type": "text", "text_random_mode": "integer", "text_random_int_range": [60, 18]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals("integer", q.textMode)
        assertEquals(60, q.textIntMin)
        assertEquals(18, q.textIntMax)
        assertTrue(ConfigPreflight.validate(restored).blockingMessage().contains("随机整数范围无效"))
    }

    @Test
    fun exports_reversed_integer_range_without_silent_sorting() {
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/integer-export.aspx",
            "随机整数",
            listOf(SurveyQuestionMeta(num = 1, title = "年龄", typeCode = "1", isTextLike = true, textInputs = 1)),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textMode = "integer", textIntMin = 60, textIntMax = 18)
            },
        )

        val entry = JSONObject(ConfigCodec.serialize(draft))
            .getJSONArray("question_entries")
            .getJSONObject(0)

        assertEquals(60, entry.getJSONArray("text_random_int_range").getInt(0))
        assertEquals(18, entry.getJSONArray("text_random_int_range").getInt(1))
    }

    @Test
    fun imports_desktop_weighted_random_entries_without_strict_ratio_loss() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/weighted.aspx",
              "survey_title": "旧桌面权重",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "单选", "type_code": "3", "options": 3, "option_texts": ["A", "B", "C"]},
                {"num": 2, "title": "矩阵", "type_code": "6", "rows": 2, "options": 3, "row_texts": ["R1", "R2"], "option_texts": ["1", "2", "3"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "单选", "question_type": "single", "option_count": 3, "probabilities": [100]},
                {"question_num": 2, "question_title": "矩阵", "question_type": "matrix", "rows": 2, "option_count": 3, "probabilities": [[0, 5], [10]]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val single = restored.questions.first { it.num == 1 }
        val matrix = restored.questions.first { it.num == 2 }

        assertEquals("weighted", single.distributionMode)
        assertEquals(listOf(100.0, 0.0, 0.0), single.optionWeights)
        assertEquals("weighted", matrix.distributionMode)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf(1.0, 0.0, 0.0), compiled.singleProb.first())
        assertEquals(false, compiled.questionStrictRatioMap[1])
        assertEquals(listOf(0.0, 5.0 / 6.0, 1.0 / 6.0), compiled.matrixProb[0])
        assertEquals(listOf(10.0 / 12.0, 1.0 / 12.0, 1.0 / 12.0), compiled.matrixProb[1])
        assertEquals(false, compiled.questionStrictRatioMap[2])

        val exported = JSONObject(ConfigCodec.serialize(restored))
            .getJSONArray("question_entries")
            .getJSONObject(0)
        assertEquals("random", exported.getString("distribution_mode"))
        assertEquals("weighted", exported.getString("android_distribution_mode"))
        assertEquals(100.0, exported.getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(0.0, exported.getJSONArray("probabilities").getDouble(2), 0.0001)
    }

    @Test
    fun imports_desktop_slider_scalar_probability_target_in_custom_mode() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/slider-scalar.aspx",
              "survey_title": "滑块数值",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "滑块", "type_code": "8", "options": 1, "slider_min": 0, "slider_max": 100}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "滑块", "question_type": "slider", "option_count": 1, "distribution_mode": "custom", "probabilities": 72.5}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals("custom", restored.questions.single().distributionMode)
        assertEquals(72.5, restored.questions.single().sliderTarget, 0.0001)
        assertEquals(72.5, ConfigCompiler.compile(restored).sliderTargets.single(), 0.0001)
    }

    @Test
    fun exports_multi_text_candidates_with_desktop_delimiter() {
        val q = SurveyQuestionMeta(
            num = 1,
            title = "资料",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
            textInputLabels = listOf("姓名", "电话"),
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/multi-text.aspx",
            "多项填空",
            listOf(q),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textCandidates = mutableListOf("张三${TextValues.MULTI_TEXT_DELIMITER}13800000000"))
            },
        )

        val entry = JSONObject(ConfigCodec.serialize(draft))
            .getJSONArray("question_entries")
            .getJSONObject(0)

        assertEquals("张三||13800000000", entry.getJSONArray("texts").getString(0))
    }

    @Test
    fun exports_text_random_modes_with_desktop_schema_fields() {
        val q1 = SurveyQuestionMeta(num = 1, title = "姓名", typeCode = "1", isTextLike = true, textInputs = 1)
        val q2 = SurveyQuestionMeta(num = 2, title = "年龄", typeCode = "1", isTextLike = true, textInputs = 1)
        val q3 = SurveyQuestionMeta(num = 3, title = "随机文本", typeCode = "1", isTextLike = true, textInputs = 1)
        val q4 = SurveyQuestionMeta(
            num = 4,
            title = "资料",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
            textInputLabels = listOf("姓名", "年龄"),
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/text-modes.aspx",
            "填空模式",
            listOf(q1, q2, q3, q4),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map { q ->
                when (q.num) {
                    1 -> q.copy(textMode = "name")
                    2 -> q.copy(textMode = "integer", textIntMin = 18, textIntMax = 60)
                    3 -> q.copy(textMode = "generic")
                    4 -> q.copy(multiTextBlankModes = mutableListOf("custom", "integer"))
                    else -> q
                }
            },
        )

        val entries = JSONObject(ConfigCodec.serialize(draft)).getJSONArray("question_entries")
        val name = entries.getJSONObject(0)
        val integer = entries.getJSONObject(1)
        val generic = entries.getJSONObject(2)
        val multiText = entries.getJSONObject(3)

        assertEquals("name", name.getString("text_random_mode"))
        assertEquals("integer", integer.getString("text_random_mode"))
        assertEquals(18, integer.getJSONArray("text_random_int_range").getInt(0))
        assertEquals(60, integer.getJSONArray("text_random_int_range").getInt(1))
        assertEquals("none", generic.getString("text_random_mode"))
        assertEquals("__RANDOM_TEXT__", generic.getJSONArray("texts").getString(0))
        assertEquals(1, generic.getInt("option_count"))
        assertEquals(2, multiText.getInt("option_count"))
        assertEquals("none", multiText.getJSONArray("multi_text_blank_modes").getString(0))
        assertEquals("integer", multiText.getJSONArray("multi_text_blank_modes").getString(1))
    }

    @Test
    fun exports_desktop_probability_payloads_for_all_question_types() {
        val q1 = SurveyQuestionMeta(num = 1, title = "单选", typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val q2 = SurveyQuestionMeta(num = 2, title = "多选", typeCode = "4", options = 3, optionTexts = listOf("A", "B", "C"))
        val q3 = SurveyQuestionMeta(
            num = 3,
            title = "矩阵",
            typeCode = "6",
            rows = 2,
            options = 3,
            rowTexts = listOf("R1", "R2"),
            optionTexts = listOf("1", "2", "3"),
        )
        val q4 = SurveyQuestionMeta(num = 4, title = "滑块", typeCode = "8", sliderMin = 0.0, sliderMax = 100.0)
        val q5 = SurveyQuestionMeta(num = 5, title = "随机滑块", typeCode = "8", sliderMin = 0.0, sliderMax = 100.0)
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/probabilities.aspx",
            "概率导出",
            listOf(q1, q2, q3, q4, q5),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map { q ->
                when (q.num) {
                    1 -> q.copy(distributionMode = "custom", optionWeights = mutableListOf(80.0, 20.0))
                    2 -> q.copy(multiRandomCount = false, multiProbabilities = mutableListOf(10.0, 20.0, 30.0))
                    3 -> q.copy(
                        distributionMode = "custom",
                        matrixRowWeights = mutableListOf(
                            mutableListOf(1.0, 2.0, 3.0),
                            mutableListOf(4.0, 5.0, 6.0),
                        ),
                    )
                    4 -> q.copy(distributionMode = "custom", sliderTarget = 72.5)
                    5 -> q.copy(distributionMode = "random", sliderTarget = 99.0)
                    else -> q
                }
            },
        )

        val entries = JSONObject(ConfigCodec.serialize(draft)).getJSONArray("question_entries")
        assertEquals(80.0, entries.getJSONObject(0).getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(20.0, entries.getJSONObject(0).getJSONArray("custom_weights").getDouble(1), 0.0001)
        assertEquals(10.0, entries.getJSONObject(1).getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(30.0, entries.getJSONObject(1).getJSONArray("custom_weights").getDouble(2), 0.0001)
        assertEquals(2, entries.getJSONObject(2).getInt("rows"))
        assertEquals(3, entries.getJSONObject(2).getInt("option_count"))
        assertEquals(1.0, entries.getJSONObject(2).getJSONArray("probabilities").getJSONArray(0).getDouble(0), 0.0001)
        assertEquals(6.0, entries.getJSONObject(2).getJSONArray("custom_weights").getJSONArray(1).getDouble(2), 0.0001)
        assertEquals(1, entries.getJSONObject(3).getInt("option_count"))
        assertEquals(72.5, entries.getJSONObject(3).getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(-1, entries.getJSONObject(4).getInt("probabilities"))
    }

    @Test
    fun exports_random_count_multiple_as_desktop_safe_probability_list() {
        val question = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 3,
            optionTexts = listOf("A", "B", "C"),
        )
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/random-multiple.aspx",
            "随机多选",
            listOf(question),
        )
        val base = SurveyConfigDraft.fromDefinition(def)
        val draft = base.copy(
            questions = base.questions.map { it.copy(multiRandomCount = true, multiProbabilities = mutableListOf(0.0, 0.0, 0.0)) },
        )

        val entry = JSONObject(ConfigCodec.serialize(draft))
            .getJSONArray("question_entries")
            .getJSONObject(0)

        assertTrue(entry.getBoolean("multi_random_count"))
        assertEquals(50.0, entry.getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(50.0, entry.getJSONArray("probabilities").getDouble(1), 0.0001)
        assertEquals(50.0, entry.getJSONArray("probabilities").getDouble(2), 0.0001)
    }

    @Test
    fun rejects_unsupported_config_schema_versions() {
        val json = """
            {
              "config_schema_version": 5,
              "url": "https://www.wjx.cn/vm/old.aspx",
              "survey_title": "旧配置",
              "survey_provider": "wjx"
            }
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.deserialize(json)
        }

        assertTrue(error.message.orEmpty().contains("schema v6"))
        assertTrue(error.message.orEmpty().contains("实际为 v5"))
    }

    @Test
    fun rejects_removed_top_level_legacy_config_fields_without_blocking_entry_ai_flag() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/legacy.aspx",
              "survey_title": "旧字段",
              "survey_provider": "wjx",
              "random_proxy_api": "https://proxy.example.com",
              "questions_info": [
                {"num": 1, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "填空", "question_type": "text", "texts": ["占位"], "ai_enabled": true}
              ]
            }
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) {
            ConfigCodec.deserialize(json)
        }

        assertTrue(error.message.orEmpty().contains("旧字段"))
        assertTrue(error.message.orEmpty().contains("random_proxy_api"))

        val legal = json.replace(
            """
              "random_proxy_api": "https://proxy.example.com",
            """.trimIndent(),
            "",
        )
        assertTrue(ConfigCodec.deserialize(legal).questions.single().useAiText)
    }

    @Test
    fun imports_and_exports_desktop_attached_option_select_entry_config() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/attached.aspx",
              "survey_title": "嵌入下拉配置",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "选择",
                  "type_code": "3",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "attached_option_selects": [
                    {"option_index": 1, "option_text": "B", "select_options": ["x", "y"], "weights": [50, 50]}
                  ],
                  "has_attached_option_select": true
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "选择",
                  "question_type": "single",
                  "option_count": 2,
                  "probabilities": [100, 0],
                  "attached_option_selects": [
                    {"option_index": 1, "option_text": "B", "select_options": ["x", "y"], "weights": [20, 80]}
                  ]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val attached = restored.questions.single().attachedOptionSelects.single()
        assertEquals(listOf(20, 80), attached["weights"])

        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf(20, 80), compiled.singleAttachedOptionSelects.single().single()["weights"])

        val exported = ConfigCodec.deserialize(ConfigCodec.serialize(restored))
        assertEquals(listOf(20, 80), exported.questions.single().attachedOptionSelects.single()["weights"])
    }

    @Test
    fun compiles_multiple_attached_option_select_entry_config() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/multiple-attached.aspx",
              "survey_title": "多选嵌入下拉配置",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "多选",
                  "type_code": "4",
                  "options": 3,
                  "option_texts": ["A", "B", "C"],
                  "attached_option_selects": [
                    {"option_index": 1, "option_text": "B", "select_options": ["x", "y"], "select_values": ["xv", "yv"], "weights": [50, 50]}
                  ],
                  "has_attached_option_select": true
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "多选",
                  "question_type": "multiple",
                  "option_count": 3,
                  "probabilities": [100, 100, 0],
                  "attached_option_selects": [
                    {"option_index": 1, "option_text": "B", "select_options": ["x", "y"], "select_values": ["xv", "yv"], "weights": [20, 80]}
                  ]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val compiled = ConfigCompiler.compile(restored)

        assertEquals(listOf(20, 80), restored.questions.single().attachedOptionSelects.single()["weights"])
        assertEquals(listOf(20, 80), compiled.multipleAttachedOptionSelects.single().single()["weights"])
    }

    @Test
    fun imports_exports_and_compiles_desktop_psycho_bias_fields() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/bias.aspx",
              "survey_title": "信度倾向",
              "survey_provider": "wjx",
              "questions_info": [
                {"num": 1, "title": "满意度A", "type_code": "5", "options": 5, "option_texts": ["1", "2", "3", "4", "5"]},
                {"num": 2, "title": "矩阵", "type_code": "6", "rows": 2, "options": 5, "row_texts": ["R1", "R2"], "option_texts": ["1", "2", "3", "4", "5"]}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "满意度A", "question_type": "scale", "option_count": 5, "probabilities": -1, "dimension": "满意度", "psycho_bias": "right"},
                {"question_num": 2, "question_title": "矩阵", "question_type": "matrix", "rows": 2, "option_count": 5, "probabilities": -1, "dimension": "满意度", "psycho_bias": ["left", "right"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals("right", restored.questions.first { it.num == 1 }.biasPreset)
        assertEquals(listOf("left", "right"), restored.questions.first { it.num == 2 }.matrixBiasPresets)
        val compiled = ConfigCompiler.compile(restored)
        assertEquals("right", compiled.questionPsychoBiasMap[1])
        assertEquals(listOf("left", "right"), compiled.questionPsychoBiasMap[2])

        val exported = JSONObject(ConfigCodec.serialize(restored)).getJSONArray("question_entries")
        assertEquals("right", exported.getJSONObject(0).getString("psycho_bias"))
        assertEquals("left", exported.getJSONObject(1).getJSONArray("psycho_bias").getString(0))
        assertEquals("right", exported.getJSONObject(1).getJSONArray("psycho_bias").getString(1))
    }

    @Test
    fun imports_and_exports_desktop_provider_identity_fields() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.credamo.com/answer.html#survey",
              "survey_title": "见数题目标识",
              "survey_provider": "credamo",
              "questions_info": [
                {
                  "num": 1,
                  "title": "入口",
                  "type_code": "radio",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "provider": "credamo",
                  "provider_question_id": "meta-q1",
                  "provider_page_id": "meta-page"
                },
                {
                  "num": 2,
                  "title": "备注",
                  "type_code": "text",
                  "is_text_like": true,
                  "text_inputs": 1,
                  "provider_question_id": "meta-q2",
                  "provider_page_id": "meta-page"
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "入口",
                  "question_type": "single",
                  "option_count": 2,
                  "survey_provider": "credamo",
                  "provider_question_id": "entry-q1",
                  "provider_page_id": "entry-page",
                  "probabilities": [70, 30]
                },
                {
                  "question_num": 2,
                  "question_title": "备注",
                  "question_type": "text",
                  "texts": ["已填写"]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals("credamo", restored.definition.questions.first { it.num == 1 }.provider)
        assertEquals("credamo", restored.definition.questions.first { it.num == 2 }.provider)
        assertEquals("credamo", restored.questions.first { it.num == 1 }.surveyProvider)
        assertEquals("entry-q1", restored.questions.first { it.num == 1 }.providerQuestionId)
        assertEquals("entry-page", restored.questions.first { it.num == 1 }.providerPageId)
        assertEquals("credamo", restored.questions.first { it.num == 2 }.surveyProvider)
        assertEquals("meta-q2", restored.questions.first { it.num == 2 }.providerQuestionId)
        assertEquals("meta-page", restored.questions.first { it.num == 2 }.providerPageId)

        val compiled = ConfigCompiler.compile(restored)
        assertEquals("single" to 0, compiled.providerQuestionConfigIndexMap["credamo:entry-page:entry-q1"])
        assertEquals(1, compiled.providerQuestionConfigNumMap["credamo:entry-page:entry-q1"])
        assertEquals("text" to 0, compiled.providerQuestionConfigIndexMap["credamo:meta-page:meta-q2"])
        assertEquals(2, compiled.providerQuestionConfigNumMap["credamo:meta-page:meta-q2"])

        val exported = JSONObject(ConfigCodec.serialize(restored))
        val exportedInfo = exported.getJSONArray("questions_info")
        val exportedEntries = exported.getJSONArray("question_entries")
        assertEquals("credamo", exportedInfo.getJSONObject(0).getString("provider"))
        assertEquals("credamo", exportedInfo.getJSONObject(1).getString("provider"))
        assertEquals("credamo", exportedEntries.getJSONObject(0).getString("survey_provider"))
        assertEquals("entry-q1", exportedEntries.getJSONObject(0).getString("provider_question_id"))
        assertEquals("entry-page", exportedEntries.getJSONObject(0).getString("provider_page_id"))
        assertEquals("credamo", exportedEntries.getJSONObject(1).getString("survey_provider"))
        assertEquals("meta-q2", exportedEntries.getJSONObject(1).getString("provider_question_id"))
        assertEquals("meta-page", exportedEntries.getJSONObject(1).getString("provider_page_id"))
    }

    @Test
    fun imports_desktop_rich_question_metadata_fields() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/rich-desktop.aspx",
              "survey_title": "桌面富元数据",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "display_num": 3,
                  "title": "图片上传",
                  "description": "请看图",
                  "type_code": "3",
                  "options": 2,
                  "option_texts": ["A", "B"],
                  "forced_texts": ["必须填"],
                  "attached_option_selects": [{"option_index": 1, "option_text": "B", "weights": [1, 2]}],
                  "has_attached_option_select": true,
                  "question_media": [
                    {"kind": "image", "scope": "title", "index": null, "source_url": "https://example.com/q.png", "label": "题干图"}
                  ],
                  "unsupported": true,
                  "unsupported_reason": "上传题暂未支持"
                }
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "图片上传", "question_type": "single", "option_count": 2, "probabilities": [50, 50]}
              ]
            }
        """.trimIndent()

        val meta = ConfigCodec.deserialize(json).definition.questions.first()
        assertEquals(3, meta.displayNum)
        assertEquals("请看图", meta.description)
        assertEquals(listOf("必须填"), meta.forcedTexts)
        assertTrue(meta.hasAttachedOptionSelect)
        assertEquals(1, meta.attachedOptionSelects.first()["option_index"])
        assertEquals("https://example.com/q.png", meta.questionMedia.single().sourceUrl)
        assertTrue(meta.unsupported)
        assertEquals("上传题暂未支持", meta.unsupportedReason)
    }

    @Test
    fun imported_forced_choice_metadata_overrides_stale_entry_weights() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/forced-choice.aspx",
              "survey_title": "强制选择",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "请选第二项",
                  "type_code": "3",
                  "options": 3,
                  "option_texts": ["A", "B", "C"],
                  "forced_option_index": 1,
                  "forced_option_text": "B"
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "请选第二项",
                  "question_type": "single",
                  "option_count": 3,
                  "distribution_mode": "custom",
                  "probabilities": [100, 0, 0],
                  "custom_weights": [100, 0, 0]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals("custom", q.distributionMode)
        assertEquals(listOf(0.0, 100.0, 0.0), q.optionWeights)
        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf(0.0, 1.0, 0.0), compiled.singleProb.single())

        val exportedEntry = JSONObject(ConfigCodec.serialize(restored))
            .getJSONArray("question_entries")
            .getJSONObject(0)
        assertEquals(0.0, exportedEntry.getJSONArray("probabilities").getDouble(0), 0.0001)
        assertEquals(100.0, exportedEntry.getJSONArray("probabilities").getDouble(1), 0.0001)
    }

    @Test
    fun imported_forced_text_metadata_overrides_stale_text_entry_candidates() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/forced-text.aspx",
              "survey_title": "强制填空",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "请填写指定内容",
                  "type_code": "1",
                  "is_text_like": true,
                  "text_inputs": 1,
                  "forced_texts": ["必须填这个"]
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "请填写指定内容",
                  "question_type": "text",
                  "texts": ["旧答案A", "旧答案B"],
                  "probabilities": [10, 90],
                  "ai_enabled": true,
                  "text_random_mode": "name"
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf("必须填这个"), restored.questions.single().textCandidates)
        assertEquals(false, restored.questions.single().useAiText)
        assertEquals("custom", restored.questions.single().textMode)
        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf("必须填这个"), compiled.texts.single())
        assertEquals(listOf(1.0), compiled.textsProb.single())

        val exportedEntry = JSONObject(ConfigCodec.serialize(restored))
            .getJSONArray("question_entries")
            .getJSONObject(0)
        assertEquals("必须填这个", exportedEntry.getJSONArray("texts").getString(0))
        assertEquals(1, exportedEntry.getJSONArray("texts").length())
    }

    @Test
    fun imported_forced_multi_text_metadata_disables_stale_blank_ai_modes() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/forced-multi-text.aspx",
              "survey_title": "强制多项填空",
              "survey_provider": "wjx",
              "questions_info": [
                {
                  "num": 1,
                  "title": "请填写指定资料",
                  "type_code": "1",
                  "is_text_like": true,
                  "is_multi_text": true,
                  "text_inputs": 2,
                  "text_input_labels": ["姓名", "电话"],
                  "forced_texts": ["张三||13800000000"]
                }
              ],
              "question_entries": [
                {
                  "question_num": 1,
                  "question_title": "请填写指定资料",
                  "question_type": "multi_text",
                  "texts": ["旧姓名||旧电话"],
                  "multi_text_blank_modes": ["name", "integer"],
                  "multi_text_blank_ai_flags": [false, true],
                  "multi_text_blank_int_ranges": [[0, 100], [18, 60]]
                }
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val q = restored.questions.single()

        assertEquals(listOf("张三||13800000000"), q.textCandidates)
        assertEquals(listOf("custom", "custom"), q.multiTextBlankModes)
        assertEquals(listOf(false, false), q.multiTextBlankAiFlags)
        val compiled = ConfigCompiler.compile(restored)
        assertEquals(listOf("张三||13800000000"), compiled.texts.single())
        assertEquals(listOf("custom", "custom"), compiled.multiTextBlankModes.single())
        assertEquals(listOf(false, false), compiled.multiTextBlankAiFlags.single())
    }

    @Test
    fun import_without_question_entries_builds_default_question_drafts() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/meta-only.aspx",
              "survey_title": "只有题目结构",
              "survey_provider": "wjx",
              "target": 12,
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]},
                {"num": 2, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        assertEquals(2, restored.questions.size)
        assertEquals(12, restored.params.targetNum)
        assertEquals("选择", restored.questions.first().title)
        assertEquals(listOf("已填写"), restored.questions.first { it.num == 2 }.textCandidates)
    }

    @Test
    fun round_trips_desktop_preserved_runtime_fields() {
        val def = SurveyDefinition(
            SurveyProviderType.WJX,
            "https://www.wjx.cn/vm/preserved.aspx",
            "保真字段",
            listOf(SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B"))),
        )
        val draft = SurveyConfigDraft.fromDefinition(def).copy(
            preserved = ConfigPreservedFields(
                dimensionGroups = listOf("态度", "满意度"),
                reverseFillEnabled = true,
                reverseFillSourcePath = "C:/data/reverse.xlsx",
                reverseFillFormat = "wjx_text",
                reverseFillStartRow = 3,
                reverseFillThreads = 4,
            ),
        )

        val restored = ConfigCodec.deserialize(ConfigCodec.serialize(draft))

        assertEquals(listOf("态度", "满意度"), restored.preserved.dimensionGroups)
        assertTrue(restored.preserved.reverseFillEnabled)
        assertEquals("C:/data/reverse.xlsx", restored.preserved.reverseFillSourcePath)
        assertEquals("wjx_text", restored.preserved.reverseFillFormat)
        assertEquals(3, restored.preserved.reverseFillStartRow)
        assertEquals(4, restored.preserved.reverseFillThreads)
    }

    @Test
    fun imports_and_exports_desktop_ai_runtime_fields_without_silent_loss() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/ai.aspx",
              "survey_title": "AI 配置",
              "survey_provider": "wjx",
              "ai_mode": "provider",
              "ai_provider": "custom",
              "ai_api_key": "sk-test",
              "ai_base_url": "https://api.example.com/v1",
              "ai_api_protocol": "auto",
              "ai_model": "model-x",
              "ai_system_prompt": "按题目生成自然回答",
              "questions_info": [
                {"num": 1, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val ai = restored.preserved.importedAiConfig

        assertTrue(ai.present)
        assertTrue(ai.isProviderMode)
        assertEquals("custom", ai.provider)
        assertEquals("sk-test", ai.apiKey)
        assertEquals("https://api.example.com/v1", ai.baseUrl)
        assertEquals("model-x", ai.model)
        assertEquals("按题目生成自然回答", ai.systemPrompt)

        val exported = JSONObject(ConfigCodec.serialize(restored))
        assertEquals("provider", exported.getString("ai_mode"))
        assertEquals("custom", exported.getString("ai_provider"))
        assertEquals("sk-test", exported.getString("ai_api_key"))
        assertEquals("https://api.example.com/v1", exported.getString("ai_base_url"))
        assertEquals("model-x", exported.getString("ai_model"))
        assertEquals("按题目生成自然回答", exported.getString("ai_system_prompt"))
    }

    @Test
    fun imported_desktop_provider_ai_config_becomes_effective_runtime_ai_when_local_ai_is_off() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/ai-runtime.aspx",
              "survey_title": "AI 运行配置",
              "survey_provider": "wjx",
              "ai_mode": "provider",
              "ai_provider": "custom",
              "ai_api_key": "sk-imported",
              "ai_base_url": "https://api.imported.example/v1",
              "ai_model": "imported-model",
              "ai_system_prompt": "导入提示词",
              "questions_info": [
                {"num": 1, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "填空", "question_type": "text", "texts": ["已填写"], "ai_enabled": true}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val (effectiveParams, effectiveAi) = ConfigCompiler.resolveAiOptions(restored)
        val compiled = ConfigCompiler.compile(restored.copy(params = effectiveParams), effectiveAi)

        assertEquals("provider", compiled.aiMode)
        assertTrue(compiled.aiEnabled)
        assertEquals("https://api.imported.example/v1", compiled.aiBaseUrl)
        assertEquals("sk-imported", compiled.aiApiKey)
        assertEquals("imported-model", compiled.aiModel)
        assertEquals("auto", compiled.aiApiProtocol)
        assertEquals("导入提示词", compiled.aiSystemPrompt)
    }

    @Test
    fun imported_desktop_ai_protocol_is_compiled_for_runtime_provider() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/ai-protocol.aspx",
              "survey_title": "AI 协议",
              "survey_provider": "wjx",
              "ai_mode": "provider",
              "ai_provider": "custom",
              "ai_api_key": "sk-test",
              "ai_base_url": "https://api.example.com/v1",
              "ai_api_protocol": "responses",
              "ai_model": "model-x",
              "questions_info": [
                {"num": 1, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val (effectiveParams, effectiveAi) = ConfigCompiler.resolveAiOptions(restored)
        val compiled = ConfigCompiler.compile(restored.copy(params = effectiveParams), effectiveAi)

        assertEquals("responses", compiled.aiApiProtocol)
    }

    @Test
    fun local_custom_ai_protocol_is_compiled_for_runtime_provider() {
        val q = SurveyQuestionMeta(
            num = 1,
            title = "填空",
            typeCode = "1",
            isTextLike = true,
            textInputs = 1,
        )
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/local-ai.aspx", "本机 AI", listOf(q)),
        )
        val localAi = ConfigCompiler.AiOptions(
            enabled = true,
            baseUrl = "https://api.local.example/v1",
            apiKey = "sk-local",
            model = "local-model",
            apiProtocol = "responses",
            systemPrompt = "本机提示词",
        )
        val (effectiveParams, effectiveAi) = ConfigCompiler.resolveAiOptions(draft, localAi)
        val compiled = ConfigCompiler.compile(draft.copy(params = effectiveParams), effectiveAi)

        assertEquals("provider", compiled.aiMode)
        assertTrue(compiled.aiEnabled)
        assertEquals("https://api.local.example/v1", compiled.aiBaseUrl)
        assertEquals("sk-local", compiled.aiApiKey)
        assertEquals("local-model", compiled.aiModel)
        assertEquals("responses", compiled.aiApiProtocol)
        assertEquals("本机提示词", compiled.aiSystemPrompt)
    }

    @Test
    fun imported_desktop_deepseek_ai_config_uses_provider_defaults_for_runtime() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/ai-deepseek.aspx",
              "survey_title": "DeepSeek AI",
              "survey_provider": "wjx",
              "ai_mode": "provider",
              "ai_provider": "deepseek",
              "ai_api_key": "sk-deepseek",
              "questions_info": [
                {"num": 1, "title": "填空", "type_code": "1", "is_text_like": true, "text_inputs": 1}
              ],
              "question_entries": [
                {"question_num": 1, "question_title": "填空", "question_type": "text", "texts": ["已填写"], "ai_enabled": true}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        val (effectiveParams, effectiveAi) = ConfigCompiler.resolveAiOptions(restored)
        val compiled = ConfigCompiler.compile(restored.copy(params = effectiveParams), effectiveAi)

        assertEquals("provider", compiled.aiMode)
        assertTrue(compiled.aiEnabled)
        assertEquals("https://api.deepseek.com/v1", compiled.aiBaseUrl)
        assertEquals("sk-deepseek", compiled.aiApiKey)
        assertEquals("deepseek-v4-flash", compiled.aiModel)
    }

    @Test
    fun imports_desktop_reverse_fill_and_dimension_group_fields() {
        val json = """
            {
              "config_schema_version": 6,
              "url": "https://www.wjx.cn/vm/preserved-desktop.aspx",
              "survey_title": "桌面保真",
              "survey_provider": "wjx",
              "reverse_fill_enabled": true,
              "reverse_fill_source_path": "/Users/me/reverse.xlsx",
              "reverse_fill_format": "wjx_score",
              "reverse_fill_start_row": 2,
              "reverse_fill_threads": 5,
              "dimension_groups": ["未分组", "态度", "态度", "满意度"],
              "questions_info": [
                {"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}
              ]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)

        assertEquals(listOf("态度", "满意度"), restored.preserved.dimensionGroups)
        assertTrue(restored.preserved.reverseFillEnabled)
        assertEquals("/Users/me/reverse.xlsx", restored.preserved.reverseFillSourcePath)
        assertEquals("wjx_score", restored.preserved.reverseFillFormat)
        assertEquals(2, restored.preserved.reverseFillStartRow)
        assertEquals(5, restored.preserved.reverseFillThreads)
        assertEquals(1, restored.questions.size)
    }

    @Test
    fun serialize_writes_desktop_schema_v2_and_keeps_legacy_key() {
        val q = SurveyQuestionMeta(num = 1, title = "姓名", typeCode = "1")
        val def = SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/abc.aspx", "测试", listOf(q))
        val json = ConfigCodec.serialize(SurveyConfigDraft.fromDefinition(def))
        assertTrue(json.contains("\"schema_version\": 2"))
        assertTrue(json.contains("\"config_schema_version\": 6"))
        assertTrue(json.contains("\"ai_config_mode\": \"inherit\""))
    }

    @Test
    fun deserialize_accepts_v5_config_with_only_schema_version() {
        val q = SurveyQuestionMeta(num = 1, title = "姓名", typeCode = "1")
        val def = SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/abc.aspx", "测试", listOf(q))
        val payload = JSONObject(ConfigCodec.serialize(SurveyConfigDraft.fromDefinition(def)))
        payload.remove("config_schema_version")
        payload.put("schema_version", 2)
        payload.put("question_entries", org.json.JSONArray().put(JSONObject().put("question_num", 1).put("question_type", "text")))

        val restored = ConfigCodec.deserialize(payload.toString())
        assertEquals("测试", restored.definition.title)
        assertEquals(1, restored.questions.size)
    }

    @Test
    fun deserialize_accepts_config_without_any_version_field() {
        // v4.1.0 导出没有版本字段，按兼容处理
        val json = """
            {
              "url": "https://www.wjx.cn/vm/abc.aspx",
              "survey_title": "无版本配置",
              "survey_provider": "wjx",
              "questions_info": [{"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        assertEquals("无版本配置", restored.definition.title)
    }

    @Test
    fun deserialize_rejects_future_desktop_schema() {
        val json = """
            {
              "schema_version": 3,
              "url": "https://www.wjx.cn/vm/abc.aspx",
              "survey_title": "未来版本"
            }
        """.trimIndent()

        val error = assertThrows(IllegalArgumentException::class.java) { ConfigCodec.deserialize(json) }
        assertTrue(error.message.orEmpty().contains("更高版本的桌面端"))
    }

    @Test
    fun deserialize_honors_explicit_ai_config_mode() {
        val json = """
            {
              "schema_version": 2,
              "url": "https://www.wjx.cn/vm/abc.aspx",
              "survey_title": "继承配置",
              "ai_config_mode": "inherit",
              "ai_api_key": "should-be-ignored",
              "questions_info": [{"num": 1, "title": "选择", "type_code": "3", "options": 2, "option_texts": ["A", "B"]}]
            }
        """.trimIndent()

        val restored = ConfigCodec.deserialize(json)
        assertTrue(!restored.preserved.importedAiConfig.present)
    }
}
