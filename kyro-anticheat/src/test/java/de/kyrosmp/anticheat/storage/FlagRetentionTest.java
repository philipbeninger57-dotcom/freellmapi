package de.kyrosmp.anticheat.storage;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class FlagRetentionTest {
    @TempDir Path directory;

    private FlagRecord record(String id, FlagStatus status, long created, long reviewed, String previous) {
        return new FlagRecord(id, UUID.randomUUID(), "Test", CheckCategory.MOVEMENT, "Fly A", 1.0,
                created, "test", "", 10, Confidence.LOW, null, 0, 0, 0,
                status, 1, reviewed > 0 ? "Staff" : null, reviewed, "", previous, 20);
    }

    private FlagStore open(Path file) throws Exception {
        FlagStore store = new FlagStore(Logger.getAnonymousLogger(), 2000);
        store.start(file.toFile()).get(5, TimeUnit.SECONDS);
        assertTrue(store.isAvailable());
        return store;
    }

    @Test void retentionPreservesOpenRecentlyResolvedAndBoundaryEvidence() throws Exception {
        Path file = directory.resolve("retention.db");
        FlagStore store = open(file);
        try {
            for (FlagRecord r : new FlagRecord[] {
                    record("old-resolved", FlagStatus.RESOLVED, 100, 200, null),
                    record("open", FlagStatus.NEW, 100, 0, "old-resolved"),
                    record("reviewed", FlagStatus.REVIEWED, 100, 200, null),
                    record("newly-resolved", FlagStatus.RESOLVED, 100, 1200, null),
                    record("created-boundary", FlagStatus.RESOLVED, 1000, 500, null),
                    record("resolved-boundary", FlagStatus.RESOLVED, 100, 1000, null),
                    record("no-resolution-time", FlagStatus.RESOLVED, 100, 0, null)
            }) {
                store.insert(r).get(5, TimeUnit.SECONDS);
            }
            store.audit(null, "Staff", "EXISTING_AUDIT", null, "preserve me").get(5, TimeUnit.SECONDS);

            assertEquals(0, store.pruneBefore(0).get(5, TimeUnit.SECONDS));
            assertTrue(store.flag("old-resolved").get().isPresent());
            assertEquals(0, store.pruneBefore(-1).get(5, TimeUnit.SECONDS));
            assertEquals(1, store.pruneBefore(1000).get(5, TimeUnit.SECONDS));
            assertTrue(store.flag("old-resolved").get().isEmpty());
            for (String id : new String[] {"open", "reviewed", "newly-resolved", "created-boundary",
                    "resolved-boundary", "no-resolution-time"}) {
                assertTrue(store.flag(id).get().isPresent(), id);
            }
            assertNull(store.flag("open").get().orElseThrow().previousFlagId());
            assertEquals(0, store.pruneBefore(1000).get(5, TimeUnit.SECONDS));
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                 var st = c.createStatement();
                 var rs = st.executeQuery("SELECT action, detail FROM kyro_audit ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals("EXISTING_AUDIT", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("RETENTION_PURGE", rs.getString(1));
                assertEquals("deleted=1; cutoff=1000", rs.getString(2));
                assertFalse(rs.next());
            }
        } finally {
            store.shutdown(2);
        }
    }

    @Test void retentionUsesBoundedBatchesAndKeepsReferencesToRemainingEvidence() throws Exception {
        FlagStore store = open(directory.resolve("bounded.db"));
        try {
            for (int i = 0; i < 1001; i++) {
                // Queued on the same worker; the final completion also waits for all earlier inserts.
                store.insert(record(String.format("old-%04d", i), FlagStatus.RESOLVED, 100, 200, null));
            }
            store.insert(record("retained", FlagStatus.NEW, 300, 0, "old-1000"))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(1000, store.pruneBefore(1000).get(10, TimeUnit.SECONDS));
            assertTrue(store.flag("old-1000").get().isPresent());
            assertEquals("old-1000", store.flag("retained").get().orElseThrow().previousFlagId());
            assertEquals(1, store.pruneBefore(1000).get(10, TimeUnit.SECONDS));
            assertNull(store.flag("retained").get().orElseThrow().previousFlagId());
        } finally {
            store.shutdown(2);
        }
    }
}
