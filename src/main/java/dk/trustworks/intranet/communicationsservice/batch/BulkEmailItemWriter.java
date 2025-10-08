package dk.trustworks.intranet.communicationsservice.batch;

import dk.trustworks.intranet.communicationsservice.batch.BulkEmailItemReader.BulkEmailContext;
import dk.trustworks.intranet.communicationsservice.model.BulkEmailAttachment;
import dk.trustworks.intranet.communicationsservice.model.BulkEmailJob;
import dk.trustworks.intranet.communicationsservice.model.BulkEmailRecipient;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
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

/**
 * ItemWriter for bulk email batch job.
 * Sends emails with configurable throttling (default 5 seconds between sends).
 * Updates recipient status after each send attempt.
 */
@JBossLog
@Named("bulkEmailItemWriter")
@Dependent
public class BulkEmailItemWriter implements ItemWriter {

    @Inject
    Mailer mailer;

    @Inject
    BulkEmailService bulkEmailService;

    @Inject
    StepContext stepContext;

    @Inject
    @BatchProperty(name = "throttleMs")
    String throttleMsStr;

    private long throttleMs;
    private int processedCount;
    private int sentCount;
    private int failedCount;
    private long startNs;

    @Override
    public void open(Serializable checkpoint) throws Exception {
        processedCount = 0;
        sentCount = 0;
        failedCount = 0;
        startNs = System.nanoTime();

        // Parse throttle delay (default 5000ms = 5 seconds)
        try {
            throttleMs = (throttleMsStr == null || throttleMsStr.isBlank())
                    ? 5000L
                    : Long.parseLong(throttleMsStr);
        } catch (Exception e) {
            throttleMs = 5000L;
        }

        log.info("BulkEmailItemWriter opened: throttleMs=" + throttleMs);
    }

    @Override
    @Transactional
    public void writeItems(List<Object> items) throws Exception {
        BulkEmailContext context = (BulkEmailContext) stepContext.getTransientUserData();

        if (context == null) {
            log.error("BulkEmailContext not found in step context");
            return;
        }

        BulkEmailJob job = context.job;
        List<BulkEmailAttachment> attachments = context.attachments;

        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof BulkEmailRecipient recipient)) {
                continue;
            }

            boolean isLastInBatch = (i == items.size() - 1);
            sendEmailToRecipient(recipient, job, attachments, isLastInBatch);
        }

        // Update job counters after processing this chunk
        bulkEmailService.updateJobCounts(job.getUuid());

        // Log progress every chunk
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        log.info("Bulk email progress: job=" + job.getUuid() +
                 ", processed=" + processedCount +
                 ", sent=" + sentCount +
                 ", failed=" + failedCount +
                 ", elapsedMs=" + elapsedMs);
    }

    /**
     * Send email to a single recipient with throttling
     */
    private void sendEmailToRecipient(BulkEmailRecipient recipient,
                                      BulkEmailJob job,
                                      List<BulkEmailAttachment> attachments,
                                      boolean isLastInBatch) {
        try {
            log.info("Sending bulk email to: " + recipient.getRecipientEmail() +
                     " (job=" + job.getUuid() + ")");

            // Create email
            Mail mail = Mail.withHtml(
                    recipient.getRecipientEmail(),
                    job.getSubject(),
                    job.getBody()
            );

            // Add attachments if present
            if (attachments != null && !attachments.isEmpty()) {
                for (BulkEmailAttachment attachment : attachments) {
                    mail.addAttachment(
                            attachment.getFilename(),
                            attachment.getContent(),
                            attachment.getContentType()
                    );
                }
            }

            // Send email
            mailer.send(mail);

            // Mark as SENT (use UPDATE query for detached entity)
            BulkEmailRecipient.update(
                "status = ?1, sentAt = ?2 WHERE id = ?3",
                BulkEmailRecipient.RecipientStatus.SENT,
                LocalDateTime.now(),
                recipient.getId()
            );

            sentCount++;
            log.info("Successfully sent bulk email to: " + recipient.getRecipientEmail());

        } catch (Exception e) {
            // Mark as FAILED (use UPDATE query for detached entity)
            log.error("Failed to send bulk email to: " + recipient.getRecipientEmail(), e);
            BulkEmailRecipient.update(
                "status = ?1, errorMessage = ?2 WHERE id = ?3",
                BulkEmailRecipient.RecipientStatus.FAILED,
                e.getMessage(),
                recipient.getId()
            );

            failedCount++;
        } finally {
            processedCount++;

            // Throttle (sleep) unless this is the last item in the batch
            if (!isLastInBatch && throttleMs > 0) {
                try {
                    Thread.sleep(throttleMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Throttle sleep interrupted");
                }
            }
        }
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return null;
    }

    @Override
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void close() throws Exception {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;

        // Get job from context to update final status
        BulkEmailContext context = (BulkEmailContext) stepContext.getTransientUserData();
        if (context != null) {
            BulkEmailJob job = context.job;

            // Update final counts
            bulkEmailService.updateJobCounts(job.getUuid());

            // Mark job as COMPLETED
            bulkEmailService.updateJobStatus(job.getUuid(), BulkEmailJob.BulkEmailJobStatus.COMPLETED);

            log.info("BulkEmailItemWriter completed: job=" + job.getUuid() +
                     ", processed=" + processedCount +
                     ", sent=" + sentCount +
                     ", failed=" + failedCount +
                     ", elapsedMs=" + elapsedMs);
        }
    }
}
