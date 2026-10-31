/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.core;

import de.kyrosmp.anticheat.core.CheckCategory;

public record CheckInfo(String name, CheckCategory category, String correlationGroup, String configPath, String measures, String limitations) {
    public String enabledPath() {
        return this.configPath + ".enabled";
    }
}

