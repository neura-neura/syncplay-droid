package dev.neura.syncplay.ui

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerFutureLifecycleTest {
    @Test
    fun completionAfterClearIsReleasedAndCannotAttach() {
        val released = mutableListOf<ListenableFuture<String>>()
        val lifecycle = ControllerFutureLifecycle<String> { released += it }
        val future = SettableFuture.create<String>()
        val generation = lifecycle.register(future)
        assertNotNull(generation)

        lifecycle.clear()
        future.set("controller")

        var attached = false
        assertFalse(
            lifecycle.withCurrent(future, generation!!, "controller") {
                attached = true
            },
        )
        assertFalse(attached)
        assertEquals(listOf(future), released)
    }

    @Test
    fun replacingFutureReleasesOldAndKeepsOnlyNewestCompletion() {
        val released = mutableListOf<ListenableFuture<String>>()
        val lifecycle = ControllerFutureLifecycle<String> { released += it }
        val first = SettableFuture.create<String>()
        val second = SettableFuture.create<String>()
        val firstGeneration = lifecycle.register(first)!!
        val secondGeneration = lifecycle.register(second)!!

        var attached = ""
        assertFalse(lifecycle.withCurrent(first, firstGeneration, "old") { attached = it })
        assertTrue(lifecycle.withCurrent(second, secondGeneration, "new") { attached = it })

        assertEquals("new", attached)
        assertEquals(listOf(first), released)
    }
}
