package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.AiConfidence;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.MatchMethod;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.services.AppSettingService;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Matcher stage flow against the real DB (runbook 2a-3 verify): exact
 * tiers auto-apply, AI proposals never auto-apply, hallucinated uuids
 * are rejected, toggle OFF ⇒ zero OpenAI calls, confirmations are
 * sticky across re-runs.
 *
 * <p>Runs against the local Docker DB (see
 * reference-run-quarkustest-against-local-db) — V457's working tables
 * must exist there.</p>
 */
@QuarkusTest
class SharePointMigrationMatcherIntegrationTest {

    private static final String SITE = "https://test.sharepoint.local/sites/matcher-it";
    private static final String AI_KEY = "employee_documents.migration.ai.enabled";

    @Inject
    SharePointFolderMatcherService matcher;

    @Inject
    AppSettingService appSettingService;

    @InjectMock
    OpenAIService openAIService;

    private String exactUserUuid;
    private String aiUserUuid;

    @BeforeEach
    void seed() {
        cleanupRows();
        exactUserUuid = UUID.randomUUID().toString();
        aiUserUuid = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> {
            persistUser(exactUserUuid, "mig.exact.tester", "Sørine", "Ærbødig-Test");
            persistUser(aiUserUuid, "mig.ai.tester", "Cleo", "Brunse-Test");
        });
        setAiFlag(false);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        setAiFlag(false);
    }

    // ── Exact tiers auto-apply ─────────────────────────────────────────────

    @Test
    void exactTiersAutoApplyAndAiTierNeverDoes() {
        long usernameFolder = persistFolder("mig.exact.tester");
        long fullnameFolder = persistFolder("Soerine Aerboedig-Test");
        long unknownFolder = persistFolder("Arkiv gamle kontrakter");

        matcher.match();

        SharePointMigrationFolder byUsername = findFolder(usernameFolder);
        assertEquals(FolderStatus.MAPPED, byUsername.getStatus());
        assertEquals(MatchMethod.USERNAME, byUsername.getMatchMethod());
        assertEquals(exactUserUuid, byUsername.getMatchedUserUuid());

        SharePointMigrationFolder byFullname = findFolder(fullnameFolder);
        assertEquals(FolderStatus.MAPPED, byFullname.getStatus());
        assertEquals(MatchMethod.FULLNAME, byFullname.getMatchMethod());
        assertEquals(exactUserUuid, byFullname.getMatchedUserUuid());

        SharePointMigrationFolder unmatched = findFolder(unknownFolder);
        assertEquals(FolderStatus.DISCOVERED, unmatched.getStatus());
        assertEquals(MatchMethod.UNMATCHED, unmatched.getMatchMethod());

        // Toggle OFF ⇒ the AI stage must never have been consulted.
        verify(openAIService, never()).askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean());
    }

    // ── AI proposals: durable, validated, never auto-applied ───────────────

    @Test
    void aiProposalIsWrittenButNeverAutoApplied_andConfirmIsSticky() {
        long folderId = persistFolder("Cleo W Brunse - (Marketing Junior)");
        setAiFlag(true);
        when(openAIService.askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> """
                        {"matches":[{"folderId":%d,"userUuid":"%s","confidence":"HIGH",
                        "reason":"Folder name is her full name with a role suffix"}]}"""
                        .formatted(folderId, aiUserUuid));

        matcher.match();

        SharePointMigrationFolder proposed = findFolder(folderId);
        assertEquals(FolderStatus.DISCOVERED, proposed.getStatus(), "AI proposals must never auto-apply");
        assertNull(proposed.getMatchedUserUuid());
        assertEquals(aiUserUuid, proposed.getAiSuggestedUserUuid());
        assertEquals(AiConfidence.HIGH, proposed.getAiConfidence());

        // One click Confirm ⇒ AI_CONFIRMED + MAPPED.
        matcher.confirmSuggestion(folderId);
        SharePointMigrationFolder confirmed = findFolder(folderId);
        assertEquals(FolderStatus.MAPPED, confirmed.getStatus());
        assertEquals(MatchMethod.AI_CONFIRMED, confirmed.getMatchMethod());
        assertEquals(aiUserUuid, confirmed.getMatchedUserUuid());

        // Sticky across a re-run: nothing about the row changes.
        matcher.match();
        SharePointMigrationFolder afterRerun = findFolder(folderId);
        assertEquals(MatchMethod.AI_CONFIRMED, afterRerun.getMatchMethod());
        assertEquals(aiUserUuid, afterRerun.getMatchedUserUuid());
    }

    @Test
    void hallucinatedUserUuidIsRejected() {
        long folderId = persistFolder("Somebody Unknown");
        setAiFlag(true);
        when(openAIService.askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> """
                        {"matches":[{"folderId":%d,"userUuid":"00000000-dead-beef-0000-000000000000",
                        "confidence":"HIGH","reason":"made up"}]}""".formatted(folderId));

        SharePointFolderMatcherService.MatchSummary summary = matcher.match();

        assertEquals(1, summary.aiRejected());
        SharePointMigrationFolder folder = findFolder(folderId);
        assertNull(folder.getAiSuggestedUserUuid(), "a hallucinated uuid must never be written");
        assertEquals(FolderStatus.DISCOVERED, folder.getStatus());
    }

    @Test
    void matchRunsOnABareWorkerThreadLikeTheJobRunner() throws Exception {
        // The job runner executes match() on a ManagedExecutor thread whose
        // originating request context is already gone — an unmatched folder
        // forces the AI-flag read, which crashed there before the fix.
        long folderId = persistFolder("Arkiv uden ejer");

        ExecutorService bare = Executors.newSingleThreadExecutor();
        try {
            bare.submit(() -> matcher.match()).get(60, TimeUnit.SECONDS);
        } finally {
            bare.shutdownNow();
        }

        SharePointMigrationFolder folder = findFolder(folderId);
        assertEquals(FolderStatus.DISCOVERED, folder.getStatus());
        verify(openAIService, never()).askQuestionWithSchema(
                anyString(), anyString(), any(), anyString(), any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void manualMappingAndSkipAreSticky() {
        long manualFolder = persistFolder("Dept folder A");
        long skipFolder = persistFolder("Dept folder B");

        matcher.mapManually(manualFolder, exactUserUuid);
        matcher.skipFolder(skipFolder, "Department folder — HR decision 2a-8");

        matcher.match();

        assertEquals(MatchMethod.MANUAL, findFolder(manualFolder).getMatchMethod());
        assertEquals(FolderStatus.MAPPED, findFolder(manualFolder).getStatus());
        assertEquals(FolderStatus.SKIPPED, findFolder(skipFolder).getStatus());
    }

    // ── Fixtures ───────────────────────────────────────────────────────────

    private void persistUser(String uuid, String username, String firstname, String lastname) {
        User user = new User();
        user.setUuid(uuid);
        user.setUsername(username);
        user.setFirstname(firstname);
        user.setLastname(lastname);
        user.setEmail(username + "@test.local");
        user.persist();

        UserStatus status = new UserStatus(UUID.randomUUID().toString(), ConsultantType.CONSULTANT,
                StatusType.ACTIVE, LocalDate.of(2020, 1, 1), 37, uuid);
        status.setCreatedAt(LocalDateTime.now());
        status.setUpdatedAt(LocalDateTime.now());
        status.setCreatedBy("matcher-it");
        status.persist();
    }

    private long persistFolder(String folderName) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SharePointMigrationFolder folder = new SharePointMigrationFolder();
            folder.setSiteUrl(SITE);
            folder.setFolderPath(folderName);
            folder.setFolderName(folderName);
            folder.persist();
            return folder.getId();
        });
    }

    private SharePointMigrationFolder findFolder(long id) {
        return QuarkusTransaction.requiringNew().call(() ->
                SharePointMigrationFolder.findById(id));
    }

    private void setAiFlag(boolean enabled) {
        appSettingService.saveSetting(AI_KEY, String.valueOf(enabled),
                "employee_documents", "matcher-it");
    }

    private void cleanupRows() {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder.delete("siteUrl", SITE);
            if (exactUserUuid != null) {
                UserStatus.delete("useruuid", exactUserUuid);
                User.delete("uuid", exactUserUuid);
            }
            if (aiUserUuid != null) {
                UserStatus.delete("useruuid", aiUserUuid);
                User.delete("uuid", aiUserUuid);
            }
        });
    }
}
