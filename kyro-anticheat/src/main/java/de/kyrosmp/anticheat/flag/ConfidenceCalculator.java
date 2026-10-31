/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.flag;

import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.RecentFlagIndex;

public record ConfidenceCalculator(int mediumRepetitions, int highRepetitions, int highTotalWithThreeGroups) {
    public ConfidenceCalculator {
        mediumRepetitions = Math.max(1, mediumRepetitions);
        highRepetitions = Math.max(mediumRepetitions, highRepetitions);
        highTotalWithThreeGroups = Math.max(3, highTotalWithThreeGroups);
    }

    public Confidence evaluate(RecentFlagIndex.Stats stats, boolean poorDataQuality) {
        Confidence c = stats.independentGroups() >= 2 && stats.repetitions() >= this.highRepetitions || stats.independentGroups() >= 3 && stats.total() >= this.highTotalWithThreeGroups ? Confidence.HIGH : (stats.repetitions() >= this.mediumRepetitions || stats.independentGroups() >= 2 ? Confidence.MEDIUM : Confidence.LOW);
        return poorDataQuality ? c.downgrade() : c;
    }
}

