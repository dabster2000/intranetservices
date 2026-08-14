package dk.trustworks.intranet.competenceservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Subject;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.dto.AudiencePreviewDTO;
import dk.trustworks.intranet.competenceservice.dto.RequirementUpsertRequest;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.model.enums.TeamMemberType;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Requirement lookup, audience resolution and visibility.
 *
 * <p>Audience is evaluated live on read and never materialised: a person who joins a team
 * today is in the audience today, with no job to run and nothing to go stale. The decision
 * itself lives in the pure {@link CompetenceAudienceMatcher}; this resolves the inputs.
 */
@JBossLog
@ApplicationScoped
public class CompetenceRequirementService {

    /** Leading or sponsoring a team counts as membership of that team's practice. */
    private static final Set<TeamMemberType> LEADERSHIP =
            Set.of(TeamMemberType.LEADER, TeamMemberType.SPONSOR);

    // -----------------------------------------------------------------------
    // targeting
    // -----------------------------------------------------------------------

    /**
     * Parses the three stored arrays.
     *
     * <p>NULL or blank means the column is <em>absent</em>; a literal {@code '[]'} means
     * an explicit empty target. The asymmetry is deliberate and preserved from the
     * questionnaire convention — all-absent reaches everyone, all-{@code '[]'} reaches
     * nobody, which is a valid way to park a requirement without deactivating it.
     */
    public Targeting targetingOf(CompetenceRequirement requirement) {
        return new Targeting(
                parseArray(requirement.getTargetPracticeUuids(), requirement.getUuid(), "practices"),
                parseArray(requirement.getTargetTeams(), requirement.getUuid(), "teams"),
                parseArray(requirement.getTargetUseruuids(), requirement.getUuid(), "users"));
    }

    private List<String> parseArray(String json, String requirementUuid, String label) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return CompetencePayloadCodec.mapper().readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            // Fail closed: an unreadable target must not silently widen to "everyone".
            log.warnf("Invalid target_%s JSON on competence requirement %s — treating as empty",
                    label, requirementUuid);
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // subjects
    // -----------------------------------------------------------------------

    /** Resolve one person into the shape the matcher needs. */
    public Subject subjectOf(String useruuid) {
        User user = User.findById(useruuid);
        if (user == null) {
            return new Subject(useruuid, false, null, Set.of(), Set.of());
        }
        return subjectsOf(List.of(user)).get(useruuid);
    }

    /**
     * Batch version: two queries for the whole population regardless of size, because the
     * matrix resolves every row's audience on every load.
     */
    public Map<String, Subject> subjectsOf(List<User> users) {
        LocalDate today = LocalDate.now();
        List<String> useruuids = users.stream().map(User::getUuid).toList();

        List<TeamRole> roles = useruuids.isEmpty()
                ? List.of()
                : TeamRole.list("useruuid in ?1", useruuids);
        List<TeamRole> active = roles.stream().filter(r -> isActive(r, today)).toList();

        Map<String, String> teamPractices = teamPracticeIndex(
                active.stream().map(TeamRole::getTeamuuid).distinct().toList());

        Map<String, Set<String>> teamsByUser = new HashMap<>();
        Map<String, Set<String>> ledPracticesByUser = new HashMap<>();
        for (TeamRole role : active) {
            teamsByUser.computeIfAbsent(role.getUseruuid(), k -> new HashSet<>())
                    .add(role.getTeamuuid());
            if (LEADERSHIP.contains(role.getTeammembertype())) {
                String practiceUuid = teamPractices.get(role.getTeamuuid());
                if (practiceUuid != null) {
                    ledPracticesByUser.computeIfAbsent(role.getUseruuid(), k -> new HashSet<>())
                            .add(practiceUuid);
                }
            }
        }

        Map<String, Subject> out = new HashMap<>();
        for (User user : users) {
            out.put(user.getUuid(), new Subject(
                    user.getUuid(),
                    isActiveEmployee(user, today),
                    user.getPracticeUuid(),
                    teamsByUser.getOrDefault(user.getUuid(), Set.of()),
                    ledPracticesByUser.getOrDefault(user.getUuid(), Set.of())));
        }
        return out;
    }

    private Map<String, String> teamPracticeIndex(List<String> teamUuids) {
        Map<String, String> index = new HashMap<>();
        if (teamUuids.isEmpty()) {
            return index;
        }
        List<Team> teams = Team.list("uuid in ?1", teamUuids);
        for (Team team : teams) {
            if (team.getPracticeUuid() != null) {
                index.put(team.getUuid(), team.getPracticeUuid());
            }
        }
        return index;
    }

    /** Same predicate {@code QuestionnaireService.getActiveReminders} uses. */
    static boolean isActive(TeamRole role, LocalDate today) {
        return (role.getStartdate() == null || !role.getStartdate().isAfter(today))
                && (role.getEnddate() == null || !role.getEnddate().isBefore(today));
    }

    /**
     * {@code getUserStatus} synthesises a TERMINATED status for a user with no status
     * rows at all, so this is safe on incomplete data — it fails closed.
     */
    static boolean isActiveEmployee(User user, LocalDate today) {
        return user.getUserStatus(today) != null
                && user.getUserStatus(today).getStatus() == StatusType.ACTIVE;
    }

    // -----------------------------------------------------------------------
    // visibility
    // -----------------------------------------------------------------------

    /**
     * A requirement is visible to an employee only when it is active, BOTH tracks are
     * published, and the person is in the audience. A half-published requirement must
     * never reach an employee — it still shows in the matrix, as "Not published yet".
     */
    public boolean isVisibleTo(CompetenceRequirement requirement, Subject subject) {
        if (!requirement.isActive()) {
            return false;
        }
        if (CompetenceContentVersion.findActive(requirement.getUuid(), ContentKind.COURSE) == null
                || CompetenceContentVersion.findActive(requirement.getUuid(), ContentKind.TEST) == null) {
            return false;
        }
        return CompetenceAudienceMatcher.inAudience(subject, targetingOf(requirement));
    }

    public List<CompetenceRequirement> visibleTo(String useruuid) {
        Subject subject = subjectOf(useruuid);
        List<CompetenceRequirement> out = new ArrayList<>();
        for (CompetenceRequirement requirement : CompetenceRequirement.listActive()) {
            if (isVisibleTo(requirement, subject)) {
                out.add(requirement);
            }
        }
        return out;
    }

    /**
     * Loads a requirement the caller may see, or 404s.
     *
     * <p>404 rather than 403 throughout the learner surface: "exists but not yours" and
     * "does not exist" must be indistinguishable, or the API becomes an oracle for what
     * other teams are being trained on.
     */
    public CompetenceRequirement requireVisible(String requirementUuid, String useruuid) {
        CompetenceRequirement requirement = CompetenceRequirement.findByUuid(requirementUuid);
        if (requirement == null || !isVisibleTo(requirement, subjectOf(useruuid))) {
            throw new WebApplicationException("No such requirement", Response.Status.NOT_FOUND);
        }
        return requirement;
    }

    // -----------------------------------------------------------------------
    // administration (spec §6.3, §11.4)
    // -----------------------------------------------------------------------

    /** Column widths from {@code V495}. Enforced here so an over-long field is a 400, not a truncation. */
    private static final int MAX_COMP_ID = 64;
    private static final int MAX_KREF = 32;
    private static final int MAX_NAME = 255;
    private static final int MAX_DESCRIPTION = 1000;

    /**
     * Room reserved at the end of a generated {@code comp_id} for a {@code -nn} collision
     * suffix, so appending one can never push the value past the column width.
     */
    private static final int COLLISION_SUFFIX_ROOM = 4;

    @Inject
    PracticeService practiceService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The real admin behind an impersonated request, or {@code null} for an ordinary one.
     *
     * <p>During impersonation {@code actorUuid} <em>is</em> the impersonated subject — the swap
     * happens in the BFF's JWT — so every line below would otherwise name the subject as the
     * author of a change they did not make, with nothing anywhere recording that the request
     * was impersonated at all. §10.4 says {@code X-Acting-For} discloses the actor; this is
     * where the authoring surface makes that sentence true.
     */
    private String actingFor() {
        return requestHeaderHolder.getActingForUuid();
    }

    /**
     * Every requirement including the inactive ones (§6.3).
     *
     * <p>The admin list is deliberately not {@link CompetenceRequirement#listActive()}: a
     * retired krav still owns content, version history and everyone's past evidence, and an
     * admin screen that cannot see it is a screen from which it can never be revived.
     */
    public List<CompetenceRequirement> listAll() {
        return CompetenceRequirement.listAllOrdered();
    }

    /**
     * Loads a requirement for an administrative operation, or 404s.
     *
     * <p>Unlike {@link #requireVisible} this applies no audience or publication filter — an
     * author edits krav they are not themselves subject to, which is the normal case. The
     * gate is the {@code competence:write} scope, not visibility.
     */
    public CompetenceRequirement requireExisting(String requirementUuid) {
        CompetenceRequirement requirement = CompetenceRequirement.findByUuid(requirementUuid);
        if (requirement == null) {
            throw new WebApplicationException("No such requirement", Response.Status.NOT_FOUND);
        }
        return requirement;
    }

    /**
     * Creates a requirement, deriving {@code comp_id} from the name.
     *
     * <p>{@code comp_id} is the import/export key (§9.6), so it is generated once here and
     * never recomputed: renaming a krav must not rename its key, or the next re-import of a
     * previously exported file would create a second requirement rather than update the one
     * it came from, and every attempt already recorded would hang off the orphan.
     *
     * <p>The new row starts with <em>no</em> content of either kind, which means
     * {@link #isVisibleTo} keeps it away from every employee until both a course and a test
     * have been published. A half-authored krav is invisible to learners and visible in the
     * matrix as "not published yet" — the state an author needs in order to work at all.
     */
    @Transactional
    public CompetenceRequirement create(RequirementUpsertRequest request, String actorUuid) {
        if (request == null) {
            throw new WebApplicationException("No requirement supplied", Response.Status.BAD_REQUEST);
        }
        CompetenceRequirement requirement = new CompetenceRequirement();
        requirement.setUuid(UUID.randomUUID().toString());
        requirement.setCompId(uniqueCompId(request.name()));
        applyEditableFields(request, requirement, true);
        requirement.persist();

        log.infof("COMPETENCE_REQUIREMENT_CREATED requirement=%s kref=%s active=%s actor=%s actingFor=%s",
                requirement.getCompId(), requirement.getKref(), requirement.isActive(), actorUuid,
                actingFor());
        return requirement;
    }

    /**
     * Full update of the editable fields (§6.3). {@code comp_id} is not one of them — see
     * {@link #create}.
     *
     * <p>Targeting is replaced wholesale rather than merged, because the editor posts the
     * arrays it is showing: a merge would make removing the last team impossible. The
     * NULL-vs-{@code []} asymmetry survives that (§5.2) — an absent array parks the field at
     * "no restriction", an empty one restricts to nobody, and only the client can tell those
     * two intentions apart.
     */
    @Transactional
    public CompetenceRequirement update(String requirementUuid, RequirementUpsertRequest request,
                                        String actorUuid) {
        CompetenceRequirement requirement = requireExisting(requirementUuid);
        if (request == null) {
            throw new WebApplicationException("No requirement supplied", Response.Status.BAD_REQUEST);
        }
        boolean wasActive = requirement.isActive();
        applyEditableFields(request, requirement, false);
        requirement.persist();

        log.infof("COMPETENCE_REQUIREMENT_UPDATED requirement=%s kref=%s active=%s wasActive=%s "
                        + "actor=%s actingFor=%s",
                requirement.getCompId(), requirement.getKref(), requirement.isActive(), wasActive,
                actorUuid, actingFor());
        return requirement;
    }

    /**
     * Validates and writes the editable fields onto {@code requirement}.
     *
     * <p>Validation runs before the first setter, so a rejected request leaves a managed
     * entity untouched — a half-applied update inside a transaction that then throws is
     * rolled back, but only if nothing has flushed in between, and relying on that is how a
     * dirty-checked field escapes.
     */
    private void applyEditableFields(RequirementUpsertRequest request,
                                     CompetenceRequirement requirement, boolean creating) {
        String name = requireText(request.name(), "Navn", MAX_NAME);
        String kref = requireText(request.kref(), "Kref", MAX_KREF);
        String description = optionalText(request.description(), "Beskrivelse", MAX_DESCRIPTION);
        Integer cadence = validCadenceOverride(request.cadenceDaysOverride());
        requireResolvableTargeting(new Targeting(request.targetPracticeUuids(),
                request.targetTeams(), request.targetUseruuids()));

        requirement.setName(name);
        requirement.setKref(kref);
        requirement.setDescription(description);
        requirement.setTargetPracticeUuids(writeArray(request.targetPracticeUuids()));
        requirement.setTargetTeams(writeArray(request.targetTeams()));
        requirement.setTargetUseruuids(writeArray(request.targetUseruuids()));
        requirement.setCadenceDaysOverride(cadence);
        if (request.sortOrder() != null) {
            requirement.setSortOrder(request.sortOrder());
        } else if (creating) {
            // Append. Display order only — an admin reorders afterwards.
            requirement.setSortOrder((int) CompetenceRequirement.count());
        }
        requirement.setActive(request.active() == null || request.active());
    }

    /**
     * The resolved audience of a requirement, for the editor's live headcount (§11.4).
     *
     * <p>This runs the same computation the matrix runs — {@link CompetenceAudienceMatcher}
     * over live team relationships and employment status — rather than a separate estimate,
     * so the preview and the grid can never disagree.
     */
    public AudiencePreviewDTO audiencePreview(String requirementUuid) {
        CompetenceRequirement requirement = requireExisting(requirementUuid);
        return audiencePreview(targetingOf(requirement));
    }

    /**
     * Preview for a targeting triple that has not been saved yet.
     *
     * <p>Loads the whole company, not the caller's reach: the audience of a krav is a
     * property of the krav, and narrowing it to what one author may otherwise see would
     * report a headcount that is wrong in exactly the direction that hides an
     * under-targeted requirement.
     */
    public AudiencePreviewDTO audiencePreview(Targeting targeting) {
        List<User> users = User.listAll(Sort.ascending("username"));
        // Not optional: User.statuses is @Transient, and without this every subject reads as
        // TERMINATED and the preview reports zero for a perfectly good targeting.
        CompetenceMatrixService.hydrateStatuses(users);
        Map<String, Subject> subjects = subjectsOf(users);

        List<AudiencePreviewDTO.Person> people = new ArrayList<>();
        for (User user : users) {
            if (CompetenceAudienceMatcher.inAudience(subjects.get(user.getUuid()), targeting)) {
                people.add(new AudiencePreviewDTO.Person(
                        user.getUuid(), CompetenceMatrixService.displayName(user)));
            }
        }
        people.sort(Comparator.comparing(AudiencePreviewDTO.Person::name,
                String.CASE_INSENSITIVE_ORDER));

        List<AudiencePreviewDTO.Unresolved> unresolved = targetProblems(targeting).stream()
                .map(p -> new AudiencePreviewDTO.Unresolved(p.targetType(), p.value(), p.reason()))
                .toList();
        return new AudiencePreviewDTO(people.size(), people, unresolved);
    }

    /**
     * useruuid → display name in one query, for the surfaces that name people alongside a
     * record ({@code /attempts/pending}, the settings change log).
     *
     * <p>Falls back to nothing rather than to a blank: a uuid with no user row is simply
     * absent from the map, and the caller decides what an unnamed row should read as. A
     * pseudonymised subject (§10.9) has no user row by construction, and silently rendering
     * it as an empty name would make an erased record look like a data bug.
     */
    public Map<String, String> displayNamesOf(Collection<String> useruuids) {
        Map<String, String> out = new HashMap<>();
        List<String> ids = useruuids == null
                ? List.of()
                : useruuids.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        List<User> users = User.list("uuid in ?1", ids);
        for (User user : users) {
            out.put(user.getUuid(), CompetenceMatrixService.displayName(user));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // targeting validation
    // -----------------------------------------------------------------------

    /**
     * One targeting entry that does not resolve.
     *
     * @param blocking whether it refuses the save. Everything that looks like a typo blocks;
     *                 "this person is not an active employee today" does not, because
     *                 pre-targeting a joiner is legitimate and the audience matcher already
     *                 declines to include them until they start.
     */
    private record TargetProblem(String targetType, String value, String reason, boolean blocking) {
    }

    /**
     * §11.4: every targeting entry must resolve to an existing, active row, and an unknown
     * uuid is a {@code 400} naming it — never a silent no-match.
     *
     * <p>The failure this prevents is the worst one the module has. A typo'd practice uuid
     * matches nobody, so the krav renders green for everyone, no cell is red, no reminder
     * fires, and the module reports full compliance for a requirement nobody was ever asked
     * to take. It is undetectable from the headcount alone, because a smaller-than-expected
     * audience looks like a targeting decision.
     */
    private void requireResolvableTargeting(Targeting targeting) {
        List<String> parts = blockingTargetingProblems(targeting);
        if (parts.isEmpty()) {
            return;
        }
        throw new WebApplicationException(
                "Ukendt mål i målretningen — " + String.join("; ", parts),
                Response.Status.BAD_REQUEST);
    }

    /**
     * The §11.4 blocking problems as messages, without throwing.
     *
     * <p>Exposed for the import path, which writes the same three columns as the REST upsert
     * and therefore has to apply the same rule — but reports every topic's errors at once
     * rather than failing on the first one, so it needs the list rather than the exception.
     * Both paths go through {@link #targetProblems} for the same reason the save-time check
     * and the preview do: three copies of "what resolves" would eventually disagree, and the
     * one that was wrong would be the one nobody looked at.
     *
     * @return one message per blocking problem, empty when the targeting resolves
     */
    public List<String> blockingTargetingProblems(Targeting targeting) {
        List<String> parts = new ArrayList<>();
        for (TargetProblem problem : targetProblems(targeting)) {
            if (problem.blocking()) {
                parts.add(problem.targetType() + " " + problem.value() + ": " + problem.reason());
            }
        }
        return parts;
    }

    /**
     * Resolves all three targeting arrays in three queries and reports what did not land.
     *
     * <p>Shared by the save-time check and the preview so the two can never disagree about
     * what "resolves" means.
     */
    private List<TargetProblem> targetProblems(Targeting targeting) {
        List<TargetProblem> problems = new ArrayList<>();
        if (targeting == null) {
            return problems;
        }
        problems.addAll(practiceProblems(targeting.practiceUuids()));
        problems.addAll(teamProblems(targeting.teamUuids()));
        problems.addAll(userProblems(targeting.userUuids()));
        return problems;
    }

    private List<TargetProblem> practiceProblems(List<String> targets) {
        List<TargetProblem> problems = new ArrayList<>();
        List<String> values = cleaned(targets);
        if (values.isEmpty()) {
            return problems;
        }
        Map<String, Practice> byUuid = new HashMap<>();
        List<Practice> rows = Practice.list("uuid in ?1", values);
        for (Practice practice : rows) {
            byUuid.put(practice.getUuid(), practice);
        }
        for (String value : values) {
            // Follows QuestionnaireService: 'UD'/null is the no-practice MEMBER token, not a
            // practice. Targeting it would create a dead letter that matches nobody — the
            // no-practice population is reachable by leaving the field empty or by team.
            if (practiceService.normalizeNoPracticeAlias(value) == null) {
                problems.add(new TargetProblem("practice", value,
                        "'UD'/null er ikke en practice — lad feltet stå tomt eller målret på team",
                        true));
                continue;
            }
            Practice practice = byUuid.get(value);
            if (practice == null) {
                problems.add(new TargetProblem("practice", value, "findes ikke", true));
            } else if (!practice.isActive()) {
                problems.add(new TargetProblem("practice", value, "er ikke aktiv", true));
            }
        }
        return problems;
    }

    private List<TargetProblem> teamProblems(List<String> targets) {
        List<TargetProblem> problems = new ArrayList<>();
        List<String> values = cleaned(targets);
        if (values.isEmpty()) {
            return problems;
        }
        Set<String> known = new HashSet<>();
        List<Team> rows = Team.list("uuid in ?1", values);
        for (Team team : rows) {
            known.add(team.getUuid());
        }
        for (String value : values) {
            if (!known.contains(value)) {
                problems.add(new TargetProblem("team", value, "findes ikke", true));
            }
        }
        return problems;
    }

    private List<TargetProblem> userProblems(List<String> targets) {
        List<TargetProblem> problems = new ArrayList<>();
        List<String> values = cleaned(targets);
        if (values.isEmpty()) {
            return problems;
        }
        LocalDate today = LocalDate.now();
        List<User> rows = User.list("uuid in ?1", values);
        CompetenceMatrixService.hydrateStatuses(rows);
        Map<String, User> byUuid = new HashMap<>();
        for (User user : rows) {
            byUuid.put(user.getUuid(), user);
        }
        for (String value : values) {
            User user = byUuid.get(value);
            if (user == null) {
                problems.add(new TargetProblem("user", value, "findes ikke", true));
            } else if (!isActiveEmployee(user, today)) {
                // Reported, never refused: naming a joiner before their start date is a
                // legitimate thing to do, and the matcher already keeps them out of the
                // audience until the status says otherwise.
                problems.add(new TargetProblem("user", value, "er ikke aktiv medarbejder i dag",
                        false));
            }
        }
        return problems;
    }

    private static List<String> cleaned(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .distinct()
                .toList();
    }

    // -----------------------------------------------------------------------
    // comp_id
    // -----------------------------------------------------------------------

    /**
     * Slugifies the name and appends a numeric suffix until nothing collides.
     *
     * <p>The lookup-then-insert is not atomic, and deliberately not made so: the UNIQUE index
     * on {@code comp_id} is the actual guarantee, and this only spares an author the
     * constraint violation in the overwhelmingly common case. Two admins creating identically
     * named krav in the same second get one success and one {@code 409} from the database,
     * which is the correct outcome.
     */
    private String uniqueCompId(String name) {
        String base = slugify(name);
        if (CompetenceRequirement.findByCompId(base) == null) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (CompetenceRequirement.findByCompId(candidate) == null) {
                return candidate;
            }
        }
        throw new WebApplicationException(
                "Kunne ikke danne et unikt id ud fra navnet — vælg et andet navn",
                Response.Status.CONFLICT);
    }

    /**
     * Name → {@code comp_id} slug.
     *
     * <p>Danish letters are folded explicitly <strong>before</strong> Unicode normalisation,
     * because NFD does not decompose {@code ø} or {@code æ} at all — they are atomic
     * codepoints, not base-plus-combining-mark. Relying on NFD alone turns "Sikker kode i
     * løsninger" into {@code sikker-kode-i-l-sninger}, which is a stable, ugly key nobody
     * notices until it is in an export file. NFD still earns its place for {@code é}, {@code ü}
     * and the rest.
     */
    private static String slugify(String name) {
        String source = name == null ? "" : name;
        String folded = source.toLowerCase(Locale.ROOT)
                .replace("æ", "ae")
                .replace("ø", "oe")
                .replace("å", "aa")
                .replace("ß", "ss");
        folded = Normalizer.normalize(folded, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = folded.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        int max = MAX_COMP_ID - COLLISION_SUFFIX_ROOM;
        if (slug.length() > max) {
            slug = slug.substring(0, max).replaceAll("-+$", "");
        }
        // A name written entirely in a script this folds away still needs a key.
        return slug.isEmpty() ? "krav" : slug;
    }

    // -----------------------------------------------------------------------
    // field helpers
    // -----------------------------------------------------------------------

    private String writeArray(List<String> values) {
        // NULL and '[]' mean different things (§5.2) — preserve the distinction the client sent.
        return values == null ? null : CompetencePayloadCodec.write(cleaned(values));
    }

    private static String requireText(String value, String label, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new WebApplicationException(label + " må ikke være tom.",
                    Response.Status.BAD_REQUEST);
        }
        if (trimmed.length() > max) {
            throw new WebApplicationException(label + " må højst være " + max + " tegn.",
                    Response.Status.BAD_REQUEST);
        }
        return trimmed;
    }

    private static String optionalText(String value, String label, int max) {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new WebApplicationException(label + " må højst være " + max + " tegn.",
                    Response.Status.BAD_REQUEST);
        }
        return trimmed;
    }

    /**
     * A cadence override of zero or less would silently become "every day" or "never"
     * depending on which comparison saw it first, so it is refused rather than normalised.
     * {@code null} keeps the global cadence, which is the ordinary case.
     */
    private static Integer validCadenceOverride(Integer cadenceDays) {
        if (cadenceDays == null) {
            return null;
        }
        if (cadenceDays < 1) {
            throw new WebApplicationException("Kadence skal være mindst 1 dag.",
                    Response.Status.BAD_REQUEST);
        }
        return cadenceDays;
    }
}
