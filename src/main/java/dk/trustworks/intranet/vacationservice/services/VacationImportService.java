package dk.trustworks.intranet.vacationservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.vacationservice.dto.CreateVacationImportRequest;
import dk.trustworks.intranet.vacationservice.dto.MatchImportRowRequest;
import dk.trustworks.intranet.vacationservice.dto.VacationImportBatchDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationImportRowDTO;
import dk.trustworks.intranet.vacationservice.engine.DanlonCsvParser;
import dk.trustworks.intranet.vacationservice.engine.NameNormalizer;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.DanlonNameMapping;
import dk.trustworks.intranet.vacationservice.model.VacationImportBatch;
import dk.trustworks.intranet.vacationservice.model.VacationImportRow;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntryType;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportBatchStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationPoolType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.vacationservice.engine.VacationRules.round2;
import static dk.trustworks.intranet.vacationservice.engine.VacationRules.startOf;

/**
 * The Danløn feriepengeforpligtelse upload pipeline: parse → match → review →
 * apply. Applying posts IMPORT_BASELINE entries whose as-of date supersedes
 * everything the ledger and payroll stamps said up to that date, for exactly
 * the (user, ferieår) pairs the file carries — employees or years absent from
 * the file are untouched, so an upload can only override, never erase.
 */
@JBossLog
@ApplicationScoped
public class VacationImportService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final int MAX_CONTENT_BYTES = 1_000_000;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    UserService userService;

    @Inject
    VacationPolicyService policyService;

    @Inject
    VacationLedgerService ledgerService;

    /** Shape persisted verbatim in {@code vacation_import_rows.raw_json}. */
    public record RawRow(String bogfoeringsgruppe, Map<Integer, RawYear> years) {
        public record RawYear(double earnedDays, double usedDays, String earnedKrRaw, String provisionKrRaw) {
        }
    }

    // ── Upload ────────────────────────────────────────────────────────────

    @Transactional
    public VacationImportBatchDTO createBatch(String companyuuid, CreateVacationImportRequest request, String actorUuid) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BadRequestException("The CSV content is required");
        }
        if (request.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new BadRequestException("The file is larger than 1 MB — that is not a Danløn feriepengeforpligtelse export");
        }
        if (request.asOfDate() == null) {
            throw new BadRequestException("An as-of date is required — it anchors the reconciliation");
        }
        if (request.asOfDate().isAfter(LocalDate.now(COPENHAGEN))) {
            throw new BadRequestException("The as-of date cannot be in the future");
        }

        DanlonCsvParser.ParsedCsv parsed;
        try {
            parsed = DanlonCsvParser.parse(request.content());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }

        VacationImportBatch batch = new VacationImportBatch();
        batch.setUuid(UUID.randomUUID().toString());
        batch.setCompanyuuid(companyuuid);
        batch.setFilename(request.filename() == null || request.filename().isBlank()
                ? "feriepengeforpligtelse.csv" : request.filename().trim());
        batch.setAsOfDate(request.asOfDate());
        batch.setStatus(VacationImportBatchStatus.PENDING);
        batch.setUploadedBy(actorUuid);

        Map<String, List<User>> usersByNormalizedName = new HashMap<>();
        for (User user : userService.listAll(true)) {
            if (user.getFullname() == null) continue;
            usersByNormalizedName.computeIfAbsent(NameNormalizer.normalize(user.getFullname()), k -> new java.util.ArrayList<>()).add(user);
        }

        int matched = 0;
        int unmatched = 0;
        List<VacationImportRow> rows = new java.util.ArrayList<>();
        for (DanlonCsvParser.ParsedRow parsedRow : parsed.rows()) {
            VacationImportRow row = new VacationImportRow();
            row.setUuid(UUID.randomUUID().toString());
            row.setBatchUuid(batch.getUuid());
            row.setLineNo(parsedRow.lineNo());
            row.setDanlonName(parsedRow.name());
            row.setRawJson(writeRawJson(parsedRow));

            String normalized = NameNormalizer.normalize(parsedRow.name());
            String resolved = DanlonNameMapping.findByNormalizedName(normalized)
                    .map(DanlonNameMapping::getUseruuid)
                    .orElseGet(() -> {
                        List<User> candidates = usersByNormalizedName.getOrDefault(normalized, List.of());
                        return candidates.size() == 1 ? candidates.get(0).getUuid() : null;
                    });
            if (resolved != null) {
                row.setUseruuid(resolved);
                row.setMatchStatus(VacationImportRowStatus.AUTO);
                matched++;
            } else {
                row.setMatchStatus(VacationImportRowStatus.UNMATCHED);
                unmatched++;
            }
            rows.add(row);
        }

        batch.setRowCount(rows.size());
        batch.setMatchedCount(matched);
        batch.setUnmatchedCount(unmatched);
        batch.persist();
        rows.forEach(row -> row.persist());
        log.infof("vacation-import: batch %s uploaded by %s — %d rows, %d matched, %d unmatched, as of %s",
                batch.getUuid(), actorUuid, rows.size(), matched, unmatched, request.asOfDate());
        return toDTO(batch, rows);
    }

    // ── Review ────────────────────────────────────────────────────────────

    public List<VacationImportBatchDTO> listBatches(String companyuuid) {
        return VacationImportBatch.findByCompany(companyuuid).stream()
                .map(b -> toDTO(b, null))
                .toList();
    }

    public VacationImportBatchDTO getBatch(String batchUuid) {
        VacationImportBatch batch = requireBatch(batchUuid);
        return toDTO(batch, VacationImportRow.findByBatch(batchUuid));
    }

    @Transactional
    public VacationImportBatchDTO matchRow(String batchUuid, String rowUuid, MatchImportRowRequest request, String actorUuid) {
        VacationImportBatch batch = requireBatch(batchUuid);
        if (batch.getStatus() != VacationImportBatchStatus.PENDING) {
            throw new WebApplicationException("The batch is already applied", Response.Status.CONFLICT);
        }
        VacationImportRow row = VacationImportRow.findById(rowUuid);
        if (row == null || !row.getBatchUuid().equals(batchUuid)) {
            throw new NotFoundException("Import row not found");
        }

        if (request != null && request.ignore()) {
            row.setUseruuid(null);
            row.setMatchStatus(VacationImportRowStatus.IGNORED);
        } else {
            if (request == null || request.useruuid() == null || request.useruuid().isBlank()) {
                throw new BadRequestException("Pick a user or ignore the row");
            }
            User user = userService.findById(request.useruuid(), true);
            if (user == null) {
                throw new BadRequestException("Unknown user: " + request.useruuid());
            }
            row.setUseruuid(user.getUuid());
            row.setMatchStatus(VacationImportRowStatus.MANUAL);
            rememberMapping(row.getDanlonName(), user.getUuid(), actorUuid);
        }

        refreshCounts(batch);
        return toDTO(batch, VacationImportRow.findByBatch(batchUuid));
    }

    private void rememberMapping(String danlonName, String useruuid, String actorUuid) {
        String normalized = NameNormalizer.normalize(danlonName);
        DanlonNameMapping mapping = DanlonNameMapping.findByNormalizedName(normalized).orElse(null);
        if (mapping == null) {
            mapping = new DanlonNameMapping();
            mapping.setUuid(UUID.randomUUID().toString());
            mapping.setNormalizedName(normalized);
            mapping.setCreatedBy(actorUuid);
            mapping.setUseruuid(useruuid);
            mapping.persist();
        } else {
            mapping.setUseruuid(useruuid);
        }
    }

    private void refreshCounts(VacationImportBatch batch) {
        List<VacationImportRow> rows = VacationImportRow.findByBatch(batch.getUuid());
        batch.setMatchedCount((int) rows.stream()
                .filter(r -> r.getMatchStatus() == VacationImportRowStatus.AUTO
                        || r.getMatchStatus() == VacationImportRowStatus.MANUAL)
                .count());
        batch.setUnmatchedCount((int) rows.stream()
                .filter(r -> r.getMatchStatus() == VacationImportRowStatus.UNMATCHED)
                .count());
    }

    // ── Apply ─────────────────────────────────────────────────────────────

    @Transactional
    public VacationImportBatchDTO apply(String batchUuid, String actorUuid) {
        VacationImportBatch batch = requireBatch(batchUuid);
        if (batch.getStatus() != VacationImportBatchStatus.PENDING) {
            throw new WebApplicationException("The batch is already applied", Response.Status.CONFLICT);
        }
        List<VacationImportRow> rows = VacationImportRow.findByBatch(batchUuid);
        long unresolved = rows.stream()
                .filter(r -> r.getMatchStatus() == VacationImportRowStatus.UNMATCHED)
                .count();
        if (unresolved > 0) {
            throw new WebApplicationException(
                    unresolved + " rows are still unmatched — resolve or ignore them first", Response.Status.CONFLICT);
        }

        List<PolicyRate> policies = policyService.rates();
        int entriesPosted = 0;
        for (VacationImportRow row : rows) {
            if (row.getMatchStatus() == VacationImportRowStatus.IGNORED) continue;
            RawRow raw = readRawJson(row.getRawJson());
            for (Map.Entry<Integer, RawRow.RawYear> yearEntry : raw.years().entrySet()) {
                entriesPosted += postBaselines(batch, row.getUseruuid(), yearEntry.getKey(), yearEntry.getValue(),
                        policies, actorUuid);
            }
        }

        batch.setStatus(VacationImportBatchStatus.APPLIED);
        batch.setAppliedAt(LocalDateTime.now());
        batch.setAppliedBy(actorUuid);
        log.infof("vacation-import: batch %s applied by %s — %d baseline entries", batchUuid, actorUuid, entriesPosted);
        return toDTO(batch, rows);
    }

    /**
     * Splits the combined Danløn figures into the two pools: earned days
     * proportionally by the policy rates at the ferieår's start (2.08 : 0.42
     * by default), used days ferie-first — matching the engine's spend order —
     * with any excess charged to ferie as overdraft.
     */
    private int postBaselines(VacationImportBatch batch, String useruuid, int ferieaar, RawRow.RawYear figures,
                              List<PolicyRate> policies, String actorUuid) {
        double ferieRate = VacationBalanceEngine.rateFor(policies, VacationPoolType.FERIE, startOf(ferieaar));
        double ffRate = VacationBalanceEngine.rateFor(policies, VacationPoolType.FERIEFRIDAGE, startOf(ferieaar));
        double totalRate = ferieRate + ffRate;
        double ferieShare = totalRate <= 0 ? 1.0 : ferieRate / totalRate;

        double earnedFerie = round2(figures.earnedDays() * ferieShare);
        double earnedFf = round2(figures.earnedDays() - earnedFerie);
        double usedFerie = Math.min(figures.usedDays(), earnedFerie);
        double usedFf = Math.min(earnedFf, round2(figures.usedDays() - usedFerie));
        double excess = round2(figures.usedDays() - usedFerie - usedFf);
        if (excess > 0) usedFerie = round2(usedFerie + excess);

        persistBaseline(batch, useruuid, ferieaar, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_EARNED, earnedFerie, actorUuid);
        persistBaseline(batch, useruuid, ferieaar, VacationPoolType.FERIE, VacationEntryType.IMPORT_BASELINE_USED, usedFerie, actorUuid);
        persistBaseline(batch, useruuid, ferieaar, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_EARNED, earnedFf, actorUuid);
        persistBaseline(batch, useruuid, ferieaar, VacationPoolType.FERIEFRIDAGE, VacationEntryType.IMPORT_BASELINE_USED, usedFf, actorUuid);
        return 4;
    }

    private void persistBaseline(VacationImportBatch batch, String useruuid, int ferieaar, VacationPoolType pool,
                                 VacationEntryType type, double days, String actorUuid) {
        // Zero-value baselines post too: "Danløn says 0" is an override statement.
        ledgerService.buildEntry(useruuid, ferieaar, pool, type, days, batch.getAsOfDate(),
                VacationEntrySource.DANLON_IMPORT, batch.getUuid(),
                "Danløn-import " + batch.getFilename(), actorUuid).persist();
    }

    // ── Mapping ───────────────────────────────────────────────────────────

    private VacationImportBatch requireBatch(String batchUuid) {
        VacationImportBatch batch = VacationImportBatch.findById(batchUuid);
        if (batch == null) {
            throw new NotFoundException("Import batch not found");
        }
        return batch;
    }

    private String writeRawJson(DanlonCsvParser.ParsedRow row) {
        Map<Integer, RawRow.RawYear> years = new LinkedHashMap<>();
        row.years().forEach((year, values) -> years.put(year,
                new RawRow.RawYear(values.earnedDays(), values.usedDays(), values.earnedKrRaw(), values.provisionKrRaw())));
        try {
            return objectMapper.writeValueAsString(new RawRow(row.bogfoeringsgruppe(), years));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private RawRow readRawJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private VacationImportBatchDTO toDTO(VacationImportBatch batch, List<VacationImportRow> rows) {
        List<VacationImportRowDTO> rowDTOs = null;
        if (rows != null) {
            Map<String, String> names = new HashMap<>();
            for (VacationImportRow row : rows) {
                if (row.getUseruuid() == null || names.containsKey(row.getUseruuid())) continue;
                try {
                    User user = userService.findById(row.getUseruuid(), true);
                    if (user != null) names.put(row.getUseruuid(), user.getFullname());
                } catch (Exception e) {
                    log.debugf("vacation-import: could not resolve user %s", row.getUseruuid());
                }
            }
            rowDTOs = rows.stream().map(row -> {
                RawRow raw = readRawJson(row.getRawJson());
                Map<Integer, VacationImportRowDTO.YearFiguresDTO> years = new LinkedHashMap<>();
                raw.years().forEach((year, figures) -> years.put(year, new VacationImportRowDTO.YearFiguresDTO(
                        figures.earnedDays(), figures.usedDays(), figures.earnedKrRaw(), figures.provisionKrRaw())));
                return new VacationImportRowDTO(row.getUuid(), row.getLineNo(), row.getDanlonName(),
                        row.getUseruuid(), names.get(row.getUseruuid()),
                        row.getMatchStatus().name(), years);
            }).toList();
        }
        return new VacationImportBatchDTO(batch.getUuid(), batch.getCompanyuuid(), batch.getFilename(),
                batch.getAsOfDate(), batch.getStatus().name(), batch.getUploadedBy(), batch.getUploadedAt(),
                batch.getAppliedAt(), batch.getAppliedBy(), batch.getRowCount(), batch.getMatchedCount(),
                batch.getUnmatchedCount(), rowDTOs);
    }
}
