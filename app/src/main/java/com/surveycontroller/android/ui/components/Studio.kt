package com.surveycontroller.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surveycontroller.android.ui.theme.ChromeDarkColors
import com.surveycontroller.android.ui.theme.LocalChromeDarkTheme

internal val distributionColors = listOf(
    Color(0xFF4D68C9), Color(0xFF8795C2), Color(0xFFB08C80), Color(0xFF748392),
    Color(0xFF8573A6), Color(0xFFB89F77), Color(0xFF55738C), Color(0xFF9D8B91),
)

@Composable
internal fun studioSuccessColor(): Color =
    if (LocalChromeDarkTheme.current) ChromeDarkColors.success else Color(0xFF237246)

@Composable
internal fun Modifier.studioFeature(): Modifier {
    val shape = MaterialTheme.shapes.extraLarge
    val surface = background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
    return if (LocalChromeDarkTheme.current) surface
    else surface.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
}

@Composable
internal fun StudioCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ),
    border: BorderStroke? = if (LocalChromeDarkTheme.current) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.large, colors = colors, border = border, content = content)
}

@Composable
internal fun StudioEmblem(icon: ImageVector) {
    val shape = RoundedCornerShape(13.dp)
    val chromeDark = LocalChromeDarkTheme.current
    val colors = MaterialTheme.colorScheme
    val background = if (chromeDark) ChromeDarkColors.emblem else colors.primaryContainer
    val foreground = if (chromeDark) ChromeDarkColors.emblemInk else colors.onPrimaryContainer
    Box(
        Modifier.size(42.dp).background(background, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = foreground,
            modifier = Modifier.size(21.dp))
    }
}

@Composable
internal fun StudioHeading(title: String, subtitle: String? = null, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) StudioEmblem(icon)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StudioTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val chromeDark = LocalChromeDarkTheme.current
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = actions,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (chromeDark) ChromeDarkColors.toolbar else MaterialTheme.colorScheme.background,
        ),
    )
}
