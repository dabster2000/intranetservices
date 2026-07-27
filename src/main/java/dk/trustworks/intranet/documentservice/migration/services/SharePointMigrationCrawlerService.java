package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.sharepoint.client.GraphApiClient;
import dk.trustworks.intranet.sharepoint.client.GraphResponseExceptionMapper.SharePointException;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.dto.DriveItemCollectionResponse;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * M1 — the read-only SharePoint inventory (runbook 2a-2 / spec §9.2).
 * Enumerates every file in the three {@code sharepoint_locations} sites
 * into the V457 working tables. <b>Never writes to SharePoint.</b>
 *
 * <ul>
 *   <li>Site/drive ids are resolved <b>once per site per run</b> (the
 *       legacy per-call-resolution bug is deliberately not replicated).</li>
 *   <li>This is the only place in the codebase implementing polite Graph
 *       behavior: a fixed ~100 ms delay between list calls and a
 *       backoff-retry on HTTP 429/503. Do not parallelize it.</li>
 *   <li>Resumable: items are keyed by {@code drive_item_id} — a re-run
 *       adds nothing for unchanged files. A changed eTag (delta crawl,
 *       runbook 2b-7) resets the item to DISCOVERED so the copier picks
 *       up the new version.</li>
 *   <li>Exclusions ({@code Forms/}, hidden/system folders, {@code ~$*}
 *       temp files, zero-byte files) are logged with a reason — no
 *       silent skips.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationCrawlerService {

    static final long POLITENESS_DELAY_MS = 100;
    static final int MAX_RETRIES = 5;
    static final int PAGE_SIZE = 200;

    @Inject
    SharePointService sharePointService;

    @Inject
    @RestClient
    GraphApiClient graphClient;

    public record CrawlSummary(
            int sites,
            int foldersNew,
            int foldersExisting,
            int itemsNew,
            int itemsUnchanged,
            int itemsChanged,
            int itemsSkipped,
            List<String> skippedReasons,
            List<String> errors) { }

    /** Walk all active locations. Read-only against SharePoint. */
    public CrawlSummary crawl() {
        List<SharePointLocationEntity> locations =
                QuarkusTransaction.requiringNew().call(SharePointLocationEntity::findAllActive);

        Counters counters = new Counters();
        for (SharePointLocationEntity location : locations) {
            try {
                crawlSite(location, counters);
                counters.sites++;
            } catch (Exception e) {
                log.errorf(e, "Crawl failed for site %s", location.getSiteUrl());
                counters.errors.add(location.getSiteUrl() + ": " + e.getMessage());
            }
        }
        log.infof("Crawl done: %d sites, %d new folders, %d new items, %d changed, %d skipped, %d errors",
                counters.sites, counters.foldersNew, counters.itemsNew,
                counters.itemsChanged, counters.itemsSkipped, counters.errors.size());
        return counters.toSummary();
    }

    private void crawlSite(SharePointLocationEntity location, Counters counters) {
        String siteUrl = location.getSiteUrl();
        // Resolve once per run — never per call.
        String siteId = sharePointService.resolveSiteId(siteUrl);
        String driveId = sharePointService.resolveDriveId(siteId, location.getDriveName());
        String basePath = location.getFolderPath() == null ? "" : location.getFolderPath().trim();
        String baseFolderId = sharePointService.resolveFolderId(driveId, basePath.isBlank() ? null : basePath);

        for (DriveItem child : listAllChildren(driveId, baseFolderId)) {
            if (!child.isFolder()) {
                skip(counters, "root file (not a personal folder): " + child.name());
                continue;
            }
            if (isExcludedFolder(child.name())) {
                skip(counters, "excluded system folder: " + child.name());
                continue;
            }
            String folderPath = basePath.isBlank() ? child.name() : basePath + "/" + child.name();
            long folderId = upsertFolder(siteUrl, folderPath, child.name(), counters);
            crawlPersonalFolder(driveId, child, folderId, counters);
            updateFolderAggregates(folderId);
        }
    }

    /** BFS under one top-level personal folder; every file becomes an item row. */
    private void crawlPersonalFolder(String driveId, DriveItem personalFolder,
                                     long folderId, Counters counters) {
        record Pending(DriveItem folder, String relativePath) { }
        Deque<Pending> queue = new ArrayDeque<>();
        queue.add(new Pending(personalFolder, ""));

        while (!queue.isEmpty()) {
            Pending current = queue.poll();
            for (DriveItem child : listAllChildren(driveId, current.folder().id())) {
                if (child.isFolder()) {
                    if (isExcludedFolder(child.name())) {
                        skip(counters, "excluded subfolder: " + current.relativePath() + "/" + child.name());
                        continue;
                    }
                    String childPath = current.relativePath().isEmpty()
                            ? child.name() : current.relativePath() + "/" + child.name();
                    queue.add(new Pending(child, childPath));
                    continue;
                }
                if (!child.isFile()) {
                    skip(counters, "neither file nor folder: " + child.name());
                    continue;
                }
                if (child.name() != null && child.name().startsWith("~$")) {
                    skip(counters, "temp file: " + current.relativePath() + "/" + child.name());
                    continue;
                }
                if (child.size() == null || child.size() == 0) {
                    skip(counters, "zero-byte file: " + current.relativePath() + "/" + child.name());
                    continue;
                }
                upsertItem(folderId, child, current.relativePath(), counters);
            }
        }
    }

    // ── Graph listing with paging + politeness ─────────────────────────────

    private List<DriveItem> listAllChildren(String driveId, String itemId) {
        List<DriveItem> all = new ArrayList<>();
        String skipToken = null;
        do {
            DriveItemCollectionResponse page = politeListChildren(driveId, itemId, skipToken);
            if (page.value() != null) all.addAll(page.value());
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
                    // The response mapper does not surface Retry-After, so use
                    // a doubling backoff starting at 5 s (429s are rare at
                    // this call rate; total volume makes this minutes, not hours).
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

    static String parseSkipToken(String nextLink) {
        if (nextLink == null || nextLink.isBlank()) return null;
        try {
            String query = URI.create(nextLink).getRawQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
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

    static boolean isExcludedFolder(String name) {
        if (name == null || name.isBlank()) return true;
        return name.equalsIgnoreCase("Forms") || name.startsWith(".") || name.startsWith("_");
    }

    // ── Working-table upserts (narrow TX each) ─────────────────────────────

    private long upsertFolder(String siteUrl, String folderPath, String folderName, Counters counters) {
        return QuarkusTransaction.requiringNew().call(() -> {
            SharePointMigrationFolder existing =
                    SharePointMigrationFolder.findBySiteAndPath(siteUrl, folderPath);
            if (existing != null) {
                // Sticky: never reset match fields or status on re-crawl.
                counters.foldersExisting++;
                return existing.getId();
            }
            SharePointMigrationFolder folder = new SharePointMigrationFolder();
            folder.setSiteUrl(siteUrl);
            folder.setFolderPath(folderPath);
            folder.setFolderName(folderName);
            folder.setStatus(FolderStatus.DISCOVERED);
            folder.persist();
            counters.foldersNew++;
            return folder.getId();
        });
    }

    private void upsertItem(long folderId, DriveItem file, String relativePath, Counters counters) {
        QuarkusTransaction.requiringNew().run(() -> {
            SharePointMigrationItem existing = SharePointMigrationItem.findByDriveItemId(file.id());
            String quickXor = file.file() != null && file.file().hashes() != null
                    ? file.file().hashes().quickXorHash() : null;
            String etag = file.eTag();
            if (existing == null) {
                SharePointMigrationItem item = new SharePointMigrationItem();
                item.setFolderId(folderId);
                item.setDriveItemId(file.id());
                item.setRelativePath(relativePath);
                item.setName(file.name());
                item.setSizeBytes(file.size() == null ? 0 : file.size());
                item.setQuickxorHash(quickXor);
                item.setEtag(etag);
                item.setStatus(ItemStatus.DISCOVERED);
                item.persist();
                counters.itemsNew++;
                return;
            }
            if (etag != null && !etag.equals(existing.getEtag())) {
                // Delta semantics (2b-7): the file changed since the last
                // crawl — refresh metadata and let the copier take the new
                // version. The already-copied document (if any) stays.
                existing.setEtag(etag);
                existing.setSizeBytes(file.size() == null ? 0 : file.size());
                existing.setQuickxorHash(quickXor);
                existing.setStatus(ItemStatus.DISCOVERED);
                existing.setError(null);
                existing.persist();
                counters.itemsChanged++;
                return;
            }
            counters.itemsUnchanged++;
        });
    }

    private void updateFolderAggregates(long folderId) {
        QuarkusTransaction.requiringNew().run(() -> {
            List<SharePointMigrationItem> items = SharePointMigrationItem.findByFolder(folderId);
            SharePointMigrationFolder folder = SharePointMigrationFolder.findById(folderId);
            if (folder == null) return;
            folder.setFileCount(items.size());
            folder.setTotalBytes(items.stream().mapToLong(SharePointMigrationItem::getSizeBytes).sum());
            folder.persist();
        });
    }

    private void skip(Counters counters, String reason) {
        counters.itemsSkipped++;
        log.infof("Crawl skip: %s", reason);
        if (counters.skippedReasons.size() < 200) {
            counters.skippedReasons.add(reason);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Crawl interrupted", e);
        }
    }

    private static final class Counters {
        int sites;
        int foldersNew;
        int foldersExisting;
        int itemsNew;
        int itemsUnchanged;
        int itemsChanged;
        int itemsSkipped;
        final List<String> skippedReasons = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        CrawlSummary toSummary() {
            return new CrawlSummary(sites, foldersNew, foldersExisting, itemsNew,
                    itemsUnchanged, itemsChanged, itemsSkipped, skippedReasons, errors);
        }
    }
}
