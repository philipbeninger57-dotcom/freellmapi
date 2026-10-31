package de.kyrosmp.anticheat.flag;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class FlagDataQualityTest {
    private final ConfidenceCalculator calculator = new ConfidenceCalculator(3, 8, 6);
    private final FlagService.Settings defaults =
            new FlagService.Settings(1.0, 600_000, 250, 1_800_000, calculator);
    private final RecentFlagIndex.Stats repeatedIndependentEvidence =
            new RecentFlagIndex.Stats(8, 10, 2);

    private Confidence confidence(int ping, double tps, FlagService.Settings settings) {
        return calculator.evaluate(
                repeatedIndependentEvidence,
                !FlagService.dataQualityReason(ping, tps, settings).isEmpty());
    }

    @Test
    void healthyMeasurementsKeepIndependentHighConfidence() {
        assertEquals(Confidence.HIGH, confidence(50, 20.0, defaults));
        assertEquals("", FlagService.dataQualityReason(250, 18.0, defaults));
    }

    @Test
    void lowTpsLimitsConfidenceEvenWithLowPing() {
        assertEquals(Confidence.MEDIUM, confidence(40, 12.0, defaults));
        assertEquals("low TPS", FlagService.dataQualityReason(40, 12.0, defaults));
    }

    @Test
    void missingTpsDoesNotImplyHealthyServer() {
        for (double unavailable : new double[] {-1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(Confidence.MEDIUM, confidence(40, unavailable, defaults));
            assertEquals(
                    "TPS unavailable", FlagService.dataQualityReason(40, unavailable, defaults));
        }
    }

    @Test
    void highPingStillLimitsConfidenceAndReasonsDoNotMislabelItAsTps() {
        assertEquals(Confidence.MEDIUM, confidence(400, 20.0, defaults));
        assertEquals(
                "high or unavailable ping", FlagService.dataQualityReason(400, 20.0, defaults));
        assertEquals(
                "high or unavailable ping, low TPS",
                FlagService.dataQualityReason(400, 12.0, defaults));
    }

    @Test
    void configurationCanExplicitlyDisableTpsQualityLimit() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("confidence.poor-data-min-tps", 0.0);
        var settings = FlagService.Settings.from(config);
        assertEquals(Confidence.HIGH, confidence(40, -1.0, settings));
        assertEquals(Confidence.MEDIUM, confidence(400, -1.0, settings));
    }

    @Test
    void oldConfigurationGetsConservativeDefaultAndCustomLimitWorks() {
        YamlConfiguration config = new YamlConfiguration();
        assertEquals(18.0, FlagService.Settings.from(config).poorDataMinTps());
        config.set("confidence.poor-data-min-tps", 19.5);
        var settings = FlagService.Settings.from(config);
        assertEquals(Confidence.MEDIUM, confidence(40, 19.0, settings));
        assertEquals(Confidence.HIGH, confidence(40, 19.5, settings));
    }

    @Test
    void poorMeasurementsNeverRaiseLowConfidence() {
        var weakEvidence = new RecentFlagIndex.Stats(1, 1, 1);
        assertEquals(
                Confidence.LOW,
                calculator.evaluate(
                        weakEvidence,
                        !FlagService.dataQualityReason(400, 10.0, defaults).isEmpty()));
    }
}
