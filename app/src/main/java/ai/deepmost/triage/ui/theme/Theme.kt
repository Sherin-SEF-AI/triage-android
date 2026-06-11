package ai.deepmost.triage.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Operational Materialism" — matte near-black surfaces, ONE earned accent (used only for
 * NEW-damage, capture-accept flash and active states), monospace numerics, hairline dividers,
 * dense layouts, instant (no-easing) transitions.
 */

val TriageAccent = Color(0xFFFF4D2E)        // the single earned accent
val SurfaceBlack = Color(0xFF0A0A0B)
val SurfaceRaised = Color(0xFF141416)
val SurfaceRaised2 = Color(0xFF1C1C1F)
val Hairline = Color(0xFF2A2A2E)
val TextPrimary = Color(0xFFE6E6E8)
val TextSecondary = Color(0xFF9A9AA0)
val OkGreen = Color(0xFF3FB36B)
val WarnAmber = Color(0xFFC9A227)

private val TriageColors = darkColorScheme(
    primary = TriageAccent,
    onPrimary = Color.White,
    secondary = TextSecondary,
    background = SurfaceBlack,
    onBackground = TextPrimary,
    surface = SurfaceRaised,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised2,
    onSurfaceVariant = TextSecondary,
    outline = Hairline,
    error = TriageAccent
)

private val Mono = FontFamily.Monospace

/** Numeric readouts are monospace throughout (per the design language). */
private val TriageTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp),
    headlineMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp)
)

private val TriageShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp)
)

@Composable
fun TriageTheme(content: @Composable () -> Unit) {
    // The app is intentionally always dark (matte near-black), independent of system setting.
    MaterialTheme(
        colorScheme = TriageColors,
        typography = TriageTypography,
        shapes = TriageShapes,
        content = content
    )
}
