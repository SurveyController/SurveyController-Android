package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.core.engine.AnswerDurationSampler
import com.surveycontroller.android.core.model.ExecutionConfig
import com.surveycontroller.android.core.model.ExecutionState
import com.surveycontroller.android.core.network.HttpClient
import com.surveycontroller.android.core.network.UserAgents
import com.surveycontroller.android.core.questions.AnswerContext
import com.surveycontroller.android.core.questions.SurveyAnswerBuilder
import com.surveycontroller.android.provider.SubmitResult
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProvider
import com.surveycontroller.android.provider.SurveyProviderType
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 问卷星平台适配器。复刻 wjx/provider/{parser,http_runtime}.py 的运行链路。
 */
class WjxProvider(
    private val http: HttpClient,
    private val answerContextFactory: (ExecutionConfig, ExecutionState, String) -> AnswerContext =
        { c, s, t -> AnswerContext(c, s, t) },
) : SurveyProvider {

    override val type = SurveyProviderType.WJX

    private val parseRetryAttempts = 3

    override suspend fun parseSurvey(url: String): SurveyDefinition {
        var lastErr: Exception? = null
        repeat(parseRetryAttempts) { attempt ->
            try {
                val resp = http.get(url, headers = UserAgents.DEFAULT_HEADERS, timeoutSeconds = 12)
                val (parsedQuestions, title) = WjxHtmlParser.parse(resp.body)
                val questions = WjxMultilevel.loadOptions(parsedQuestions, resp.body, url, http)
                if (questions.isNotEmpty() || attempt == parseRetryAttempts - 1) {
                    return SurveyDefinition(SurveyProviderType.WJX, url, title, questions)
                }
            } catch (e: WjxPageStateException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
            }
            delay(350)
        }
        throw lastErr ?: RuntimeException("问卷星解析失败")
    }

    override suspend fun fillSurveyHttp(
        config: ExecutionConfig,
        state: ExecutionState,
        threadName: String,
        proxyAddress: String?,
        userAgent: String?,
    ): SubmitResult {
        val url = config.url
        val shortid = WjxSubmitCodec.shortidFromUrl(url)
        val ua = UserAgents.resolve(userAgent)
        val headers = UserAgents.DEFAULT_HEADERS.toMutableMap().apply {
            this["User-Agent"] = ua
            this["Referer"] = url
        }

        state.updateThreadStatus(threadName, "加载问卷", running = true)
        val pageResp = http.get(url, headers = headers, timeoutSeconds = 15, proxyAddress = proxyAddress)
        val pageHtml = pageResp.body
        try {
            WjxHtmlParser.assertAnswerable(pageHtml)
        } catch (e: WjxPageStateException) {
            return SubmitResult.ProviderUnavailable(e.message ?: "问卷当前不可填写")
        }

        state.updateThreadStatus(threadName, "生成答案", running = true)
        val answerCtx = answerContextFactory(config, state, threadName)
        val plan = SurveyAnswerBuilder(answerCtx).buildPlan()
        if (plan.actions.isEmpty()) return SubmitResult.Failure("未生成任何答案")
        val orderedQuestions = config.orderedQuestions()
        val questionsByNum = orderedQuestions.associateBy { it.num }
        // 对标 multilevel_answer_action：多级下拉把选项索引换成文本路径。
        val actions = plan.actions.map { action ->
            questionsByNum[action.questionNum]?.let { WjxMultilevel.applyAnswer(action, it) } ?: action
        }

        // 登记待定分布选择（提交成功后并入统计）
        state.discardPendingDistribution(threadName)
        for (action in actions) {
            for ((optionIndex, optionCount, rowIndex) in action.pendingDistributionChoices) {
                val statKey = if (rowIndex == null) "q:${action.configQuestionNum}" else "matrix:${action.configQuestionNum}:$rowIndex"
                state.appendPendingDistribution(threadName, statKey, optionIndex, optionCount)
            }
        }

        val submitData = WjxSubmitCodec.buildSubmitData(
            actions, orderedQuestions, plan.skippedQuestionNums,
        )
        if (!config.submitEnabled) {
            state.commitPendingDistributionFor(answerCtx)
            return SubmitResult.Success
        }

        val currentMs = System.currentTimeMillis()
        val ktimes = AnswerDurationSampler.sampleSeconds(config)
        val startSeconds = WjxSubmitCodec.resolveStartSeconds(currentMs, ktimes)
        val sceneId = WjxSubmitCodec.extractSceneId(pageHtml)
        val jqnonce = java.util.UUID.randomUUID().toString()
        val domain = WjxSubmitCodec.submitDomain(url)
        val submitUrl = "https://$domain/joinnew/processjq.ashx"

        val params = mapOf(
            "shortid" to shortid,
            "starttime" to WjxSubmitCodec.formatStartTime(startSeconds),
            "cst" to (startSeconds * 1000).toString(),
            "source" to "directphone",
            "submittype" to "1",
            "ktimes" to ktimes.toString(),
            "rn" to (2000000000 + Random.nextDouble() * 100000000).toString(),
            "jcn" to shortid,
            "nw" to "1",
            "jwt" to "4",
            "jpm" to "62",
            "capt" to "2",
            "t" to currentMs.toString(),
            "wxfs" to "100",
            "jqnonce" to jqnonce,
            "jqsign" to WjxSubmitCodec.buildJqsign(jqnonce, ktimes),
            "access_token" to "1",
            "openid" to Random.nextInt(100000000, 1000000000).toString(),
            "unionId" to Random.nextInt(100000000, 1000000000).toString(),
            "wxappid" to "wx8fe84c5d52db247a",
            "iwx" to "1",
        )
        val form = mapOf("submitdata" to submitData, "sceneId" to sceneId)

        val submitHeaders = headers.toMutableMap().apply {
            this["Accept"] = "text/plain, */*; q=0.01"
            this["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
            this["Origin"] = "https://$domain"
            this["X-Requested-With"] = "XMLHttpRequest"
        }

        state.updateThreadStatus(threadName, "提交问卷", running = true)
        val resp = http.postForm(
            url = submitUrl,
            queryParams = params,
            formFields = form,
            headers = submitHeaders,
            timeoutSeconds = 30,
            proxyAddress = proxyAddress,
        )
        val result = WjxSubmitCodec.classifyResponse(resp.body)
        if (result is SubmitResult.Success) {
            state.commitPendingDistributionFor(answerCtx)
        } else {
            state.discardPendingDistribution(threadName)
        }
        return result
    }
}

private fun ExecutionState.commitPendingDistributionFor(ctx: AnswerContext) {
    commitPendingDistribution(ctx.threadName)
}
