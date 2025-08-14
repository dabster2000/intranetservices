package dk.trustworks.intranet.aggregates.users.query;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.sender.AggregateRootChangeEvent;
import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserBankInfo;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.messaging.producer.KafkaEventPublisher;
import dk.trustworks.intranet.messaging.backfill.TopicMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.util.List;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;
import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.USER_EVENT;

@JBossLog
@ApplicationScoped
public class UserEventHandler {

    @Inject
    SlackService slackService;

    @Inject
    EventBus eventBus;

    @Inject
    SNSEventSender snsEventSender;

    @Inject
    dk.trustworks.intranet.config.FeatureFlags featureFlags;

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
        Salary salary = new JsonObject(event.getEventContent()).mapTo(Salary.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserSalaryUpdateTopic, salary.getUseruuid(), salary.getActivefrom());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserSalaryUpdateTopic");
        }
        //userSalaryCalculatorService.recalculateSalary(salary.getUseruuid());
    }

    private void createUserSalary(AggregateRootChangeEvent event) {
        Salary salary = new JsonObject(event.getEventContent()).mapTo(Salary.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserSalaryUpdateTopic, salary.getUseruuid(), salary.getActivefrom());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserSalaryUpdateTopic");
        }
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void deleteUserStatus(AggregateRootChangeEvent event) {
        String useruuid = event.getAggregateRootUUID();
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserStatusUpdateTopic, useruuid, DateUtils.getCompanyStartDate());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserStatusUpdateTopic [delete]");
        }
        //userAvailabilityCalculatorService.updateUserAvailability(useruuid);
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void createUserStatus(AggregateRootChangeEvent event) {
        UserStatus userStatus = new JsonObject(event.getEventContent()).mapTo(UserStatus.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserStatusUpdateTopic, userStatus.getUseruuid(), userStatus.getStatusdate());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserStatusUpdateTopic [create]");
        }
        //userAvailabilityCalculatorService.updateUserAvailability(useruuid);
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void updateUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        //userService.updateOne(user);
    }

    private void createUser(AggregateRootChangeEvent event) {
        User user = new JsonObject(event.getEventContent()).mapTo(User.class);
        int nextNumber = 0;
        List<UserAccount> userAccountList = UserAccount.<UserAccount>findAll().list();
        for (UserAccount userAccount : userAccountList) {
            try {
                int danlonNumber = Integer.parseInt(userAccount.getDanlon());
                if(danlonNumber>nextNumber) nextNumber = danlonNumber;
            } catch (NumberFormatException e) {
                // ignore
            }
        }

        UserAccount userAccount = new UserAccount(user.getUuid(), -1, (nextNumber+1)+"", "");
        QuarkusTransaction.begin();
        userAccount.persist();
        QuarkusTransaction.commit();
    }
}
