package dev.yatpa.paper.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.bukkit.Location;

public class TeleportLogService {
    public record Entry(
            Instant timestamp,
            String action,
            String actor,
            String payer,
            String fromWorld,
            double fromX,
            double fromY,
            double fromZ,
            String toWorld,
            double toX,
            double toY,
            double toZ) {
    }

    private final int maxEntries;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public TeleportLogService(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    public synchronized void record(String action, String actor, String payer, Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
            return;
        }
        entries.addFirst(new Entry(
                Instant.now(),
                action,
                actor,
                payer,
                from.getWorld().getName(),
                from.getX(),
                from.getY(),
                from.getZ(),
                to.getWorld().getName(),
                to.getX(),
                to.getY(),
                to.getZ()));
        while (entries.size() > maxEntries) {
            entries.removeLast();
        }
    }

    public synchronized List<Entry> recent(int limit) {
        int capped = Math.max(1, limit);
        List<Entry> out = new ArrayList<>(Math.min(entries.size(), capped));
        int i = 0;
        for (Entry entry : entries) {
            if (i++ >= capped) {
                break;
            }
            out.add(entry);
        }
        return out;
    }
}
