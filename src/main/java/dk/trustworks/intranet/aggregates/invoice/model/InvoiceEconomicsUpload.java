package dk.trustworks.intranet.aggregates.invoice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks e-conomics upload attempts for invoices, enabling retry logic and audit trails.
 *
 * <p>Each upload represents an attempt to upload an invoice to a specific company's e-conomics
 * system. For queued internal invoices, there will be two upload records: one for the issuing
 * company and one for the debtor company.
 *
 * <p>Features:
 * <ul>
 *   <li>Idempotency: Unique constraint prevents duplicate uploads</li>
 *   <li>Retry logic: Tracks attempt count and supports exponential backoff</li>
 *   <li>Audit trail: Preserves error messages and timestamps</li>
 *   <li>Observable: Query upload status for monitoring</li>
 * </ul>
 *
 * @see dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "invoice_economics_uploads",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_invoice_company_type",
           columnNames = {"invoiceuuid", "companyuuid", "upload_type"}
       ))
public class InvoiceEconomicsUpload extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    @Column(length = 36)
    private String uuid;

    @Column(name = "invoiceuuid", length = 40, nullable = false)
    private String invoiceuuid;

    @Column(name = "companyuuid", length = 36, nullable = false)
    private String companyuuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_type", nullable = false)
    private UploadType uploadType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UploadStatus status = UploadStatus.PENDING;

    @Column(name = "journal_number", nullable = false)
    private int journalNumber;

    @Column(name = "voucher_number")
    private Integer voucherNumber;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Type of upload indicating the company role.
     */
    public enum UploadType {
        /** Upload to the company issuing the invoice */
        ISSUER,
        /** Upload to the company receiving/paying the invoice (internal invoices only) */
        DEBTOR
    }

    /**
     * Current status of the upload attempt.
     */
    public enum UploadStatus {
        /** Upload queued but not yet attempted */
        PENDING,
        /** Upload succeeded */
        SUCCESS,
        /** Upload failed (may be retried if attempt_count < max_attempts) */
        FAILED
    }

    /**
     * Creates a new upload task.
     *
     * @param invoiceuuid Invoice UUID
     * @param companyuuid Company UUID
     * @param uploadType Upload type (ISSUER or DEBTOR)
     * @param journalNumber E-conomics journal number to use
     */
    public InvoiceEconomicsUpload(String invoiceuuid, String companyuuid,
                                   UploadType uploadType, int journalNumber) {
        this.uuid = UUID.randomUUID().toString();
        this.invoiceuuid = invoiceuuid;
        this.companyuuid = companyuuid;
        this.uploadType = uploadType;
        this.journalNumber = journalNumber;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Marks an upload attempt.
     */
    public void recordAttempt() {
        this.attemptCount++;
        this.lastAttemptAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Marks upload as successful.
     *
     * @param voucherNumber Voucher number from e-conomics response
     */
    public void markSuccess(int voucherNumber) {
        this.status = UploadStatus.SUCCESS;
        this.voucherNumber = voucherNumber;
        recordAttempt();
    }

    /**
     * Marks upload as failed.
     *
     * @param error Error message
     */
    public void markFailed(String error) {
        this.status = UploadStatus.FAILED;
        this.lastError = error != null && error.length() > 5000
            ? error.substring(0, 5000) + "... (truncated)"
            : error;
        recordAttempt();
    }

    /**
     * Checks if this upload can be retried.
     *
     * @return true if attempts remaining and status is FAILED
     */
    public boolean canRetry() {
        return status == UploadStatus.FAILED && attemptCount < maxAttempts;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
