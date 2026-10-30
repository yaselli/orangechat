package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class ChartDisplayToolTest {
    private fun json(text: String) = JsonInstant.parseToJsonElement(text)
    private fun error(text: String) = validateChartArgs(json(text) as JsonObject)

    @Test
    fun acceptsAllThreeChartShapesAndRejectsMixedDataOrMismatchedLabels() {
        assertNull(error("""{"style":"line","x_axis":{"data":["a","b"]},"series":[{"values":[1,2]}]}"""))
        assertNull(error("""{"style":"bar","series":[{"values":[-2,0,3]}]}"""))
        assertNull(error("""{"style":"scatter","series":[{"points":[{"x":1,"y":2}]}]}"""))
        assertNotNull(error("""{"style":"line","series":[{"points":[{"x":1,"y":2}]}]}"""))
        assertNotNull(error("""{"style":"bar","x_axis":{"data":["a"]},"series":[{"values":[1,2]}]}"""))
        assertNotNull(error("""{"style":"scatter","series":[{"points":[],"values":[1]}]}"""))
    }

    @Test
    fun rejectsEmptySeriesWrongTypesNonfiniteNumbersAndExcessivePointCounts() {
        for (values in listOf("[]", "[null]", "[\"3\"]", "[true]", "[1e309]", "[1e101]")) {
            assertNotNull(values, error("""{"style":"line","series":[{"values":$values}]}"""))
        }
        val tooMany = buildJsonObject {
            put("style", "line")
            put("series", buildJsonArray { add(buildJsonObject {
                put("values", buildJsonArray { repeat(2001) { add(JsonPrimitive(it)) } })
            }) })
        }
        assertNotNull(validateChartArgs(tooMany))
    }

    @Test
    fun validatesLogCoordinatesAndCategoricalAndBarAxisConstraints() {
        assertNotNull(error("""{"style":"line","x_axis":{"scale":"log"},"series":[{"values":[1]}]}"""))
        assertNotNull(error("""{"style":"bar","y_axis":{"min":1},"series":[{"values":[1]}]}"""))
        assertNotNull(error("""{"style":"scatter","x_axis":{"scale":"log"},"series":[{"points":[{"x":0,"y":2}]}]}"""))
        assertNotNull(error("""{"style":"line","y_axis":{"min":3,"max":2},"series":[{"values":[1]}]}"""))
        assertNotNull(error("""{"style":"line","y_axis":{"min":"1"},"series":[{"values":[1]}]}"""))
        assertNull(error("""{"style":"line","y_axis":{"scale":"log"},"series":[{"values":[1,10,100]}]}"""))
    }

    @Test
    fun executionReturnsAnErrorPayloadRatherThanClaimingInvalidChartsWereDisplayed() = runBlocking {
        val tool = buildChartDisplayTool()
        for (input in listOf("[]", "null", "{\"style\":\"pie\"}", "{\"style\":\"bar\",\"series\":[]}")) {
            val output = tool.execute(json(input)).single() as UIMessagePart.Text
            assertTrue(output.text.contains("INVALID_CHART"))
            assertFalse(output.text.contains("\"success\":true"))
        }
        val output = tool.execute(json("""{"style":"bar","series":[{"values":[1]}]}""")).single() as UIMessagePart.Text
        assertTrue(output.text.contains("\"success\":true"))
    }
}
