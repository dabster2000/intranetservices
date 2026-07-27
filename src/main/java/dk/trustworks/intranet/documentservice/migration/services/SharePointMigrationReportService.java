package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationItem.ItemStatus;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The migration report (runbook 2a-6 / spec §9.6): totals per site and
 * per user, status/match-method distributions, the green-criteria strip,
 * and every SKIPPED / FAILED / unmatched / oversize / irrecoverable item
 * — no silent truncation. Also the CSV export behind the admin card.
 *
 * <p>Green = 0 FAILED items · 0 unresolved folders (each MAPPED or
 * SKIPPED-with-note) · 0 unconfirmed AI suggestions · 100% of MAPPED
 * folders' items VERIFIED.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMigrationReportService {

    @Inject
    SigningCaseRepository signingCaseRepository;

    public record SiteTotals(String siteUrl, long folders, long files, long bytes) { }

    public record GreenCriteria(
            long failedItems,
            long unresolvedFolders,
            long unconfirmedAiSuggestions,
            long mappedItemsNotVerified,
            boolean green) { }

    public record SkippedFolder(String siteUrl, String folderName, int fileCount, String note) { }

    public record ProblemItem(String folderName, String name, String relativePath,
                              long sizeBytes, String status, String error) { }

    public record IrrecoverableCase(String caseKey, String documentName, String uploadStatus) { }

    public record UserTotals(String userUuid, String displayName, long documents, long bytes) { }

    public record MigrationReport(
            List<SiteTotals> sites,
            Map<String, Long> folderStatusCounts,
            Map<String, Long> itemStatusCounts,
            Map<String, Long> matchMethodCounts,
            GreenCriteria greenCriteria,
            List<SkippedFolder> skippedFolders,
            List<ProblemItem> problemItems,
            List<ProblemItem> oversizeItems,
            List<IrrecoverableCase> irrecoverableCases,
            List<String> unlinkedUploadedCases,
            List<UserTotals> topUsers,
            Map<String, Long> categoryCounts,
            long needsReviewCount,
            long migratedDocuments,
            long migratedBytes,
            long legacyRowsRemaining) { }

    public MigrationReport buildReport(long oversizeThresholdBytes) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<SharePointMigrationFolder> folders = SharePointMigrationFolder.listAll();
            List<SharePointMigrationItem> items = SharePointMigrationItem.listAll();
            Map<Long, SharePointMigrationFolder> foldersById = folders.stream()
                    .collect(Collectors.toMap(SharePointMigrationFolder::getId, f -> f));

            List<SiteTotals> sites = folders.stream()
                    .collect(Collectors.groupingBy(SharePointMigrationFolder::getSiteUrl))
                    .entrySet().stream()
                    .map(e -> new SiteTotals(e.getKey(), e.getValue().size(),
                            e.getValue().stream().mapToLong(SharePointMigrationFolder::getFileCount).sum(),
                            e.getValue().stream().mapToLong(SharePointMigrationFolder::getTotalBytes).sum()))
                    .sorted((a, b) -> a.siteUrl().compareTo(b.siteUrl()))
                    .toList();

            Map<String, Long> folderStatusCounts = new TreeMap<>(folders.stream()
                    .collect(Collectors.groupingBy(f -> f.getStatus().name(), Collectors.counting())));
            Map<String, Long> itemStatusCounts = new TreeMap<>(items.stream()
                    .collect(Collectors.groupingBy(i -> i.getStatus().name(), Collectors.counting())));
            Map<String, Long> matchMethodCounts = new TreeMap<>(folders.stream()
                    .collect(Collectors.groupingBy(f -> f.getMatchMethod().name(), Collectors.counting())));

            long failedItems = items.stream().filter(i -> i.getStatus() == ItemStatus.FAILED).count();
            long unresolvedFolders = folders.stream()
                    .filter(f -> f.getStatus() == FolderStatus.DISCOVERED && f.getMatchedUserUuid() == null)
                    .count();
            long unconfirmedAi = SharePointMigrationFolder.countUnconfirmedAiSuggestions();
            long mappedNotVerified = items.stream()
                    .filter(i -> {
                        SharePointMigrationFolder folder = foldersById.get(i.getFolderId());
                        boolean folderMapped = folder != null && folder.getStatus() != FolderStatus.DISCOVERED
                                && folder.getStatus() != FolderStatus.SKIPPED;
                        return folderMapped && i.getStatus() != ItemStatus.VERIFIED
                                && i.getStatus() != ItemStatus.SKIPPED;
                    })
                    .count();
            GreenCriteria green = new GreenCriteria(failedItems, unresolvedFolders, unconfirmedAi,
                    mappedNotVerified,
                    failedItems == 0 && unresolvedFolders == 0 && unconfirmedAi == 0
                            && mappedNotVerified == 0);

            List<SkippedFolder> skipped = folders.stream()
                    .filter(f -> f.getStatus() == FolderStatus.SKIPPED)
                    .map(f -> new SkippedFolder(f.getSiteUrl(), f.getFolderName(), f.getFileCount(), f.getNote()))
                    .toList();

            List<ProblemItem> problems = items.stream()
                    .filter(i -> i.getStatus() == ItemStatus.FAILED || i.getStatus() == ItemStatus.SKIPPED)
                    .map(i -> toProblemItem(i, foldersById))
                    .toList();

            List<ProblemItem> oversize = items.stream()
                    .filter(i -> i.getSizeBytes() > oversizeThresholdBytes)
                    .map(i -> toProblemItem(i, foldersById))
                    .toList();

            List<IrrecoverableCase> irrecoverable = signingCaseRepository
                    .list("sharepointUploadStatus IN ('FAILED','PENDING')")
                    .stream()
                    .map(c -> new IrrecoverableCase(c.getCaseKey(), c.getDocumentName(),
                            c.getSharepointUploadStatus()))
                    .toList();

            List<String> unlinkedUploaded = signingCaseRepository
                    .findBySharepointUploadStatus("UPLOADED").stream()
                    .filter(c -> EmployeeDocument.findBySigningCase(c.getCaseKey()).isEmpty())
                    .map(SigningCase::getCaseKey)
                    .toList();

            List<EmployeeDocument> migrated = EmployeeDocument.list("source", EmployeeDocumentSource.MIGRATION);
            Map<String, List<EmployeeDocument>> byUser = migrated.stream()
                    .collect(Collectors.groupingBy(EmployeeDocument::getUserUuid));
            List<UserTotals> topUsers = byUser.entrySet().stream()
                    .map(e -> new UserTotals(e.getKey(), resolveUserName(e.getKey()),
                            e.getValue().size(),
                            e.getValue().stream().mapToLong(EmployeeDocument::getFileSizeBytes).sum()))
                    .sorted((a, b) -> Long.compare(b.documents(), a.documents()))
                    .limit(25)
                    .toList();

            Map<String, Long> categoryCounts = new TreeMap<>(migrated.stream()
                    .collect(Collectors.groupingBy(d -> d.getCategory().name(), Collectors.counting())));
            long needsReview = migrated.stream().filter(EmployeeDocument::isNeedsReview).count();

            long legacyRemaining = File.<File>list("type", "DOCUMENT").stream()
                    .filter(f -> f.getRelateduuid() != null && User.findById(f.getRelateduuid()) != null)
                    .count();

            return new MigrationReport(sites, folderStatusCounts, itemStatusCounts, matchMethodCounts,
                    green, skipped, problems, oversize, irrecoverable, unlinkedUploaded, topUsers,
                    categoryCounts, needsReview, migrated.size(),
                    migrated.stream().mapToLong(EmployeeDocument::getFileSizeBytes).sum(),
                    legacyRemaining);
        });
    }

    /** Full item dump for the CSV export (admin card). */
    public String buildCsv() {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<SharePointMigrationFolder> folders = SharePointMigrationFolder.listAll();
            Map<Long, SharePointMigrationFolder> foldersById = folders.stream()
                    .collect(Collectors.toMap(SharePointMigrationFolder::getId, f -> f));
            Map<String, String> userNames = new HashMap<>();

            StringBuilder csv = new StringBuilder(
                    "site_url;folder_name;matched_user;match_method;folder_status;"
                            + "relative_path;file_name;size_bytes;item_status;employee_document_uuid;error\n");
            List<SharePointMigrationItem> items = SharePointMigrationItem.listAll();
            for (SharePointMigrationItem item : items) {
                SharePointMigrationFolder folder = foldersById.get(item.getFolderId());
                String userName = folder == null || folder.getMatchedUserUuid() == null ? ""
                        : userNames.computeIfAbsent(folder.getMatchedUserUuid(),
                                SharePointMigrationReportService::resolveUserName);
                csv.append(csvField(folder == null ? "" : folder.getSiteUrl())).append(';')
                        .append(csvField(folder == null ? "" : folder.getFolderName())).append(';')
                        .append(csvField(userName)).append(';')
                        .append(folder == null ? "" : folder.getMatchMethod().name()).append(';')
                        .append(folder == null ? "" : folder.getStatus().name()).append(';')
                        .append(csvField(item.getRelativePath())).append(';')
                        .append(csvField(item.getName())).append(';')
                        .append(item.getSizeBytes()).append(';')
                        .append(item.getStatus().name()).append(';')
                        .append(item.getEmployeeDocumentUuid() == null ? "" : item.getEmployeeDocumentUuid()).append(';')
                        .append(csvField(item.getError())).append('\n');
            }
            // Folders without items (empty/newly skipped) still appear.
            for (SharePointMigrationFolder folder : folders) {
                if (items.stream().noneMatch(i -> i.getFolderId().equals(folder.getId()))) {
                    csv.append(csvField(folder.getSiteUrl())).append(';')
                            .append(csvField(folder.getFolderName())).append(';')
                            .append(';').append(folder.getMatchMethod().name()).append(';')
                            .append(folder.getStatus().name()).append(";;;0;;;")
                            .append(csvField(folder.getNote())).append('\n');
                }
            }
            return csv.toString();
        });
    }

    private static ProblemItem toProblemItem(SharePointMigrationItem item,
                                             Map<Long, SharePointMigrationFolder> foldersById) {
        SharePointMigrationFolder folder = foldersById.get(item.getFolderId());
        return new ProblemItem(folder == null ? "?" : folder.getFolderName(),
                item.getName(), item.getRelativePath(), item.getSizeBytes(),
                item.getStatus().name(), item.getError());
    }

    public static String resolveUserName(String userUuid) {
        User user = User.findById(userUuid);
        if (user == null) return userUuid;
        String name = ((user.getFirstname() == null ? "" : user.getFirstname()) + " "
                + (user.getLastname() == null ? "" : user.getLastname())).trim();
        return name.isBlank() ? userUuid : name;
    }

    private static String csvField(String value) {
        if (value == null) return "";
        String cleaned = value.replace('\n', ' ').replace('\r', ' ');
        return cleaned.contains(";") || cleaned.contains("\"")
                ? '"' + cleaned.replace("\"", "\"\"") + '"'
                : cleaned;
    }
}
