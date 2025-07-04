package dk.trustworks.intranet.appplatform.jobs;

import dk.trustworks.intranet.appplatform.model.AppToken;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

@JBossLog
@ApplicationScoped
public class AppTokenCleanupJob {

    @Scheduled(cron = "0 0 3 * * ?")
    public void deleteExpiredTokens() {
        long deleted = AppToken.delete("revoked = true and expiresAt < ?1", LocalDateTime.now());
        log.info("Cleaned up " + deleted + " expired revoked tokens");
    }
}
