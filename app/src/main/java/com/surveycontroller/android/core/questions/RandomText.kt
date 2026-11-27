package com.surveycontroller.android.core.questions

import java.time.LocalDate
import kotlin.random.Random

/**
 * 随机文本与动态令牌解析。复刻 software/core/questions/utils.py 的随机生成函数。
 */
object RandomText {
    const val DEFAULT_FILL_TEXT = "已填写"
    const val RANDOM_INT_TOKEN_PREFIX = "__RANDOM_INT__:"

    private val surnames = listOf(
        "张", "王", "李", "赵", "陈", "杨", "刘", "黄", "周", "吴", "徐", "孙", "马", "朱", "胡", "林",
        "郭", "何", "高", "罗", "郑", "梁", "谢", "宋", "唐", "韩", "曹", "许", "邓", "冯",
    )
    private const val maleGiven = "伟俊涛强磊刚凯鹏鑫宇浩瑞博杰宁豪轩皓浩宇子豪思远家豪文博宇航志强明浩志伟文涛梓豪志鹏伟豪君豪承泽"
    private const val femaleGiven = "婷雅静怡欣萱琳玲芳颖慧敏雪晶莉倩蕾佳媛茜悦岚蓉瑶诗梦菲琪韵彤璐"
    private const val neutralGiven = "嘉明华建安晨泽文超洋"

    private val mobilePrefixes = listOf(
        "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
        "147", "150", "151", "152", "153", "155", "156", "157", "158", "159",
        "166", "171", "172", "173", "175", "176", "177", "178", "180", "181",
        "182", "183", "184", "185", "186", "187", "188", "189", "198", "199",
    )

    private val idCardChecksumWeights = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val idCardChecksumChars = "10X98765432"
    private val fallbackAreaCodes = listOf("110100", "310100", "440100", "330100", "510100")

    fun randomChineseName(gender: String? = null, random: Random = Random.Default): String {
        val surname = surnames.random(random)
        val len = if (random.nextDouble() < 0.65) 1 else 2
        val pool = when (gender) {
            "男" -> maleGiven + neutralGiven
            "女" -> femaleGiven + neutralGiven
            else -> maleGiven + femaleGiven + neutralGiven
        }
        val given = buildString { repeat(len) { append(pool.random(random)) } }
        return "$surname$given"
    }

    fun randomMobile(random: Random = Random.Default): String {
        val tail = buildString { repeat(8) { append(random.nextInt(0, 10)) } }
        return mobilePrefixes.random(random) + tail
    }

    fun randomIdCard(
        gender: String? = null,
        random: Random = Random.Default,
        referenceYear: Int? = null,
    ): String {
        val area = fallbackAreaCodes.random(random)
        val age = random.nextInt(18, 61)
        val birthYear = (referenceYear ?: LocalDate.now().year) - age
        val start = LocalDate.of(birthYear, 1, 1)
        val end = LocalDate.of(birthYear, 12, 31)
        val days = (start.until(end).days + (end.dayOfYear - start.dayOfYear)).coerceAtLeast(0)
        val birth = start.plusDays(random.nextLong(0, (end.toEpochDay() - start.toEpochDay() + 1)))
        val seqPrefix = random.nextInt(0, 100)
        val genderDigit = when (gender) {
            "男" -> listOf(1, 3, 5, 7, 9).random(random)
            "女" -> listOf(0, 2, 4, 6, 8).random(random)
            else -> random.nextInt(0, 10)
        }
        val first17 = "%s%04d%02d%02d%02d%d".format(
            area, birth.year, birth.monthValue, birth.dayOfMonth, seqPrefix, genderDigit,
        )
        return first17 + idCardChecksum(first17)
    }

    private fun idCardChecksum(first17: String): Char {
        var total = 0
        for (i in 0 until 17) total += (first17[i] - '0') * idCardChecksumWeights[i]
        return idCardChecksumChars[total % 11]
    }

    fun randomGenericText(random: Random = Random.Default): String {
        val samples = listOf("已填写", "同上", "无", "OK", "收到", "确认", "正常", "通过", "测试数据", "自动填写")
        return samples.random(random) + random.nextInt(10, 1000)
    }

    fun parseRandomIntToken(token: String?): Pair<Int, Int>? {
        val text = token?.trim() ?: return null
        if (!text.startsWith(RANDOM_INT_TOKEN_PREFIX)) return null
        val payload = text.removePrefix(RANDOM_INT_TOKEN_PREFIX)
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        val a = parts[0].trim().toIntOrNull() ?: return null
        val b = parts[1].trim().toIntOrNull() ?: return null
        return if (a <= b) a to b else b to a
    }

    /** 解析动态文本令牌。复刻 resolve_dynamic_text_token。 */
    fun resolveDynamicToken(
        token: String?,
        gender: String? = null,
        random: Random = Random.Default,
        referenceYear: Int? = null,
    ): String {
        if (token == null) return DEFAULT_FILL_TEXT
        val text = token.trim()
        parseRandomIntToken(text)?.let { (lo, hi) -> return random.nextInt(lo, hi + 1).toString() }
        return when (text) {
            "__RANDOM_NAME__" -> randomChineseName(gender, random)
            "__RANDOM_MOBILE__" -> randomMobile(random)
            "__RANDOM_ID_CARD__" -> randomIdCard(gender, random, referenceYear)
            "__RANDOM_TEXT__" -> randomGenericText(random)
            else -> text.ifEmpty { DEFAULT_FILL_TEXT }
        }
    }
}
