package dk.trustworks.intranet.expenseservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.expenseservice.utils.LocalDateSerializer;
import dk.trustworks.intranet.expenseservice.dto.ExpenseClassificationDTOs;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
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

    @Column(name = "accountant_notes", columnDefinition = "TEXT")
    private String accountantNotes;

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

    @Transient
    @ToString.Exclude
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private ExpenseClassificationDTOs.Submission classification;

    @JsonIgnore
    private int vouchernumber;

    @JsonIgnore
    private Integer journalnumber;

    @JsonIgnore
    private String accountingyear;

    @Column(name = "ai_validation_approved")
    private Boolean aiValidationApproved;

    @Column(name = "ai_validation_reason", columnDefinition = "TEXT")
    private String aiValidationReason;

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

    @Column(name = "review_state")
    private String reviewState;

    @Column(name = "ai_rule_id")
    private String aiRuleId;

    @JsonIgnore
    @Column(name = "ai_rule_ids_json", columnDefinition = "json")
    private String aiRuleIdsJson;

    @Column(name = "employee_justification")
    private String employeeJustification;

    @Column(name = "hr_decision")
    private String hrDecision;

    @Column(name = "hr_decision_by")
    private String hrDecisionBy;

    @Column(name = "hr_decision_at")
    private java.time.LocalDateTime hrDecisionAt;

    @Column(name = "hr_comment")
    private String hrComment;

    @Column(name = "ai_validation_count", nullable = false)
    private int aiValidationCount = 0;

    @Column(name = "extracted_amount_dkk")
    private Double extractedAmountDkk;

    @Column(name = "extracted_guest_count")
    private Integer extractedGuestCount;

    @Column(name = "extracted_merchant_name", length = 200)
    private String extractedMerchantName;

    /** STORED generated column — computed by DB as extracted_amount_dkk / extracted_guest_count. Read-only in JPA. */
    @Column(name = "extracted_per_person_dkk", insertable = false, updatable = false)
    private Double extractedPerPersonDkk;

    // ---- Unified state model (Phase 0). Derived mirror; see ExpenseStateDeriver. ----
    @Column(name = "state")
    private String state;

    @Column(name = "attention_owner")
    private String attentionOwner;

    @Column(name = "attention_kind")
    private String attentionKind;

    // Unified AI tiers (Phase 1). Now populated, so serialized to the API.
    @Column(name = "ai_outcome")
    private String aiOutcome;

    @Column(name = "ai_confidence")
    private Double aiConfidence;

    /**
     * Raw JSON array of non-blocking AI findings. Write-only on the entity (set by the
     * AI service); the parsed list is exposed read-only via {@link #getSoftFlagsList()}.
     */
    @JsonIgnore
    @Column(name = "soft_flags", columnDefinition = "json")
    private String softFlags;

    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Integer version = 0;

    public Expense() {
        uuid = UUID.randomUUID().toString();
        datecreated = LocalDate.now();
        datemodified = LocalDate.now();
    }

    public boolean isPaidOut() {
        return paidOut != null;
    }

    /**
     * Phase 1: keep the unified {@code state} correct on every write WITHOUT clobbering a
     * directly-written head/terminal value.
     *
     * <ul>
     *   <li><b>Tail</b> ({@link ExpenseStateDeriver#isStatusDerivedState}: the e-conomic
     *       pipeline statuses + the VALIDATED bridge): {@code state} is derived from
     *       {@code status} — the e-conomic pipeline owns it.</li>
     *   <li><b>Head/terminal</b> (CREATED, DELETED, null, unknown): {@code state} is
     *       authoritative — the AI/routing/decision code writes it directly and the hook
     *       preserves it. A fresh row with no {@code state} yet falls back to the full
     *       legacy deriver once (covers brand-new inserts + any legacy path).</li>
     * </ul>
     */
    @PrePersist
    @PreUpdate
    void syncDerivedState() {
        if (ExpenseStateDeriver.isStatusDerivedState(status)) {
            ExpenseStateDeriver.DerivedState d = ExpenseStateDeriver.deriveFromStatus(status);
            this.state = d.state();
            this.attentionOwner = d.owner();
            this.attentionKind = d.kind();
        } else if (this.state == null) {
            ExpenseStateDeriver.DerivedState d =
                    ExpenseStateDeriver.derive(status, reviewState, aiValidationApproved, hrDecision);
            this.state = d.state();
            this.attentionOwner = d.owner();
            this.attentionKind = d.kind();
        }
        // else: head/terminal with an authoritative state already set → preserve.
    }

    @Transient
    @JsonProperty("isLocked")
    public boolean isLocked() {
        return datecreated.isBefore(LocalDate.now());
    }

    private static final ObjectReader AI_RULE_IDS_READER =
            new ObjectMapper().readerForListOf(String.class);

    /** Empty list on malformed legacy rows so one bad row never crashes the API. */
    @Transient
    @JsonProperty("aiRuleIds")
    public List<String> getAiRuleIds() {
        if (aiRuleIdsJson == null || aiRuleIdsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return AI_RULE_IDS_READER.readValue(aiRuleIdsJson);
        } catch (JsonProcessingException ex) {
            return Collections.emptyList();
        }
    }

    private static final ObjectReader SOFT_FLAGS_READER =
            new ObjectMapper().readerForListOf(String.class);

    /**
     * Parsed soft-flag finding labels (non-blocking AI concerns) for optional accounting
     * spot-check. The raw {@code soft_flags} column holds a JSON array of label strings.
     * Empty list on null/malformed JSON so one bad row never crashes the API. Mirrors
     * {@link #getAiRuleIds()}.
     */
    @Transient
    @JsonProperty("softFlags")
    public List<String> getSoftFlagsList() {
        if (softFlags == null || softFlags.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return SOFT_FLAGS_READER.readValue(softFlags);
        } catch (JsonProcessingException ex) {
            return Collections.emptyList();
        }
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
