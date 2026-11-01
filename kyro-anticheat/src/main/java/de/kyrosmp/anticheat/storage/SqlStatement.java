package de.kyrosmp.anticheat.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SqlStatement(String sql, List<Object> params) {
    public SqlStatement(String sql, List<Object> params) {
        params = Collections.unmodifiableList(new ArrayList<>(params));
        long placeholders = sql.chars().filter(c -> c == 63).count();
        if (placeholders != (long) params.size()) {
            throw new IllegalStateException(
                    "SQL hat "
                            + placeholders
                            + " Platzhalter, aber "
                            + params.size()
                            + " Parameter");
        } else {
            this.sql = sql;
            this.params = params;
        }
    }
}
