package com.noho501.externalvideooutput

import org.junit.Assert.assertNotNull
import org.junit.Test

class ExternalVideoOutputTest {

    @Test
    fun sharedSingletonIsNotNull() {
        assertNotNull(ExternalVideoOutput.shared)
    }

    @Test
    fun sharedSingletonReturnsSameInstance() {
        val a = ExternalVideoOutput.shared
        val b = ExternalVideoOutput.shared
        assert(a === b) { "shared must return the same singleton instance" }
    }

    @Test
    fun initialStateIsDisconnected() {
        val output = ExternalVideoOutput.shared
        assert(!output.isConnected) { "isConnected should be false before start()" }
        assert(output.surface == null) { "surface should be null before start()" }
    }
}
