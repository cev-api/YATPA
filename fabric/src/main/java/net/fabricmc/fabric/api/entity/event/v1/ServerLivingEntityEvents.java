package net.fabricmc.fabric.api.entity.event.v1;

public final class ServerLivingEntityEvents {
    public static final Event.ALLOW_DAMAGE ALLOW_DAMAGE = new Event.ALLOW_DAMAGE();

    public static final class Event {
        public static class ALLOW_DAMAGE {
            public void register(Listener listener) {
                // no-op stub
            }
        }
    }

    @FunctionalInterface
    public interface Listener {
        boolean onAllowDamage(Object entity, Object source, float amount);
    }
}
