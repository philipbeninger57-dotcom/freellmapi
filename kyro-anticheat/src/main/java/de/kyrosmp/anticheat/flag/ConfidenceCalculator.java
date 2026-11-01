package de.kyrosmp.anticheat.flag;

public record ConfidenceCalculator(
        int mediumRepetitions, int highRepetitions, int highTotalWithThreeGroups) {
    public ConfidenceCalculator(
            int mediumRepetitions, int highRepetitions, int highTotalWithThreeGroups) {
        mediumRepetitions = Math.max(1, mediumRepetitions);
        highRepetitions = Math.max(mediumRepetitions, highRepetitions);
        highTotalWithThreeGroups = Math.max(3, highTotalWithThreeGroups);
        this.mediumRepetitions = mediumRepetitions;
        this.highRepetitions = highRepetitions;
        this.highTotalWithThreeGroups = highTotalWithThreeGroups;
    }

    public Confidence evaluate(RecentFlagIndex.Stats stats, boolean poorDataQuality) {
        Confidence c =
                (stats.independentGroups() < 2 || stats.repetitions() < this.highRepetitions)
                                && (stats.independentGroups() < 3
                                        || stats.total() < this.highTotalWithThreeGroups)
                        ? (stats.repetitions() < this.mediumRepetitions
                                        && stats.independentGroups() < 2
                                ? Confidence.LOW
                                : Confidence.MEDIUM)
                        : Confidence.HIGH;
        return poorDataQuality ? c.downgrade() : c;
    }
}
