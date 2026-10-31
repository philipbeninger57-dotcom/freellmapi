package de.kyrosmp.anticheat.flag;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlagThrottleHardeningTest {
    @Test void clockRollbackDoesNotSuppressDetectionUntilOldTimeCatchesUp() {
        FlagThrottle throttle = new FlagThrottle(40, 1200);
        UUID id = UUID.randomUUID();
        assertFalse(throttle.isDuplicate(id, "Fly A", 10_000));
        assertEquals(FlagThrottle.Action.STORE,
                throttle.register(id,"Fly A",10_000,Confidence.LOW,"before").action());
        assertFalse(throttle.isDuplicate(id, "Fly A", 5_000));
        var after = throttle.register(id,"Fly A",5_000,Confidence.LOW,"after");
        assertEquals(FlagThrottle.Action.STORE, after.action());
        assertEquals("after", after.flagId());
        assertTrue(throttle.isDuplicate(id,"Fly A", 5_010));
    }
}
