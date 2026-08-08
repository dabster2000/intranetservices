package dk.trustworks.intranet.aggregates.invoice.economics.book;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Durable intent record for the irreversible e-conomic booking POST.
 *
 * <p>Written in its own transaction <em>before</em> {@code POST /invoices/booked} is issued, and
 * completed in a second transaction after the vendor responds. The row freezes the two things a
 * retry must replay byte-identically: the {@code Idempotency-Key} and the request body. Without
 * that, a retry re-resolves {@code draftInvoiceNumber} live, changes the payload under an
 * unchanged key, and e-conomic answers {@code 400 PayloadChanged} — the 2026-08-07 incident.
 *
 * <p><b>This table is evidence, never state.</b> {@code invoices.economics_booked_number} remains
 * the sole source of truth for "is this invoice booked". Every invoice booked before this table
 * existed, and every invoice booked by an old task during a blue/green rollout, has no row here.
 * Nothing may treat a missing row as an anomaly.
 *
 * <p><b>The TTL is load-bearing.</b> e-conomic remembers an idempotency key for one hour, not the
 * ~24h asserted by an older comment in {@code InvoiceFinalizationOrchestrator}. Past that window
 * the vendor no longer deduplicates, so replaying a key <em>creates a second booking</em> rather
 * than returning the cached response. {@link #isReplaySafe()} is the guard; there is no safe blind
 * retry.
 *
 * @see InvoiceBookingAttemptWriter
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "invoice_booking_attempt",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_invoice_booking_attempt_draft",
           columnNames = {"invoice_uuid", "draft_invoice_number"}
       ))
public class InvoiceBookingAttempt extends PanacheEntityBase {

    /** e-conomic remembers an idempotency key for this long. Verified against production. */
    public static final int IDEMPOTENCY_TTL_MINUTES = 60;

    /** {@code last_error} is TEXT; truncate defensively, as InvoiceEconomicsUpload does. */
    public static final int MAX_ERROR_LENGTH = 5000;

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "invoice_uuid", length = 40, nullable = false)
    private String invoiceUuid;

    @Column(name = "company_uuid", length = 36, nullable = false)
    private String companyUuid;

    /**
     * Diagnostic only. This is {@code CreatedResult.number} from the Q2C draft API — an ordinal
     * that production reuses across unrelated invoices and days, indexed non-uniquely. It is
     * deliberately NOT part of the natural key.
     */
    @Column(name = "economics_draft_number")
    private Integer economicsDraftNumber;

    /**
     * The identifier actually sent to the legacy booking endpoint, resolved exactly once when the
     * row is reserved and frozen thereafter. Re-resolving this on retry is what broke the incident.
     */
    @Column(name = "draft_invoice_number", nullable = false)
    private int draftInvoiceNumber;

    /** null | "ean" | "Email" — already canonicalised by the caller. */
    @Column(name = "send_by", length = 10)
    private String sendBy;

    @Column(name = "idempotency_key", length = 100, nullable = false)
    private String idempotencyKey;

    @Column(name = "payload_json", columnDefinition = "TEXT", nullable = false)
    private String payloadJson;

    @Column(name = "payload_hash", length = 64, nullable = false)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 24, nullable = false)
    private State state = State.PENDING;

    @Column(name = "booked_number")
    private Integer bookedNumber;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** When the POST was <em>issued</em> — the start of the vendor's idempotency window. */
    @Column(name = "posted_at")
    private LocalDateTime postedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Lifecycle of one booking intent.
     *
     * <p>{@link #NEEDS_RECONCILIATION} is terminal and human-handled. Automatic recovery of a lost
     * booked number is not possible with today's client: {@code EconomicsBookingApiClient} exposes
     * only {@code getBooked(int)} — there is no filtered list — and the {@code tw:{uuid}} marker in
     * {@code otherReference} that would have allowed a lookup was removed in commit 3c5d98e7.
     */
    public enum State {
        /** Reserved; the POST may not yet have been issued (see {@code postedAt}). */
        PENDING,
        /** e-conomic accepted the booking and {@code bookedNumber} is recorded. */
        BOOKED,
        /** e-conomic rejected it for a reason a retry cannot fix. The invoice stays PENDING_REVIEW. */
        FAILED,
        /** Outcome unknown, or the TTL expired. Terminal: never auto-retry, never auto-POST. */
        NEEDS_RECONCILIATION,
        /** The underlying draft was cancelled; a new draft gets a new row. */
        SUPERSEDED
    }

    /**
     * True when replaying this attempt's key is still guaranteed to hit e-conomic's idempotency
     * cache rather than create a second booking.
     *
     * <p>An attempt that was never posted is trivially safe — no key has reached the vendor yet.
     */
    public boolean isReplaySafe() {
        if (postedAt == null) return true;
        return postedAt.plusMinutes(IDEMPOTENCY_TTL_MINUTES).isAfter(LocalDateTime.now());
    }

    public void setLastError(String error) {
        this.lastError = (error != null && error.length() > MAX_ERROR_LENGTH)
                ? error.substring(0, MAX_ERROR_LENGTH)
                : error;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
