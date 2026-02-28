package dev.yatpa.paper.data;

import java.util.UUID;

public record TeleportRequest(UUID sender, UUID receiver, RequestType type, long createdAtMillis) {
}