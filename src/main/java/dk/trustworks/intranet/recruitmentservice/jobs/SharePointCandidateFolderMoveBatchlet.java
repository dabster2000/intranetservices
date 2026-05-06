package dk.trustworks.intranet.recruitmentservice.jobs;

import dk.trustworks.intranet.batch.monitoring.MonitoredBatchlet;
import dk.trustworks.intranet.recruitmentservice.services.SharePointEmployeeFolderService;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.Objects;

/**
 * Periodic post-commit job placeholder — to be repurposed in Task 9 of the
 * recruitment-page-improvements plan as an S3 retention cleanup batchlet.
 * <p>
 * The previous responsibility (copy SharePoint folder on hire) has moved to
 * {@code CandidateConversionUseCase}'s synchronous promote flow via
 * {@link SharePointEmployeeFolderService#copyToEmployeeFolder}.
 */
@JBossLog
@Dependent
@Named("sharepointCandidateFolderMoveBatchlet")
public class SharePointCandidateFolderMoveBatchlet extends MonitoredBatchlet {

    @Inject
    SharePointEmployeeFolderService folderService;

    @Override
    @Transactional
    protected String doProcess() throws Exception {
        // Body cleared in Task 7. The batchlet will be repurposed in Task 9
        // of the recruitment-page-improvements plan (S3 retention cleanup).
        // Until then it is a no-op so the schedule wiring keeps compiling.
        log.debug("SharePointCandidateFolderMoveBatchlet: no-op (awaiting Task 9 rewrite)");
        // Touch the field so the IDE/Compiler sees it as used; a no-op call
        // would still prove DI works once the new method is in place.
        Objects.requireNonNull(folderService);
        return "COMPLETED: no-op";
    }
}
