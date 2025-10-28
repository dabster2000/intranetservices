package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "expenses")
public class Expense extends PanacheEntityBase {

    @Id
    private String uuid;
    private String useruuid;
    private Double amount;
    private String account;
    private String accountname;
    private String description = "";
    private String projectuuid = "";

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate datecreated;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate datemodified;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate expensedate;

    private String status;

    @Column(name = "paid_out")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime paidOut;

    private boolean customerexpense;

    @Transient
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String expensefile;

    @JsonIgnore
    private int vouchernumber;

    @JsonIgnore
    private Integer journalnumber;

    @JsonIgnore
    private String accountingyear;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "last_retry_at")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime lastRetryAt;

    @Column(name = "is_orphaned")
    private Boolean isOrphaned = false;

    public Expense() {
        uuid = UUID.randomUUID().toString();
        datecreated = LocalDate.now();
        datemodified = LocalDate.now();
    }

    public boolean isPaidOut() {
        return paidOut != null;
    }

    @Transient
    @JsonProperty("isLocked")
    public boolean isLocked() {
        return datecreated.isBefore(LocalDate.now());
    }

    /**
     * Increment retry count and update last retry timestamp.
     * Used when retrying expense upload after failure.
     */
    public void incrementRetryCount() {
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        this.retryCount++;
        this.lastRetryAt = LocalDateTime.now();
    }

    /**
     * Mark this expense as having an orphaned voucher reference.
     * This happens when our database has a voucher number but it doesn't exist in e-conomics.
     */
    public void markAsOrphaned() {
        this.isOrphaned = true;
        this.lastRetryAt = LocalDateTime.now();
    }

    /**
     * Clear orphaned status after successful recovery.
     */
    public void clearOrphaned() {
        this.isOrphaned = false;
    }

    /**
     * Check if this expense is eligible for retry based on retry count and time since last attempt.
     * @param maxRetries Maximum number of retry attempts allowed
     * @param minMinutesBetweenRetries Minimum minutes to wait between retry attempts
     * @return true if eligible for retry
     */
    public boolean shouldRetry(int maxRetries, int minMinutesBetweenRetries) {
        // Never retry if max retries exceeded
        if (retryCount != null && retryCount >= maxRetries) {
            return false;
        }

        // If never retried, eligible
        if (lastRetryAt == null) {
            return true;
        }

        // Check if enough time has passed since last retry
        LocalDateTime nextRetryTime = lastRetryAt.plusMinutes(minMinutesBetweenRetries);
        return LocalDateTime.now().isAfter(nextRetryTime);
    }

    /**
     * Get safe retry count (never null).
     */
    public int getSafeRetryCount() {
        return retryCount != null ? retryCount : 0;
    }

    /**
     * Check if this expense has a cached voucher issue.
     * This is detected when we have voucher details but the voucher doesn't exist.
     */
    public boolean hasKnownCacheIssue() {
        return Boolean.TRUE.equals(isOrphaned) ||
               (vouchernumber > 0 && "UP_FAILED".equals(status) &&
                errorMessage != null && errorMessage.contains("not found"));
    }
}
