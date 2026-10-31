package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;

import java.util.Set;
import java.util.UUID;

public record SummaryQuery(
        CheckCategory category,
        boolean onlyNew,
        boolean onlyCurrent,
        long currentSince,
        SummaryQuery.OnlineFilter online,
        Set<UUID> onlineUuids,
        SummaryQuery.Sort sort,
        int page,
        int pageSize) {
    public SummaryQuery(
            CheckCategory category,
            boolean onlyNew,
            boolean onlyCurrent,
            long currentSince,
            SummaryQuery.OnlineFilter online,
            Set<UUID> onlineUuids,
            SummaryQuery.Sort sort,
            int page,
            int pageSize) {
        onlineUuids = onlineUuids == null ? Set.of() : Set.copyOf(onlineUuids);
        page = Math.max(0, page);
        pageSize = Math.max(1, pageSize);
        this.category = category;
        this.onlyNew = onlyNew;
        this.onlyCurrent = onlyCurrent;
        this.currentSince = currentSince;
        this.online = online;
        this.onlineUuids = onlineUuids;
        this.sort = sort;
        this.page = page;
        this.pageSize = pageSize;
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

        public SummaryQuery.OnlineFilter next() {
            return values()[(this.ordinal() + 1) % values().length];
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

        public SummaryQuery.Sort next() {
            return values()[(this.ordinal() + 1) % values().length];
        }
    }
}
