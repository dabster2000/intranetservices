package dk.trustworks.intranet.messaging.emitters;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MessageEmitter {
    public static final String YEAR_CHANGE_EVENT = "send-availability-year-calculate";

}
