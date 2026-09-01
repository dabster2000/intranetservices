package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillItem;
import dk.trustworks.intranet.agreementservice.model.AgreementBackfillRun;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.enums.BackfillRunStatus;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Phase-4 corpus walk (template-clauses spec §10): every ACTIVE
 * employee's mapped SharePoint folder is enumerated <b>by files</b> —
 * live Graph listing with paging, never the migration tables' folder
 * aggregates, which the 84-empty-folders incident proved can read
 * "empty" while holding hundreds of files. Each new PDF is downloaded,
 * hashed and put through one extraction call; proposals land as
 * {@code PROPOSED} items for the human review queue.
 *
 * <p>Runs on the single-flight job-runner thread (the
 * {@code DocumentMigrationJobRunner} pattern): narrow
 * {@code requiringNew} transactions per write, politeness delay and
 * 429/503 backoff on every Graph call, live counter updates so the
 * console can show progress.</p>
 */
@JBossLog
@ApplicationScoped
public class AgreementBackfillWalkerService {

    static final int PAGE_SIZE = 200;
    static final int MAX_RETRIES = 5;
    static final long POLITENESS_DELAY_MS = 150;
    /** Contracts are a few MB; anything larger is not a signable document. */
    static final long MAX_FILE_BYTES = 40L * 1024 * 1024;

    @Inject
    UserService userService;

    @Inject
    SharePointService sharePointService;

    @RestClient
    GraphApiClient graphClient;

    @Inject
    AgreementExtractionService extractionService;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Console-facing summary; also folded into the run row. */
    public record WalkSummary(String runUuid, boolean dryRun, int employees, int foldersTotal,
                              int foldersWalked, int filesSeen, int filesSkipped, int documentsNew,
                              int proposalsCreated, int errors, List<String> notes) {
    }

    /**
     * Execute the walk for an already-persisted RUNNING run row. Never
     * throws: a terminal failure marks the run FAILED and is reported in
     * the summary.
     */
    public WalkSummary walk(String runUuid, boolean dryRun) {
        Counters counters = new Counters();
        try {
            // Corpus: active employees (incl. leave states — people on
            // leave still hold agreements) of every internal type.
            List<User> employees = QuarkusTransaction.requiringNew().call(() ->
                    userService.findEmployedUsersByDate(LocalDate.now(), true,
                            ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT));
            Set<String> activeUuids = employees.stream().map(User::getUuid).collect(Collectors.toSet());

            List<SharePointMigrationFolder> folders = QuarkusTransaction.requiringNew().call(() ->
                    SharePointMigrationFolder.<SharePointMigrationFolder>list(
                                    "matchedUserUuid IS NOT NULL AND status IN (?1, ?2, ?3)",
                                    FolderStatus.MAPPED, FolderStatus.COPYING, FolderStatus.VERIFIED)
                            .stream()
                            .filter(folder -> activeUuids.contains(folder.getMatchedUserUuid()))
                            .toList());

            counters.employees = employees.size();
            counters.foldersTotal = folders.size();
            Set<String> coveredUsers = folders.stream()
                    .map(SharePointMigrationFolder::getMatchedUserUuid).collect(Collectors.toSet());
            long uncovered = activeUuids.stream().filter(uuid -> !coveredUsers.contains(uuid)).count();
            if (uncovered > 0) {
                // No silent caps: an employee without a mapped folder is
                // invisible to the walk and HR must know.
                counters.note(uncovered + " active employees have no mapped SharePoint folder and were not walked");
            }
            updateRunCounters(runUuid, counters);

            List<String> typeKeys = QuarkusTransaction.requiringNew().call(() ->
                    AgreementType.<AgreementType>list("active", true).stream()
                            .map(AgreementType::getTypeKey).toList());

            // Resolve each site once (the crawler posture).
            Map<String, String> driveIdBySite = new HashMap<>();
            Map<String, List<SharePointMigrationFolder>> foldersBySite = folders.stream()
                    .collect(Collectors.groupingBy(SharePointMigrationFolder::getSiteUrl));

            for (Map.Entry<String, List<SharePointMigrationFolder>> site : foldersBySite.entrySet()) {
                String driveId;
                try {
                    driveId = driveIdBySite.computeIfAbsent(site.getKey(), this::resolveDriveId);
                } catch (Exception e) {
                    counters.error("Site unresolvable: " + site.getKey() + " (" + e.getMessage() + ")");
                    updateRunCounters(runUuid, counters);
                    continue;
                }
                for (SharePointMigrationFolder folder : site.getValue()) {
                    try {
                        walkFolder(runUuid, dryRun, driveId, folder, typeKeys, counters);
                        counters.foldersWalked++;
                    } catch (Exception e) {
                        counters.error("Folder failed: " + folder.getFolderPath() + " (" + e.getMessage() + ")");
                    }
                    updateRunCounters(runUuid, counters);
                }
            }

            finishRun(runUuid, counters, null);
            return counters.toSummary(runUuid, dryRun);
        } catch (Exception e) {
            log.errorf(e, "Agreement backfill run %s failed", runUuid);
            counters.error("Run failed: " + e.getMessage());
            finishRun(runUuid, counters, e.getMessage());
            return counters.toSummary(runUuid, dryRun);
        }
    }

    // ── Folder walk (BFS by files, the crawler posture) ─────────────────────

    private void walkFolder(String runUuid, boolean dryRun, String driveId,
                            SharePointMigrationFolder folder, List<String> typeKeys,
                            Counters counters) {
        String folderId;
        try {
            folderId = sharePointService.resolveFolderId(driveId, folder.getFolderPath());
        } catch (SharePointException e) {
            if (e.getStatusCode() == 404) {
                // Stale duplicate mapping (the Adam Hoppe case) — note, don't fail.
                counters.note("Folder gone in SharePoint: " + folder.getFolderPath());
                return;
            }
            throw e;
        }

        record Pending(String itemId, String relativePath) { }
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(folderId, ""));

        while (!queue.isEmpty()) {
            Pending current = queue.poll();
            for (DriveItem child : listAllChildren(driveId, current.itemId())) {
                if (child.isFolder()) {
                    if (isExcludedFolder(child.name())) {
                        counters.filesSkipped++;
                        continue;
                    }
                    queue.add(new Pending(child.id(),
                            current.relativePath().isEmpty() ? child.name()
                                    : current.relativePath() + "/" + child.name()));
                    continue;
                }
                if (!child.isFile()) {
                    continue;
                }
                counters.filesSeen++;
                if (!isCandidateFile(child)) {
                    counters.filesSkipped++;
                    continue;
                }
                try {
                    processFile(runUuid, dryRun, driveId, folder, child, typeKeys, counters);
                } catch (Exception e) {
                    counters.error("File failed: " + child.name() + " (" + e.getClass().getSimpleName() + ")");
                }
            }
        }
    }

    /**
     * PDFs only (spec §10.2 — extraction is PDFBox text + page-1
     * vision); temp files and zero-byte rows are the crawler posture.
     * Subfolders named for sickness records are excluded up front
     * (data minimization: health data never reaches the AI call).
     */
    static boolean isCandidateFile(DriveItem file) {
        String name = file.name() == null ? "" : file.name();
        if (name.startsWith("~$")) {
            return false;
        }
        if (file.size() == null || file.size() == 0 || file.size() > MAX_FILE_BYTES) {
            return false;
        }
        String mime = file.file() != null && file.file().mimeType() != null
                ? file.file().mimeType().toLowerCase(Locale.ROOT) : "";
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") || mime.equals("application/pdf");
    }

    /** Crawler exclusions + health-record subfolders (GDPR special category). */
    static boolean isExcludedFolder(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return name.equalsIgnoreCase("Forms") || name.startsWith(".") || name.startsWith("_")
                || lower.contains("sygdom") || lower.contains("sygemeld");
    }

    private void processFile(String runUuid, boolean dryRun, String driveId,
                             SharePointMigrationFolder folder, DriveItem file,
                             List<String> typeKeys, Counters counters) throws Exception {
        String userUuid = folder.getMatchedUserUuid();

        // Fast path: unchanged id+eTag was itemized before — no download.
        boolean unchanged = QuarkusTransaction.requiringNew().call(() ->
                AgreementBackfillItem.findByUserAndItemId(userUuid, file.id())
                        .map(existing -> existing.getETag() != null && existing.getETag().equals(file.eTag()))
                        .orElse(false));
        if (unchanged) {
            return;
        }

        if (dryRun) {
            counters.documentsNew++;
            return;
        }

        DriveItem fresh = politeGetItem(driveId, file.id());
        byte[] bytes = downloadBytes(driveId, fresh);
        String sha256 = sha256Hex(bytes);

        // Idempotency on content: already itemized (any review state) — skip.
        boolean known = QuarkusTransaction.requiringNew().call(() -> {
            var existing = AgreementBackfillItem.findByUserAndSha(userUuid, sha256);
            existing.ifPresent(item -> {
                // Keep the pointer fresh so the preview follows a moved file.
                item.setSharepointItemId(file.id());
                item.setETag(fresh.eTag() != null ? fresh.eTag() : file.eTag());
                if (fresh.webUrl() != null) {
                    item.setWebUrl(fresh.webUrl());
                }
            });
            return existing.isPresent();
        });
        if (known) {
            return;
        }

        AgreementExtractionService.ExtractionResult result = extractionService.extract(bytes, typeKeys);

        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillItem item = new AgreementBackfillItem();
            item.setRunUuid(runUuid);
            item.setUserUuid(userUuid);
            item.setSiteUrl(folder.getSiteUrl());
            item.setDriveId(driveId);
            item.setSharepointItemId(file.id());
            item.setETag(fresh.eTag() != null ? fresh.eTag() : file.eTag());
            item.setWebUrl(fresh.webUrl() != null ? fresh.webUrl() : file.webUrl());
            item.setFileName(file.name());
            item.setFileSize(file.size() == null ? bytes.length : file.size());
            item.setDocSha256(sha256);
            item.setStatus(result.status().name());
            item.setProposalJson(writeProposals(result.proposals()));
            item.setExtractionNote(result.note());
            item.persist();
        });
        counters.documentsNew++;
        counters.proposalsCreated += result.proposals().size();
    }

    private String writeProposals(List<AgreementExtractionService.Proposal> proposals) {
        if (proposals == null || proposals.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(proposals);
        } catch (Exception e) {
            log.warnf("Could not serialize %d proposals: %s", proposals.size(), e.getMessage());
            return null;
        }
    }

    // ── Graph plumbing (paging, politeness, downloads) ──────────────────────

    private String resolveDriveId(String siteUrl) {
        // Drive name from the matching sharepoint_locations row so the
        // walker resolves exactly what the crawler crawled.
        String driveName = QuarkusTransaction.requiringNew().call(() -> {
            SharePointLocationEntity location =
                    SharePointLocationEntity.find("siteUrl", siteUrl).firstResult();
            return location == null ? null : location.getDriveName();
        });
        String siteId = sharePointService.resolveSiteId(siteUrl);
        return sharePointService.resolveDriveId(siteId, driveName);
    }

    private List<DriveItem> listAllChildren(String driveId, String itemId) {
        List<DriveItem> all = new ArrayList<>();
        String skipToken = null;
        do {
            DriveItemCollectionResponse page = politeListChildren(driveId, itemId, skipToken);
            if (page.value() != null) {
                all.addAll(page.value());
            }
            skipToken = parseSkipToken(page.odataNextLink());
        } while (skipToken != null);
        return all;
    }

    private DriveItemCollectionResponse politeListChildren(String driveId, String itemId, String skipToken) {
        int attempt = 0;
        while (true) {
            pause(POLITENESS_DELAY_MS);
            try {
                return graphClient.listChildrenById(driveId, itemId, PAGE_SIZE, skipToken);
            } catch (SharePointException e) {
                attempt++;
                if ((e.getStatusCode() == 429 || e.getStatusCode() == 503) && attempt <= MAX_RETRIES) {
                    long backoffMs = 5000L * (1L << (attempt - 1));
                    log.warnf("Graph %d on listChildren (attempt %d/%d) — backing off %d ms",
                            e.getStatusCode(), attempt, MAX_RETRIES, backoffMs);
                    pause(backoffMs);
                    continue;
                }
                throw e;
            }
        }
    }

    private DriveItem politeGetItem(String driveId, String itemId) {
        int attempt = 0;
        while (true) {
            pause(POLITENESS_DELAY_MS);
            try {
                return graphClient.getItem(driveId, itemId);
            } catch (SharePointException e) {
                attempt++;
                if ((e.getStatusCode() == 429 || e.getStatusCode() == 503) && attempt <= MAX_RETRIES) {
                    long backoffMs = 5000L * (1L << (attempt - 1));
                    pause(backoffMs);
                    continue;
                }
                throw e;
            }
        }
    }

    /**
     * Download one itemized document again — the review console's PDF
     * preview. Fresh metadata first so a moved file still resolves.
     */
    public byte[] downloadItemBytes(String driveId, String itemId) throws Exception {
        return downloadBytes(driveId, politeGetItem(driveId, itemId));
    }

    /**
     * Prefer the pre-authenticated {@code @microsoft.graph.downloadUrl}
     * from fresh metadata, fall back to the {@code /content} 302-follow
     * (the migration copier's pattern).
     */
    byte[] downloadBytes(String driveId, DriveItem item) throws Exception {
        if (item.downloadUrl() != null && !item.downloadUrl().isBlank()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(item.downloadUrl())).GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 == 2) {
                return response.body();
            }
            throw new IllegalStateException("downloadUrl fetch failed with HTTP " + response.statusCode());
        }
        try (jakarta.ws.rs.core.Response response = graphClient.downloadContent(driveId, item.id())) {
            String location = response.getHeaderString("Location");
            if (location != null) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(location)).GET().build();
                HttpResponse<byte[]> redirected = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (redirected.statusCode() / 100 == 2) {
                    return redirected.body();
                }
                throw new IllegalStateException("redirected download failed with HTTP " + redirected.statusCode());
            }
            if (response.hasEntity()) {
                Object entity = response.getEntity();
                if (entity instanceof byte[] bytes) {
                    return bytes;
                }
                if (entity instanceof InputStream stream) {
                    return stream.readAllBytes();
                }
            }
            throw new IllegalStateException("Graph returned neither redirect nor content");
        }
    }

    static String parseSkipToken(String nextLink) {
        if (nextLink == null || nextLink.isBlank()) {
            return null;
        }
        try {
            String query = URI.create(nextLink).getRawQuery();
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equalsIgnoreCase("$skiptoken") || key.equalsIgnoreCase("skiptoken")) {
                    return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            log.warnf("Could not parse @odata.nextLink '%s' — stopping pagination", nextLink);
        }
        return null;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Backfill walk interrupted");
        }
    }

    // ── Run-row bookkeeping (narrow TX each) ────────────────────────────────

    private void updateRunCounters(String runUuid, Counters counters) {
        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillRun run = AgreementBackfillRun.findById(runUuid);
            if (run != null) {
                counters.applyTo(run);
            }
        });
    }

    private void finishRun(String runUuid, Counters counters, String errorMessage) {
        QuarkusTransaction.requiringNew().run(() -> {
            AgreementBackfillRun run = AgreementBackfillRun.findById(runUuid);
            if (run == null) {
                return;
            }
            counters.applyTo(run);
            run.setStatus(errorMessage == null
                    ? BackfillRunStatus.COMPLETED.name() : BackfillRunStatus.FAILED.name());
            run.setFinishedAt(LocalDateTime.now());
            run.setErrorMessage(errorMessage);
            run.setCorpusSummary(counters.corpusSummary());
        });
    }

    private static final class Counters {
        int employees;
        int foldersTotal;
        int foldersWalked;
        int filesSeen;
        int filesSkipped;
        int documentsNew;
        int proposalsCreated;
        final List<String> notes = new ArrayList<>();
        int errors;

        void note(String text) {
            if (notes.size() < 50) {
                notes.add(text);
            }
        }

        void error(String text) {
            errors++;
            note(text);
        }

        void applyTo(AgreementBackfillRun run) {
            run.setEmployeesTotal(employees);
            run.setFoldersTotal(foldersTotal);
            run.setFoldersWalked(foldersWalked);
            run.setFilesSeen(filesSeen);
            run.setFilesSkipped(filesSkipped);
            run.setDocumentsNew(documentsNew);
            run.setProposalsCreated(proposalsCreated);
            run.setErrorsCount(errors);
        }

        String corpusSummary() {
            String base = employees + " aktive medarbejdere, " + foldersWalked + "/" + foldersTotal
                    + " mapper gennemgået, " + filesSeen + " filer, " + documentsNew + " nye dokumenter";
            return notes.isEmpty() ? base
                    : truncateTo(base + " — " + String.join("; ", notes), 500);
        }

        static String truncateTo(String text, int max) {
            return text.length() <= max ? text : text.substring(0, max - 1) + "…";
        }

        WalkSummary toSummary(String runUuid, boolean dryRun) {
            return new WalkSummary(runUuid, dryRun, employees, foldersTotal, foldersWalked,
                    filesSeen, filesSkipped, documentsNew, proposalsCreated, errors, List.copyOf(notes));
        }
    }
}
