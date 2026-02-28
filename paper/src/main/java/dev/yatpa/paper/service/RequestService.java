package dev.yatpa.paper.service;

import dev.yatpa.paper.data.RequestType;
import dev.yatpa.paper.data.TeleportRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RequestService {
    private final int timeoutSeconds;
    private final int cooldownSeconds;
    private final Map<UUID, TeleportRequest> pendingByReceiver = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldownBySender = new ConcurrentHashMap<>();

    public RequestService(int timeoutSeconds, int cooldownSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.cooldownSeconds = cooldownSeconds;
    }

    public int cooldownRemaining(UUID sender) {
        long last = cooldownBySender.getOrDefault(sender, 0L);
        long elapsed = (System.currentTimeMillis() - last) / 1000L;
        long remaining = cooldownSeconds - elapsed;
        return (int) Math.max(0, remaining);
    }

    public boolean canSend(UUID sender) {
        return cooldownRemaining(sender) <= 0;
    }

    public boolean create(UUID sender, UUID receiver, RequestType type) {
        if (hasPair(sender, receiver)) {
            return false;
        }
        pendingByReceiver.put(receiver, new TeleportRequest(sender, receiver, type, System.currentTimeMillis()));
        cooldownBySender.put(sender, System.currentTimeMillis());
        return true;
    }

    private boolean hasPair(UUID sender, UUID receiver) {
        TeleportRequest existing = pendingByReceiver.get(receiver);
        return existing != null && existing.sender().equals(sender);
    }

    public Optional<TeleportRequest> pendingFor(UUID receiver) {
        TeleportRequest request = pendingByReceiver.get(receiver);
        if (request == null) {
            return Optional.empty();
        }
        long ageSeconds = (System.currentTimeMillis() - request.createdAtMillis()) / 1000L;
        if (ageSeconds > timeoutSeconds) {
            pendingByReceiver.remove(receiver);
            return Optional.empty();
        }
        return Optional.of(request);
    }

    public Optional<TeleportRequest> removeFor(UUID receiver) {
        return Optional.ofNullable(pendingByReceiver.remove(receiver));
    }

    public List<TeleportRequest> purgeExpired() {
        long now = System.currentTimeMillis();
        List<TeleportRequest> expired = new ArrayList<>();
        pendingByReceiver.entrySet().removeIf(entry -> {
            boolean timedOut = ((now - entry.getValue().createdAtMillis()) / 1000L) > timeoutSeconds;
            if (timedOut) {
                expired.add(entry.getValue());
            }
            return timedOut;
        });
        return expired;
    }
}
