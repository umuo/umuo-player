package app.umuo.mobileui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 手机视觉独立于 TV 焦点主题，避免调整手机时改变遥控交互的对比度。 */
internal object MobileStyle {
    val Background = Color(0xFF0D0F12)
    val Surface = Color(0xFF191C21)
    val Elevated = Color(0xFF24282E)
    val Accent = Color(0xFFE6B66F)
    val Text = Color(0xFFF1EEE8)
    val Muted = Color(0xFF969AA3)
    val Border = Color(0xFF2B2E34)
}

@Composable internal fun MobileCinemaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MobileStyle.Accent, onPrimary = MobileStyle.Background,
            background = MobileStyle.Background, surface = MobileStyle.Surface,
            onSurface = MobileStyle.Text, onBackground = MobileStyle.Text,
            secondary = MobileStyle.Accent, secondaryContainer = MobileStyle.Elevated,
            onSecondaryContainer = MobileStyle.Accent, surfaceVariant = MobileStyle.Elevated,
            onSurfaceVariant = MobileStyle.Muted, outline = MobileStyle.Border,
            outlineVariant = MobileStyle.Border, primaryContainer = MobileStyle.Elevated,
            onPrimaryContainer = MobileStyle.Accent, surfaceTint = Color.Transparent,
            surfaceDim = MobileStyle.Background, surfaceBright = MobileStyle.Elevated,
            surfaceContainerLowest = MobileStyle.Background, surfaceContainerLow = MobileStyle.Surface,
            surfaceContainer = MobileStyle.Surface, surfaceContainerHigh = MobileStyle.Elevated,
            surfaceContainerHighest = MobileStyle.Elevated
        ),
        shapes = Shapes(
            small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(16.dp)
        ),
        content = content
    )
}

@Composable internal fun MobileBottomNavigation(current: String, onNavigate: (String) -> Unit) {
    Column(Modifier.background(MobileStyle.Background).navigationBarsPadding()) {
        HorizontalDivider(color = MobileStyle.Border.copy(alpha = .6f), thickness = .5.dp)
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            listOf("媒体库", "设置").forEach { route ->
                val active = current == route
                Column(
                    Modifier.weight(1f).fillMaxHeight().semantics { selected = active }
                        .clickable(role = Role.Tab) { onNavigate(route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (route == "媒体库") Icons.Rounded.VideoLibrary else Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = if (active) MobileStyle.Accent else MobileStyle.Muted,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        route, color = if (active) MobileStyle.Accent else MobileStyle.Muted,
                        fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
