package com.soniccontrol.core.devices

import com.soniccontrol.core.devices.ConnectionCoordinator.Breaker
import com.soniccontrol.core.devices.ConnectionCoordinator.Decision
import com.soniccontrol.core.devices.ConnectionCoordinator.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 010: one gate for automatic connection attempts — budget per hour and a circuit breaker with event-driven recovery. */
class ConnectionCoordinatorTest {
    private var now = 0L
    private val c = ConnectionCoordinator({ now })

    @Test fun hourlyBudgetBoundsOpensAndRefillsAsTheWindowSlides() {
        var allowed = 0
        repeat(100) { if (c.tryAcquire(Kind.RFCOMM) == Decision.Allowed) { allowed++; c.reportSuccess(Kind.RFCOMM) }; now += 10_000 }
        // 100 tries in 1000 s: never more than 40 in any rolling hour
        assertEquals(40, allowed)
        now += ConnectionCoordinator.HOUR
        assertEquals(Decision.Allowed, c.tryAcquire(Kind.RFCOMM))
        assertTrue(c.snapshot.value.kinds.getValue(Kind.RFCOMM).opensLastHour <= 40)
    }

    @Test fun consecutiveFailuresOpenTheCircuitAndTheCooldownAllowsOneTrial() {
        repeat(5) { assertEquals(Decision.Allowed, c.tryAcquire(Kind.GATT)); c.reportFailure(Kind.GATT); now += 1_000 }
        assertEquals(Breaker.OPEN, c.breaker(Kind.GATT))
        assertTrue(c.tryAcquire(Kind.GATT) is Decision.Denied)
        now += 10 * 60_000
        assertEquals(Decision.Allowed, c.tryAcquire(Kind.GATT))          // half-open: one trial
        c.reportFailure(Kind.GATT)
        assertEquals("a failed trial re-opens at once", Breaker.OPEN, c.breaker(Kind.GATT))
        assertTrue(c.tryAcquire(Kind.GATT) is Decision.Denied)
    }

    @Test fun aRelevantEventGivesAnImmediateTrialAndSuccessClosesTheCircuit() {
        repeat(5) { c.tryAcquire(Kind.GATT); c.reportFailure(Kind.GATT) }
        assertEquals(Breaker.OPEN, c.breaker(Kind.GATT))
        val wake = c.wake.value
        c.onRelevantEvent("audio connected")
        assertTrue(c.wake.value != wake)
        assertEquals(Breaker.HALF_OPEN, c.breaker(Kind.GATT))
        assertEquals(Decision.Allowed, c.tryAcquire(Kind.GATT))
        c.reportSuccess(Kind.GATT)
        assertEquals(Breaker.CLOSED, c.breaker(Kind.GATT))
        assertEquals("audio connected", c.snapshot.value.lastWakeReason)
    }

    @Test fun kindsAreIndependent() {
        repeat(5) { c.tryAcquire(Kind.GATT); c.reportFailure(Kind.GATT) }
        assertEquals(Decision.Allowed, c.tryAcquire(Kind.RFCOMM))
        assertEquals(Breaker.CLOSED, c.breaker(Kind.RFCOMM))
    }

    @Test fun l2capOpensItsCircuitAfterThreeRefusals() {
        repeat(3) { c.tryAcquire(Kind.L2CAP); c.reportFailure(Kind.L2CAP) }
        assertTrue(c.tryAcquire(Kind.L2CAP) is Decision.Denied)
    }
}
