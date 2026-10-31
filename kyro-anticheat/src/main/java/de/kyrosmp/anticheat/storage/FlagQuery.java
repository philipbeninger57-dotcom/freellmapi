/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;

public record FlagQuery(CheckCategory category, boolean onlyNew, Sort sort, int page, int pageSize) {
    public FlagQuery {
        page = Math.max(0, page);
        pageSize = Math.max(1, pageSize);
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

        public Sort next() {
            return this == DATE ? VL : DATE;
        }
    }
}

