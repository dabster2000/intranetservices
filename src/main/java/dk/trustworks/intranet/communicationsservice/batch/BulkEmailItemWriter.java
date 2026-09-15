package dk.trustworks.intranet.communicationsservice.batch;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceMailDispatch;
import dk.trustworks.intranet.communicationsservice.batch.BulkEmailItemReader.BulkEmailContext;
import dk.trustworks.intranet.communicationsservice.model.*;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemWriter;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** One fresh policy check and committed result per recipient, outside the throttled chunk snapshot. */
@JBossLog
@Named("bulkEmailItemWriter")
@Dependent
public class BulkEmailItemWriter implements ItemWriter {
    @Inject Mailer mailer;
    @Inject BulkEmailService bulkEmailService;
    @Inject ConferenceMailDispatch conferenceDispatch;
    @Inject StepContext stepContext;
    @Inject @BatchProperty(name = "throttleMs") String throttleMsStr;
    private long throttleMs;

    @Override public void open(Serializable checkpoint) {
        try { throttleMs = throttleMsStr == null || throttleMsStr.isBlank() ? 5000L : Long.parseLong(throttleMsStr); }
        catch (NumberFormatException e) { throttleMs = 5000L; }
    }

    @Override
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public void writeItems(List<Object> items) {
        BulkEmailContext context = (BulkEmailContext) stepContext.getTransientUserData();
        if (context == null) throw new IllegalStateException("Missing bulk email context");
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof BulkEmailRecipient recipient)) continue;
            sendEmailToRecipient(recipient, context.job, context.attachments);
            if (i < items.size() - 1 && throttleMs > 0) {
                try { Thread.sleep(throttleMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Bulk mail interrupted"); }
            }
        }
        QuarkusTransaction.requiringNew().run(() -> bulkEmailService.updateJobCounts(context.job.getUuid()));
    }

    void sendEmailToRecipient(BulkEmailRecipient recipient, BulkEmailJob job, List<BulkEmailAttachment> attachments) {
        // A release/rollback pause preserves pending recipients and never turns them into failures.
        if ("CONFERENCE".equals(job.getMailOrigin()) && !conferenceDispatch.isEnabled()) return;
        // Recheck saved state: held jobs and already-finalized recipients are never dispatched.
        boolean pending = QuarkusTransaction.requiringNew().call(() -> {
            BulkEmailJob current = BulkEmailJob.findById(job.getUuid());
            BulkEmailRecipient target = BulkEmailRecipient.findById(recipient.getId());
            if (current == null || target == null || current.getStatus() == BulkEmailJob.BulkEmailJobStatus.HELD
                    || target.getStatus() != BulkEmailRecipient.RecipientStatus.PENDING) return false;
            if (current.getMailOrigin() == null) {
                current.setStatus(BulkEmailJob.BulkEmailJobStatus.HELD);
                current.setHoldReason("LEGACY_UNCLASSIFIED");
                return false;
            }
            return true;
        });
        if (!pending) return;
        boolean conference = "CONFERENCE".equals(job.getMailOrigin());
        try {
            if (conference && (job.getConferenceUuid() == null || !job.getConferenceUuid().equals(recipient.getConferenceUuid())
                    || recipient.getParticipantUuid() == null || recipient.getNormalizedEmail() == null))
                throw new IllegalStateException("CONFERENCE_CONTEXT_REQUIRED");
            String html = conference ? conferenceDispatch.prepare(job.getConferenceUuid(), recipient.getRecipientEmail(),
                    dk.trustworks.intranet.aggregates.conference.services.ConferenceMailRenderer.personalize(job.getBody(), recipient.getRecipientName()),
                    job.getUnsubscribeFooter()) : job.getBody();
            if (html == null) { skipped(recipient); return; }
            Mail outgoing = Mail.withHtml(recipient.getRecipientEmail(), job.getSubject(), html);
            if (attachments != null) for (BulkEmailAttachment attachment : attachments)
                outgoing.addAttachment(attachment.getFilename(), attachment.getContent(), attachment.getContentType());
            if (conference && !conferenceDispatch.allowedAtDispatch(job.getConferenceUuid(), recipient.getRecipientEmail())) {
                skipped(recipient); return;
            }
            mailer.send(outgoing);
            QuarkusTransaction.requiringNew().run(() -> BulkEmailRecipient.update(
                    "status = ?1, sentAt = ?2 where id = ?3", BulkEmailRecipient.RecipientStatus.SENT,
                    LocalDateTime.now(), recipient.getId()));
        } catch (Exception e) {
            // Never persist/log SMTP exception content: it may contain finalized capability URLs.
            QuarkusTransaction.requiringNew().run(() -> BulkEmailRecipient.update(
                    "status = ?1, errorMessage = ?2 where id = ?3", BulkEmailRecipient.RecipientStatus.FAILED,
                    conference ? "CONFERENCE_DELIVERY_FAILED" : "MAIL_DELIVERY_FAILED", recipient.getId()));
            log.warnf("Bulk recipient delivery failed: job=%s recipientId=%s", job.getUuid(), recipient.getId());
        }
    }

    private void skipped(BulkEmailRecipient recipient) {
        QuarkusTransaction.requiringNew().run(() -> BulkEmailRecipient.update(
                "status = ?1, skipReason = ?2 where id = ?3", BulkEmailRecipient.RecipientStatus.SKIPPED,
                "UNSUBSCRIBED", recipient.getId()));
    }
    @Override public Serializable checkpointInfo() { return null; }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void close() {
        BulkEmailContext context = (BulkEmailContext) stepContext.getTransientUserData();
        if (context == null) return;
        bulkEmailService.updateJobCounts(context.job.getUuid());
        BulkEmailJob current = BulkEmailJob.findById(context.job.getUuid());
        if (current == null || current.getStatus() == BulkEmailJob.BulkEmailJobStatus.HELD) return;
        int finished = current.getSentCount() + current.getFailedCount() + current.getSkippedCount();
        if (finished == current.getTotalRecipients())
            bulkEmailService.updateJobStatus(current.getUuid(), BulkEmailJob.BulkEmailJobStatus.COMPLETED);
        else
            bulkEmailService.updateJobStatus(current.getUuid(), "CONFERENCE".equals(current.getMailOrigin())
                    ? BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING : BulkEmailJob.BulkEmailJobStatus.PENDING);
    }
}
