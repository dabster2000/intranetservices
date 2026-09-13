package dk.trustworks.intranet.aggregates.crm.news;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** UTC timestamps. A fenced lease protects updates across ECS replicas and redeploys. */
@Entity
@Table(name = "client_news_state")
public class ClientNewsState {
    @Id @Column(name = "client_uuid", length = 36)
    public String clientUuid;
    @Column(name = "status", nullable = false, length = 10)
    public String status;
    @Column(name = "last_attempt_at")
    public LocalDateTime lastAttemptAt;
    @Column(name = "last_successful_check_at")
    public LocalDateTime lastSuccessfulCheckAt;
    @Column(name = "checked_through")
    public LocalDateTime checkedThrough;
    @Column(name = "lease_token", length = 36)
    public String leaseToken;
    @Column(name = "lease_until")
    public LocalDateTime leaseUntil;
    @Column(name = "next_attempt_at")
    public LocalDateTime nextAttemptAt;
    @Column(name = "attempt_day")
    public LocalDate attemptDay;
    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;
    @Column(name = "items_json", columnDefinition = "LONGTEXT", nullable = false)
    public String itemsJson;
    @Column(name = "failure_code", length = 40)
    public String failureCode;
}
