package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_STATUS_BLOCKED
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_STATUS_FALLBACK
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_FORMAT_WJX_SEQUENCE
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_FORMAT_WJX_TEXT
import com.surveycontroller.android.core.reverse_fill.ReverseFillBuilder
import com.surveycontroller.android.core.reverse_fill.ReverseFillRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseFillTest {

    @Test
    fun builds_samples_and_resolves_by_option_text() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 3, optionTexts = listOf("男", "女", "其他"))
        // 行：表头 + 两份数据
        val rows = listOf(
            mapOf(0 to "1.性别"),
            mapOf(0 to "女"),
            mapOf(0 to "男"),
        )
        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        assertEquals(2, spec.totalSamples)
        assertEquals(1, spec.questionPlans.size)
        assertEquals(true, spec.questionPlans.first().matchedByHeader)
        assertEquals(listOf("1.性别"), spec.questionPlans.first().columnHeaders)

        val runtime = ReverseFillRuntime(spec)
        runtime.reserve("t1")
        // 第一份数据是"女" → 索引 1
        assertEquals(1, runtime.resolve(1, "t1")?.choiceIndex)
        runtime.commit("t1")
        runtime.reserve("t1")
        // 第二份数据是"男" → 索引 0
        assertEquals(0, runtime.resolve(1, "t1")?.choiceIndex)
    }

    @Test
    fun discard_requeues_sample() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(mapOf(0 to "题1"), mapOf(0 to "A"))
        val spec = ReverseFillBuilder.build(rows, listOf(q1), 1, REVERSE_FILL_FORMAT_WJX_TEXT, 5)
        val runtime = ReverseFillRuntime(spec)
        runtime.reserve("t1")
        runtime.discard("t1", requeue = true)
        assertEquals(1, runtime.remaining)
        assertNull(runtime.resolve(1, "t1"))
    }

    @Test
    fun failed_sample_retries_once_then_is_discarded_like_desktop_runtime() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(mapOf(0 to "题1"), mapOf(0 to "A"))
        val spec = ReverseFillBuilder.build(rows, listOf(q1), 1, REVERSE_FILL_FORMAT_WJX_TEXT, 5)
        val runtime = ReverseFillRuntime(spec)

        runtime.reserve("t1")
        assertEquals(0, runtime.resolve(1, "t1")?.choiceIndex)
        assertEquals(false, runtime.markFailed("t1", maxRetries = 1))
        assertEquals(1, runtime.remaining)

        runtime.reserve("t1")
        assertEquals(0, runtime.resolve(1, "t1")?.choiceIndex)
        assertEquals(true, runtime.markFailed("t1", maxRetries = 1))
        assertEquals(0, runtime.remaining)
        assertEquals(false, runtime.reserve("t1"))
    }

    @Test
    fun target_reachability_counts_queued_and_reserved_samples() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(
            mapOf(0 to "题1"),
            mapOf(0 to "A"),
            mapOf(0 to "B"),
        )
        val spec = ReverseFillBuilder.build(rows, listOf(q1), 1, REVERSE_FILL_FORMAT_WJX_TEXT, 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(true, runtime.canReachTarget(successCount = 0, target = 2))
        runtime.reserve("t1")
        assertEquals(true, runtime.canReachTarget(successCount = 0, target = 2))
        assertEquals(true, runtime.markFailed("t1", maxRetries = 0))
        assertEquals(false, runtime.canReachTarget(successCount = 0, target = 2))
        assertEquals(true, runtime.canReachTarget(successCount = 1, target = 2))
    }

    @Test
    fun committed_sample_clears_prior_failure_count() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(
            mapOf(0 to "题1"),
            mapOf(0 to "A"),
            mapOf(0 to "A"),
        )
        val spec = ReverseFillBuilder.build(rows, listOf(q1), 1, REVERSE_FILL_FORMAT_WJX_TEXT, 5)
        val runtime = ReverseFillRuntime(spec)

        runtime.reserve("t1")
        assertEquals(false, runtime.markFailed("t1", maxRetries = 1))
        runtime.reserve("t1")
        runtime.commit("t1")

        runtime.reserve("t1")
        assertEquals(false, runtime.markFailed("t1", maxRetries = 1))
        assertEquals(1, runtime.remaining)
    }

    @Test
    fun start_row_skips_leading_data_rows() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(
            mapOf(0 to "题1"),
            mapOf(0 to "A"),
            mapOf(0 to "B"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 2, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(1, spec.totalSamples)
        assertEquals(2, spec.samples.first().rowNumber)
        assertEquals(2, spec.previewRows.first().rowNumber)
        runtime.reserve("t1")
        assertEquals(1, runtime.resolve(1, "t1")?.choiceIndex)
    }

    @Test
    fun blank_selected_rows_are_preserved_as_samples_and_fall_back_at_runtime() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(
            mapOf(0 to "题1"),
            mapOf(0 to ""),
            mapOf(0 to "B"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(2, spec.totalSamples)
        assertEquals(listOf(1, 2), spec.samples.map { it.rowNumber })
        assertEquals(0, spec.previewRows.first().answeredQuestions)
        assertEquals(1, spec.previewRows.first().totalQuestions)

        runtime.reserve("t1")
        assertNull(runtime.resolve(1, "t1"))
        runtime.commit("t1")

        runtime.reserve("t1")
        assertEquals(1, runtime.resolve(1, "t1")?.choiceIndex)
    }

    @Test
    fun sequence_format_resolves_one_based_option_numbers() {
        val q1 = SurveyQuestionMeta(num = 1, typeCode = "3", options = 3, optionTexts = listOf("男", "女", "其他"))
        val rows = listOf(
            mapOf(0 to "性别"),
            mapOf(0 to "2"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_SEQUENCE, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        runtime.reserve("t1")
        assertEquals(1, runtime.resolve(1, "t1")?.choiceIndex)
    }

    @Test
    fun explicit_question_number_headers_are_not_stolen_by_similar_titles() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val q2 = SurveyQuestionMeta(num = 2, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val rows = listOf(
            mapOf(0 to "2、性别"),
            mapOf(0 to "女"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1, q2), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 1)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(listOf(2), spec.questionPlans.map { it.questionNum })
        assertEquals(1, spec.totalSamples)
        runtime.reserve("t1")
        assertNull(runtime.resolve(1, "t1"))
        assertEquals(1, runtime.resolve(2, "t1")?.choiceIndex)
    }

    @Test
    fun matrix_columns_are_reordered_by_row_label_suffix() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "品牌",
            typeCode = "6",
            rows = 2,
            options = 3,
            rowTexts = listOf("外观", "功能"),
            optionTexts = listOf("差", "中", "好"),
        )
        val rows = listOf(
            mapOf(0 to "1、品牌-功能", 1 to "1、品牌-外观"),
            mapOf(0 to "好", 1 to "中"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        runtime.reserve("t1")
        assertEquals(listOf(1, 2), runtime.resolve(1, "t1")?.matrixChoiceIndexes)
        assertEquals(listOf("1、品牌-外观", "1、品牌-功能"), spec.questionPlans.first().columnHeaders)
    }

    @Test
    fun matrix_extra_explicit_column_is_ignored_when_row_labels_identify_complete_set() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "品牌",
            typeCode = "6",
            rows = 2,
            options = 3,
            rowTexts = listOf("外观", "功能"),
            optionTexts = listOf("差", "中", "好"),
        )
        val rows = listOf(
            mapOf(0 to "1、品牌-备注", 1 to "1、品牌-功能", 2 to "1、品牌-外观"),
            mapOf(0 to "这列不是行答案", 1 to "好", 2 to "中"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals("reverse_fill", spec.questionPlans.first().status)
        assertEquals(listOf("1、品牌-外观", "1、品牌-功能"), spec.questionPlans.first().columnHeaders)
        runtime.reserve("t1")
        assertEquals(listOf(1, 2), runtime.resolve(1, "t1")?.matrixChoiceIndexes)
    }

    @Test
    fun matrix_duplicate_row_label_still_degrades_instead_of_guessing_between_columns() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "品牌",
            typeCode = "6",
            rows = 2,
            options = 3,
            rowTexts = listOf("外观", "功能"),
            optionTexts = listOf("差", "中", "好"),
        )
        val rows = listOf(
            mapOf(0 to "1、品牌-外观", 1 to "1、品牌-功能", 2 to "1、品牌-外观"),
            mapOf(0 to "中", 1 to "好", 2 to "差"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("矩阵题解析出 2 行，但 Excel 中对应了 3 列，已使用常规配置", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().answeredQuestions)
    }

    @Test
    fun matrix_with_missing_columns_degrades_instead_of_partially_replaying() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "品牌",
            typeCode = "6",
            rows = 2,
            options = 2,
            rowTexts = listOf("外观", "功能"),
            optionTexts = listOf("差", "好"),
        )
        val rows = listOf(
            mapOf(0 to "1、品牌-外观"),
            mapOf(0 to "好"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 1)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("矩阵题解析出 2 行，但 Excel 中对应了 1 列，已使用常规配置", spec.questionPlans.first().detail)
        assertEquals(1, spec.totalSamples)
        runtime.reserve("t1")
        assertNull(runtime.resolve(1, "t1"))
    }

    @Test
    fun multi_text_columns_are_reordered_by_blank_label_suffix() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "联系方式",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
            textInputLabels = listOf("姓名", "手机"),
        )
        val rows = listOf(
            mapOf(0 to "1、联系方式—手机", 1 to "1、联系方式—姓名"),
            mapOf(0 to "13800000000", 1 to "张三"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        runtime.reserve("t1")
        assertEquals(listOf("张三", "13800000000"), runtime.resolve(1, "t1")?.textValues)
        assertEquals(listOf("1、联系方式—姓名", "1、联系方式—手机"), spec.questionPlans.first().columnHeaders)
    }

    @Test
    fun multi_text_extra_explicit_column_is_ignored_when_blank_labels_identify_complete_set() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "联系方式",
            typeCode = "1",
            isTextLike = true,
            isMultiText = true,
            textInputs = 2,
            textInputLabels = listOf("姓名", "手机"),
        )
        val rows = listOf(
            mapOf(0 to "1、联系方式—备注", 1 to "1、联系方式—手机", 2 to "1、联系方式—姓名"),
            mapOf(0 to "非答案列", 1 to "13800000000", 2 to "张三"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals("reverse_fill", spec.questionPlans.first().status)
        assertEquals(listOf("1、联系方式—姓名", "1、联系方式—手机"), spec.questionPlans.first().columnHeaders)
        runtime.reserve("t1")
        assertEquals(listOf("张三", "13800000000"), runtime.resolve(1, "t1")?.textValues)
    }

    @Test
    fun unsupported_composite_choice_degrades_question_and_removes_replayed_answers() {
        val q1 = SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B"))
        val rows = listOf(
            mapOf(0 to "选择"),
            mapOf(0 to "A"),
            mapOf(0 to "B〖补充〗"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals(listOf(2), spec.questionPlans.first().sampleRows)
        assertEquals(2, spec.totalSamples)
        assertEquals(listOf(1, 2), spec.samples.map { it.rowNumber })
        assertEquals(listOf(0, 0), spec.samples.map { it.answers.size })
        assertEquals("检测到“选项+附加填空”复合值，反填暂不支持", spec.questionPlans.first().detail)
    }

    @Test
    fun partial_blank_matrix_degrades_question_instead_of_silently_falling_back() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "矩阵",
            typeCode = "6",
            rows = 2,
            options = 2,
            rowTexts = listOf("外观", "功能"),
            optionTexts = listOf("差", "好"),
        )
        val rows = listOf(
            mapOf(0 to "1、矩阵-外观", 1 to "1、矩阵-功能"),
            mapOf(0 to "好", 1 to ""),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("矩阵题存在部分行为空，无法稳定回放", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().answeredQuestions)
    }

    @Test
    fun multiple_choice_values_are_replayed_from_wjx_text_export() {
        val q1 = SurveyQuestionMeta(num = 1, title = "多选", typeCode = "4", options = 3, optionTexts = listOf("A", "B", "C"))
        val q2 = SurveyQuestionMeta(num = 2, title = "单选", typeCode = "3", options = 2, optionTexts = listOf("是", "否"))
        val rows = listOf(
            mapOf(0 to "多选", 1 to "单选"),
            mapOf(0 to "A┋C", 1 to "是"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1, q2), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val multiplePlan = spec.questionPlans.first { it.questionNum == 1 }
        val runtime = ReverseFillRuntime(spec)

        assertEquals("reverse_fill", multiplePlan.status)
        assertEquals(2, spec.previewRows.first().answeredQuestions)
        assertEquals(2, spec.previewRows.first().totalQuestions)
        runtime.reserve("t1")
        assertEquals(listOf(0, 2), runtime.resolve(1, "t1")?.choiceIndexes)
        assertEquals(0, runtime.resolve(2, "t1")?.choiceIndex)
    }

    @Test
    fun multiple_choice_with_fillable_option_is_degraded_before_parsing_values() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "多选",
            typeCode = "4",
            options = 2,
            optionTexts = listOf("A", "其他"),
            fillableOptions = listOf(1),
        )
        val rows = listOf(
            mapOf(0 to "多选"),
            mapOf(0 to "A┋其他〖补充〗"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("多选题包含选项附加填空或内嵌下拉，已使用常规配置", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().totalQuestions)
    }

    @Test
    fun slider_and_location_values_are_replayed_when_export_values_are_structured() {
        val slider = SurveyQuestionMeta(num = 1, title = "满意度", typeCode = "8", sliderMin = 0.0, sliderMax = 100.0)
        val location = SurveyQuestionMeta(num = 2, title = "地区", typeCode = "1", isTextLike = true, isLocation = true)
        val rows = listOf(
            mapOf(0 to "满意度", 1 to "地区"),
            mapOf(0 to "72.5分", 1 to "北京-北京-东城区"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(slider, location), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)
        val runtime = ReverseFillRuntime(spec)

        assertEquals(listOf("reverse_fill", "reverse_fill"), spec.questionPlans.map { it.status })
        assertEquals(2, spec.previewRows.first().answeredQuestions)
        assertEquals(2, spec.previewRows.first().totalQuestions)
        runtime.reserve("t1")
        assertEquals(72.5, runtime.resolve(1, "t1")?.sliderValue ?: -1.0, 0.000001)
        assertEquals(listOf("北京", "北京", "东城区"), runtime.resolve(2, "t1")?.locationParts)
    }

    @Test
    fun unstructured_location_value_degrades_instead_of_guessing_address_parts() {
        val q1 = SurveyQuestionMeta(num = 1, title = "地区", typeCode = "1", isTextLike = true, isLocation = true)
        val rows = listOf(
            mapOf(0 to "地区"),
            mapOf(0 to "北京市东城区"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("无法把“北京市东城区”解析为省/市/区县三段", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().answeredQuestions)
    }

    @Test
    fun partial_location_parts_degrade_instead_of_replaying_an_incomplete_address() {
        val q1 = SurveyQuestionMeta(num = 1, title = "地区", typeCode = "1", isTextLike = true, isLocation = true)
        val rows = listOf(
            mapOf(0 to "地区"),
            mapOf(0 to "北京-北京"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("无法把“北京-北京”解析为省/市/区县三段", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().answeredQuestions)
    }

    @Test
    fun order_questions_are_marked_auto_handled_instead_of_reversed() {
        val q1 = SurveyQuestionMeta(num = 1, title = "排序", typeCode = "11", options = 3, optionTexts = listOf("A", "B", "C"))
        val rows = listOf(
            mapOf(0 to "排序"),
            mapOf(0 to "A→B→C"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_BLOCKED, spec.questionPlans.first().status)
        assertEquals("排序题目前不参与反填覆盖，运行时按常规排序逻辑处理", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().totalQuestions)
        assertEquals(1, spec.totalSamples)
        assertEquals(1, spec.samples.first().rowNumber)
    }

    @Test
    fun single_with_fillable_option_is_degraded_before_parsing_values() {
        val q1 = SurveyQuestionMeta(
            num = 1,
            title = "选择",
            typeCode = "3",
            options = 2,
            optionTexts = listOf("A", "其他"),
            fillableOptions = listOf(1),
        )
        val rows = listOf(
            mapOf(0 to "选择"),
            mapOf(0 to "其他〖补充〗"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertEquals("选项附加填空或内嵌下拉暂不参与反填回放，已使用常规配置", spec.questionPlans.first().detail)
        assertEquals(0, spec.previewRows.first().totalQuestions)
    }

    @Test
    fun preview_rows_report_answer_coverage() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val q2 = SurveyQuestionMeta(num = 2, title = "姓名", typeCode = "1", isTextLike = true, textInputs = 1)
        val rows = listOf(
            mapOf(0 to "性别", 1 to "姓名"),
            mapOf(0 to "男", 1 to "张三"),
            mapOf(0 to "女", 1 to ""),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1, q2), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(2, spec.previewRows.first().answeredQuestions)
        assertEquals(2, spec.previewRows.first().totalQuestions)
        assertEquals(1, spec.previewRows[1].answeredQuestions)
        assertEquals(2, spec.previewRows[1].totalQuestions)
    }

    @Test
    fun plan_marks_sequential_fallback_when_header_does_not_match() {
        val q1 = SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女"))
        val rows = listOf(
            mapOf(0 to "第一列"),
            mapOf(0 to "男"),
        )

        val spec = ReverseFillBuilder.build(rows, listOf(q1), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 5)

        assertEquals(false, spec.questionPlans.first().matchedByHeader)
        assertEquals(listOf("第一列"), spec.questionPlans.first().columnHeaders)
    }

    // ===== 多选题多列回放（对标 surveycore parse_multiple_row_answer） =====

    @Test
    fun multiple_sequence_columns_replay_zero_one_flags() {
        val question = SurveyQuestionMeta(
            num = 5, title = "标题", typeCode = "4", options = 3,
            optionTexts = listOf("选项1", "选项2", "选项3"),
        )
        val rows = listOf(
            mapOf(0 to "序号", 1 to "5、标题(选项1)", 2 to "5、(选项2)", 3 to "5、(选项3)"),
            mapOf(0 to "1", 1 to "1", 2 to "0", 3 to "1"),
            mapOf(0 to "2", 1 to "0", 2 to "1", 3 to "0"),
        )

        val spec = ReverseFillBuilder.build(
            rows, listOf(question), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_SEQUENCE, targetNum = 2,
        )

        assertEquals("reverse_fill", spec.questionPlans.first().status)
        assertEquals(listOf(1, 2, 3), spec.questionPlans.first().columnIndexes)
        val runtime = ReverseFillRuntime(spec)
        runtime.reserve("t1")
        assertEquals(listOf(0, 2), runtime.resolve(5, "t1")?.choiceIndexes)
        runtime.commit("t1")
        runtime.reserve("t1")
        assertEquals(listOf(1), runtime.resolve(5, "t1")?.choiceIndexes)
    }

    @Test
    fun multiple_text_columns_match_option_labels_regardless_of_order() {
        val question = SurveyQuestionMeta(
            num = 5, title = "标题", typeCode = "4", options = 2,
            optionTexts = listOf("甲", "乙"),
        )
        val rows = listOf(
            mapOf(0 to "5、标题（乙）", 1 to "5、(甲)"),
            mapOf(0 to "1", 1 to "0"),
            mapOf(0 to "0", 1 to "1"),
        )

        val spec = ReverseFillBuilder.build(
            rows, listOf(question), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_TEXT, targetNum = 2,
        )

        assertEquals("reverse_fill", spec.questionPlans.first().status)
        val runtime = ReverseFillRuntime(spec)
        runtime.reserve("t1")
        assertEquals(listOf(1), runtime.resolve(5, "t1")?.choiceIndexes)
        runtime.commit("t1")
        runtime.reserve("t1")
        assertEquals(listOf(0), runtime.resolve(5, "t1")?.choiceIndexes)
    }

    @Test
    fun multiple_columns_reject_partial_blank_or_invalid_flags() {
        val question = SurveyQuestionMeta(
            num = 5, title = "标题", typeCode = "4", options = 3,
            optionTexts = listOf("甲", "乙", "丙"),
        )
        val rows = listOf(
            mapOf(0 to "5、标题(选项1)", 1 to "5、(选项2)", 2 to "5、(选项3)"),
            mapOf(0 to "1", 1 to "", 2 to "0"),
        )

        val spec = ReverseFillBuilder.build(
            rows, listOf(question), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_SEQUENCE, targetNum = 1,
        )

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertTrue(spec.questionPlans.first().detail.contains("0 或 1"))
    }

    @Test
    fun multiple_columns_with_wrong_count_fall_back() {
        val question = SurveyQuestionMeta(
            num = 5, title = "标题", typeCode = "4", options = 3,
            optionTexts = listOf("甲", "乙", "丙"),
        )
        val rows = listOf(
            mapOf(0 to "5、标题(选项1)", 1 to "5、(选项2)"),
            mapOf(0 to "1", 1 to "0"),
        )

        val spec = ReverseFillBuilder.build(
            rows, listOf(question), startRow = 1, format = REVERSE_FILL_FORMAT_WJX_SEQUENCE, targetNum = 1,
        )

        assertEquals(REVERSE_FILL_STATUS_FALLBACK, spec.questionPlans.first().status)
        assertTrue(spec.questionPlans.first().detail.contains("多选题"))
    }
}
