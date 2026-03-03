package net.fabricmc.fabric.api.networking.v1;

public final class ServerPlayConnectionEvents {
    public static final Event.DISCONNECT DISCONNECT = new Event.DISCONNECT();

    public static final class Event {
        public static class DISCONNECT {
            public void register(Listener listener) {
                // no-op stub
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        void onDisconnect(Object handler, Object server);
    }
}
