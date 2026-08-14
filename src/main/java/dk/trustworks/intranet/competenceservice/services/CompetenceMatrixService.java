package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Subject;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatus;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.services.CompetenceStatusService.Snapshot;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.security.AuthorizationService;
import dk.trustworks.intranet.security.ScopeResolution;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The people × requirements grid (spec §5.5, §8.4) and the single definition of who a
 * leader may see (spec §3.3).
 *
 * <p>Three properties carry the weight here.
 *
 * <ol>
 *   <li><strong>Row-level reach is resolved once, fail-closed, and narrows the query.</strong>
 *       The matrix is other people's training records — personal data — so it is gated by
 *       {@code competence:approve} at the resource and narrowed per row by
 *       {@link AuthorizationService#resolveReach}. An unresolvable reach yields an
 *       <em>empty</em> matrix, never a full one: a resolver failure that widened the grid
 *       would leak every employee's compliance history to whoever asked first.</li>
 *   <li><strong>Statuses come from one snapshot for the whole population.</strong> The grid
 *       is O(users × requirements); resolving a cell at a time would be thousands of
 *       queries on a page that a leader reloads all day (spec §10.10).</li>
 *   <li><strong>There is exactly one narrowing.</strong> {@link #reachableUsers(String)}
 *       exposes it so the export service reuses this population rather than deriving its
 *       own. A second, subtly different narrowing — one that forgets the audience union,
 *       or applies reach after loading rather than in the WHERE clause — is precisely how a
 *       data-scope leak gets shipped: both surfaces look right in isolation and disagree
 *       only for the people who matter.</li>
 * </ol>
 *
 * <p>Inactive requirements are out (soft-retired krav leave the grid and keep their
 * history), but <em>half-published</em> ones stay in and read "Not published yet" — that is
 * the state a content owner most needs to see, and it is exactly what the calculator
 * already produces when the snapshot has no active label for the requirement (spec §5.1).
 */
@JBossLog
@ApplicationScoped
public class CompetenceMatrixService {

    /**
     * The key the row-level narrowing resolves. Approving and authoring are deliberately
     * separate keys (spec §3.1), so the matrix must ask about approval and never about
     * {@code competence:write}.
     */
    private static final String PERMISSION_APPROVE = "competence:approve";

    @Inject
    AuthorizationService authorizationService;

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    CompetenceStatusService statusService;

    // -----------------------------------------------------------------------
    // wire shapes
    // -----------------------------------------------------------------------

    /**
     * One whole grid. Plain records all the way down — no entity leaks into the response,
     * so the REST layer returns this directly and nothing lazy is serialized.
     *
     * @param columns the active requirements, in {@code sortOrder, name} order
     * @param rows    one per person in reach, in display-name order; each row's cells are
     *                parallel to {@code columns}
     * @param kpis    the four numbers of spec §8.4, computed here so the frontend does not
     *                recompute them from the cells and drift
     */
    public record Matrix(List<MatrixColumn> columns, List<MatrixRow> rows, MatrixKpis kpis) {

        /** The fail-closed answer: no columns, no rows, no numbers. */
        public static Matrix empty() {
            return new Matrix(List.of(), List.of(), MatrixKpis.zero());
        }
    }

    /**
     * A requirement column. The grid labels the header with {@code kref} and puts
     * {@code name} in the {@code title} attribute (spec §8.4), so both travel.
     */
    public record MatrixColumn(String requirementUuid, String compId, String kref, String name) {
    }

    public record MatrixRow(String useruuid, String name, List<MatrixCell> cells) {
    }

    /**
     * One cell: both track statuses plus the collapsed verdict.
     *
     * <p>Both tracks travel because the cell verdict deliberately loses information — a red
     * cell does not say whether the microcourse or the test is the thing to chase, and the
     * leader's next action depends on which.
     *
     * @param inAudience whether this person is currently in this requirement's audience.
     *                   Rows are the <em>union</em> of all audiences, so a person pulled in
     *                   by one requirement necessarily gets a cell for every other one too,
     *                   including requirements they are not required to take. Those cells
     *                   would otherwise read "Not started" and send a leader chasing
     *                   training nobody owes; they are shown greyed and are excluded from
     *                   the KPIs. Historic evidence still shows: someone who has left the
     *                   audience but passed keeps a green cell.
     */
    public record MatrixCell(String requirementUuid,
                             CompetenceStatus.Course courseStatus,
                             CompetenceStatus.Test testStatus,
                             CompetenceStatus.Cell status,
                             boolean inAudience) {

        /**
         * Whether this cell counts towards coverage. "Not published yet" is the content
         * owner's debt, not the employee's, and a requirement outside this person's
         * audience is nobody's — neither belongs in a compliance percentage.
         */
        public boolean applicable() {
            return inAudience && status != CompetenceStatus.Cell.NOT_PUBLISHED;
        }
    }

    /**
     * The four KPI cards above the grid (spec §8.4).
     *
     * @param applicableCells  cells counted — in audience and published
     * @param approvedCells    of those, approved
     * @param approvedCoverage {@code approvedCells / applicableCells} as a fraction in
     *                         [0,1] — the card multiplies by 100. Zero when there is
     *                         nothing to cover: {@code 0/0} would be {@code NaN}, which is
     *                         not valid JSON and renders as a broken card rather than an
     *                         honest "nothing to show"
     * @param awaitingApproval passed but undecided — the approver's queue length
     * @param overdue          past cadence on either track
     * @param retakeRequired   a new version landed on either track
     */
    public record MatrixKpis(int applicableCells,
                             int approvedCells,
                             double approvedCoverage,
                             int awaitingApproval,
                             int overdue,
                             int retakeRequired) {

        public static MatrixKpis zero() {
            return new MatrixKpis(0, 0, 0d, 0, 0, 0);
        }
    }

    // -----------------------------------------------------------------------
    // the grid
    // -----------------------------------------------------------------------

    /**
     * The grid as one actor may see it.
     *
     * @param actorUuid the caller, from {@code RequestHeaderHolder} — never a path
     *                  parameter, or the narrowing becomes an IDOR surface
     */
    public Matrix matrix(String actorUuid) {
        ScopeResolution reach = reachOf(actorUuid);
        if (reach.isNone()) {
            // Fail closed. No grant, an unknown actor and a resolver failure all land here,
            // and the difference between them is not knowable from the outside — so the
            // only safe answer is nothing. Never fall through to "show everyone".
            log.debugf("Competence matrix denied for actor %s — no reach for %s",
                    actorUuid, PERMISSION_APPROVE);
            return Matrix.empty();
        }

        List<CompetenceRequirement> requirements = CompetenceRequirement.listActive();
        Population population = narrow(reach, requirements);
        if (population.users().isEmpty()) {
            return new Matrix(columnsOf(requirements), List.of(), MatrixKpis.zero());
        }

        List<String> useruuids = population.users().stream().map(User::getUuid).toList();
        // ONE snapshot for the whole grid — four queries regardless of how big it gets.
        Snapshot snapshot = statusService.snapshot(useruuids);
        LocalDateTime now = LocalDateTime.now();

        int applicableCells = 0;
        int approvedCells = 0;
        int awaitingApproval = 0;
        int overdue = 0;
        int retakeRequired = 0;

        List<MatrixRow> rows = new ArrayList<>(population.users().size());
        for (User user : population.users()) {
            Subject subject = population.subjects().get(user.getUuid());
            List<MatrixCell> cells = new ArrayList<>(requirements.size());

            for (int i = 0; i < requirements.size(); i++) {
                CompetenceRequirement requirement = requirements.get(i);
                CompetenceStatus.Course course =
                        statusService.courseStatus(requirement, snapshot, user.getUuid(), now);
                CompetenceStatus.Test test =
                        statusService.testStatus(requirement, snapshot, user.getUuid(), now);
                CompetenceStatus.Cell verdict = CompetenceStatusCalculator.cellStatus(course, test);
                boolean inAudience = CompetenceAudienceMatcher.inAudience(
                        subject, population.targetings().get(i));

                MatrixCell cell = new MatrixCell(
                        requirement.getUuid(), course, test, verdict, inAudience);
                cells.add(cell);

                if (!cell.applicable()) {
                    continue;
                }
                applicableCells++;
                switch (verdict) {
                    case APPROVED -> approvedCells++;
                    case AWAITING_APPROVAL -> awaitingApproval++;
                    case OVERDUE -> overdue++;
                    case RETAKE_REQUIRED -> retakeRequired++;
                    default -> {
                        // NOT_STARTED and COURSE_COMPLETED are in the denominator only.
                    }
                }
            }
            rows.add(new MatrixRow(user.getUuid(), displayName(user), cells));
        }

        double coverage = applicableCells == 0 ? 0d : (double) approvedCells / applicableCells;
        MatrixKpis kpis = new MatrixKpis(applicableCells, approvedCells, coverage,
                awaitingApproval, overdue, retakeRequired);

        log.debugf("Competence matrix for actor %s: %d rows x %d requirements, %d applicable cells",
                actorUuid, rows.size(), requirements.size(), applicableCells);
        return new Matrix(columnsOf(requirements), rows, kpis);
    }

    /**
     * The population the matrix would show, for callers that need the narrowing without the
     * grid — the audit export above all.
     *
     * <p>Exposed on purpose. The export answers "whose records may this actor download",
     * which must be the same question the matrix answers; re-deriving it there would give
     * two implementations that agree on the easy cases and diverge on exactly the ones that
     * matter (a lead whose reach changed, a person who left an audience). One narrowing,
     * one answer.
     *
     * @return insertion-ordered so a caller iterating it produces stable output
     */
    public Set<String> reachableUsers(String actorUuid) {
        ScopeResolution reach = reachOf(actorUuid);
        if (reach.isNone()) {
            return Set.of();
        }
        return narrow(reach, CompetenceRequirement.listActive()).users().stream()
                .map(User::getUuid)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // -----------------------------------------------------------------------
    // narrowing
    // -----------------------------------------------------------------------

    private ScopeResolution reachOf(String actorUuid) {
        return authorizationService.resolveReach(
                actorUuid, PERMISSION_APPROVE, LocalDate.now(), Set.of());
    }

    /**
     * The population and everything derived while computing it.
     *
     * @param targetings parallel to the requirement list it was built from, so a cell can
     *                   ask "is this person in this requirement's audience" without
     *                   re-parsing the three JSON target arrays per cell
     */
    private record Population(List<User> users,
                              Map<String, Subject> subjects,
                              List<Targeting> targetings) {

        static Population empty() {
            return new Population(List.of(), Map.of(), List.of());
        }
    }

    /**
     * Candidates ∩ reach: every requirement's audience unioned, restricted to the subjects
     * the actor may see.
     */
    private Population narrow(ScopeResolution reach, List<CompetenceRequirement> requirements) {
        List<Targeting> targetings = requirements.stream()
                .map(requirementService::targetingOf)
                .toList();
        List<User> candidates = loadCandidates(reach);
        if (candidates.isEmpty() || requirements.isEmpty()) {
            return Population.empty();
        }

        // ONE batch for the whole population: two queries, not two per person.
        Map<String, Subject> subjects = requirementService.subjectsOf(candidates);

        List<User> population = new ArrayList<>();
        for (User user : candidates) {
            // Belt and braces: the subject set already reached the WHERE clause above, and
            // this predicate keeps the guarantee if that loader is ever widened.
            if (!reach.permits(user.getUuid())) {
                continue;
            }
            Subject subject = subjects.get(user.getUuid());
            boolean inAnyAudience = targetings.stream()
                    .anyMatch(targeting -> CompetenceAudienceMatcher.inAudience(subject, targeting));
            if (inAnyAudience) {
                population.add(user);
            }
        }
        population.sort(Comparator.comparing(
                CompetenceMatrixService::displayName, String.CASE_INSENSITIVE_ORDER));
        return new Population(population, subjects, targetings);
    }

    /**
     * The people the actor may see at all, before the audience union.
     *
     * <p>A bounded reach becomes the {@code WHERE} clause rather than a post-filter, so
     * rows out of scope are never loaded in the first place — the convention the rest of
     * the authorization work follows.
     */
    private List<User> loadCandidates(ScopeResolution reach) {
        List<User> users = reach.unbounded()
                ? User.listAll(Sort.ascending("username"))
                : User.list("uuid in ?1", new ArrayList<>(reach.subjects()));
        hydrateStatuses(users);
        return users;
    }

    /**
     * Attaches employment statuses.
     *
     * <p><strong>Not optional.</strong> {@code User.statuses} is {@code @Transient}, so a
     * bare {@code list()} leaves it empty and {@code getUserStatus(date)} then falls back to
     * its synthetic TERMINATED status — for <em>everyone</em>. The audience matcher admits
     * only active employees, so the whole grid would come back empty and look exactly like
     * a permission problem.
     *
     * <p>Only the statuses are hydrated: salaries, bank details and contact info are what
     * the general-purpose user hydration also pulls, and none of them belongs anywhere near
     * a training grid. Not loading them is a stronger guarantee than stripping them on the
     * way out.
     *
     * <p>Package-private and static rather than private: {@code CompetenceRequirementService}
     * resolves the same population for the audience preview and needs the identical
     * hydration. Copying ten lines would mean copying the reasoning above too, and the copy
     * that eventually drifts is the one that silently returns an empty audience.
     */
    static void hydrateStatuses(List<User> users) {
        if (users.isEmpty()) {
            return;
        }
        List<String> useruuids = users.stream().map(User::getUuid).toList();
        Map<String, List<UserStatus>> byUser =
                UserStatus.<UserStatus>list("useruuid in ?1 order by statusdate", useruuids)
                        .stream()
                        .collect(Collectors.groupingBy(UserStatus::getUseruuid));
        for (User user : users) {
            user.setStatuses(new ArrayList<>(byUser.getOrDefault(user.getUuid(), List.of())));
        }
    }

    // -----------------------------------------------------------------------
    // presentation helpers
    // -----------------------------------------------------------------------

    private List<MatrixColumn> columnsOf(List<CompetenceRequirement> requirements) {
        return requirements.stream()
                .map(r -> new MatrixColumn(r.getUuid(), r.getCompId(), r.getKref(), r.getName()))
                .toList();
    }

    /**
     * {@code User.getFullname()} concatenates the two name columns unguarded and renders
     * the literal {@code "null null"} for a half-filled row, which then sorts among the
     * N's. Fall back to the username instead — it is never blank.
     */
    static String displayName(User user) {
        String first = user.getFirstname() == null ? "" : user.getFirstname().trim();
        String last = user.getLastname() == null ? "" : user.getLastname().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? user.getUsername() : full;
    }
}
