package com.homebudget.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homebudget.core.common.Money
import com.homebudget.domain.model.MonthTotals
import com.homebudget.presentation.theme.Numeric
import com.homebudget.presentation.theme.expenseColor
import com.homebudget.presentation.theme.incomeColor
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

val MonthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
val ShortMonthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)
val DayFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

object CategoryIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "home" to Icons.Filled.Home, "grocery" to Icons.Filled.LocalGroceryStore, "bolt" to Icons.Filled.Bolt,
        "fire" to Icons.Filled.LocalFireDepartment, "water" to Icons.Filled.WaterDrop, "wifi" to Icons.Filled.Wifi,
        "medical" to Icons.Filled.LocalHospital, "school" to Icons.Filled.School, "bus" to Icons.Filled.DirectionsBus,
        "shopping" to Icons.Filled.ShoppingBag, "movie" to Icons.Filled.Movie, "restaurant" to Icons.Filled.Restaurant,
        "phone" to Icons.Filled.PhoneAndroid, "gift" to Icons.Filled.CardGiftcard, "travel" to Icons.Filled.Flight,
        "child" to Icons.Filled.ChildCare, "pets" to Icons.Filled.Pets, "build" to Icons.Filled.Build,
        "salary" to Icons.Filled.Payments, "business" to Icons.Filled.Storefront, "laptop" to Icons.Filled.Laptop,
        "key" to Icons.Filled.Key, "savings" to Icons.Filled.Savings, "more" to Icons.Filled.MoreHoriz,
    )
    fun of(key: String?): ImageVector = all[key] ?: Icons.Filled.Category
    val colors = listOf("#43A047", "#00897B", "#039BE5", "#3949AB", "#5E35B1", "#8E24AA", "#D81B60", "#E53935",
        "#F4511E", "#FB8C00", "#FBC02D", "#8D6E63", "#546E7A", "#757575", "#1B5E52", "#2E7D32")
}

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

@Composable
fun CategoryBadge(iconKey: String?, colorHex: String?, size: Dp = 40.dp, label: String? = null) {
    val c = colorHex?.let { parseColor(it) } ?: MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(CategoryIcons.of(iconKey), contentDescription = label, tint = c, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun MoneyText(
    minor: Long,
    currency: String = "BDT",
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    showSign: Boolean = false,
) {
    Text(Money.format(minor, currency, showSign), modifier, style = style.merge(Numeric), color = color, maxLines = 1)
}

@Composable
fun MonthSelector(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month") }
        Text(month.format(MonthFmt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { contentDescription = "Selected month ${month.format(MonthFmt)}" })
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month") }
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, title: String? = null, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null || action != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    action?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        value()
    }
}

@Composable
fun SettingsRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Budget utilization bar; turns red past 100%. */
@Composable
fun BudgetBar(basisPoints: Int?, modifier: Modifier = Modifier) {
    val fraction = ((basisPoints ?: 0) / 10_000f).coerceIn(0f, 1f)
    val over = (basisPoints ?: 0) > 10_000
    val warn = (basisPoints ?: 0) >= 8_000
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = when {
        over -> expenseColor()
        warn -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Canvas(modifier.fillMaxWidth().height(10.dp).semantics { contentDescription = "Budget used ${(basisPoints ?: 0) / 100} percent" }) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

data class DonutSlice(val label: String, val value: Long, val color: Color)

@Composable
fun DonutChart(slices: List<DonutSlice>, modifier: Modifier = Modifier, center: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val gapDeg = if (slices.size > 1) 1.5f else 0f
    Box(modifier.semantics { contentDescription = "Category chart: " + slices.joinToString { "${it.label} ${it.value * 100 / total} percent" } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val stroke = size.minDimension * 0.14f
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            if (slices.isEmpty()) drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
            var start = -90f
            for (s in slices) {
                val sweep = 360f * s.value / total
                drawArc(s.color, start + gapDeg / 2, (sweep - gapDeg).coerceAtLeast(0.5f), false, tl, Size(d, d), style = Stroke(stroke))
                start += sweep
            }
        }
        center()
    }
}

/** Paired income/expense bars per month. */
@Composable
fun IncomeExpenseBars(series: List<MonthTotals>, modifier: Modifier = Modifier) {
    val inc = incomeColor()
    val exp = expenseColor()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = series.maxOfOrNull { maxOf(it.incomeMinor, it.expenseMinor) }?.coerceAtLeast(1) ?: 1
    Column(modifier.semantics {
        contentDescription = "Income versus expense: " + series.joinToString { "${it.month.format(ShortMonthFmt)} income ${Money.format(it.incomeMinor)} expense ${Money.format(it.expenseMinor)}" }
    }) {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = series.size.coerceAtLeast(1)
            val groupW = size.width / n
            val barW = (groupW * 0.28f).coerceAtMost(28f)
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            series.forEachIndexed { i, m ->
                val cx = groupW * i + groupW / 2
                val hi = size.height * m.incomeMinor / max
                val he = size.height * m.expenseMinor / max
                drawRoundRect(inc, Offset(cx - barW - 2f, size.height - hi), Size(barW, hi), CornerRadius(6f))
                drawRoundRect(exp, Offset(cx + 2f, size.height - he), Size(barW, he), CornerRadius(6f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            series.forEach { Text(it.month.format(ShortMonthFmt), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(inc); Text(" Income   ", style = MaterialTheme.typography.labelMedium)
            LegendDot(exp); Text(" Expense", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun LegendDot(color: Color) = Box(Modifier.size(10.dp).clip(CircleShape).background(color))

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.clip(RoundedCornerShape(50)).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.SemiBold,
    )
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
val ListSpacing = Arrangement.spacedBy(12.dp)
