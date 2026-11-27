package com.surveycontroller.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val baseline = Typography()

val AppTypography = baseline.copy(
    displaySmall = baseline.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    headlineLarge = baseline.headlineLarge.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold),
    headlineMedium = baseline.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = baseline.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = baseline.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = baseline.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    labelLarge = baseline.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    bodyMedium = baseline.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp),
)
