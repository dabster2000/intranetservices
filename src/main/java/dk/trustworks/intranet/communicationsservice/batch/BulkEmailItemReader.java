package dk.trustworks.intranet.communicationsservice.batch;

import dk.trustworks.intranet.communicationsservice.model.BulkEmailAttachment;
import dk.trustworks.intranet.communicationsservice.model.BulkEmailJob;
import dk.trustworks.intranet.communicationsservice.model.BulkEmailRecipient;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import jakarta.batch.api.chunk.ItemReader;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * ItemReader for bulk email batch job.
 * Loads a pending bulk email job and reads its recipients one by one.
 * Attachments are loaded once and stored in step context for reuse.
 */
@JBossLog
@Named("bulkEmailItemReader")
@Dependent
public class BulkEmailItemReader implements ItemReader {

    @Inject
    BulkEmailService bulkEmailService;

    @Inject
    StepContext stepContext;

    private List<BulkEmailRecipient> recipients;
    private int index;
    private String currentJobUuid;

    @Override
    @Transactional
    public void open(Serializable checkpoint) throws Exception {
        index = (checkpoint instanceof Integer) ? (Integer) checkpoint : 0;

        // Find the first pending job
        List<BulkEmailJob> pendingJobs = bulkEmailService.findPendingJobs();

        if (pendingJobs.isEmpty()) {
            log.info("No pending bulk email jobs found");
            recipients = new ArrayList<>();
            return;
        }

        BulkEmailJob job = pendingJobs.get(0);
        currentJobUuid = job.getUuid();

        log.info("BulkEmailItemReader: Processing job " + currentJobUuid +
                 " - Subject: '" + job.getSubject() + "', Recipients: " + job.getTotalRecipients());

        // Mark job as PROCESSING
        bulkEmailService.updateJobStatus(currentJobUuid, BulkEmailJob.BulkEmailJobStatus.PROCESSING);

        // Load all pending recipients
        recipients = bulkEmailService.findPendingRecipients(currentJobUuid);

        // Load attachments and store in step context for reuse by writer
        List<BulkEmailAttachment> attachments = bulkEmailService.findAttachments(currentJobUuid);
        stepContext.setTransientUserData(new BulkEmailContext(job, attachments));

        log.info("BulkEmailItemReader opened: job=" + currentJobUuid +
                 ", pendingRecipients=" + recipients.size() +
                 ", attachments=" + attachments.size());
    }

    @Override
    public Object readItem() throws Exception {
        if (recipients == null || index >= recipients.size()) {
            return null; // No more items
        }
        return recipients.get(index++);
    }

    @Override
    public Serializable checkpointInfo() throws Exception {
        return index;
    }

    @Override
    public void close() throws Exception {
        log.info("BulkEmailItemReader closed: job=" + currentJobUuid +
                 ", processedRecipients=" + index);
    }

    /**
     * Context object stored in step transient user data.
     * Contains job metadata and attachments shared across all recipients.
     */
    public static class BulkEmailContext implements Serializable {
        public final BulkEmailJob job;
        public final List<BulkEmailAttachment> attachments;

        public BulkEmailContext(BulkEmailJob job, List<BulkEmailAttachment> attachments) {
            this.job = job;
            this.attachments = attachments;
        }
    }
}
