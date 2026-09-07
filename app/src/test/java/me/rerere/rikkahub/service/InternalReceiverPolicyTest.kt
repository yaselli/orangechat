package me.rerere.rikkahub.service

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class InternalReceiverPolicyTest {
    @Test fun internalAlarmAndWorkflowReceiversAreNotExternallyCallable() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = manifest.getElementsByTagName("receiver")
        val expected = setOf(
            ".data.service.ProactiveMessageReceiver",
            ".data.service.DailySummaryReceiver",
            ".workflow.trigger.WorkflowBootReceiver",
            ".service.KeepAliveRestartReceiver",
        )
        val found = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as org.w3c.dom.Element
            val ns = "http://schemas.android.com/apk/res/android"
            val name = node.getAttributeNS(ns, "name")
            if (name in expected) {
                found += name
                assertEquals(name, "false", node.getAttributeNS(ns, "exported"))
            }
        }
        assertEquals(expected, found)
    }
}
