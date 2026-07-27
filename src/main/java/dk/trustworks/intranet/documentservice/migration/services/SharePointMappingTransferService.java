package dk.trustworks.intranet.documentservice.migration.services;

import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.FolderStatus;
import dk.trustworks.intranet.documentservice.migration.model.SharePointMigrationFolder.MatchMethod;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 2a-9 — uuid-keyed mapping transfer between environments (runbook
 * 2a-9, added 2026-07-27). The staging rehearsal cannot run name
 * matching (the nightly sync scrambles staging's user directory), so
 * the HR-confirmed matching happens in PROD and travels to staging as
 * {@code (siteUrl, folderPath) → matchedUserUuid} — uuids survive the
 * sync untouched, and no names leave the environment.
 *
 * <p>Import is fail-closed per entry (the file is never trusted:
 * unknown users are rejected, existing mappings are never overwritten)
 * and idempotent — re-importing the same file applies 0. No schema
 * change; works on V457's tables as-is. Deleted with the rest of the
 * tooling at 2c-6.</p>
 */
@JBossLog
@ApplicationScoped
public class SharePointMappingTransferService {

    /** Structural sanity cap — 3 sites × ~90 folders expected; 2000 is generous. */
    static final int MAX_IMPORT_ENTRIES = 2000;
    static final int EXPORT_VERSION = 1;

    public record MappingEntry(
            String siteUrl,
            String folderPath,
            String matchedUserUuid,
            String matchMethod) { }

    public record MappingExport(
            int version,
            String exportedAt,
            List<MappingEntry> mappings) { }

    public record ImportSummary(
            int applied,
            int skippedAlreadyMapped,
            int skippedUnknownFolder,
            int skippedUnknownUser) { }

    // ── Export ─────────────────────────────────────────────────────────────

    /**
     * One entry per mapped folder (methods USERNAME/FULLNAME/
     * AI_CONFIRMED/MANUAL). Keyed on {@code matchedUserUuid} being set
     * rather than status — a folder that already progressed past MAPPED
     * (COPYING/VERIFIED) still carries its confirmed mapping.
     */
    public MappingExport export() {
        List<MappingEntry> mappings = QuarkusTransaction.requiringNew().call(() ->
                SharePointMigrationFolder.<SharePointMigrationFolder>list("matchedUserUuid IS NOT NULL")
                        .stream()
                        .filter(f -> f.getMatchMethod() != MatchMethod.UNMATCHED)
                        .map(f -> new MappingEntry(f.getSiteUrl(), f.getFolderPath(),
                                f.getMatchedUserUuid(), f.getMatchMethod().name()))
                        .toList());
        log.infof("Mapping export: %d entries", mappings.size());
        return new MappingExport(EXPORT_VERSION, LocalDateTime.now().toString(), mappings);
    }

    // ── Import ─────────────────────────────────────────────────────────────

    /**
     * Structural validation is strict (a malformed file is rejected
     * whole, 400); semantic skips are counted per entry. Existing
     * mappings are never overwritten; the exported match method is
     * preserved so the report's method distribution stays honest.
     */
    public ImportSummary importMappings(MappingExport request) {
        validateStructure(request);

        return QuarkusTransaction.requiringNew().call(() -> {
            int applied = 0;
            int alreadyMapped = 0;
            int unknownFolder = 0;
            int unknownUser = 0;

            for (MappingEntry entry : request.mappings()) {
                SharePointMigrationFolder folder =
                        SharePointMigrationFolder.findBySiteAndPath(entry.siteUrl(), entry.folderPath());
                if (folder == null) {
                    unknownFolder++;
                    continue;
                }
                if (folder.getMatchedUserUuid() != null) {
                    alreadyMapped++;
                    continue;
                }
                // Never trust the file: the uuid must exist in THIS
                // environment's user table (fail closed per entry).
                if (User.findById(entry.matchedUserUuid()) == null) {
                    unknownUser++;
                    continue;
                }
                folder.setMatchedUserUuid(entry.matchedUserUuid());
                folder.setMatchMethod(MatchMethod.valueOf(entry.matchMethod()));
                folder.setStatus(FolderStatus.MAPPED);
                folder.persist();
                applied++;
            }

            // Counts only — folder paths are employee names, they stay out of the logs.
            log.infof("Mapping import: applied=%d alreadyMapped=%d unknownFolder=%d unknownUser=%d",
                    applied, alreadyMapped, unknownFolder, unknownUser);
            return new ImportSummary(applied, alreadyMapped, unknownFolder, unknownUser);
        });
    }

    /** Hand-rolled request validation (@Valid is inert in this codebase). */
    static void validateStructure(MappingExport request) {
        if (request == null || request.mappings() == null) {
            throw new BadRequestException("Import body must contain a mappings array");
        }
        if (request.version() != EXPORT_VERSION) {
            throw new BadRequestException("Unsupported mapping export version: " + request.version());
        }
        if (request.mappings().size() > MAX_IMPORT_ENTRIES) {
            throw new BadRequestException("Import too large: " + request.mappings().size()
                    + " entries (max " + MAX_IMPORT_ENTRIES + ")");
        }
        for (MappingEntry entry : request.mappings()) {
            if (entry == null
                    || isBlankOrOver(entry.siteUrl(), 500)
                    || isBlankOrOver(entry.folderPath(), 1024)
                    || isBlankOrOver(entry.matchedUserUuid(), 36)) {
                throw new BadRequestException("Malformed mapping entry (siteUrl/folderPath/matchedUserUuid)");
            }
            if (entry.matchMethod() == null || !isImportableMethod(entry.matchMethod())) {
                throw new BadRequestException("Invalid matchMethod: " + entry.matchMethod());
            }
        }
    }

    static boolean isImportableMethod(String method) {
        try {
            return MatchMethod.valueOf(method) != MatchMethod.UNMATCHED;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isBlankOrOver(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
}
