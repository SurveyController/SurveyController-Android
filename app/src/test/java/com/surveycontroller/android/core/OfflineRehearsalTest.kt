package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.AnswerRule
import com.surveycontroller.android.core.questions.OfflineRehearsal
import com.surveycontroller.android.core.questions.ReverseFillAnswer
import com.surveycontroller.android.core.reverse_fill.ReverseFillSample
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OfflineRehearsalTest {
    @Test
    fun seededSamplesShowRulesDistributionsAndAiPlaceholder() = runBlocking {
        val config = ExecutionConfig(
            questionsMetadata = mapOf(
                1 to SurveyQuestionMeta(num = 1, title = "来源", options = 2, optionTexts = listOf("甲", "乙"), forcedOptionIndex = 0),
                2 to SurveyQuestionMeta(num = 2, title = "目标", options = 2, optionTexts = listOf("是", "否")),
                3 to SurveyQuestionMeta(num = 3, title = "填空", textInputs = 1),
                4 to SurveyQuestionMeta(num = 4, title = "随机", options = 3, optionTexts = listOf("春", "夏", "秋")),
                5 to SurveyQuestionMeta(num = 5, title = "随机文本", textInputs = 1),
            ),
            questionConfigIndexMap = mapOf(1 to ("single" to 0), 2 to ("single" to 1), 3 to ("text" to 0),
                4 to ("multiple" to 0), 5 to ("text" to 1)),
            singleProb = listOf(-1, listOf(0.5, 0.5)),
            multipleProb = listOf(listOf(-1.0)),
            texts = listOf(listOf("备用"), listOf("__RANDOM_ID_CARD__")),
            textsProb = listOf(listOf(1.0), listOf(1.0)),
            textAiFlags = listOf(true, false),
            answerRules = listOf(AnswerRule("a", 1, "selected", listOf(0), 2, "must_select", listOf(1)).toMap()),
        )
        val first = OfflineRehearsal.generate(config, 42)
        val repeat = OfflineRehearsal.generate(config, 42)
        val different = OfflineRehearsal.generate(config, 43)

        assertEquals(first, repeat)
        assertNotEquals(first.samples, different.samples)
        assertEquals(20, first.samples.size)
        assertEquals(listOf("甲", "否", "〔AI 填空占位〕"), first.samples.first().questions.take(3).map { it.answer })
        assertEquals(listOf("命中第 1 题的必选规则"), first.samples.first().questions[1].details)
        assertEquals(mapOf("是" to 0, "否" to 20), first.distributions[1].counts)
    }

    @Test
    fun reverseFillUsesOnlyAvailableRowsInOrder() = runBlocking {
        val config = ExecutionConfig(
            questionsMetadata = mapOf(1 to SurveyQuestionMeta(num = 1, title = "填空", textInputs = 1)),
            questionConfigIndexMap = mapOf(1 to ("text" to 0)),
        )
        val reverse = ReverseFillSpec("auto", 1, listOf(ReverseFillSample(9, mapOf(
            1 to ReverseFillAnswer(kind = "text", textValue = "表格内容"),
        ))))
        val result = OfflineRehearsal.generate(config, 42, reverse)
        val sample = result.samples.single()

        assertEquals(9, sample.sourceRow)
        assertEquals("表格内容", sample.questions.single().answer)
        assertEquals(listOf("来自反填表格"), sample.questions.single().details)
    }
}
