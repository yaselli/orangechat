package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceNotificationIdsTest {
    @Test fun allFixedServiceAndErrorNotificationsHaveDistinctNonzeroIds() {
        val ids = ServiceNotificationIds::class.java.declaredFields
            .filter { it.type == Int::class.javaPrimitiveType }
            .map { it.getInt(null) }
        assertTrue(ids.isNotEmpty())
        assertTrue(ids.all { it > 0 })
        assertEquals(ids.size, ids.toSet().size)
    }
}
