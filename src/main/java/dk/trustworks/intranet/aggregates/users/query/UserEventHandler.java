package dk.trustworks.intranet.aggregates.users.query;

import dk.trustworks.intranet.aggregates.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserStatus;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.json.JsonObject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.READ_USER_EVENT;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.SEND_BROWSER_EVENT;

@JBossLog
@ApplicationScoped
public class UserEventHandler {

    @Inject
    UserService userService;

    @Inject
    StatusService statusService;

    @Inject
    SalaryService salaryService;

    @Blocking
    @Incoming(READ_USER_EVENT)
    @Outgoing(SEND_BROWSER_EVENT)
    public String readUserEvent(AggregateRootChangeEvent event) {
        log.info("UserEventHandler.readUserEvent -> event = " + event);
        AggregateEventType type = event.getEventType();
        switch (type) {
            case CREATE_USER -> createUser(event);
            case UPDATE_USER -> updateUser(event);
            case CREATE_USER_STATUS -> createUserStatus(event);
            case DELETE_USER_STATUS -> deleteUserStatus(event);
            case CREATE_USER_SALARY -> createUserSalary(event);
            case DELETE_USER_SALARY -> deleteUserSalary(event);
        }
        return event.getAggregateRootUUID();
    }

    private void deleteUserSalary(AggregateRootChangeEvent event) {
        salaryService.delete(event.getEventContent());
    }

    private void createUserSalary(AggregateRootChangeEvent event) {
        Salary salary = new JsonObject(event.getEventContent()).mapTo(Salary.class);
        salaryService.create(salary);
    }

    private void deleteUserStatus(AggregateRootChangeEvent event) {
        statusService.delete(event.getEventContent());
    }

    private void createUserStatus(AggregateRootChangeEvent event) {
        UserStatus userStatus = new JsonObject(event.getEventContent()).mapTo(UserStatus.class);
        statusService.create(userStatus);
    }

    private void updateUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        userService.updateOne(user);
    }

    private void createUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        userService.createUser(user);
    }
}
