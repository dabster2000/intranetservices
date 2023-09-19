package dk.trustworks.intranet.messaging.emitters;

import dk.trustworks.intranet.messaging.dto.DateRangeMap;
import dk.trustworks.intranet.messaging.dto.UserDateMap;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class MessageEmitter {
    public static final String SEND_YEAR_CHANGE_EVENT = "send-availability-year-calculate";
    public static final String READ_YEAR_CHANGE_EVENT = "read-availability-year-calculate";
    public static final String SEND_USER_CHANGE_EVENT = "send-availability-user-calculate";
    public static final String READ_USER_CHANGE_EVENT = "read-availability-user-calculate";
    public static final String SEND_USER_DAY_CHANGE_EVENT = "send-availability-user-day-calculate";
    public static final String READ_USER_DAY_CHANGE_EVENT = "read-availability-user-day-calculate";
    public static final String SEND_BUDGET_UPDATE_MONTH_EVENT = "send-budget-update-month-calculate";
    public static final String READ_BUDGET_UPDATE_MONTH_EVENT = "read-budget-update-month-calculate";

    @Channel(SEND_YEAR_CHANGE_EVENT)
    Emitter<DateRangeMap> yearChangeEmitter;

    @Channel(SEND_USER_DAY_CHANGE_EVENT)
    Emitter<UserDateMap> userDayChangeEmitter;

    @Channel(SEND_USER_CHANGE_EVENT)
    Emitter<String> userChangeEmitter;

    @Channel(SEND_BUDGET_UPDATE_MONTH_EVENT)
    Emitter<DateRangeMap> budgetUpdateMonthEmitter;

    public void sendYearChange(DateRangeMap dateRangeMap) {
        yearChangeEmitter.send(dateRangeMap);
    }

    public void sendUserDayChange(UserDateMap userDateMap) {
        userDayChangeEmitter.send(userDateMap);
    }

    public void sendUserChange(String useruuid) {
        userChangeEmitter.send(useruuid);
    }

    public void sendBudgetUpdateMonthEvent(DateRangeMap dateRangeMap) {
        budgetUpdateMonthEmitter.send(dateRangeMap);
    }

}
