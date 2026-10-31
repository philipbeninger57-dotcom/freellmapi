/*
 * Decompiled with CFR 0.152.
 */
package de.kyrosmp.anticheat.storage;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record SqlStatement(String sql, List<Object> params) {
    public SqlStatement {
        params = Collections.unmodifiableList(new ArrayList<>(params));
        long placeholders = sql.chars().filter(c -> c == 63).count();
        if (placeholders != (long)params.size()) {
            throw new IllegalStateException("SQL hat " + placeholders + " Platzhalter, aber " + params.size() + " Parameter");
        }
    }
}

