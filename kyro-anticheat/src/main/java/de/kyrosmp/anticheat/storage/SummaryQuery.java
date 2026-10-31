/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;
import java.util.Set;
import java.util.UUID;

public record SummaryQuery(CheckCategory category, boolean onlyNew, boolean onlyCurrent, long currentSince, OnlineFilter online, Set<UUID> onlineUuids, Sort sort, int page, int pageSize) {
    public SummaryQuery {
        onlineUuids = onlineUuids == null ? Set.of() : Set.copyOf(onlineUuids);
        page = Math.max(0, page);
        pageSize = Math.max(1, pageSize);
    }

    public static enum OnlineFilter {
        ALL("All"),
        ONLINE("Online only"),
        OFFLINE("Offline only");

        private final String label;

        private OnlineFilter(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public OnlineFilter next() {
            return OnlineFilter.values()[(this.ordinal() + 1) % OnlineFilter.values().length];
        }
    }

    public static enum Sort {
        LAST_FLAG("Last flag"),
        MAX_VL("Highest open VL"),
        FLAG_COUNT("Flag count"),
        NAME("Name");

        private final String label;

        private Sort(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public Sort next() {
            return Sort.values()[(this.ordinal() + 1) % Sort.values().length];
        }
    }
}

