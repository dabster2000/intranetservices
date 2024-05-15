package dk.trustworks.intranet.aggregates.users.query;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.users.services.SalaryService;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserBankInfo;
import dk.trustworks.intranet.userservice.model.UserStatus;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.USER_EVENT;

@JBossLog
@ApplicationScoped
public class UserEventHandler {

    @Inject
    UserService userService;

    @Inject
    StatusService statusService;

    @Inject
    SalaryService salaryService;

    @Inject
    SlackService slackService;

    @Inject
    EventBus eventBus;

    //@Blocking
    //@Incoming(READ_USER_EVENT)
    //@Outgoing(SEND_BROWSER_EVENT)
    @ConsumeEvent(value = USER_EVENT, blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void readUserEvent(AggregateRootChangeEvent event) {
        log.info("UserEventHandler.readUserEvent -> event = " + event);
        AggregateEventType type = event.getEventType();
        switch (type) {
            case CREATE_USER -> createUser(event);
            case UPDATE_USER -> updateUser(event);
            case CREATE_USER_STATUS -> createUserStatus(event);
            case DELETE_USER_STATUS -> deleteUserStatus(event);
            case CREATE_USER_SALARY -> createUserSalary(event);
            case DELETE_USER_SALARY -> deleteUserSalary(event);
            case CREATE_BANK_INFO -> createBankInfo(event);
        }
        eventBus.publish(BROWSER_EVENT, event.getAggregateRootUUID());
    }

    private void createBankInfo(AggregateRootChangeEvent event) {
        UserBankInfo userBankInfo = new JsonObject(event.getEventContent()).mapTo(UserBankInfo.class);
        try {
            slackService.sendMessage(User.findByUsername("hans.lassen").get(), "*"+userBankInfo.getFullname()+"* has submitted new bank account information for approval.");
            if(!userBankInfo.getFullname().equals("Hans Ernst Lassen")) slackService.sendMessage(User.findByUsername("marie.myssing").get(), "*"+userBankInfo.getFullname()+"* has submitted new bank account information for approval.");
        } catch (SlackApiException | IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteUserSalary(AggregateRootChangeEvent event) {
        //
    }

    private void createUserSalary(AggregateRootChangeEvent event) {
        Salary salary = new JsonObject(event.getEventContent()).mapTo(Salary.class);
        //salaryService.create(salary);
    }

    private void deleteUserStatus(AggregateRootChangeEvent event) {
        //
    }

    private void createUserStatus(AggregateRootChangeEvent event) {
        UserStatus userStatus = new JsonObject(event.getEventContent()).mapTo(UserStatus.class);

    }

    private void updateUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        //userService.updateOne(user);
    }

    private void createUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        //userService.createUser(user);
    }
}
