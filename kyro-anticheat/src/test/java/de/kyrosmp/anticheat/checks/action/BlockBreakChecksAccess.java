package de.kyrosmp.anticheat.checks.action;

import de.kyrosmp.anticheat.core.StrictConfig;
import de.kyrosmp.anticheat.flag.FlagSink;

/** Test access to the package-private line-of-sight test. */
public final class BlockBreakChecksAccess {
    private BlockBreakChecksAccess() {}

    public static BlockBreakChecks create(FlagSink sink, StrictConfig strict, boolean hidden) {
        BlockBreakChecks checks = new BlockBreakChecks(sink, strict);
        checks.hidden = (p, b) -> hidden;
        return checks;
    }
}
