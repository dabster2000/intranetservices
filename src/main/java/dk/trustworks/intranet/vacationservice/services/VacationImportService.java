package dk.trustworks.intranet.vacationservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.vacationservice.dto.CreateVacationImportRequest;
import dk.trustworks.intranet.vacationservice.dto.MatchImportRowRequest;
import dk.trustworks.intranet.vacationservice.dto.VacationImportBatchDTO;
import dk.trustworks.intranet.vacationservice.dto.VacationImportRowDTO;
import dk.trustworks.intranet.vacationservice.engine.DanlonCsvParser;
import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner;
import dk.trustworks.intranet.vacationservice.engine.ImportBaselinePlanner.BaselineEntry;
import dk.trustworks.intranet.vacationservice.engine.ImportCompanyGate;
import dk.trustworks.intranet.vacationservice.engine.NameNormalizer;
import dk.trustworks.intranet.vacationservice.engine.VacationBalanceEngine.PolicyRate;
import dk.trustworks.intranet.vacationservice.model.DanlonNameMapping;
import dk.trustworks.intranet.vacationservice.model.VacationImportBatch;
import dk.trustworks.intranet.vacationservice.model.VacationImportRow;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportBatchStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus;
import dk.trustworks.intranet.vacationservice.model.enums.VacationImportRowStatus.Bucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Danløn feriepengeforpligtelse upload pipeline: parse → match → review →
 * apply. Applying posts IMPORT_BASELINE entries whose as-of date supersedes
 * everything the ledger and payroll stamps said up to that date, for exactly
 * the (user, ferieår) pairs the file carries — employees or years absent from
 * the file are untouched, so an upload can only override, never erase.
 *
 * <p>A file speaks only for its own company. Danløn exports one line per
 * employment record, so a person who has transferred between the Trustworks
 * companies still appears in their former employer's export with a historical
 * record — and because payroll moves the available balance at the transfer,
 * the receiving company's figures already contain it. Matching therefore has
 * two steps: who is this, and does this company's file get to speak for them
 * on the batch's as-of date. See {@link #applyCompanyGate}.</p>
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
    EmploymentCompanyLookup employmentCompanyLookup;

    @Inject
    VacationPolicyService policyService;

    @Inject
    VacationLedgerService ledgerService;

    @Inject
    TransactionSynchronizationRegistry txSyncRegistry;

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

        // Pass one: identify the person. Nothing is judged yet — the company
        // gate below needs the whole set of resolved users so it can ask about
        // all of them in one query.
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
            row.setUseruuid(resolved);
            rows.add(row);
        }

        applyCompanyGate(batch, rows);

        batch.setRowCount(rows.size());
        refreshCountsFrom(batch, rows);
        batch.persist();
        rows.forEach(row -> row.persist());
        log.infof("vacation-import: batch %s uploaded by %s — %d rows, %d to apply, %d to resolve, %d skipped, as of %s",
                batch.getUuid(), actorUuid, rows.size(), batch.getMatchedCount(), batch.getUnmatchedCount(),
                rows.size() - batch.getMatchedCount() - batch.getUnmatchedCount(), request.asOfDate());
        return toDTO(batch, rows);
    }

    /**
     * Decides, for every row that resolved to a person, whether this company's
     * file is entitled to state that person's balance on the batch's as-of
     * date — and records the evidence either way.
     *
     * <p>The date is {@code batch.asOfDate}, never today. The file is a
     * statement about one instant, and the transfer rule ("payroll moves the
     * available balance, so the receiving company's figures already contain
     * the old company's remainder") has a different answer either side of the
     * transfer. Judging an as-of-June A/S export against today's employment
     * would exclude a person who transferred in August — from the only file
     * that lists them for June — and hand them no baseline at all, silently.</p>
     *
     * <p>The company comes from {@link EmploymentCompanyLookup}, one targeted
     * {@code userstatus} query, and emphatically not from
     * {@code User.getUserStatus}: the users above are loaded shallow for name
     * matching, a shallow User carries no statuses, and {@code getUserStatus}
     * answers an empty timeline with a synthetic TERMINATED status whose
     * company is null. Every employee in every file would come back
     * UNKNOWN_COMPANY, and nothing would throw or log.</p>
     */
    private void applyCompanyGate(VacationImportBatch batch, List<VacationImportRow> rows) {
        Set<String> resolvedUsers = rows.stream()
                .map(VacationImportRow::getUseruuid)
                .filter(useruuid -> useruuid != null && !useruuid.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> companyByUser = employmentCompanyLookup.companiesAt(resolvedUsers, batch.getAsOfDate());

        Map<String, String> companyNames = new HashMap<>();
        for (VacationImportRow row : rows) {
            String companyAtAsOf = row.getUseruuid() == null ? null : companyByUser.get(row.getUseruuid());
            row.setCompanyAtAsOf(companyAtAsOf);
            row.setMatchStatus(ImportCompanyGate.verdict(row.getUseruuid(), companyAtAsOf, batch.getCompanyuuid()));

            // Same spirit as logMergedRows: the skip is correct, but it is also
            // invisible — a person simply stops appearing in the apply. Name
            // them, so an operator can check the transfer really happened.
            if (row.getMatchStatus() == VacationImportRowStatus.OTHER_COMPANY) {
                log.warnf("vacation-import: batch %s line %d skips \"%s\" (user %s) — userstatus puts them at %s on %s, "
                                + "not at the batch company %s; their balance was transferred with them",
                        batch.getUuid(), row.getLineNo(), row.getDanlonName(), row.getUseruuid(),
                        companyName(companyNames, companyAtAsOf), batch.getAsOfDate(),
                        companyName(companyNames, batch.getCompanyuuid()));
            }
        }

        // A whole file with no determinable company is a system fault, not a
        // data fault — it is precisely what the shallow-User regression looks
        // like from the outside. Say so distinctly; the alternative is HR
        // staring at 200 rows they cannot resolve.
        if (!resolvedUsers.isEmpty() && companyByUser.isEmpty()) {
            log.errorf("vacation-import: batch %s resolved %d employees but the userstatus lookup returned a company for "
                            + "none of them as of %s — this is a lookup fault, not a data fault",
                    batch.getUuid(), resolvedUsers.size(), batch.getAsOfDate());
        }
    }

    /**
     * Refuses a batch that was uploaded before the company gate existed.
     *
     * <p>{@link #applyCompanyGate} runs in {@code createBatch} and nowhere
     * else, and {@code apply} deliberately trusts the stored verdict so an HR
     * override is never silently undone. A batch uploaded before this feature
     * shipped therefore carries pre-gate verdicts: every row AUTO or MANUAL,
     * none of them ever checked against an employment record. Applying it
     * would post exactly the stale cross-company baselines the gate exists to
     * stop — and the review screen would show "all rows will apply", which
     * reads as a clean, gated batch rather than an unchecked one.</p>
     *
     * <p>The fingerprint is exact rather than heuristic: {@link
     * ImportCompanyGate#verdict} returns AUTO only when the resolved company
     * equals the batch company, so a post-gate AUTO row always carries a
     * company. AUTO with none can only have been written before the gate.</p>
     *
     * <p>A pre-gate batch in which HR happened to resolve <em>every</em> row by
     * hand is not caught, because MANUAL legitimately carries no company when
     * the timeline cannot place the person. That is the intended blind spot:
     * MANUAL is an explicit human decision, which is the one thing the gate
     * never overrules anyway.</p>
     */
    private void requireGatedBatch(VacationImportBatch batch, List<VacationImportRow> rows) {
        long ungated = rows.stream()
                .filter(row -> ImportCompanyGate.isUngatedAutoRow(row.getMatchStatus(), row.getCompanyAtAsOf()))
                .count();
        if (ungated == 0) return;
        log.warnf("vacation-import: refusing batch %s — %d auto-matched rows carry no company verdict, so it predates "
                + "the employment gate and was never checked against userstatus", batch.getUuid(), ungated);
        throw new WebApplicationException(
                "This upload was made before the import started checking employment records, so its "
                        + ungated + " auto-matched rows were never verified against the company they belong to. "
                        + "Upload the file again to have it checked — your saved name matches are reused.",
                Response.Status.CONFLICT);
    }

    /** Company names for log lines only; resolved once per company per batch. */
    private String companyName(Map<String, String> cache, String companyuuid) {
        if (companyuuid == null) return "an unknown company";
        return cache.computeIfAbsent(companyuuid, uuid -> {
            Company company = Company.findById(uuid);
            return company == null || company.getName() == null ? uuid : company.getName();
        });
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
            // The evidence described a person this row no longer points at.
            row.setCompanyAtAsOf(null);
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
            // MANUAL is HR's override and is final: the company gate runs once,
            // in createBatch, and apply trusts the stored status. Re-deriving
            // the verdict at apply time would silently undo every override.
            // The company is still recorded — as evidence, and as the trigger
            // for the audit line below.
            String companyAtAsOf = employmentCompanyLookup
                    .companiesAt(List.of(user.getUuid()), batch.getAsOfDate())
                    .get(user.getUuid());
            row.setCompanyAtAsOf(companyAtAsOf);
            row.setMatchStatus(VacationImportRowStatus.MANUAL);
            if (companyAtAsOf == null || !companyAtAsOf.equals(batch.getCompanyuuid())) {
                Map<String, String> companyNames = new HashMap<>();
                log.warnf("vacation-import: batch %s line %d overridden by %s onto user %s (%s) whose company on %s is %s, "
                                + "not the batch company %s — imported deliberately",
                        batchUuid, row.getLineNo(), actorUuid, user.getUuid(), user.getFullname(),
                        batch.getAsOfDate(), companyName(companyNames, companyAtAsOf),
                        companyName(companyNames, batch.getCompanyuuid()));
            }
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
        refreshCountsFrom(batch, VacationImportRow.findByBatch(batch.getUuid()));
    }

    /**
     * Counts by {@link Bucket}, not by constant. {@code matchedCount} is
     * everything that will be applied and {@code unmatchedCount} everything
     * that blocks the apply — so UNKNOWN_COMPANY lands in the number the
     * review screen already gates its Apply button on, and a status added
     * later cannot fall between the two and become invisible.
     */
    private void refreshCountsFrom(VacationImportBatch batch, List<VacationImportRow> rows) {
        batch.setMatchedCount((int) rows.stream()
                .filter(r -> r.getMatchStatus().bucket() == Bucket.APPLIES)
                .count());
        batch.setUnmatchedCount((int) rows.stream()
                .filter(r -> r.getMatchStatus().bucket() == Bucket.BLOCKS)
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

        // Everything in the BLOCKS bucket, not just UNMATCHED. An
        // UNKNOWN_COMPANY row is a person the system cannot place, and dropping
        // it silently is the one outcome the company gate exists to prevent —
        // so it stops the apply exactly as an unmatched name does. The two
        // cases need different actions from HR, so they are counted and named
        // separately in the message.
        Map<VacationImportRowStatus, Long> blocking = rows.stream()
                .filter(r -> r.getMatchStatus().bucket() == Bucket.BLOCKS)
                .collect(Collectors.groupingBy(VacationImportRow::getMatchStatus,
                        () -> new EnumMap<>(VacationImportRowStatus.class), Collectors.counting()));
        if (!blocking.isEmpty()) {
            String detail = blocking.entrySet().stream()
                    .map(entry -> switch (entry.getKey()) {
                        case UNMATCHED -> entry.getValue() + " unmatched — no single employee carries that Danløn name";
                        case UNKNOWN_COMPANY -> entry.getValue() + " with no employment record on " + batch.getAsOfDate()
                                + " — nothing says which company holds their balance";
                        // Wording only; the bucket above is what decides.
                        default -> entry.getValue() + " " + entry.getKey();
                    })
                    .collect(Collectors.joining("; "));
            throw new WebApplicationException(
                    "The batch still has rows to resolve: " + detail + ". Match them to an employee or ignore them first.",
                    Response.Status.CONFLICT);
        }

        requireGatedBatch(batch, rows);

        // Claim the batch before posting anything. The status check above is a
        // plain read, so two simultaneous applies would both pass it and both
        // post the same baselines — every baseline in a batch shares the as-of
        // date and the batch uuid, so the keys would be identical and the loser
        // would surface as a bare duplicate-key 409 explaining nothing. The
        // conditional update takes the row lock, matches nothing for the second
        // caller, and sends it the same message as any other late apply.
        LocalDateTime appliedAt = LocalDateTime.now();
        int claimed = VacationImportBatch.update(
                "status = ?1, appliedAt = ?2, appliedBy = ?3 WHERE uuid = ?4 AND status = ?5",
                VacationImportBatchStatus.APPLIED, appliedAt, actorUuid, batchUuid, VacationImportBatchStatus.PENDING);
        if (claimed == 0) {
            throw new WebApplicationException("The batch is already applied", Response.Status.CONFLICT);
        }
        batch.setStatus(VacationImportBatchStatus.APPLIED);
        batch.setAppliedAt(appliedAt);
        batch.setAppliedBy(actorUuid);

        // The planner merges the lines of anyone Danløn listed more than once
        // before it splits anything into pools — see ImportBaselinePlanner for
        // why that order is the whole point.
        List<PolicyRate> policies = policyService.rates();
        List<BaselineEntry> plan = ImportBaselinePlanner.plan(toPlannerRows(rows), policies);
        logMergedRows(batchUuid, rows);
        plan.forEach(entry -> persistBaseline(batch, entry, actorUuid));

        // Say "posting", not "applied". The old wording claimed success from
        // inside the transaction: when the duplicate-key violation took the
        // Technology batch down at commit, the log read "batch … applied … 208
        // baseline entries" on all six failed attempts and pointed the
        // investigation away from the writer. Whether the work survived is
        // only knowable after completion, so that is where success is logged.
        log.infof("vacation-import: batch %s posting %d baseline entries for %s",
                batchUuid, plan.size(), actorUuid);
        logOutcomeAfterCompletion(batchUuid, actorUuid, plan.size());
        return toDTO(batch, rows);
    }

    /**
     * Records who was merged, because the merge leaves no trace of itself.
     *
     * <p>Before the planner, two Danløn lines for one person collided on the
     * ledger's dedup key and the apply died loudly. That collision was also,
     * accidentally, the only automatic detector of the opposite mistake — two
     * different people whose names normalise to one existing user, silently
     * summed onto whichever of them holds the account. Merging by design
     * removes the alarm, so the merge has to announce itself instead: the
     * batch's rows stay in {@code vacation_import_rows} for the audit, and
     * this line tells an operator which employees to go and check.</p>
     */
    private void logMergedRows(String batchUuid, List<VacationImportRow> rows) {
        Map<String, List<Integer>> linesByUser = new LinkedHashMap<>();
        for (VacationImportRow row : rows) {
            // Only the rows that will actually be merged. Counting a skipped
            // line here would warn about a merge that is not going to happen —
            // an AUTO + OTHER_COMPANY pair is one line applied, not two summed.
            if (row.getMatchStatus().bucket() != Bucket.APPLIES) continue;
            if (row.getUseruuid() == null || row.getUseruuid().isBlank()) continue;
            linesByUser.computeIfAbsent(row.getUseruuid(), k -> new java.util.ArrayList<>()).add(row.getLineNo());
        }
        linesByUser.forEach((useruuid, lines) -> {
            if (lines.size() < 2) return;
            log.warnf("vacation-import: batch %s merges %d lines %s onto user %s — verify they are one person",
                    batchUuid, lines.size(), lines, useruuid);
        });
    }

    /**
     * Logs the committed outcome, so a rollback can never leave a success line
     * behind. Registered interposed, matching {@code AggregateEventSender}; if
     * there is no transaction to hang it on there is nothing to roll back
     * either, and the pre-commit line above already stands as the record.
     */
    private void logOutcomeAfterCompletion(String batchUuid, String actorUuid, int entries) {
        try {
            txSyncRegistry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == Status.STATUS_COMMITTED) {
                        log.infof("vacation-import: batch %s applied by %s — %d baseline entries committed",
                                batchUuid, actorUuid, entries);
                    } else {
                        log.errorf("vacation-import: batch %s NOT applied — the transaction rolled back (status %d); "
                                + "the batch stays PENDING and no baseline was written", batchUuid, status);
                    }
                }
            });
        } catch (Exception e) {
            log.debugf("vacation-import: batch %s has no transaction to report completion on", batchUuid);
        }
    }

    /** Adapts the persisted rows to the planner's view of them — figures only. */
    private List<ImportBaselinePlanner.Row> toPlannerRows(List<VacationImportRow> rows) {
        return rows.stream().map(row -> {
            Map<Integer, ImportBaselinePlanner.Figures> years = new LinkedHashMap<>();
            readRawJson(row.getRawJson()).years().forEach((ferieaar, figures) -> years.put(ferieaar,
                    new ImportBaselinePlanner.Figures(figures.earnedDays(), figures.usedDays())));
            return new ImportBaselinePlanner.Row(row.getUseruuid(), row.getMatchStatus(), years);
        }).toList();
    }

    private void persistBaseline(VacationImportBatch batch, BaselineEntry entry, String actorUuid) {
        // Zero-value baselines post too: "Danløn says 0" is an override statement.
        ledgerService.buildEntry(entry.useruuid(), entry.ferieaar(), entry.pool(), entry.type(), entry.days(),
                batch.getAsOfDate(), VacationEntrySource.DANLON_IMPORT, batch.getUuid(),
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
            Map<String, String> companyNames = new HashMap<>();
            rowDTOs = rows.stream().map(row -> {
                RawRow raw = readRawJson(row.getRawJson());
                Map<Integer, VacationImportRowDTO.YearFiguresDTO> years = new LinkedHashMap<>();
                raw.years().forEach((year, figures) -> years.put(year, new VacationImportRowDTO.YearFiguresDTO(
                        figures.earnedDays(), figures.usedDays(), figures.earnedKrRaw(), figures.provisionKrRaw())));
                String companyAtAsOfName = row.getCompanyAtAsOf() == null
                        ? null : companyName(companyNames, row.getCompanyAtAsOf());
                return new VacationImportRowDTO(row.getUuid(), row.getLineNo(), row.getDanlonName(),
                        row.getUseruuid(), names.get(row.getUseruuid()),
                        row.getMatchStatus().name(), row.getCompanyAtAsOf(), companyAtAsOfName, years);
            }).toList();
        }
        return new VacationImportBatchDTO(batch.getUuid(), batch.getCompanyuuid(), batch.getFilename(),
                batch.getAsOfDate(), batch.getStatus().name(), batch.getUploadedBy(), batch.getUploadedAt(),
                batch.getAppliedAt(), batch.getAppliedBy(), batch.getRowCount(), batch.getMatchedCount(),
                batch.getUnmatchedCount(), rowDTOs);
    }
}
