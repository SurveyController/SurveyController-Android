package com.surveycontroller.android.core.persona

import com.surveycontroller.android.core.questions.PersonaBoost
import kotlin.random.Random

/**
 * 虚拟人物画像。1:1 复刻 software/core/persona/generator.py + context.py。
 * 每份问卷生成一个逻辑自洽的画像，并据此对匹配选项加权（x3）。
 */
data class Persona(
    val gender: String = "",
    val ageGroup: String = "",
    val education: String = "",
    val occupation: String = "",
    val incomeLevel: String = "",
    val maritalStatus: String = "",
    val hasChildren: Boolean = false,
    val satisfactionTendency: Double = 0.5,
) {
    fun keywordMap(): Map<String, List<String>> {
        val m = HashMap<String, List<String>>()
        if (gender.isNotEmpty()) m["gender"] =
            if (gender == "男") listOf("男", "男性", "先生", "男生") else listOf("女", "女性", "女士", "女生")
        if (ageGroup.isNotEmpty()) m["age_group"] = when (ageGroup) {
            "18-25" -> listOf("18", "19", "20", "21", "22", "23", "24", "25", "18-25", "18~25", "18岁", "20岁", "大学", "青年")
            "26-35" -> listOf("26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "26-35", "26~35", "30岁", "青年", "中青年")
            "36-45" -> listOf("36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "36-45", "36~45", "40岁", "中年")
            "46-60" -> listOf("46", "47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "46-60", "46~60", "50岁", "中年", "中老年")
            else -> emptyList()
        }
        if (education.isNotEmpty()) m["education"] = when (education) {
            "高中及以下" -> listOf("高中", "初中", "中专", "职高", "小学", "高中及以下", "高中以下", "中学")
            "大专" -> listOf("大专", "专科", "高职")
            "本科" -> listOf("本科", "大学", "学士", "大学本科")
            "研究生及以上" -> listOf("研究生", "硕士", "博士", "博士后", "研究生及以上", "硕士及以上")
            else -> emptyList()
        }
        if (occupation.isNotEmpty()) m["occupation"] = when (occupation) {
            "学生" -> listOf("学生", "在校", "在读", "校园")
            "上班族" -> listOf("上班", "在职", "企业", "公司", "职员", "白领", "员工", "工作", "在职人员")
            "自由职业" -> listOf("自由职业", "自由", "个体", "创业", "自营", "个体户", "自由职业者")
            "退休" -> listOf("退休", "离退休", "退休人员")
            else -> emptyList()
        }
        if (incomeLevel.isNotEmpty()) m["income_level"] = when (incomeLevel) {
            "低" -> listOf("3000以下", "3000元以下", "5000以下", "5000元以下", "低收入", "无收入", "2000以下")
            "中" -> listOf("5000-10000", "5000~10000", "5001-10000", "10000-20000", "10000~20000", "万元", "中等收入", "1万", "一万")
            "高" -> listOf("20000以上", "20000元以上", "2万以上", "3万以上", "50000以上", "高收入", "5万")
            else -> emptyList()
        }
        if (maritalStatus.isNotEmpty()) m["marital_status"] =
            if (maritalStatus == "未婚") listOf("未婚", "单身", "恋爱", "未婚/单身") else listOf("已婚", "已婚已育", "已婚未育", "结婚")
        m[if (hasChildren) "has_children" else "no_children"] =
            if (hasChildren) listOf("有孩子", "有子女", "已育", "有小孩") else listOf("无子女", "无孩子", "未育", "没有孩子", "没有小孩")
        return m
    }

    fun toDescription(): String {
        val parts = mutableListOf<String>()
        if (gender.isNotEmpty()) parts.add("${gender}性")
        if (ageGroup.isNotEmpty()) parts.add("${ageGroup}岁")
        if (education.isNotEmpty()) parts.add("学历$education")
        if (occupation.isNotEmpty()) parts.add(occupation)
        if (incomeLevel.isNotEmpty()) parts.add(mapOf("低" to "收入较低", "中" to "收入中等", "高" to "收入较高")[incomeLevel] ?: "")
        if (maritalStatus.isNotEmpty()) parts.add(maritalStatus)
        if (hasChildren) parts.add("有孩子")
        return if (parts.isEmpty()) "一名普通用户" else parts.filter { it.isNotEmpty() }.joinToString("、")
    }

    companion object {
        private fun weightedChoice(options: List<String>, weights: List<Int>, random: Random): String {
            val total = weights.sum()
            var pivot = random.nextInt(total)
            for (i in options.indices) {
                pivot -= weights[i]
                if (pivot < 0) return options[i]
            }
            return options.last()
        }

        /** 随机生成逻辑自洽的画像。复刻 generate_persona。 */
        fun generate(random: Random = Random.Default): Persona {
            val gender = if (random.nextBoolean()) "男" else "女"
            val ageGroup = weightedChoice(listOf("18-25", "26-35", "36-45", "46-60"), listOf(35, 35, 20, 10), random)
            val education = when (ageGroup) {
                "18-25" -> weightedChoice(listOf("高中及以下", "大专", "本科", "研究生及以上"), listOf(15, 20, 50, 15), random)
                "26-35", "36-45" -> weightedChoice(listOf("高中及以下", "大专", "本科", "研究生及以上"), listOf(10, 20, 45, 25), random)
                else -> weightedChoice(listOf("高中及以下", "大专", "本科", "研究生及以上"), listOf(25, 25, 35, 15), random)
            }
            val occupation = when (ageGroup) {
                "18-25" -> weightedChoice(listOf("学生", "上班族", "自由职业"), listOf(55, 35, 10), random)
                "46-60" -> weightedChoice(listOf("上班族", "自由职业", "退休"), listOf(50, 25, 25), random)
                else -> weightedChoice(listOf("上班族", "自由职业"), listOf(75, 25), random)
            }
            val income = when {
                occupation == "学生" -> weightedChoice(listOf("低", "中"), listOf(85, 15), random)
                occupation == "退休" -> weightedChoice(listOf("低", "中", "高"), listOf(30, 50, 20), random)
                ageGroup == "36-45" || ageGroup == "46-60" -> weightedChoice(listOf("低", "中", "高"), listOf(15, 45, 40), random)
                ageGroup == "26-35" -> weightedChoice(listOf("低", "中", "高"), listOf(20, 50, 30), random)
                else -> weightedChoice(listOf("低", "中", "高"), listOf(40, 45, 15), random)
            }
            val marital = when (ageGroup) {
                "18-25" -> weightedChoice(listOf("未婚", "已婚"), listOf(90, 10), random)
                "26-35" -> weightedChoice(listOf("未婚", "已婚"), listOf(45, 55), random)
                "36-45" -> weightedChoice(listOf("未婚", "已婚"), listOf(15, 85), random)
                else -> weightedChoice(listOf("未婚", "已婚"), listOf(10, 90), random)
            }
            val hasChildren = when {
                marital == "未婚" -> random.nextDouble() < 0.03
                ageGroup == "36-45" || ageGroup == "46-60" -> random.nextDouble() < 0.90
                ageGroup == "26-35" -> random.nextDouble() < 0.50
                else -> random.nextDouble() < 0.10
            }
            val satisfaction = gaussian(0.6, 0.15, random).coerceIn(0.1, 0.9)
            return Persona(gender, ageGroup, education, occupation, income, marital, hasChildren, satisfaction)
        }

        private fun gaussian(mean: Double, sd: Double, random: Random): Double {
            var u = 0.0; var v = 0.0
            while (u == 0.0) u = random.nextDouble()
            while (v == 0.0) v = random.nextDouble()
            return mean + sd * kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
        }
    }
}

/** 基于某个画像的权重加成实现。复刻 apply_persona_boost（匹配关键词的选项 x3）。 */
class PersonaBoostImpl(private val persona: Persona) : PersonaBoost {
    private val boostFactor = 3.0

    override fun boost(optionTexts: List<String>, probabilities: List<Double>): List<Double> {
        val keywords = persona.keywordMap().values.flatten()
        if (keywords.isEmpty()) return probabilities
        val boosted = probabilities.toMutableList()
        for (i in optionTexts.indices) {
            if (i >= boosted.size) break
            val text = optionTexts[i].trim()
            if (text.isEmpty()) continue
            if (keywords.any { it in text }) boosted[i] = boosted[i] * boostFactor
        }
        return boosted
    }

    override fun gender(): String? = persona.gender.ifEmpty { null }
}
