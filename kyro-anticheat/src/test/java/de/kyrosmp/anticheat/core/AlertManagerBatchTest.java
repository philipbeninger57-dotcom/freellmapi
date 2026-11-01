package de.kyrosmp.anticheat.core;

import static org.junit.jupiter.api.Assertions.*;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

class AlertManagerBatchTest {
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    static final class Recipient {
        final UUID id = UUID.randomUUID();
        final List<Runnable> queued = new ArrayList<>();
        final List<String> received = new ArrayList<>();
        boolean permission = true;
        boolean onEntityThread;
        final ScheduledTask task = proxy(ScheduledTask.class, (p, m, a) -> null);
        final EntityScheduler scheduler =
                proxy(
                        EntityScheduler.class,
                        (p, m, a) -> {
                            if (m.getName().equals("run")) {
                                @SuppressWarnings("unchecked")
                                Consumer<ScheduledTask> action = (Consumer<ScheduledTask>) a[1];
                                queued.add(
                                        () -> {
                                            onEntityThread = true;
                                            try {
                                                action.accept(task);
                                            } finally {
                                                onEntityThread = false;
                                            }
                                        });
                                return task;
                            }
                            return null;
                        });
        final Player player =
                proxy(
                        Player.class,
                        (p, m, a) ->
                                switch (m.getName()) {
                                    case "getUniqueId" -> id;
                                    case "getScheduler" -> scheduler;
                                    case "hasPermission" -> {
                                        assertTrue(
                                                onEntityThread,
                                                "Permission access must stay on the player's"
                                                    + " scheduler");
                                        yield permission;
                                    }
                                    case "sendMessage" -> {
                                        assertTrue(onEntityThread);
                                        if (a[0] instanceof String[] messages)
                                            received.addAll(Arrays.asList(messages));
                                        else received.add((String) a[0]);
                                        yield null;
                                    }
                                    default -> null;
                                });

        void deliver() {
            List<Runnable> copy = List.copyOf(queued);
            queued.clear();
            copy.forEach(Runnable::run);
        }
    }

    static final class Fixture {
        final List<Runnable> global = Collections.synchronizedList(new ArrayList<>());
        final List<String> warnings = new ArrayList<>();
        final List<Recipient> recipients = new ArrayList<>();
        final AlertManager manager;

        Fixture() {
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            logger.addHandler(
                    new Handler() {
                        @Override
                        public void publish(LogRecord record) {
                            if (record.getLevel().intValue() >= Level.WARNING.intValue())
                                warnings.add(record.getMessage());
                        }

                        @Override
                        public void flush() {}

                        @Override
                        public void close() {}
                    });
            manager = new AlertManager(null, logger);
            manager.setConsole(false);
            manager.scheduleGlobal = global::add;
            manager.onlinePlayers = () -> recipients.stream().map(r -> r.player).toList();
        }

        void flush() {
            global.removeFirst().run();
        }

        Recipient addRecipient() {
            Recipient r = new Recipient();
            recipients.add(r);
            return r;
        }
    }

    @Test
    void burstUsesOneGlobalAndOneEntityTaskPerRecipientInOrder() {
        Fixture f = new Fixture();
        Recipient first = f.addRecipient();
        Recipient second = f.addRecipient();
        f.manager.send("first");
        f.manager.send("second");
        f.manager.send("third");
        assertEquals(1, f.global.size());
        f.flush();
        assertEquals(1, first.queued.size());
        assertEquals(1, second.queued.size());
        first.deliver();
        second.deliver();
        assertEquals(
                List.of(
                        AlertManager.PREFIX + "first",
                        AlertManager.PREFIX + "second",
                        AlertManager.PREFIX + "third"),
                first.received);
        assertEquals(first.received, second.received);
        f.manager.send("next batch");
        assertEquals(1, f.global.size());
    }

    @Test
    void permissionsAndTogglesAreCheckedAtDeliveryTime() {
        Fixture f = new Fixture();
        Recipient revoked = f.addRecipient();
        Recipient disabled = f.addRecipient();
        f.manager.send("pending");
        f.flush();
        revoked.permission = false;
        assertFalse(f.manager.toggle(disabled.player));
        revoked.deliver();
        disabled.deliver();
        assertTrue(revoked.received.isEmpty());
        assertTrue(disabled.received.isEmpty());
        revoked.permission = true;
        assertTrue(f.manager.toggle(disabled.player));
        f.manager.send("allowed");
        f.flush();
        revoked.deliver();
        disabled.deliver();
        assertEquals(1, revoked.received.size());
        assertEquals(1, disabled.received.size());
    }

    @Test
    void concurrentOverloadIsBoundedAndProducesVisibleSummary() {
        Fixture f = new Fixture();
        Recipient recipient = f.addRecipient();
        IntStream.range(0, 1000).parallel().forEach(i -> f.manager.send("flag " + i));
        assertEquals(1, f.global.size());
        f.flush();
        assertEquals(1, recipient.queued.size());
        recipient.deliver();
        assertEquals(257, recipient.received.size());
        assertTrue(recipient.received.getLast().contains("744 additional staff alerts omitted"));
        assertTrue(
                f.warnings.stream()
                        .anyMatch(s -> s.contains("744 additional staff alerts omitted")));
        f.manager.send("recovered");
        f.flush();
        recipient.deliver();
        assertEquals(AlertManager.PREFIX + "recovered", recipient.received.getLast());
    }

    @Test
    void failedSchedulingDoesNotLeaveBatchPermanentlyStuck() {
        Fixture f = new Fixture();
        Recipient recipient = f.addRecipient();
        f.manager.scheduleGlobal =
                action -> {
                    throw new IllegalStateException("stopped");
                };
        f.manager.send("failed");
        assertTrue(
                f.warnings.stream().anyMatch(s -> s.contains("Could not schedule 1 staff alerts")));
        f.manager.scheduleGlobal = f.global::add;
        f.manager.send("recovered");
        assertEquals(1, f.global.size());
        f.flush();
        recipient.deliver();
        assertEquals(List.of(AlertManager.PREFIX + "recovered"), recipient.received);
    }
}
