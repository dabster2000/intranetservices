package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.arc.ClientProxy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The legacy files re-home deletes source objects from the {@code bucket.files}
 * bucket, which is SHARED between staging and production. The
 * {@code dk.trustworks.employee-documents.migration.legacy-rehome.enabled}
 * guard (default false) must therefore keep a real Copy run from touching the
 * legacy rows anywhere the flag is not explicitly armed (production only).
 *
 * <p>Runs against the local Docker DB (see
 * reference-run-quarkustest-against-local-db) — V457's working tables must
 * exist there.</p>
 */
@QuarkusTest
class SharePointMigrationCopyLegacyGuardIntegrationTest {

    @Inject
    SharePointMigrationCopyService copyService;

    @Inject
    DocumentMigrationJobRunner jobRunner;

    @InjectMock
    S3Client s3Client;

    private String userUuid;
    private String fileUuid;

    @BeforeEach
    void seed() {
        userUuid = UUID.randomUUID().toString();
        fileUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            // Empty working tables ⇒ the SharePoint sub-job is a deterministic
            // no-op and the run can never attempt a real Graph call.
            SharePointMigrationItem.deleteAll();
            SharePointMigrationFolder.deleteAll();
            persistUser(userUuid);
            File legacy = new File();
            legacy.setUuid(fileUuid);
            legacy.setRelateduuid(userUuid);
            legacy.setType("DOCUMENT");
            legacy.setFilename("ansaettelseskontrakt.pdf");
            legacy.setUploaddate(LocalDate.of(2021, 3, 1));
            legacy.persist();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            File.deleteById(fileUuid);
            EmployeeDocument.delete("migratedFrom", "files:" + fileUuid);
            UserStatus.delete("useruuid", userUuid);
            User.deleteById(userUuid);
        });
    }

    @Test
    void realCopyWithGuardOffLeavesLegacyRowsUntouched() {
        ClientProxy.unwrap(copyService).legacyRehomeEnabled = false;

        SharePointMigrationCopyService.CopySummary summary = copyService.copy(false);

        assertTrue(summary.legacyRehomeDisabled(), "summary must announce the disabled guard");
        assertTrue(summary.legacyCandidates() >= 1, "candidates are still counted");
        assertEquals(0, summary.legacyCopied(), "nothing may be copied while disabled");
        assertEquals(0, summary.legacyFailed());

        // The files row survives, no employee document was created, and the
        // S3 delete path was never reached.
        assertNotNull(QuarkusTransaction.requiringNew().call(() -> File.findById(fileUuid)));
        assertNull(QuarkusTransaction.requiringNew().call(() ->
                EmployeeDocument.find("migratedFrom", "files:" + fileUuid).firstResult()));
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void dryRunStillCountsCandidatesAndAnnouncesTheGuard() {
        ClientProxy.unwrap(copyService).legacyRehomeEnabled = false;

        SharePointMigrationCopyService.CopySummary summary = copyService.copy(true);

        assertTrue(summary.dryRun());
        assertTrue(summary.legacyRehomeDisabled(),
                "dry-run summaries must also show that this env will not re-home");
        assertTrue(summary.legacyCandidates() >= 1);
        assertEquals(0, summary.legacyCopied());
        assertNotNull(QuarkusTransaction.requiringNew().call(() -> File.findById(fileUuid)));
    }

    @Test
    void dryRunCopyRunsThroughTheJobRunnerLikeProduction() throws Exception {
        // The 2026-07-28 staging rehearsal's very first dry-run died with
        // "neither a transaction nor a CDI request context is active":
        // parameters.uploadMaxSizeBytes() (and storeMigrated's reads on the
        // real-copy path) run bare on the ManagedExecutor thread. Submit from
        // a bare thread so no request context can propagate — exactly the
        // production shape (the HTTP request's context is gone by run time).
        ClientProxy.unwrap(copyService).legacyRehomeEnabled = false;

        ExecutorService bare = Executors.newSingleThreadExecutor();
        try {
            bare.submit(() -> jobRunner.start(
                    DocumentMigrationJobRunner.JobType.COPY_DRY_RUN,
                    () -> copyService.copy(true))).get(10, TimeUnit.SECONDS);
        } finally {
            bare.shutdownNow();
        }

        for (int i = 0; i < 120 && jobRunner.status().running(); i++) {
            Thread.sleep(250);
        }
        DocumentMigrationJobRunner.JobStatus status = jobRunner.status();
        assertFalse(status.running(), "dry-run should have finished");
        assertNull(status.error(), "dry-run must not die on the job-runner thread: " + status.error());
        assertNotNull(status.summary());
    }

    @Test
    void dryRunWithGuardArmedDoesNotSetTheDisabledFlag() {
        ClientProxy.unwrap(copyService).legacyRehomeEnabled = true;
        try {
            SharePointMigrationCopyService.CopySummary summary = copyService.copy(true);

            assertTrue(summary.dryRun());
            assertFalse(summary.legacyRehomeDisabled());
            assertTrue(summary.legacyCandidates() >= 1);
            // Dry-run never writes regardless of the guard.
            assertNotNull(QuarkusTransaction.requiringNew().call(() -> File.findById(fileUuid)));
        } finally {
            ClientProxy.unwrap(copyService).legacyRehomeEnabled = false;
        }
    }

    private void persistUser(String uuid) {
        User user = new User();
        user.setUuid(uuid);
        user.setUsername("legacy.guard.tester");
        user.setFirstname("Legacy");
        user.setLastname("Guard-Test");
        user.setEmail("legacy.guard.tester@test.local");
        user.persist();

        UserStatus status = new UserStatus(UUID.randomUUID().toString(), ConsultantType.CONSULTANT,
                StatusType.ACTIVE, LocalDate.of(2020, 1, 1), 37, uuid);
        status.setCreatedAt(LocalDateTime.now());
        status.setUpdatedAt(LocalDateTime.now());
        status.setCreatedBy("legacy-guard-it");
        status.persist();
    }
}
