package dk.trustworks.intranet.aggregates.users.query;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.messaging.dto.DomainEventEnvelope;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserBankInfo;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;

import static dk.trustworks.intranet.messaging.emitters.AggregateMessageEmitter.BROWSER_EVENT;

@JBossLog
@ApplicationScoped
public class UserEventHandler {

    @Inject
    SlackService slackService;

    @Inject
    EventBus eventBus;

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

    @ConsumeEvent(value = "domain.events.UPDATE_USER_STATUS", blocking = true)
    @CacheInvalidateAll(cacheName = "user-cache")
    public void onUpdateUserStatus(String envelopeJson) {
        DomainEventEnvelope env = DomainEventEnvelope.fromJson(envelopeJson);
        updateUserStatus(env);
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
    }

    private void createUserSalary(DomainEventEnvelope env) {
    }

    private void deleteUserStatus(DomainEventEnvelope env) {
    }

    private void createUserStatus(DomainEventEnvelope env) {
    }

    private void updateUserStatus(DomainEventEnvelope env) {
    }

    private void updateUser(DomainEventEnvelope env) {
    }

    private void createUser(DomainEventEnvelope env) {
        User user = new JsonObject(env.getPayload()).mapTo(User.class);

        // Create UserAccount with economics and username only
        // Note: Danløn numbers are now managed via UserDanlonHistoryService and generated
        // by specific business rules (company transition, salary type change, re-employment)
        // rather than being auto-assigned at user creation
        UserAccount userAccount = new UserAccount();
        userAccount.setUseruuid(user.getUuid());
        userAccount.setEconomics(-1);
        userAccount.setUsername("");

        QuarkusTransaction.begin();
        userAccount.persist();
        QuarkusTransaction.commit();
    }
}
