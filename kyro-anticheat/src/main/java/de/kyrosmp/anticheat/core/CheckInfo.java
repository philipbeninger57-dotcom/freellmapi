package de.kyrosmp.anticheat.core;

public record CheckInfo(
        String name,
        CheckCategory category,
        String correlationGroup,
        String configPath,
        String measures,
        String limitations) {
    public String enabledPath() {
        return this.configPath + ".enabled";
    }
}
