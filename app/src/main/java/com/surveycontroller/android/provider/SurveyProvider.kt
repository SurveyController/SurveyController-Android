package com.surveycontroller.android.provider

import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState

/** 单次提交的结果分类。对应 Python 端响应分类。 */
sealed interface SubmitResult {
    data object Success : SubmitResult
    data object Cancelled : SubmitResult
    data class ProxyFailure(val message: String) : SubmitResult
    data class Unknown(val message: String) : SubmitResult
    data class Rejected(val questionNum: Int?, val reason: String) : SubmitResult
    data object Verification : SubmitResult
    data class AiFailure(val message: String) : SubmitResult
    data class ProviderUnavailable(val message: String) : SubmitResult
    data class Failure(val message: String) : SubmitResult
}

/**
 * 平台适配器统一接口。对应 Python 端 software/providers/registry.py 的分发目标。
 * UI/UX 可自由重设计，但本层必须 1:1 复刻各平台 HTTP 协议。
 */
interface SurveyProvider {
    val type: SurveyProviderType

    /** 解析问卷结构。 */
    suspend fun parseSurvey(url: String): SurveyDefinition

    /** 生成答案并提交一份问卷（纯 HTTP 链路）。 */
    suspend fun fillSurveyHttp(
        config: ExecutionConfig,
        state: ExecutionState,
        threadName: String = "",
        proxyAddress: String? = null,
        userAgent: String? = null,
    ): SubmitResult
}
