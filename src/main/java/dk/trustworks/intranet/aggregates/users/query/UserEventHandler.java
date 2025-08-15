package dk.trustworks.intranet.aggregates.users.query;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.sender.SNSEventSender;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.messaging.emitters.enums.AggregateEventType;
import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserBankInfo;
import dk.trustworks.intranet.userservice.model.UserStatus;
import dk.trustworks.intranet.utils.DateUtils;
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

    @ConsumeEvent(value = "domain.events.CREATE_USER", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onCreateUser(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        createUser(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.UPDATE_USER", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onUpdateUser(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        updateUser(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.CREATE_USER_STATUS", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onCreateUserStatus(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        createUserStatus(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.DELETE_USER_STATUS", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onDeleteUserStatus(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        deleteUserStatus(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.CREATE_USER_SALARY", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onCreateUserSalary(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        createUserSalary(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.DELETE_USER_SALARY", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onDeleteUserSalary(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        deleteUserSalary(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    @ConsumeEvent(value = "domain.events.CREATE_BANK_INFO", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onCreateBankInfo(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        createBankInfo(env);
        eventBus.publish(BROWSER_EVENT, env.getAggregateId());
    }

    private void createBankInfo(DomainEventEnvelope env) {
        UserBankInfo userBankInfo = new JsonObject(env.getPayload()).mapTo(UserBankInfo.class);
        try {
            slackService.sendMessage(User.findByUsername("hans.lassen").get(), "*"+userBankInfo.getFullname()+"* has submitted new bank account information for approval.");
            if(!userBankInfo.getFullname().equals("Hans Ernst Lassen")) slackService.sendMessage(User.findByUsername("marie.myssing").get(), "*"+userBankInfo.getFullname()+"* has submitted new bank account information for approval.");
        } catch (SlackApiException | IOException e) {
            e.printStackTrace();
        }
    }

    private void deleteUserSalary(DomainEventEnvelope env) {
        Salary salary = new JsonObject(env.getPayload()).mapTo(Salary.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserSalaryUpdateTopic, salary.getUseruuid(), salary.getActivefrom());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserSalaryUpdateTopic");
        }
        //userSalaryCalculatorService.recalculateSalary(salary.getUseruuid());
    }

    private void createUserSalary(DomainEventEnvelope env) {
        Salary salary = new JsonObject(env.getPayload()).mapTo(Salary.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserSalaryUpdateTopic, salary.getUseruuid(), salary.getActivefrom());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserSalaryUpdateTopic");
        }
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void deleteUserStatus(DomainEventEnvelope env) {
        String useruuid = env.getAggregateId();
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserStatusUpdateTopic, useruuid, DateUtils.getCompanyStartDate());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserStatusUpdateTopic [delete]");
        }
        //userAvailabilityCalculatorService.updateUserAvailability(useruuid);
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void createUserStatus(DomainEventEnvelope env) {
        UserStatus userStatus = new JsonObject(env.getPayload()).mapTo(UserStatus.class);
        if (featureFlags.isSnsEnabled()) {
            snsEventSender.sendEvent(SNSEventSender.UserStatusUpdateTopic, userStatus.getUseruuid(), userStatus.getStatusdate());
        } else {
            log.debug("SNS disabled (feature.sns.enabled=false). Skipping SNS publish: UserStatusUpdateTopic [create]");
        }
        //userAvailabilityCalculatorService.updateUserAvailability(useruuid);
        //userSalaryCalculatorService.recalculateSalary(useruuid);
    }

    private void updateUser(DomainEventEnvelope env) {
        User user = new JsonObject(env.getPayload()).mapTo(User.class);
        //userService.updateOne(user);
    }

    private void createUser(DomainEventEnvelope env) {
        User user = new JsonObject(env.getPayload()).mapTo(User.class);
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
