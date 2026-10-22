package com.surveycontroller.android.app

/** 版本与 GitHub 仓库常量。对标桌面端 software/app/version.py。 */
object AppVersion {
    const val VERSION = "1.0.1"
    const val GITHUB_OWNER = "shiaho777"
    const val GITHUB_REPO = "SurveyController-Android"

    const val LATEST_RELEASE_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases"
    const val ISSUE_FEEDBACK = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/issues/new"
    const val DOC_SITE = "https://surveydoc.hungrym0.com/"

    /** 语义版本比较：a>b 返回正、相等 0、a<b 负。忽略前导 v 与非数字后缀。 */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V")
            .split(".").map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
