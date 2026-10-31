package de.kyrosmp.anticheat.gui;

import static org.junit.jupiter.api.Assertions.*;

import de.kyrosmp.anticheat.core.CheckCategory;
import de.kyrosmp.anticheat.flag.Confidence;
import de.kyrosmp.anticheat.flag.FlagRecord;
import de.kyrosmp.anticheat.flag.FlagStatus;
import de.kyrosmp.anticheat.storage.FlagStore;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

class InvestigationReviewTest {
    @TempDir Path directory;
    private final Plugin plugin =
            GuiAsyncRegressionTest.proxy(
                    Plugin.class, (p, m, a) -> m.getName().equals("isEnabled") ? true : null);

    private void checkReview(boolean revokeManage) throws Exception {
        FlagStore store = new FlagStore(Logger.getAnonymousLogger(), 100);
        store.start(directory.resolve("flags.db").toFile()).get(5, TimeUnit.SECONDS);
        GuiAsyncRegressionTest.Entity staff = new GuiAsyncRegressionTest.Entity();
        GuiAsyncRegressionTest.Entity target = new GuiAsyncRegressionTest.Entity();
        GuiContext ctx =
                new GuiContext(plugin, store, null, null, new GuiText("dd.MM.yyyy", "UTC"));
        ctx.players = id -> target.player;
        ctx.inspections().players = id -> id.equals(target.id) ? target.player : staff.player;
        try {
            store.insert(
                            new FlagRecord(
                                    "one",
                                    target.id,
                                    "Target",
                                    CheckCategory.MOVEMENT,
                                    "Fly A",
                                    1,
                                    100,
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
                                    20))
                    .get(5, TimeUnit.SECONDS);
            ctx.investigate(staff.player, target.id, "Target");
            target.runQueued();
            staff.runQueued();
            InspectionManager.Session session = ctx.inspections().current(staff.id);
            assertNotNull(session);
            assertEquals(
                    FlagStatus.NEW,
                    store.flag("one").get(5, TimeUnit.SECONDS).orElseThrow().status());
            if (revokeManage) staff.denied.add(GuiContext.PERM_MANAGE);
            ctx.inspections().correct(staff.player, session, target.location, true);
            assertTrue(session.initialTeleport().get(5, TimeUnit.SECONDS));
            staff.runQueued();
            assertEquals(
                    revokeManage ? FlagStatus.NEW : FlagStatus.REVIEWED,
                    store.flag("one").get(5, TimeUnit.SECONDS).orElseThrow().status());
        } finally {
            ctx.inspections().shutdown();
            store.shutdown(2);
        }
    }

    @Test
    void successfulInitialTeleportReviewsEvidenceOnlyAfterArrival() throws Exception {
        checkReview(false);
    }

    @Test
    void managePermissionRevocationPreservesUnreviewedEvidence() throws Exception {
        checkReview(true);
    }
}
