package net.fabricmc.fabric.api.event.lifecycle.v1;

public final class ServerTickEvents {
    public static final Event START_SERVER_TICK = new Event();

    public static class Event {
        public void register(Listener listener) {
            // no-op stub
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onTick(Object server);
    }
}
