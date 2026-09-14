package dk.trustworks.intranet.aggregates.crm.retention.jobs;

import dk.trustworks.intranet.aggregates.crm.retention.services.CrmRetentionPurgeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The first of the two switches, on its own.
 *
 * <p>{@code dk.trustworks.crm.retention.purge.enabled} stops the job from starting at all —
 * so far off that not even a run row is written. That is deliberately a different thing from
 * the {@code app_settings} arming row, which lets the run happen and files a {@code STOPPED}
 * row saying it deleted nothing. Conflating the two is how a destructive job ends up either
 * invisible when it is stopped or armed when it was only meant to be running, so the two are
 * tested apart: this class owns the kill switch, {@code CrmRetentionFeatureFlagTest} owns the
 * arming row.
 */
@ExtendWith(MockitoExtension.class)
class CrmRetentionPurgeJobTest {

    @Mock
    private CrmRetentionPurgeService purgeService;

    @InjectMocks
    private CrmRetentionPurgeJob job;

    @Test
    @DisplayName("the kill switch off means the service is never even reached")
    void killSwitchOffNeverStartsARun() {
        job.purgeEnabled = false;

        job.nightly();

        verify(purgeService, never()).nightly();
    }

    @Test
    @DisplayName("the kill switch on hands the night over to the service, which decides whether it is armed")
    void killSwitchOnRunsTheSweep() {
        job.purgeEnabled = true;

        job.nightly();

        verify(purgeService).nightly();
    }
}
