package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceMailDispatch;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.model.enums.MailStatus;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class MailResource {

    @Inject
    ConferenceMailDispatch conferenceDispatch;

    @Inject
    PhotoService photoService;

    @Inject
    Mailer mailer;

    @ConfigProperty(name = "quarkus.mailer.from")
    String defaultFrom;

    @Transactional
    public void sendingHTML(TrustworksMail mail) {
        // Deliberately does NOT log the mail object: its toString carries
        // subject and body, and callers include candidate correspondence.
        log.infof("MailResource.sendingHTML queued mail %s", mail.getUuid());

        requireSystemMail(mail);
        mail.setMailOrigin("SYSTEM");
        mail.setStatus(MailStatus.READY);
        mail.persist();
    }

    @Transactional
    public void queueConference(TrustworksMail mail) {
        requireConferenceMail(mail);
        mail.setStatus(MailStatus.POLICY_READY);
        mail.persist();
    }

    private static void requireSystemMail(TrustworksMail mail) {
        if (mail.getConferenceUuid() != null || "CONFERENCE".equals(mail.getMailOrigin()))
            throw new IllegalArgumentException("Use typed conference mail admission");
    }

    private static void requireConferenceMail(TrustworksMail mail) {
        if (!"CONFERENCE".equals(mail.getMailOrigin()) || mail.getConferenceUuid() == null
                || mail.getParticipantUuid() == null || mail.getNormalizedEmail() == null
                || isSet(mail.getCc()) || isSet(mail.getBcc()))
            throw new IllegalArgumentException("Valid conference recipient context is required");
    }

    /** Mails drained per run. At the 5-minute mail-send cadence this is
     * up to 240 mails/hour; the old one-per-run drain managed 12. */
    static final int DRAIN_BATCH_SIZE = 20;

    /** Send tries before a mail is parked as {@link MailStatus#FAILED}. */
    static final int MAX_ATTEMPTS = 5;

    /** Width of {@code mail.last_error} (V494). */
    static final int LAST_ERROR_MAX_LENGTH = 500;

    /**
     * Drain the outbox — driven by the JBeret {@code mail-send} job via
     * {@code BatchScheduler} (every 5 min), NOT {@code @Scheduled}.
     * <p>
     * V494 rework: up to {@link #DRAIN_BATCH_SIZE} READY mails per run,
     * oldest first, each in its own transactions so one failing mail
     * neither rolls back nor blocks the others. The attempt is counted
     * and committed BEFORE the send — a send that kills the JVM still
     * burns an attempt — and at {@link #MAX_ATTEMPTS} the row is parked
     * as FAILED (poison-pill isolation) instead of stalling the queue,
     * which is exactly what the pre-V494 single-row drain did: the send
     * threw, the transaction rolled back, and the same row was picked
     * first again every run, forever.
     * <p>
     * Only send failures are contained per mail; an infrastructure
     * failure (DB down) propagates and fails the monitored batchlet.
     */
    public void sendMailJob() {
        List<String> readyIds = QuarkusTransaction.requiringNew().call(() ->
                TrustworksMail.<TrustworksMail>find("status in ?1",
                                Sort.ascending("createdAt", "uuid"), dispatchableStatuses(conferenceDispatch.isEnabled()))
                        .page(0, DRAIN_BATCH_SIZE)
                        .list().stream()
                        .map(TrustworksMail::getUuid)
                        .toList());
        for (String uuid : readyIds) {
            drainOne(uuid);
        }
    }

    /** What the drain does with a picked-up row — pure, pinned by the
     * DB-free tier. */
    enum ClaimOutcome { SEND, PARK_FAILED, SKIP }

    /**
     * The claim rule: only READY rows are touched (the row may have been
     * sent by an overlapping run since the id was listed); a READY row
     * that already spent {@link #MAX_ATTEMPTS} tries is parked, not
     * retried.
     */
    static List<MailStatus> dispatchableStatuses(boolean conferenceEnabled) {
        return conferenceEnabled ? List.of(MailStatus.READY, MailStatus.POLICY_READY) : List.of(MailStatus.READY);
    }

    static ClaimOutcome claimOutcome(MailStatus status, int attemptCount) {
        return claimOutcome(status, attemptCount, true);
    }

    static ClaimOutcome claimOutcome(MailStatus status, int attemptCount, boolean conferenceEnabled) {
        if (status == null || !dispatchableStatuses(conferenceEnabled).contains(status)) {
            return ClaimOutcome.SKIP;
        }
        return attemptCount >= MAX_ATTEMPTS ? ClaimOutcome.PARK_FAILED : ClaimOutcome.SEND;
    }

    /** After a failed try: park at the attempt ceiling, else stay READY. */
    static MailStatus statusAfterFailure(int attemptCount) {
        return attemptCount >= MAX_ATTEMPTS ? MailStatus.FAILED : MailStatus.READY;
    }

    /**
     * Exception class + message, truncated to the {@code last_error}
     * column — production sql_mode is STRICT_TRANS_TABLES, so an
     * over-long error string would 1406 the bookkeeping UPDATE and turn
     * one failure into another.
     */
    static String truncatedError(Throwable failure, int maxLength) {
        String text = failure.getClass().getSimpleName()
                + (failure.getMessage() != null ? ": " + failure.getMessage() : "");
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private void drainOne(String uuid) {
        TrustworksMail claimed = QuarkusTransaction.requiringNew().call(() -> {
            TrustworksMail mail = TrustworksMail.findById(uuid);
            if (mail == null) {
                return null;
            }
            if (mail.getMailOrigin() == null && (mail.getStatus() == MailStatus.READY || mail.getStatus() == MailStatus.POLICY_READY)) {
                mail.setStatus(MailStatus.HELD);
                mail.setHoldReason("LEGACY_UNCLASSIFIED");
                return null;
            }
            if ("CONFERENCE".equals(mail.getMailOrigin()) && !conferenceDispatch.isEnabled()) return null;
            switch (claimOutcome(mail.getStatus(), mail.getAttemptCount(), conferenceDispatch.isEnabled())) {
                case SKIP -> {
                    return null;
                }
                case PARK_FAILED -> {
                    // Backstop for tries that never reached the failure
                    // bookkeeping (JVM death mid-send).
                    mail.setStatus(MailStatus.FAILED);
                    log.errorf("Mail %s parked as FAILED after %d attempts (last error: %s)",
                            uuid, mail.getAttemptCount(), mail.getLastError());
                    return null;
                }
                case SEND -> mail.setAttemptCount(mail.getAttemptCount() + 1);
            }
            return mail;
        });
        if (claimed == null) {
            return;
        }
        // The entity is detached here; only already-loaded scalars are read.
        try {
            log.infof("Sending queued mail %s (attempt %d)", uuid, claimed.getAttemptCount());
            boolean conference = "CONFERENCE".equals(claimed.getMailOrigin());
            if (conference) requireConferenceMail(claimed);
            String html = conference ? conferenceDispatch.prepare(claimed.getConferenceUuid(), claimed.getTo(),
                    claimed.getBody(), claimed.getUnsubscribeFooter()) : claimed.getBody();
            if (html == null) { markSkipped(uuid); return; }
            Mail outgoing = applyHeaders(Mail.withHtml(claimed.getTo(), claimed.getSubject(), html), claimed);
            if (conference && !conferenceDispatch.allowedAtDispatch(claimed.getConferenceUuid(), claimed.getTo())) {
                markSkipped(uuid); return;
            }
            mailer.send(outgoing);
            QuarkusTransaction.requiringNew().run(() ->
                    TrustworksMail.update("status = ?1 where uuid = ?2", MailStatus.SENT, uuid));
        } catch (Exception e) {
            String error = "CONFERENCE".equals(claimed.getMailOrigin()) ? "CONFERENCE_DELIVERY_FAILED" : truncatedError(e, LAST_ERROR_MAX_LENGTH);
            MailStatus after = statusAfterFailure(claimed.getAttemptCount());
            if (after == MailStatus.READY && "CONFERENCE".equals(claimed.getMailOrigin())) after = MailStatus.POLICY_READY;
            final MailStatus nextStatus = after;
            QuarkusTransaction.requiringNew().run(() ->
                    TrustworksMail.update("lastError = ?1, status = ?2 where uuid = ?3",
                            error, nextStatus, uuid));
            log.warnf("Mail %s send attempt %d failed%s: %s", uuid, claimed.getAttemptCount(),
                    after == MailStatus.FAILED ? " — parked as FAILED" : "", error);
        }
    }

    private void markSkipped(String uuid) {
        QuarkusTransaction.requiringNew().run(() -> TrustworksMail.update(
                "status = ?1, skipReason = ?2 where uuid = ?3", MailStatus.SKIPPED, "UNSUBSCRIBED", uuid));
    }

    /**
     * Apply the optional sender/reply/copy headers persisted since V455.
     * Every field is nullable and skipped when absent, so a pre-V455 caller
     * produces exactly the message it always did.
     * <p>
     * {@code fromName} only decorates the configured
     * {@code quarkus.mailer.from} address ("Name &lt;addr&gt;") — the
     * envelope address is never swapped, because SES rejects unverified
     * sender identities with a 554.
     */
    Mail applyHeaders(Mail out, TrustworksMail mail) {
        if (isSet(mail.getFromName())) {
            out.setFrom(mail.getFromName().trim() + " <" + defaultFrom + ">");
        }
        if (isSet(mail.getReplyTo())) {
            out.setReplyTo(mail.getReplyTo().trim());
        }
        for (String cc : splitAddresses(mail.getCc())) {
            out.addCc(cc);
        }
        for (String bcc : splitAddresses(mail.getBcc())) {
            out.addBcc(bcc);
        }
        return out;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Split a stored comma-separated address list; empty for null/blank. */
    private static List<String> splitAddresses(String raw) {
        if (!isSet(raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    @Deprecated
    public void sendingNis2Mail(String mailTo) {
        throw new IllegalStateException("Legacy conference sender requires typed list/participant context");
    }

    @Deprecated
    public void sendingWaitingListMail(String mailTo) {
        throw new IllegalStateException("Legacy conference sender requires typed list/participant context");
    }

    @Deprecated
    public void sendingInvitationMail(String mailTo) {
        throw new IllegalStateException("Legacy conference sender requires typed list/participant context");
    }

    @Deprecated
    public void sendingDenyMail(String mailTo) {
        throw new IllegalStateException("Legacy conference sender requires typed list/participant context");
    }

    @Deprecated
    public void sendingWithdrawMail(String mailTo) {
        throw new IllegalStateException("Legacy conference sender requires typed list/participant context");
    }


    public void sendingMail(String to, String subject, String body) {
        log.info("MailResource.sendingMail");
        log.info("to = " + to);
        log.info("subject = " + subject);
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), to, subject, body);
        mail.setMailOrigin("SYSTEM");
        mail.setStatus(MailStatus.READY);
        mail.persist();
    }

    /**
     * Send an email immediately with attachments.
     * This method bypasses the queue and sends the email directly.
     *
     * @param trustworksMail the email with attachments to send
     * @throws RuntimeException if email sending fails
     */
    public void sendWithAttachments(TrustworksMail trustworksMail) {
        requireSystemMail(trustworksMail);
        sendImmediate(trustworksMail, false);
    }

    public boolean sendConferenceWithAttachments(TrustworksMail trustworksMail) {
        requireConferenceMail(trustworksMail);
        return sendImmediate(trustworksMail, true);
    }

    private boolean sendImmediate(TrustworksMail source, boolean conference) {
        String html = conference ? conferenceDispatch.prepare(source.getConferenceUuid(), source.getTo(),
                source.getBody(), source.getUnsubscribeFooter()) : source.getBody();
        if (html == null) return false;
        Mail outgoing = applyHeaders(Mail.withHtml(source.getTo(), source.getSubject(), html), source);
        if (source.hasAttachments()) for (EmailAttachment attachment : source.getAttachments()) {
            outgoing.addAttachment(attachment.getFilename(), attachment.getContent(), attachment.getContentType());
        }
        if (conference && !conferenceDispatch.allowedAtDispatch(source.getConferenceUuid(), source.getTo())) return false;
        try {
            mailer.send(outgoing);
            return true;
        } catch (Exception e) {
            // Mailer exception text can contain recipient-specific HTML/capability URLs.
            if (conference) throw new IllegalStateException("CONFERENCE_DELIVERY_FAILED");
            throw e;
        }
    }
}
