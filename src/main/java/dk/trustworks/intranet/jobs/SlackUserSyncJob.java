package dk.trustworks.intranet.jobs;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;

@JBossLog
@ApplicationScoped
public class SlackUserSyncJob {

    @Inject
    SlackService slackService;

    @Transactional
    //@Scheduled(cron = "0 30 2 * * ?") // Disabled: replaced by JBeret job 'slack-user-sync' via BatchScheduler
    public void syncSlackUserIds() {
        log.info("Starting Slack user synchronization job");
        List<User> users = User.list("slackusername is null or slackusername = ''");
        log.info("Users missing Slack ID: " + users.size());
        for (User user : users) {
            try {
                String slackId = slackService.findUserIdByEmail(user.getEmail());
                if (slackId == null) {
                    log.warn("No Slack account found for " + user.getEmail());
                    continue;
                }
                QuarkusTransaction.requiringNew().run(() -> {
                    User.update("slackusername = ?1 where uuid = ?2", slackId, user.uuid);
                });
                log.info("Updated Slack ID for " + user.getEmail() + " -> " + slackId);
            } catch (Exception e) {
                log.error("Failed syncing user " + user.getEmail() + ": " + e.getMessage(), e);
            }
        }
    }
}
