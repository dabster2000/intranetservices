package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-off reconciliation of {@code sharepoint_migration_items.folder_id}
 * against where each file actually lives in SharePoint today.
 *
 * <p><b>Why this exists.</b> A drive item keeps its id when it is moved, and
 * the crawler used to key on that id alone: a file found under a new folder
 * matched its existing row, was counted "unchanged", and kept pointing at
 * whichever folder it was first seen in. When the employee folders were lifted
 * out of the {@code 2. XXX - Trustworkers} container, 1,153 files stayed
 * attached to that container's row — which a human had (correctly) marked
 * SKIPPED, since it is not a person. The copier only walks MAPPED/COPYING
 * folders, so those files have sat at DISCOVERED ever since while the
 * per-person folders reported {@code file_count = 0}.</p>
 *
 * <p>The crawler now re-points a moved item itself, so a full crawl heals most
 * of this as a side effect. This job exists for what a crawl cannot give you:
 * a <b>dry run</b> that shows exactly which files would change owner before
 * anything is written, reconciliation of items whose folder the crawl no longer
 * reaches, and an explicit count of <b>orphans</b> — rows whose file has been
 * deleted from SharePoint, which no crawl will ever visit again.</p>
 *
 * <p>Read-only against SharePoint. Idempotent: a second run reports everything
 * as already correct.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationOwnershipRepairService {

    static final long POLITENESS_DELAY_MS = 100;
    static final int MAX_RETRIES = 5;
    private static final int MAX_REPORTED = 50;

    @Inject
    SharePointService sharePointService;

    @Inject
    @RestClient
    GraphApiClient graphClient;

    /**
     * @param candidates     items examined
     * @param repointed      items whose folder_id was (or would be) corrected
     * @param alreadyCorrect items already attached to the right folder
     * @param orphaned       items whose file no longer exists in SharePoint
     * @param outsideScope   items whose file now sits outside any crawled base path
     * @param foldersCreated folder rows created for a destination never crawled
     * @param foldersReopened VERIFIED folders set back to MAPPED by inbound work
     */
    public record RepairSummary(
            boolean dryRun,
            int candidates,
            int repointed,
            int alreadyCorrect,
            int orphaned,
            int outsideScope,
            int foldersCreated,
            int foldersReopened,
            List<String> moves,
            List<String> errors) { }

    /**
     * @param dryRun   classify and report without writing
     * @param allItems reconcile every item, not just those stranded on a
     *                 SKIPPED folder. Costs one Graph call per item, so the
     *                 default is the targeted set that is actually known broken.
     */
    public RepairSummary repair(boolean dryRun, boolean allItems) {
        record Candidate(Long itemId, String driveItemId, String name, ItemStatus status,
                         Long folderId, String folderPath, String siteUrl) { }

        List<Candidate> candidates = QuarkusTransaction.requiringNew().call(() -> {
            List<SharePointMigrationFolder> folders = SharePointMigrationFolder.listAll();
            Map<Long, SharePointMigrationFolder> byId = new HashMap<>();
            folders.forEach(f -> byId.put(f.getId(), f));
            return SharePointMigrationItem.<SharePointMigrationItem>listAll().stream()
                    .filter(i -> {
                        SharePointMigrationFolder f = byId.get(i.getFolderId());
                        return f != null && (allItems || f.getStatus() == FolderStatus.SKIPPED);
                    })
                    .map(i -> {
                        SharePointMigrationFolder f = byId.get(i.getFolderId());
                        return new Candidate(i.getId(), i.getDriveItemId(), i.getName(),
                                i.getStatus(), f.getId(), f.getFolderPath(), f.getSiteUrl());
                    })
                    .toList();
        });

        Map<String, String> basePathBySite = QuarkusTransaction.requiringNew().call(() -> {
            Map<String, String> map = new HashMap<>();
            for (SharePointLocationEntity l : SharePointLocationEntity.<SharePointLocationEntity>findAllActive()) {
                map.put(l.getSiteUrl(), l.getFolderPath() == null ? "" : l.getFolderPath().trim());
            }
            return map;
        });

        Counters counters = new Counters(dryRun);
        Map<String, String> driveIdBySite = new HashMap<>();

        for (Candidate candidate : candidates) {
            counters.candidates++;
            try {
                String basePath = basePathBySite.get(candidate.siteUrl());
                if (basePath == null) {
                    // The location row was deactivated: nothing to reconcile
                    // against, and guessing a base path would invent ownership.
                    counters.outsideScope++;
                    continue;
                }
                String driveId = driveIdBySite.computeIfAbsent(candidate.siteUrl(), this::resolveDriveId);

                DriveItem fresh;
                try {
                    fresh = politeGetItem(driveId, candidate.driveItemId());
                } catch (SharePointException e) {
                    if (e.getStatusCode() == 404) {
                        counters.orphaned++;
                        counters.report("orphan (deleted in SharePoint): " + candidate.name());
                        continue;
                    }
                    throw e;
                }

                String parentPath = folderPathFromParentReference(
                        fresh.parentReference() == null ? null : fresh.parentReference().path());
                String personalFolder = personalFolderUnder(basePath, parentPath);
                if (personalFolder == null) {
                    counters.outsideScope++;
                    counters.report("outside any crawled folder: " + candidate.name() + " → " + parentPath);
                    continue;
                }

                String targetPath = basePath.isBlank() ? personalFolder : basePath + "/" + personalFolder;
                if (targetPath.equals(candidate.folderPath())) {
                    counters.alreadyCorrect++;
                    continue;
                }
                String relativePath = relativePathUnder(targetPath, parentPath);

                counters.report(candidate.name() + ": " + lastSegment(candidate.folderPath())
                        + " → " + personalFolder);
                counters.touched.add(candidate.folderId());

                if (dryRun) {
                    counters.repointed++;
                    continue;
                }
                applyMove(candidate.itemId(), candidate.siteUrl(), targetPath, personalFolder,
                        relativePath, candidate.status(), counters);
            } catch (Exception e) {
                log.warnf("Ownership repair failed for item %s: %s", candidate.driveItemId(), e.getMessage());
                counters.errors.add(candidate.name() + ": " + e.getMessage());
            }
        }

        if (!dryRun) counters.touched.forEach(this::updateFolderAggregates);

        log.infof("Ownership repair done (dryRun=%s): %d candidates, %d re-pointed, %d already correct, "
                        + "%d orphaned, %d outside scope, %d folders created, %d folders re-opened",
                dryRun, counters.candidates, counters.repointed, counters.alreadyCorrect,
                counters.orphaned, counters.outsideScope, counters.foldersCreated,
                counters.foldersReopened);
        return counters.toSummary();
    }

    /** The write half — one narrow transaction per item, like the crawler. */
    private void applyMove(Long itemId, String siteUrl, String targetPath, String folderName,
                           String relativePath, ItemStatus itemStatus, Counters counters) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationFolder target =
                    SharePointMigrationFolder.findBySiteAndPath(siteUrl, targetPath);
            if (target == null) {
                // The destination has never been crawled (or was renamed away).
                // DISCOVERED, so the matcher picks it up rather than this job
                // guessing whose folder it is.
                target = new SharePointMigrationFolder();
                target.setSiteUrl(siteUrl);
                target.setFolderPath(targetPath);
                target.setFolderName(folderName);
                target.setStatus(FolderStatus.DISCOVERED);
                target.persist();
                counters.foldersCreated++;
            }

            SharePointMigrationItem item = SharePointMigrationItem.findById(itemId);
            if (item == null) return;
            item.setFolderId(target.getId());
            item.setRelativePath(relativePath);
            item.persist();
            counters.repointed++;
            counters.touched.add(target.getId());

            // Same rule as the crawler: a move is not new content, so an item
            // already COPIED/VERIFIED elsewhere does not re-open its new home.
            if (itemStatus == ItemStatus.DISCOVERED && target.getStatus() == FolderStatus.VERIFIED) {
                target.setStatus(FolderStatus.MAPPED);
                target.persist();
                counters.foldersReopened++;
            }
        });
    }

    private void updateFolderAggregates(Long folderId) {
        QuarkusTransaction.requiringNew().run(() -> {
            List<SharePointMigrationItem> items = SharePointMigrationItem.findByFolder(folderId);
            SharePointMigrationFolder folder = SharePointMigrationFolder.findById(folderId);
            if (folder == null) return;
            folder.setFileCount(items.size());
            folder.setTotalBytes(items.stream().mapToLong(SharePointMigrationItem::getSizeBytes).sum());
            folder.persist();
        });
    }

    // ── Path arithmetic (pure — unit tested) ───────────────────────────────

    /**
     * The drive-relative folder path out of a Graph {@code parentReference.path},
     * which looks like {@code /drive/root:/General/Medarbejdere/…} or
     * {@code /drives/b!xyz/root:/General/…} and is percent-encoded.
     *
     * @return the path with no leading slash, or null when unparseable
     */
    static String folderPathFromParentReference(String path) {
        if (path == null || path.isBlank()) return null;
        int marker = path.indexOf("root:");
        if (marker < 0) return null;
        String tail = path.substring(marker + "root:".length());
        // Decode %XX only: URLDecoder would also turn a literal '+' in a
        // filename into a space, and SharePoint folder names do contain them.
        String decoded = URLDecoder.decode(tail.replace("+", "%2B"), StandardCharsets.UTF_8);
        while (decoded.startsWith("/")) decoded = decoded.substring(1);
        while (decoded.endsWith("/")) decoded = decoded.substring(0, decoded.length() - 1);
        return decoded;
    }

    /**
     * The top-level personal folder a file now sits under, i.e. the first path
     * segment below {@code basePath}. Null when the file is outside the base
     * path, or sits directly in it (the crawler treats those as root files too).
     */
    static String personalFolderUnder(String basePath, String parentPath) {
        if (parentPath == null) return null;
        String remainder;
        if (basePath == null || basePath.isBlank()) {
            remainder = parentPath;
        } else {
            if (!parentPath.equals(basePath) && !parentPath.startsWith(basePath + "/")) return null;
            remainder = parentPath.substring(basePath.length());
        }
        while (remainder.startsWith("/")) remainder = remainder.substring(1);
        if (remainder.isBlank()) return null;
        int slash = remainder.indexOf('/');
        return slash < 0 ? remainder : remainder.substring(0, slash);
    }

    /** The path under the personal folder — "" for a file at its root. */
    static String relativePathUnder(String personalFolderPath, String parentPath) {
        if (parentPath == null || !parentPath.startsWith(personalFolderPath)) return "";
        String rest = parentPath.substring(personalFolderPath.length());
        while (rest.startsWith("/")) rest = rest.substring(1);
        return rest;
    }

    private static String lastSegment(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ── Graph plumbing (mirrors the crawler's politeness) ──────────────────

    private String resolveDriveId(String siteUrl) {
        String driveName = QuarkusTransaction.requiringNew().call(() -> {
            SharePointLocationEntity location =
                    SharePointLocationEntity.find("siteUrl", siteUrl).firstResult();
            return location == null ? null : location.getDriveName();
        });
        return sharePointService.resolveDriveId(sharePointService.resolveSiteId(siteUrl), driveName);
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
                    log.warnf("Graph %d on getItem (attempt %d/%d) — backing off %d ms",
                            e.getStatusCode(), attempt, MAX_RETRIES, backoffMs);
                    pause(backoffMs);
                    continue;
                }
                throw e;
            }
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ownership repair interrupted", e);
        }
    }

    private static final class Counters {
        private final boolean dryRun;
        int candidates;
        int repointed;
        int alreadyCorrect;
        int orphaned;
        int outsideScope;
        int foldersCreated;
        int foldersReopened;
        final Set<Long> touched = new LinkedHashSet<>();
        final List<String> moves = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        Counters(boolean dryRun) {
            this.dryRun = dryRun;
        }

        void report(String line) {
            if (moves.size() < MAX_REPORTED) moves.add(line);
        }

        RepairSummary toSummary() {
            return new RepairSummary(dryRun, candidates, repointed, alreadyCorrect, orphaned,
                    outsideScope, foldersCreated, foldersReopened, moves, errors);
        }
    }
}
