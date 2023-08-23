package dk.trustworks.intranet.aggregateservices.messaging;

import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class MessageEmitter {

    public static final String SEND_YEAR_CHANGE_EVENT = "send-availability-year-calculate";
    public static final String READ_YEAR_CHANGE_EVENT = "read-availability-year-calculate";
    public static final String SEND_USER_CHANGE_EVENT = "send-availability-user-calculate";
    public static final String READ_USER_CHANGE_EVENT = "read-availability-user-calculate";
    public static final String SEND_USER_DAY_CHANGE_EVENT = "send-availability-user-day-calculate";
    public static final String READ_USER_DAY_CHANGE_EVENT = "read-availability-user-day-calculate";

    @Inject
    @Channel(SEND_YEAR_CHANGE_EVENT)
    Emitter<DateRangeMap> yearChangeEmitter;

    @Inject
    @Channel(SEND_USER_DAY_CHANGE_EVENT)
    Emitter<UserDateMap> userDayChangeEmitter;

    @Inject
    @Channel(SEND_USER_CHANGE_EVENT)
    Emitter<String> userChangeEmitter;

    public void sendYearChange(DateRangeMap dateRangeMap) {
        yearChangeEmitter.send(dateRangeMap);
    }

    public void sendUserDayChange(UserDateMap userDateMap) {
        userDayChangeEmitter.send(userDateMap);
    }

    public void sendUserChange(String useruuid) {
        userChangeEmitter.send(useruuid);
    }

}
