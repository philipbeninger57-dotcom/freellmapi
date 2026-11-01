package de.kyrosmp.anticheat.storage;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

class FlagStoreTest {
    @TempDir Path directory;

    private FlagStore open(Path file) throws Exception {
        FlagStore store = new FlagStore(Logger.getAnonymousLogger(), 100);
        store.start(file.toFile()).get(5, TimeUnit.SECONDS);
        return store;
    }

    private FlagRecord record(String id, UUID player, long time) {
        return new FlagRecord(
                id,
                player,
                "Test",
                CheckCategory.MOVEMENT,
                "Fly A",
                1.0,
                time,
                "test",
                "",
                10,
                Confidence.LOW,
                null,
                0,
                0,
                0,
                FlagStatus.NEW,
                1,
                null,
                0,
                "",
                null,
                20);
    }

    @Test
    void malformedSchemaFailsClosedAndLeavesDatabaseUntouched() throws Exception {
        Path file = directory.resolve("invalid.db");
        Class.forName("org.sqlite.JDBC");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var st = c.createStatement()) {
            st.execute("CREATE TABLE kyro_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT)");
            st.execute("INSERT INTO kyro_meta VALUES ('schema_version', 'invalid')");
        }
        FlagStore store = open(file);
        try {
            assertFalse(store.isAvailable());
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                    var st = c.createStatement();
                    var rs = st.executeQuery("SELECT meta_value FROM kyro_meta")) {
                assertTrue(rs.next());
                assertEquals("invalid", rs.getString(1));
            }
        } finally {
            store.shutdown(2);
        }
    }

    @Test
    void reopeningFlagClearsReviewMetadataForBulkAndSingleUpdates() throws Exception {
        FlagStore store = open(directory.resolve("flags.db"));
        UUID player = UUID.randomUUID();
        try {
            store.insert(record("one", player, 100)).get(5, TimeUnit.SECONDS);
            store.updateStatus("one", FlagStatus.REVIEWED, null, "Staff", 200)
                    .get(5, TimeUnit.SECONDS);
            assertEquals("Staff", store.flag("one").get().orElseThrow().reviewedBy());
            assertEquals(
                    1,
                    store.updatePlayerStatus(
                                    player,
                                    List.of(FlagStatus.REVIEWED),
                                    FlagStatus.NEW,
                                    null,
                                    "Staff",
                                    "REOPEN",
                                    300)
                            .get(5, TimeUnit.SECONDS));
            FlagRecord reopened = store.flag("one").get().orElseThrow();
            assertNull(reopened.reviewedBy());
            assertEquals(0, reopened.reviewedAt());
            assertEquals(FlagStatus.NEW, reopened.status());
        } finally {
            store.shutdown(2);
        }
    }

    @Test
    void paginationWithIdenticalTimestampsHasStableOrder() throws Exception {
        FlagStore store = open(directory.resolve("pagination.db"));
        UUID player = UUID.randomUUID();
        try {
            for (String id : List.of("c", "b", "a")) {
                store.insert(record(id, player, 100)).get(5, TimeUnit.SECONDS);
            }
            var flags =
                    store.playerFlags(
                                    player, new FlagQuery(null, false, FlagQuery.Sort.DATE, 0, 10))
                            .get();
            assertEquals(List.of("a", "b", "c"), flags.stream().map(FlagRecord::flagId).toList());
        } finally {
            store.shutdown(2);
        }
    }

    @Test
    void forcedShutdownCompletesFuturesOfDiscardedOperations() throws Exception {
        FlagStore store = open(directory.resolve("shutdown.db"));
        var field = FlagStore.class.getDeclaredField("executor");
        field.setAccessible(true);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) field.get(store);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(
                () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        CompletableFuture<?> pending = store.stats(UUID.randomUUID());
        store.shutdown(0);
        release.countDown();
        assertTrue(pending.isDone());
        assertTrue(pending.isCompletedExceptionally());
        assertFalse(store.isAvailable());
    }
}
