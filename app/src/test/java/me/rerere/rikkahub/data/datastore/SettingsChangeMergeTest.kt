package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsChangeMergeTest {
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)

    private fun verify(before: String, desired: String, current: String, expected: String) {
        assertEquals(json(expected), mergeSettingChanges(json(before), json(desired), json(current)))
    }

    @Test
    fun queuedSwitchChangesPreservePreviouslySavedSwitch() {
        verify(
            """{"tools":{"time":false,"battery":false}}""",
            """{"tools":{"time":false,"battery":true}}""",
            """{"tools":{"time":true,"battery":false}}""",
            """{"tools":{"time":true,"battery":true}}""",
        )
    }

    @Test
    fun explicitDisableStillWorks() {
        verify(
            """{"tools":{"time":true,"battery":false}}""",
            """{"tools":{"time":false,"battery":false}}""",
            """{"tools":{"time":true,"battery":true}}""",
            """{"tools":{"time":false,"battery":true}}""",
        )
    }

    @Test
    fun backgroundMcpUpdateSurvivesSwitchSave() {
        verify(
            """{"tools":{"time":false},"mcp":["old"]}""",
            """{"tools":{"time":true},"mcp":["old"]}""",
            """{"tools":{"time":false},"mcp":["new"],"other":1}""",
            """{"tools":{"time":true},"mcp":["new"],"other":1}""",
        )
    }

    @Test
    fun unchangedStaleSnapshotCannotUndoSavedState() {
        verify("""{"enabled":false}""", """{"enabled":false}""",
            """{"enabled":true}""", """{"enabled":true}""")
    }

    @Test
    fun explicitNullAndRemovalArePreserved() {
        verify("""{"a":1,"b":2}""", """{"a":null}""",
            """{"a":1,"b":2,"c":3}""", """{"a":null,"c":3}""")
    }

    @Test
    fun changedListIsReplacedWithoutTouchingOtherFields() {
        verify("""{"list":[1],"enabled":false}""", """{"list":[],"enabled":false}""",
            """{"list":[1],"enabled":true}""", """{"list":[],"enabled":true}""")
    }

    @Test
    fun newNestedKeyKeepsConcurrentKeys() {
        verify("""{"tools":{}}""", """{"tools":{"a":true}}""",
            """{"tools":{"b":true}}""", """{"tools":{"a":true,"b":true}}""")
    }
}
