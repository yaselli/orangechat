package me.rerere.rikkahub.ui.components.charts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import me.rerere.rikkahub.ui.theme.MaterialSurface as Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R

private class ChartTableColumn(
    val title: String,
    val numeric: Boolean,
    val color: Color? = null,
)

private class ChartTableData(
    val columns: List<ChartTableColumn>,
    val rows: List<List<String>>,
)

private fun ChartSpec.buildTableData(
    colors: List<Color>,
    seriesNames: List<String>,
    seriesHeader: String,
): ChartTableData = when (style) {
    ChartStyle.Scatter -> ChartTableData(
        columns = listOf(
            ChartTableColumn(seriesHeader, numeric = false),
            ChartTableColumn(xAxis.title ?: "x", numeric = true),
            ChartTableColumn(yAxis.title ?: "y", numeric = true),
        ),
        rows = series.flatMapIndexed { index, s ->
            val name = seriesNames[index]
            s.points.map { point ->
                listOf(name, formatChartNumber(point.x, xAxis.format), formatChartNumber(point.y, yAxis.format))
            }
        },
    )

    else -> ChartTableData(
        columns = buildList {
            add(ChartTableColumn(xAxis.title.orEmpty(), numeric = false))
            series.indices.forEach { index ->
                add(ChartTableColumn(seriesNames[index], numeric = true, color = colors[index]))
            }
        },
        rows = (0 until categoryCount).map { index ->
            buildList {
                add(categoryLabel(index))
                series.forEach { s ->
                    add(s.values.getOrNull(index)?.let { formatChartNumber(it, yAxis.format) } ?: "-")
                }
            }
        },
    )
}

/** Only visible rows are composed, including charts with thousands of scatter points. */
@Composable
internal fun ChartTable(
    spec: ChartSpec,
    colors: List<Color>,
    seriesNames: List<String>,
    modifier: Modifier = Modifier,
) {
    val seriesHeader = stringResource(R.string.chart_series)
    val table = remember(spec, colors, seriesNames, seriesHeader) {
        spec.buildTableData(colors, seriesNames, seriesHeader)
    }
    Box(modifier.height(320.dp).horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width((table.columns.size * 120).dp)) {
            item(key = "header") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Row {
                        table.columns.forEach { column ->
                            Text(column.title, Modifier.width(120.dp).padding(8.dp),
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = column.color ?: MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            items(table.rows) { row ->
                Row {
                    row.forEachIndexed { index, text ->
                        Text(text, Modifier.width(120.dp).padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = if (table.columns[index].numeric) TextAlign.End else TextAlign.Start)
                    }
                }
            }
        }
    }
}
