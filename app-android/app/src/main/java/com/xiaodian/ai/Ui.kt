package com.xiaodian.ai

// 主题（字号整体加大）、状态颜色和几个通用小组件。
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BaseType = Typography()

private val BigTypography = Typography(
    displayLarge = BaseType.displayLarge,
    displayMedium = BaseType.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = BaseType.headlineLarge,
    headlineMedium = BaseType.headlineMedium,
    headlineSmall = BaseType.headlineSmall.copy(fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = BaseType.titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseType.titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = BaseType.bodyLarge.copy(fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = BaseType.bodyMedium.copy(fontSize = 17.sp, lineHeight = 24.sp),
    bodySmall = BaseType.bodySmall.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelLarge = BaseType.labelLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
    labelMedium = BaseType.labelMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = BaseType.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
)

private val AppColors = lightColorScheme(
    primary = Color(0xFF8A4B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2E1500),
    secondary = Color(0xFF2E6A3F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB4F1BC),
    onSecondaryContainer = Color(0xFF00210B),
    tertiary = Color(0xFF00629E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD0E4FF),
    onTertiaryContainer = Color(0xFF001D34),
)

@Composable
fun XiaodianTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = BigTypography, content = content)
}

/** 状态底色：待付款灰、已下单橙、制作中蓝、待取/配送绿、取消红。 */
@Composable
fun statusContainerColor(status: String): Color = when (status) {
    "pending_payment" -> MaterialTheme.colorScheme.surfaceVariant
    "ordered" -> MaterialTheme.colorScheme.primaryContainer
    "making" -> MaterialTheme.colorScheme.tertiaryContainer
    "ready", "awaiting_pickup", "delivering" -> MaterialTheme.colorScheme.secondaryContainer
    "cancelled" -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
fun statusContentColor(status: String): Color = when (status) {
    "pending_payment" -> MaterialTheme.colorScheme.onSurfaceVariant
    "ordered" -> MaterialTheme.colorScheme.onPrimaryContainer
    "making" -> MaterialTheme.colorScheme.onTertiaryContainer
    "ready", "awaiting_pickup", "delivering" -> MaterialTheme.colorScheme.onSecondaryContainer
    "cancelled" -> MaterialTheme.colorScheme.onErrorContainer
    else -> MaterialTheme.colorScheme.onSurface
}

/** 小标签（状态、自取/配送）。 */
@Composable
fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 危险操作的确认弹窗。 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("先不") } },
    )
}

fun shortCustomer(id: String): String = if (id.length > 8) id.take(8) + "…" else id
