package dk.trustworks.intranet.messaging.routing;

public final class EventAddress {
    private EventAddress() {}
    public static final String PREFIX = "domain.events.";
    public static String of(String eventType) {
        return PREFIX + eventType;
    }
}
