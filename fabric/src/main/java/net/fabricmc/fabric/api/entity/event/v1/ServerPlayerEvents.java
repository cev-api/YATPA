package net.fabricmc.fabric.api.entity.event.v1;

public final class ServerPlayerEvents {
    public static final Event AFTER_RESPAWN = new Event();

    public static class Event {
        public void register(Listener listener) {
            // no-op stub
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onAfterRespawn(Object oldPlayer, Object newPlayer, boolean alive);
    }
}
