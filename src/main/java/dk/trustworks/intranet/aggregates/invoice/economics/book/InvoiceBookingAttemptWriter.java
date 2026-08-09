package dk.trustworks.intranet.aggregates.invoice.economics.book;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Tx1 and Tx2 of the e-conomic booking outbox.
 *
 * <p>A separate bean because a {@code REQUIRES_NEW} boundary only works when the method is invoked
 * through the CDI proxy — a {@code this.reserve(...)} call inside
 * {@code InvoiceFinalizationOrchestrator} would bypass the interceptor entirely. Same rationale as
 * {@code SyncFailureRecorder:14-18}.
 *
 * <p>{@code REQUIRES_NEW} is mandatory here, not stylistic. Callers may sit inside a
 * {@code @Transactional} that later rolls back — that is precisely the failure this table exists to
 * survive.
 *
 * <p><b>The shared-session hazard.</b> {@code InvoiceFinalizationOrchestrator:420-426} records that
 * a {@code REQUIRES_NEW} facade shares the Hibernate session, so an unrelated lock timeout
 * auto-flushed and rolled back a just-persisted booked state (split-brain: invoice dba892b4 /
 * bookedNumber 28084, 2026-06-30). {@code REQUIRES_NEW} suspends the JTA transaction; it does not
 * reliably hand you a fresh persistence context. Two defences follow from that:
 * <ul>
 *   <li>{@link #markBooked} never writes through a managed {@code Invoice}. It issues a bulk JPQL
 *       update, which bypasses the persistence context for the write.</li>
 *   <li>That query runs with {@code FlushMode.MANUAL} set <em>on the query</em>, so Hibernate's
 *       pre-query auto-flush cannot drag a caller's dirty entities into this transaction. The mode
 *       is deliberately not set EM-wide, which would change behaviour for unrelated code.</li>
 * </ul>
 *
 * @see InvoiceBookingAttempt
 */
@ApplicationScoped
@JBossLog
public class InvoiceBookingAttemptWriter {

    @Inject
    InvoiceBookingAttemptRepository attempts;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Tx1 — reserve the intent, or return the existing one for this (invoice, draft) pair.
     *
     * <p>Commits alone. After this returns, the idempotency key and the request body are durable,
     * so any later attempt replays byte-identically instead of re-resolving anything.
     *
     * <p>The caller must inspect the returned row's {@link InvoiceBookingAttempt#getState() state}
     * before POSTing — a {@code BOOKED} row means the work is already done, and
     * {@code NEEDS_RECONCILIATION} must never be POSTed.
     *
     * @param idempotencyKey the key to send. Deliberately supplied by the caller rather than minted
     *                       here: this deploy keeps the invariant {@code "book-" + invoiceUuid} so
     *                       that old tasks still serving traffic during a blue/green rollout collide
     *                       with new tasks at the vendor instead of booking a second invoice.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public InvoiceBookingAttempt reserve(String invoiceUuid,
                                         String companyUuid,
                                         Integer economicsDraftNumber,
                                         int draftInvoiceNumber,
                                         String sendBy,
                                         String idempotencyKey,
                                         EconomicsBookingRequest body) {

        Optional<InvoiceBookingAttempt> existing =
                attempts.findByInvoiceAndDraft(invoiceUuid, draftInvoiceNumber);
        if (existing.isPresent()) {
            InvoiceBookingAttempt a = existing.get();
            log.infof("bookDraft reserve: reusing attempt invoiceUuid=%s draftInvoiceNumber=%d "
                            + "state=%s attemptCount=%d postedAt=%s",
                    invoiceUuid, draftInvoiceNumber, a.getState(), a.getAttemptCount(), a.getPostedAt());
            return a;
        }

        String payloadJson = serialise(body);

        InvoiceBookingAttempt a = new InvoiceBookingAttempt();
        a.setUuid(UUID.randomUUID().toString());
        a.setInvoiceUuid(invoiceUuid);
        a.setCompanyUuid(companyUuid);
        a.setEconomicsDraftNumber(economicsDraftNumber);
        a.setDraftInvoiceNumber(draftInvoiceNumber);
        a.setSendBy(sendBy);
        a.setIdempotencyKey(idempotencyKey);
        a.setPayloadJson(payloadJson);
        a.setPayloadHash(sha256(payloadJson));
        a.setState(InvoiceBookingAttempt.State.PENDING);
        attempts.persist(a);

        log.infof("bookDraft reserve: created attempt uuid=%s invoiceUuid=%s draftInvoiceNumber=%d "
                        + "idempotencyKey=%s", a.getUuid(), invoiceUuid, draftInvoiceNumber, idempotencyKey);
        return a;
    }

    /**
     * Stamps {@code posted_at} and increments {@code attempt_count} immediately before the POST is
     * issued. This starts the vendor's one-hour idempotency clock, so it must commit <em>before</em>
     * the call goes out — otherwise a task death between the two leaves an attempt that looks
     * un-posted and would be replayed as new.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markPosted(String attemptUuid) {
        InvoiceBookingAttempt a = require(attemptUuid);
        if (a.getPostedAt() == null) a.setPostedAt(LocalDateTime.now());
        a.setAttemptCount(a.getAttemptCount() + 1);
        attempts.persist(a);
    }

    /**
     * Tx2 — record the vendor's acceptance and the booked number on the invoice.
     *
     * <p>The invoice write is a bulk JPQL update guarded by {@code economics_booked_number IS NULL}.
     * That guard is what makes this safe under mixed-version traffic: it can never overwrite a
     * number another task already recorded. A zero-row result is therefore not an error — it means
     * somebody else got there first — but it is logged, because it should be rare.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markBooked(String attemptUuid, String invoiceUuid, int bookedNumber) {
        InvoiceBookingAttempt a = require(attemptUuid);
        a.setState(InvoiceBookingAttempt.State.BOOKED);
        a.setBookedNumber(bookedNumber);
        a.setCompletedAt(LocalDateTime.now());
        a.setLastError(null);
        attempts.persist(a);

        int rows = updateInvoiceBooked(invoiceUuid, bookedNumber);
        if (rows == 0) {
            log.warnf("bookDraft markBooked: invoice %s already carried an economics_booked_number; "
                            + "left untouched (attempt %s recorded bookedNumber=%d)",
                    invoiceUuid, attemptUuid, bookedNumber);
        }
    }

    /**
     * Records a <em>retryable</em> failure (409 / 5xx) without leaving {@code PENDING}.
     *
     * <p>The row must stay {@code PENDING} so the next attempt replays the same key and the same
     * frozen body. Whether that replay is still safe is decided by
     * {@link InvoiceBookingAttempt#isReplaySafe()} against {@code posted_at}, not here.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markPostedFailure(String attemptUuid, String error) {
        InvoiceBookingAttempt a = require(attemptUuid);
        a.setLastError(error);
        attempts.persist(a);
    }

    /** Terminal failure the vendor told us about. The invoice stays PENDING_REVIEW. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markFailed(String attemptUuid, String error) {
        InvoiceBookingAttempt a = require(attemptUuid);
        a.setState(InvoiceBookingAttempt.State.FAILED);
        a.setLastError(error);
        a.setCompletedAt(LocalDateTime.now());
        attempts.persist(a);
        // The e-conomic error mapper is a ResponseExceptionMapper and cannot see which invoice a
        // failed call belonged to — it logs only a traceId. Emit the correlation here instead.
        log.errorf("bookDraft FAILED: attempt=%s invoiceUuid=%s draftInvoiceNumber=%d "
                        + "idempotencyKey=%s — %s",
                a.getUuid(), a.getInvoiceUuid(), a.getDraftInvoiceNumber(), a.getIdempotencyKey(), error);
    }

    /**
     * Terminal, human-handled. Reached when the vendor outcome is unknown, when the idempotency TTL
     * has expired, or when our persisted payload no longer matches what we would send.
     *
     * <p>Never auto-retried. Automatic recovery of a lost booked number is not possible with today's
     * client — {@code EconomicsBookingApiClient} offers only {@code getBooked(int)}, there is no
     * filtered list, and the {@code tw:{uuid}} marker that would have allowed a lookup was removed
     * in commit 3c5d98e7.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markNeedsReconciliation(String attemptUuid, String why) {
        InvoiceBookingAttempt a = require(attemptUuid);
        a.setState(InvoiceBookingAttempt.State.NEEDS_RECONCILIATION);
        a.setLastError(why);
        attempts.persist(a);
        log.errorf("bookDraft NEEDS_RECONCILIATION: attempt=%s invoiceUuid=%s draftInvoiceNumber=%d "
                        + "idempotencyKey=%s postedAt=%s — %s",
                a.getUuid(), a.getInvoiceUuid(), a.getDraftInvoiceNumber(),
                a.getIdempotencyKey(), a.getPostedAt(), why);
    }

    /**
     * Marks every open attempt for an invoice as superseded, called when the underlying draft is
     * cancelled. A new draft gets a new row.
     *
     * <p>Deliberately leaves {@code BOOKED} and {@code NEEDS_RECONCILIATION} rows alone: a booked
     * attempt is a fact, and a reconciliation case must stay visible to whoever is handling it.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markSuperseded(String invoiceUuid) {
        for (InvoiceBookingAttempt a : attempts.listOpenByInvoice(invoiceUuid)) {
            a.setState(InvoiceBookingAttempt.State.SUPERSEDED);
            a.setCompletedAt(LocalDateTime.now());
            attempts.persist(a);
        }
    }

    /**
     * Bulk JPQL update of the booked state.
     *
     * <p>Bulk rather than {@code em.merge} so nothing is written through the shared persistence
     * context, and {@code FlushMode.MANUAL} on the query so Hibernate's pre-query auto-flush cannot
     * pull a caller's dirty entities into this transaction. Both defences exist because of the
     * 2026-06-30 split-brain recorded at {@code InvoiceFinalizationOrchestrator:420-426}.
     */
    private int updateInvoiceBooked(String invoiceUuid, int bookedNumber) {
        EntityManager em = Panache.getEntityManager();
        // Enum values are bound as parameters rather than written as fully-qualified JPQL literals:
        // the literal form silently breaks if either enum is ever moved between packages.
        Query q = em.createQuery(
                "UPDATE Invoice SET economicsBookedNumber = :n, invoicenumber = :n, "
                        + "status = :status, economicsStatus = :economicsStatus "
                        + "WHERE uuid = :uuid AND economicsBookedNumber IS NULL");
        q.setParameter("n", bookedNumber);
        q.setParameter("status", InvoiceStatus.CREATED);
        q.setParameter("economicsStatus", EconomicsInvoiceStatus.BOOKED);
        q.setParameter("uuid", invoiceUuid);
        try {
            q.unwrap(org.hibernate.query.Query.class).setHibernateFlushMode(org.hibernate.FlushMode.MANUAL);
        } catch (IllegalStateException ignored) {
            // Unwrap unavailable — proceed; the bulk update itself still bypasses the write path.
        }
        return q.executeUpdate();
    }

    private InvoiceBookingAttempt require(String attemptUuid) {
        InvoiceBookingAttempt a = attempts.findById(attemptUuid);
        if (a == null) {
            // Cannot happen on the normal path — reserve() committed this row before the POST.
            throw new WebApplicationException(
                    "Booking attempt " + attemptUuid + " vanished", Response.Status.CONFLICT);
        }
        return a;
    }

    /**
     * Serialises the request body with the application ObjectMapper — the same one the REST client
     * uses — so the stored JSON is what would actually go on the wire.
     *
     * <p>Public so callers can re-serialise a rebuilt body and compare its hash against the frozen
     * one before POSTing. That check is the guarantee that a replay is byte-identical.
     */
    public String serialise(EconomicsBookingRequest body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise EconomicsBookingRequest", e);
        }
    }

    /** Hash of the exact JSON that would be sent. Used to prove a replay has not drifted. */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
