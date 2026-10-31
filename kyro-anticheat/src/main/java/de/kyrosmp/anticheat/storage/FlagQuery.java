package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;

public record FlagQuery(
        CheckCategory category, boolean onlyNew, FlagQuery.Sort sort, int page, int pageSize) {
    public FlagQuery(
            CheckCategory category, boolean onlyNew, FlagQuery.Sort sort, int page, int pageSize) {
        page = Math.max(0, page);
        pageSize = Math.max(1, pageSize);
        this.category = category;
        this.onlyNew = onlyNew;
        this.sort = sort;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static enum Sort {
        DATE("Date (newest first)"),
        VL("VL (highest first)");

        private final String label;

        private Sort(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public FlagQuery.Sort next() {
            return this == DATE ? VL : DATE;
        }
    }
}
