package net.fabricmc.fabric.api.command.v2;

public final class CommandRegistrationCallback {
    public static final Event EVENT = new Event();

    public static class Event {
        public void register(Callback callback) {
            // no-op stub for compilation
        }
    }

    @FunctionalInterface
    public interface Callback {
        void register(Object dispatcher, Object access, Object environment);
    }
}
