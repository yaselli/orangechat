package me.rerere.rikkahub.ui.components.charts

import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.rikkahub.ui.components.message.MessagePartBlock
import me.rerere.rikkahub.ui.components.message.chartSpecOf
import me.rerere.rikkahub.ui.components.message.groupMessageParts
import org.junit.Assert.*
import org.junit.Test

class ChartRenderingTest {
    private val valid = """{"style":"line","series":[{"values":[1,2]}]}"""
    private fun tool(input: String = valid, output: String = """{"success":true}""") = UIMessagePart.Tool(
        "call", "chart_display", input, listOf(UIMessagePart.Text(output)))

    @Test
    fun successfulChartStaysInOrderOutsideCollapsedToolTraceAndKeepsTrace() {
        val parts = listOf(UIMessagePart.Text("before"), tool(), UIMessagePart.Text("after"))
        val blocks = parts.groupMessageParts()
        assertTrue(blocks[0] is MessagePartBlock.ContentBlock)
        assertTrue(blocks[1] is MessagePartBlock.ThinkingBlock)
        assertTrue(blocks[2] is MessagePartBlock.ChartBlock)
        assertTrue(blocks[3] is MessagePartBlock.ContentBlock)
        assertEquals(listOf(1.0, 2.0), (blocks[2] as MessagePartBlock.ChartBlock).spec.series.single().values)
    }

    @Test
    fun unexecutedFailedDeniedMalformedAndNamespacedCallsDoNotRenderCharts() {
        assertNull(chartSpecOf(tool().copy(output = emptyList())))
        assertNull(chartSpecOf(tool(output = """{"error":"INVALID_CHART"}""")))
        assertNull(chartSpecOf(tool(input = """{"style":"line","series":[{"values":[]}]}""")))
        assertNull(chartSpecOf(tool().copy(approvalState = ToolApprovalState.Denied("no"))))
        assertNull(chartSpecOf(tool().copy(toolName = "mcp_12345678_chart_display")))
        assertNull(chartSpecOf(tool(output = "not json")))
    }

    @Test
    fun scalesSupportNegativeConstantsLogAndLargeOutOfRangeValuesWithoutUnboundedTicks() {
        val negative = buildAxisScale(listOf(-5.0, 5.0), null, null, false, true)
        assertTrue(negative.min <= -5 && negative.max >= 5)
        val constant = buildAxisScale(listOf(10.0, 10.0), null, null, false, false)
        assertTrue(constant.min < constant.max)
        val log = buildAxisScale(listOf(1.0, 100.0), null, null, true, false)
        assertEquals(0.5f, log.fraction(10.0), 0.0001f)
        val tight = buildAxisScale(listOf(1e100), 1e-100, 2e-100, false, false)
        assertTrue(tight.fraction(1e100).isFinite())
        assertTrue(tight.ticks.size <= 100)
    }
    @Test
    fun singleUserBoundRemainsAuthoritativeWhenAllValuesAreClipped() {
        val upper = buildAxisScale(listOf(50.0), null, 10.0, false, false)
        assertEquals(10.0, upper.max, 0.0)
        assertTrue(upper.min < upper.max)
        val lower = buildAxisScale(listOf(-50.0), 10.0, null, false, false)
        assertEquals(10.0, lower.min, 0.0)
        assertTrue(lower.min < lower.max)
        val logUpper = buildAxisScale(listOf(1000.0), null, 10.0, true, false)
        assertEquals(10.0, logUpper.max, 0.00001)
        assertTrue(logUpper.min < logUpper.max)
    }

}
