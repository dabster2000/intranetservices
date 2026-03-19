package dk.trustworks.intranet.aggregates.bugreport.entities;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for the Bug Report bounded context.
 * <p>
 * Business rules (state machine, access control) live here.
 * The service layer is orchestration-only.
 */
@Data
@Entity
@Table(name = "bug_reports")
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class BugReport extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "reporter_uuid", nullable = false, length = 36)
    private String reporterUuid;

    @Column(name = "assignee_uuid", length = 36)
    private String assigneeUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BugReportStatus status;

    @Column(name = "title", length = 500)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "steps_to_reproduce", columnDefinition = "TEXT")
    private String stepsToReproduce;

    @Column(name = "expected_behavior", columnDefinition = "TEXT")
    private String expectedBehavior;

    @Column(name = "actual_behavior", columnDefinition = "TEXT")
    private String actualBehavior;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false)
    private Severity severity;

    @Column(name = "screenshot_s3_key", length = 512)
    private String screenshotS3Key;

    @Column(name = "log_excerpt", columnDefinition = "MEDIUMTEXT")
    private String logExcerpt;

    @Column(name = "page_url", length = 2000)
    private String pageUrl;

    @Column(name = "user_agent", length = 1000)
    private String userAgent;

    @Column(name = "viewport_width")
    private Integer viewportWidth;

    @Column(name = "viewport_height")
    private Integer viewportHeight;

    @Column(name = "console_errors", columnDefinition = "TEXT")
    private String consoleErrors;

    @Column(name = "user_roles", length = 500)
    private String userRoles;

    @Column(name = "ai_raw_response", columnDefinition = "TEXT")
    private String aiRawResponse;

    @Column(name = "previously_worked")
    private Boolean previouslyWorked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "reportUuid", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BugReportComment> comments = new ArrayList<>();

    @OneToMany(mappedBy = "reportUuid", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<BugReportNotification> notifications = new ArrayList<>();

    // ---- Factory method ----

    /**
     * Creates a new DRAFT bug report. This is the only way to create a BugReport.
     */
    public static BugReport createDraft(String reporterUuid, String pageUrl, String userAgent,
                                         Integer viewportWidth, Integer viewportHeight,
                                         String consoleErrors, String userRoles) {
        Objects.requireNonNull(reporterUuid, "reporterUuid must not be null");
        var report = new BugReport();
        report.uuid = UUID.randomUUID().toString();
        report.reporterUuid = reporterUuid;
        report.status = BugReportStatus.DRAFT;
        report.severity = Severity.MEDIUM;
        report.pageUrl = pageUrl;
        report.userAgent = userAgent;
        report.viewportWidth = viewportWidth;
        report.viewportHeight = viewportHeight;
        report.consoleErrors = consoleErrors;
        report.userRoles = userRoles;
        report.createdAt = LocalDateTime.now();
        report.updatedAt = LocalDateTime.now();
        return report;
    }

    // ---- State machine ----

    /**
     * Checks whether the given status transition is valid for this report.
     * Admin force-close (any non-CLOSED -> CLOSED) is always allowed.
     */
    public boolean canTransitionTo(BugReportStatus target) {
        if (this.status == target) return false;
        if (target == BugReportStatus.CLOSED && this.status != BugReportStatus.CLOSED) return true;
        return this.status.allowedTransitions().contains(target);
    }

    /**
     * Transitions this report to the given status.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(BugReportStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Cannot transition bug report from %s to %s".formatted(this.status, target));
        }
        this.status = target;
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Access control ----

    public boolean isReporter(String userUuid) {
        return this.reporterUuid.equals(userUuid);
    }

    public boolean isDraft() {
        return this.status == BugReportStatus.DRAFT;
    }

    /**
     * Only the reporter can delete, and only when the report is a DRAFT.
     */
    public boolean canBeDeletedBy(String userUuid) {
        return isDraft() && isReporter(userUuid);
    }

    /**
     * Only the reporter can edit their own report (title, description, etc.).
     */
    public boolean canBeEditedBy(String userUuid) {
        return isReporter(userUuid);
    }

    // ---- Domain operations ----

    /**
     * Updates the AI-generated fields on this draft report.
     */
    public void applyAiAnalysis(String title, String description, String stepsToReproduce,
                                 String expectedBehavior, String actualBehavior,
                                 Severity severity, String aiRawResponse) {
        this.title = title;
        this.description = description;
        this.stepsToReproduce = stepsToReproduce;
        this.expectedBehavior = expectedBehavior;
        this.actualBehavior = actualBehavior;
        if (severity != null) {
            this.severity = severity;
        }
        this.aiRawResponse = aiRawResponse;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Updates user-editable fields on this report.
     *
     * @throws IllegalStateException if the caller is not the reporter
     */
    public void updateFields(String userUuid, String title, String description,
                              String stepsToReproduce, String expectedBehavior,
                              String actualBehavior, Severity severity) {
        if (!canBeEditedBy(userUuid)) {
            throw new IllegalStateException("Only the reporter can edit this bug report");
        }
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (stepsToReproduce != null) this.stepsToReproduce = stepsToReproduce;
        if (expectedBehavior != null) this.expectedBehavior = expectedBehavior;
        if (actualBehavior != null) this.actualBehavior = actualBehavior;
        if (severity != null) this.severity = severity;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Assigns this report to an employee.
     */
    public void assignTo(String assigneeUuid) {
        Objects.requireNonNull(assigneeUuid, "assigneeUuid must not be null");
        this.assigneeUuid = assigneeUuid;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Adds a comment to this report.
     */
    public BugReportComment addComment(String authorUuid, String content, boolean isSystem) {
        Objects.requireNonNull(authorUuid, "authorUuid must not be null");
        Objects.requireNonNull(content, "comment content must not be null");
        var comment = BugReportComment.create(this.uuid, authorUuid, content, isSystem);
        this.comments.add(comment);
        return comment;
    }

    @PrePersist
    private void onPrePersist() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.updatedAt == null) this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    private void onPreUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
