package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.content.CompetenceContent;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.domain.CompetenceContentValidator;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter.ParsedTopic;
import dk.trustworks.intranet.competenceservice.domain.CompetenceImportAdapter.TopicErrors;
import dk.trustworks.intranet.competenceservice.domain.CompetencePlaceholderScanner;
import dk.trustworks.intranet.competenceservice.domain.CompetencePlaceholderScanner.Finding;
import dk.trustworks.intranet.competenceservice.dto.CompetenceExportFormat;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceCourseCompletion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.model.ContentStatus;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authoring: drafts, publish, import and export (spec §6.3, §9.4, §9.6, §11.1, §11.2).
 *
 * <p>Four properties drive nearly every decision in this class.
 *
 * <ol>
 *   <li><strong>A draft must never reach an employee.</strong> Only {@link #publish} moves
 *       content into an employee's reach, and import writes DRAFTs exclusively — which is
 *       also what makes "export a topic, edit it externally, import it back" a safe loop.</li>
 *   <li><strong>The publish order is fixed by the database, not by taste.</strong> The
 *       outgoing ACTIVE must be archived <em>before</em> the DRAFT is activated; see
 *       {@link #publish} for the full reasoning.</li>
 *   <li><strong>Correcting a typo must not reset anybody's cadence clock.</strong> Publishing
 *       with {@code forcedRetake = false} carries the previous version's completions forward
 *       with their original {@code completed_at}, by APPENDING rows — the completion table
 *       refuses UPDATE at the database level.</li>
 *   <li><strong>Placeholder-bearing content must not go live by accident.</strong> The
 *       2026-08 content ships with 28 unresolved authoring markers; publishing them to an
 *       auditor would undermine the exact claim this module exists to support.</li>
 * </ol>
 *
 * <p>Nothing here logs a payload, a question, an option or an answer. The stable
 * {@code COMPETENCE_*} tokens are what the production log sweep filters on — an untokenised
 * line is invisible to it.
 */
@JBossLog
@ApplicationScoped
public class CompetenceContentService {

    /**
     * §9.6's {@code exportedAt} shape: ISO-8601, UTC, millisecond precision, {@code Z}
     * suffix. Fixed rather than {@code ISO_INSTANT} because that formatter drops the
     * fraction when it happens to be zero, which would make the exported field's shape
     * depend on the wall clock.
     */
    private static final DateTimeFormatter EXPORTED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Column widths from V495. Exceeding one is a 400 naming the topic, never a 500. */
    private static final int MAX_COMP_ID = 64;
    private static final int MAX_KREF = 32;
    private static final int MAX_NAME = 255;
    private static final int MAX_DESCRIPTION = 1000;

    /**
     * How much of a targeting array a retargeting line prints before it is cut. A file may
     * name a hundred user uuids; the log has to say what changed without becoming the place
     * the whole audience is stored.
     */
    private static final int MAX_LOGGED_TARGET = 200;

    @Inject
    EntityManager em;

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /**
     * The real admin behind an impersonated request, or {@code null} for an ordinary one.
     *
     * <p>During impersonation {@code actorUuid} <em>is</em> the impersonated subject (the swap
     * happens in the BFF's JWT), so without this an admin who impersonates a content owner and
     * publishes a test version leaves that owner's uuid in {@code published_by} and in the log,
     * and nothing anywhere records that the publish was impersonated. §10.4 says
     * {@code X-Acting-For} discloses the actor — this is what makes it disclose anything.
     */
    private String actingFor() {
        return requestHeaderHolder.getActingForUuid();
    }

    // -----------------------------------------------------------------------
    // draft
    // -----------------------------------------------------------------------

    /**
     * Creates or replaces the single DRAFT for one (requirement, kind).
     *
     * <p>Upsert rather than create-or-409: {@code uk_competence_content_draft} allows exactly
     * one draft per (requirement, kind), and the editor's save button means "this is the
     * draft now". A second draft is not a state the product has a name for.
     *
     * <p>The payload is re-serialised through {@link CompetencePayloadCodec#write} rather
     * than stored as it arrived, so a hand-edited or imported file lands in the same
     * canonical shape the editor produces. Without that, two byte-different strings could
     * mean the same content and every diff, comparison and test would have to know it.
     */
    @Transactional
    public CompetenceContentVersion upsertDraft(String requirementUuid, ContentKind kind,
                                                String versionLabel, String payloadJson,
                                                String actorUuid) {
        CompetenceRequirement requirement = requireRequirement(requirementUuid);

        // Parse first: a malformed payload is a 400 from the codec naming the offending
        // field, which is more useful than any message this method could compose.
        Object payload = parseAndValidate(kind, versionLabel, payloadJson);

        boolean created = CompetenceContentVersion.findDraft(requirement.getUuid(), kind) == null;
        CompetenceContentVersion draft = storeDraft(requirement, kind, versionLabel, payload);

        log.infof("COMPETENCE_DRAFT_SAVED requirement=%s kind=%s version=%s created=%s actor=%s "
                        + "actingFor=%s",
                requirement.getCompId(), kind, draft.getVersionLabel(), created, actorUuid,
                actingFor());
        return draft;
    }

    /**
     * Throws away the DRAFT.
     *
     * <p>A real DELETE, unlike everywhere else in this module: {@code competence_content_version}
     * carries no append-only trigger, because an unpublished draft is not evidence about
     * anybody. ACTIVE and ARCHIVED rows are never deleted here — they are what an approved
     * attempt points at.
     *
     * <p>Which also makes this the one destructive operation on the authoring surface, and the
     * only place a row leaves the module without the database keeping a copy — so it is the
     * last event that can afford to be anonymous. The actor travels here for that reason
     * alone; nothing else in the method needs it.
     */
    @Transactional
    public void discardDraft(String requirementUuid, ContentKind kind, String actorUuid) {
        CompetenceContentVersion draft = CompetenceContentVersion.findDraft(requirementUuid, kind);
        if (draft == null) {
            throw new WebApplicationException("No draft to discard", Response.Status.NOT_FOUND);
        }
        String versionLabel = draft.getVersionLabel();
        draft.delete();
        log.infof("COMPETENCE_DRAFT_DISCARDED requirement=%s kind=%s version=%s actor=%s actingFor=%s",
                requirementUuid, kind, versionLabel, actorUuid, actingFor());
    }

    // -----------------------------------------------------------------------
    // publish
    // -----------------------------------------------------------------------

    /**
     * DRAFT → ACTIVE, previous ACTIVE → ARCHIVED, in one transaction.
     *
     * <p><strong>Write order is not interchangeable.</strong> {@code active_key} is a plain
     * column written by {@code trg_competence_content_version_keys_ins/upd} to
     * {@code requirement_uuid + ':' + content_kind} whenever the row is ACTIVE, and it carries
     * the {@code uk_competence_content_active} unique index. Activating the draft while the
     * old ACTIVE still holds that key collides — verified against the running schema, not
     * inferred. So: archive, {@link EntityManager#flush()}, then activate. The explicit flush
     * is the point of injecting the EntityManager at all; without it Hibernate is free to
     * order the two UPDATEs however it likes at commit, and the failure would be intermittent.
     *
     * <p><strong>Force-retake.</strong> {@code forcedRetake = true} (a real content change)
     * does nothing extra: §5.3 compares the latest completion's {@code version_label} to the
     * active one, so a new label flips the whole audience to "New version — retake required"
     * on its own. {@code forcedRetake = false} (a typo fix) carries the completions forward —
     * see {@link #carryCompletionsForward}.
     *
     * <p>Note that {@code forcedRetake = false} has <em>no effect on a TEST</em>. §5.4 compares
     * the label on the passed attempt, and attempts are immutable — the row trigger permits
     * only the scoring write, the abandoned flag and GDPR pseudonymisation, so there is
     * nothing to carry forward and everyone re-takes. The publish dialog has to say so.
     *
     * <p>Publishing a version whose label equals the outgoing one leaves everyone green even
     * with force-retake on, because the comparison is on the label. That is a documented
     * property (§5.3), not a bug, and the confirmation dialog must state it.
     *
     * @param acknowledgeUnresolved caller has seen the placeholder list and wants it live anyway
     */
    @Transactional
    public CompetenceContentVersion publish(String requirementUuid, ContentKind kind,
                                            boolean forcedRetake, boolean acknowledgeUnresolved,
                                            String actorUuid) {
        CompetenceRequirement requirement = requireRequirement(requirementUuid);

        CompetenceContentVersion draft =
                CompetenceContentVersion.findDraft(requirement.getUuid(), kind);
        if (draft == null) {
            throw new WebApplicationException("No draft to publish", Response.Status.CONFLICT);
        }

        // Re-validate rather than trust the draft save. The draft may have been written by
        // an import, by an older build of the validator, or by anything else holding
        // competence:write — publish is the last gate before an employee sees it.
        Object payload = parseAndValidate(kind, draft.getVersionLabel(), draft.getPayloadJson());

        List<Finding> unresolved = kind == ContentKind.COURSE
                ? CompetencePlaceholderScanner.scan((CompetenceContent.CoursePayload) payload)
                : List.of();
        if (!unresolved.isEmpty() && !acknowledgeUnresolved) {
            throw new WebApplicationException(unresolvedMessage(unresolved), Response.Status.CONFLICT);
        }

        CompetenceContentVersion outgoing =
                CompetenceContentVersion.findActive(requirement.getUuid(), kind);
        String fromLabel = outgoing == null ? null : outgoing.getVersionLabel();

        // --- step 1: archive the outgoing ACTIVE, and flush so the unique index sees it ---
        if (outgoing != null) {
            outgoing.setStatus(ContentStatus.ARCHIVED);
            outgoing.setSupersededByUuid(draft.getUuid());
            em.flush();
        }

        // --- step 2: only now may the draft take the key ---
        LocalDateTime now = LocalDateTime.now();
        draft.setStatus(ContentStatus.ACTIVE);
        draft.setForcedRetake(forcedRetake);
        draft.setPublishedBy(actorUuid);
        draft.setPublishedAt(now);
        // Flush here too, so a key collision surfaces as a failure of this statement rather
        // than of the commit, where it would carry no useful attribution.
        em.flush();

        int carriedForward = 0;
        if (!forcedRetake && kind == ContentKind.COURSE && outgoing != null) {
            carriedForward = carryCompletionsForward(outgoing, draft);
        }

        log.infof("COMPETENCE_CONTENT_PUBLISHED requirement=%s kind=%s from=%s to=%s "
                        + "forcedRetake=%s carriedForward=%d unresolvedPlaceholders=%d actor=%s "
                        + "actingFor=%s",
                requirement.getCompId(), kind, fromLabel, draft.getVersionLabel(),
                forcedRetake, carriedForward, unresolved.size(), actorUuid, actingFor());
        return draft;
    }

    /**
     * Re-points the outgoing version's completions at the new one, by appending.
     *
     * <p>{@code competence_course_completion} refuses UPDATE and DELETE at the database level
     * (V495), so "re-stamp the old rows" is not available and would not be wanted anyway: the
     * original row is the evidence that this person read <em>that</em> version on that day.
     * The new row is a supersede record that says the reading still counts.
     *
     * <p><strong>{@code completedAt} is copied, never set to now.</strong> Cadence is evaluated
     * at read time against {@code completed_at} (§5.8), so stamping the publish time would
     * silently give the whole audience a fresh year of validity because somebody fixed a typo.
     * Copying it keeps the renewal clock exactly where the employee's own action put it.
     *
     * <p>One row per person, not per historical completion: a user who completed the outgoing
     * version twice needs one carry-forward row, and only their newest one is meaningful since
     * {@code findLatest} orders by {@code completedAt}. Volume is bounded by audience size —
     * tens of rows.
     *
     * @return how many completions were carried forward
     */
    private int carryCompletionsForward(CompetenceContentVersion outgoing,
                                        CompetenceContentVersion incoming) {
        Map<String, CompetenceCourseCompletion> latestPerUser = new LinkedHashMap<>();
        for (CompetenceCourseCompletion row
                : CompetenceCourseCompletion.listForRequirementVersion(outgoing.getUuid())) {
            CompetenceCourseCompletion held = latestPerUser.get(row.getUseruuid());
            if (held == null || isNewer(row.getCompletedAt(), held.getCompletedAt())) {
                latestPerUser.put(row.getUseruuid(), row);
            }
        }
        for (CompetenceCourseCompletion source : latestPerUser.values()) {
            CompetenceCourseCompletion carried = new CompetenceCourseCompletion();
            carried.setUuid(UUID.randomUUID().toString());
            carried.setUseruuid(source.getUseruuid());
            carried.setRequirementUuid(source.getRequirementUuid());
            carried.setContentVersionUuid(incoming.getUuid());
            carried.setVersionLabel(incoming.getVersionLabel());
            carried.setCompletedAt(source.getCompletedAt());
            carried.persist();
        }
        return latestPerUser.size();
    }

    /**
     * The placeholder findings for a requirement, so the editor can show its per-topic
     * counter without attempting a publish and reading the 409 as an answer.
     *
     * <p>Scans the DRAFT when one exists and the ACTIVE otherwise: the counter belongs next
     * to what the author is editing, and with no draft open the live content is what they
     * would be asked about. TEST content carries no placeholders — the markers live in
     * course callouts and video notes — so this is always empty for a test.
     */
    public List<Finding> unresolvedPlaceholders(String requirementUuid, ContentKind kind) {
        if (kind != ContentKind.COURSE) {
            return List.of();
        }
        CompetenceContentVersion version = CompetenceContentVersion.findDraft(requirementUuid, kind);
        if (version == null) {
            version = CompetenceContentVersion.findActive(requirementUuid, kind);
        }
        if (version == null) {
            return List.of();
        }
        return CompetencePlaceholderScanner.scan(
                CompetencePayloadCodec.readCourse(version.getPayloadJson()));
    }

    /**
     * The 409 body. Names the count, the distinct TW refs and the screens, because "there are
     * placeholders" is not actionable and the author has thirteen screens to look through.
     */
    private String unresolvedMessage(List<Finding> findings) {
        Set<String> refs = CompetencePlaceholderScanner.distinctRefs(findings);
        Set<String> screens = new LinkedHashSet<>();
        for (Finding finding : findings) {
            screens.add("\"" + finding.screenTitle() + "\"");
        }
        StringBuilder message = new StringBuilder();
        message.append(findings.size()).append(" unresolved placeholder")
                .append(findings.size() == 1 ? "" : "s");
        if (!refs.isEmpty()) {
            message.append(" (").append(String.join(", ", refs)).append(")");
        }
        message.append(" on ").append(String.join(", ", screens))
                .append(". Publish again with acknowledgeUnresolved=true to publish anyway.");
        return message.toString();
    }

    // -----------------------------------------------------------------------
    // import
    // -----------------------------------------------------------------------

    /**
     * @param topics              topics read from the file
     * @param requirementsCreated requirements the file brought into existence
     * @param draftsWritten       COURSE + TEST drafts written across all topics
     * @param compIds             the topics touched, in file order
     */
    public record ImportSummary(int topics, int requirementsCreated, int draftsWritten,
                                List<String> compIds) {
    }

    /**
     * Reads a {@code kompetencemodul-full-export} file and writes DRAFTs — never ACTIVE.
     *
     * <p>That is the concept's own rule (C-2: a draft must never reach an employee) and it is
     * also what makes the export/edit/import loop safe: a file with a mistake in it costs an
     * author a re-import, never an audience a wrong training obligation. Someone then has to
     * look at each draft and press publish.
     *
     * <p>Creating a requirement from a file is deliberately allowed. 7.b.2 and 7.b.4 are
     * expected to arrive as content rather than as a migration, and requiring an admin to
     * hand-create four requirements before importing them would be a step that exists only to
     * be forgotten.
     *
     * <p>Errors are reported for the whole file at once and nothing is written when any topic
     * fails — a partially imported four-topic file is a state nobody can reason about.
     */
    @Transactional
    public ImportSummary importContent(String json, String actorUuid) {
        CompetenceImportAdapter.Result parsed = CompetenceImportAdapter.parse(json);

        // Field-length checks the adapter does not make: without them an over-long name is a
        // truncation or a 500 at flush time rather than a message naming the topic. And the
        // §11.4 targeting check, which the adapter cannot make either — it never touches the
        // database. Both run over every topic before the write loop, so the file still fails
        // atomically.
        List<TopicErrors> problems = new ArrayList<>(parsed.problems());
        for (ParsedTopic topic : parsed.topics()) {
            List<String> errors = requirementFieldErrors(topic);
            errors.addAll(targetingErrors(topic));
            if (!errors.isEmpty()) {
                problems.add(new TopicErrors(topic.compId(), errors));
            }
        }
        if (!problems.isEmpty()) {
            throw new WebApplicationException(importErrorMessage(problems), Response.Status.BAD_REQUEST);
        }

        int requirementsCreated = 0;
        int draftsWritten = 0;
        List<String> compIds = new ArrayList<>();
        for (ParsedTopic topic : parsed.topics()) {
            CompetenceRequirement requirement = CompetenceRequirement.findByCompId(topic.compId());
            if (requirement == null) {
                requirement = createRequirement(topic);
                requirementsCreated++;
            } else {
                applyTopicToRequirement(topic, requirement, actorUuid);
            }
            compIds.add(topic.compId());

            if (topic.course() != null) {
                storeDraft(requirement, ContentKind.COURSE, topic.courseVersion(), topic.course());
                draftsWritten++;
            }
            if (topic.test() != null) {
                storeDraft(requirement, ContentKind.TEST, topic.testVersion(), topic.test());
                draftsWritten++;
            }
        }

        log.infof("COMPETENCE_CONTENT_IMPORTED topics=%d requirementsCreated=%d draftsWritten=%d "
                        + "compIds=%s actor=%s actingFor=%s",
                parsed.topics().size(), requirementsCreated, draftsWritten,
                String.join(",", compIds), actorUuid, actingFor());
        return new ImportSummary(parsed.topics().size(), requirementsCreated, draftsWritten, compIds);
    }

    private CompetenceRequirement createRequirement(ParsedTopic topic) {
        CompetenceRequirement requirement = new CompetenceRequirement();
        requirement.setUuid(UUID.randomUUID().toString());
        requirement.setCompId(topic.compId());
        requirement.setKref(topic.kref());
        requirement.setName(topic.name());
        requirement.setDescription(topic.desc());
        requirement.setTargetPracticeUuids(writeArray(topic.targetPracticeUuids()));
        requirement.setTargetTeams(writeArray(topic.targetTeams()));
        requirement.setTargetUseruuids(writeArray(topic.targetUseruuids()));
        requirement.setCadenceDaysOverride(topic.cadenceDaysOverride());
        // Sort order is a display concern only; appending keeps the import order stable and
        // an admin can reorder afterwards.
        requirement.setSortOrder((int) CompetenceRequirement.count());
        requirement.setActive(true);
        requirement.persist();
        return requirement;
    }

    /**
     * Applies a file's topic metadata onto an existing requirement.
     *
     * <p><strong>Targeting is only overwritten when the file supplies a non-null array.</strong>
     * A null field in the file means "absent", not "empty" — the same asymmetry the stored
     * columns use (§5.2). Treating absent as empty would let a v1 export, which carries no
     * targeting at all, silently empty an audience on re-import, and the module would keep
     * reporting green because nobody was in scope any more. An author who genuinely wants an
     * empty target sends {@code []}, which is non-null and does overwrite.
     *
     * <p>The same reasoning applies to name, kref, description and cadence: a field the file
     * does not carry leaves the stored value alone.
     *
     * <p><strong>Every targeting change is logged before it is written.</strong> This is the one
     * path on which an audience can move without anybody opening the requirement editor, and
     * {@code COMPETENCE_CONTENT_IMPORTED} counts topics and drafts — it cannot say that a krav
     * changed hands. Widening or narrowing an audience through a file would otherwise be the
     * one authoring change with no trace of what it was before.
     */
    private void applyTopicToRequirement(ParsedTopic topic, CompetenceRequirement requirement,
                                         String actorUuid) {
        if (isNotBlank(topic.kref())) {
            requirement.setKref(topic.kref());
        }
        if (isNotBlank(topic.name())) {
            requirement.setName(topic.name());
        }
        if (isNotBlank(topic.desc())) {
            requirement.setDescription(topic.desc());
        }
        if (topic.cadenceDaysOverride() != null) {
            requirement.setCadenceDaysOverride(topic.cadenceDaysOverride());
        }
        if (topic.targetPracticeUuids() != null) {
            String next = writeArray(topic.targetPracticeUuids());
            logRetargeting(requirement, "practices", requirement.getTargetPracticeUuids(), next,
                    actorUuid);
            requirement.setTargetPracticeUuids(next);
        }
        if (topic.targetTeams() != null) {
            String next = writeArray(topic.targetTeams());
            logRetargeting(requirement, "teams", requirement.getTargetTeams(), next, actorUuid);
            requirement.setTargetTeams(next);
        }
        if (topic.targetUseruuids() != null) {
            String next = writeArray(topic.targetUseruuids());
            logRetargeting(requirement, "users", requirement.getTargetUseruuids(), next, actorUuid);
            requirement.setTargetUseruuids(next);
        }
    }

    /**
     * One line per targeting column an import actually moves — silent when the file repeats
     * what is already stored, which is the ordinary case for a re-import.
     */
    private void logRetargeting(CompetenceRequirement requirement, String field, String before,
                                String after, String actorUuid) {
        if (Objects.equals(before, after)) {
            return;
        }
        log.infof("COMPETENCE_REQUIREMENT_RETARGETED requirement=%s source=import field=%s "
                        + "from=%s to=%s actor=%s actingFor=%s",
                requirement.getCompId(), field, clipTarget(before), clipTarget(after), actorUuid,
                actingFor());
    }

    /** {@code absent} rather than {@code null}: the two mean different things here (§5.2). */
    private static String clipTarget(String json) {
        if (json == null) {
            return "absent";
        }
        return json.length() <= MAX_LOGGED_TARGET
                ? json
                : json.substring(0, MAX_LOGGED_TARGET) + "…";
    }

    /**
     * §11.4 applied to an imported topic.
     *
     * <p>The REST upsert refuses a targeting array naming a practice, team or user uuid that
     * does not resolve; without this the import — which writes the very same three columns —
     * did not. The failure that permits is the worst one the module has: a typo'd uuid matches
     * nobody, so the krav vanishes from every learner's list, every matrix cell reads
     * "not applicable", coverage reports 100 % over what is left, and the notifier names it to
     * no one. The module reports full compliance for a requirement nobody was ever asked to
     * take, and the headcount alone cannot tell that apart from deliberately narrow targeting.
     *
     * <p>Returned rather than thrown so the problems join the per-topic list and the whole file
     * still fails at once.
     */
    private List<String> targetingErrors(ParsedTopic topic) {
        List<String> problems = requirementService.blockingTargetingProblems(new Targeting(
                topic.targetPracticeUuids(), topic.targetTeams(), topic.targetUseruuids()));
        List<String> errors = new ArrayList<>(problems.size());
        for (String problem : problems) {
            errors.add("Ukendt mål i målretningen — " + problem);
        }
        return errors;
    }

    private List<String> requirementFieldErrors(ParsedTopic topic) {
        List<String> errors = new ArrayList<>();
        if (topic.compId() != null && topic.compId().length() > MAX_COMP_ID) {
            errors.add("compId må højst være " + MAX_COMP_ID + " tegn.");
        }
        if (topic.kref() != null && topic.kref().length() > MAX_KREF) {
            errors.add("kref må højst være " + MAX_KREF + " tegn.");
        }
        if (topic.name() != null && topic.name().length() > MAX_NAME) {
            errors.add("Navnet må højst være " + MAX_NAME + " tegn.");
        }
        if (topic.desc() != null && topic.desc().length() > MAX_DESCRIPTION) {
            errors.add("Beskrivelsen må højst være " + MAX_DESCRIPTION + " tegn.");
        }
        return errors;
    }

    /** Per-topic, because someone importing a four-topic file wants everything at once. */
    private String importErrorMessage(List<TopicErrors> problems) {
        List<String> parts = new ArrayList<>();
        for (TopicErrors problem : problems) {
            String where = problem.compId() == null ? "Filen" : problem.compId();
            parts.add(where + ": " + String.join("; ", problem.errors()));
        }
        return String.join(" | ", parts);
    }

    // -----------------------------------------------------------------------
    // export
    // -----------------------------------------------------------------------

    /**
     * The full content export (§9.6), or one topic when {@code compId} is given.
     *
     * <p>Exports the ACTIVE version of each kind and <strong>falls back to the DRAFT when
     * there is no ACTIVE</strong>, so a topic that has been authored but never published still
     * round-trips instead of exporting as an empty shell. The envelope deliberately does not
     * record which of the two a payload came from: import only ever writes drafts, so the
     * distinction cannot change the outcome of a re-import, and carrying it would invite
     * somebody to build a "restore as active" path that bypasses {@link #publish}.
     *
     * <p>Targeting is exported as arrays, or omitted entirely when the stored column is absent —
     * which is what {@link #applyTopicToRequirement} reads as "leave the audience alone".
     *
     * <p>Always emits {@code formatVersion 2}. v1 is an import-only shape.
     *
     * <p><strong>Logged with the actor, like the two CSV exports (§10.7, §10.9).</strong> This
     * is the most sensitive export in the module, not the least: a topic carries its quiz
     * questions with {@code correct} on every option — the complete answer key — plus any
     * unpublished draft. A holder of {@code competence:write} who keeps the file can pass every
     * test at 100 % afterwards and the attempts log normally, so the name on this line is the
     * only thing that makes the download reconstructable after the fact.
     */
    public String exportContent(String compId, String actorUuid) {
        List<CompetenceRequirement> requirements;
        if (compId != null && !compId.isBlank()) {
            CompetenceRequirement one = CompetenceRequirement.findByCompId(compId.trim());
            if (one == null) {
                throw new WebApplicationException("No such requirement", Response.Status.NOT_FOUND);
            }
            requirements = List.of(one);
        } else {
            requirements = CompetenceRequirement.listAllOrdered();
        }

        List<CompetenceExportFormat.Topic> topics = new ArrayList<>();
        for (CompetenceRequirement requirement : requirements) {
            topics.add(exportTopic(requirement));
        }

        CompetenceExportFormat.Envelope envelope = new CompetenceExportFormat.Envelope(
                CompetenceExportFormat.KIND,
                CompetenceExportFormat.CURRENT_FORMAT_VERSION,
                EXPORTED_AT.format(Instant.now()),
                topics);

        log.infof("COMPETENCE_EXPORT kind=content scope=%s topics=%d actor=%s actingFor=%s",
                compId == null || compId.isBlank() ? "all" : compId, topics.size(), actorUuid,
                actingFor());
        return CompetencePayloadCodec.write(envelope);
    }

    private CompetenceExportFormat.Topic exportTopic(CompetenceRequirement requirement) {
        Targeting targeting = requirementService.targetingOf(requirement);

        CompetenceContentVersion course = publishedOrDraft(requirement.getUuid(), ContentKind.COURSE);
        CompetenceContentVersion test = publishedOrDraft(requirement.getUuid(), ContentKind.TEST);

        CompetenceExportFormat.Course courseOut = null;
        if (course != null) {
            CompetenceContent.CoursePayload payload =
                    readCourseOrFail(course, requirement.getCompId());
            courseOut = new CompetenceExportFormat.Course(
                    course.getVersionLabel(), payload.screens());
        }
        CompetenceExportFormat.Quiz quizOut = null;
        if (test != null) {
            CompetenceContent.TestPayload payload = readTestOrFail(test, requirement.getCompId());
            quizOut = new CompetenceExportFormat.Quiz(
                    test.getVersionLabel(), payload.questions());
        }

        return new CompetenceExportFormat.Topic(
                requirement.getCompId(),
                requirement.getKref(),
                requirement.getName(),
                requirement.getDescription(),
                targeting.practiceUuids(),
                targeting.teamUuids(),
                targeting.userUuids(),
                requirement.getCadenceDaysOverride(),
                courseOut,
                quizOut);
    }

    private CompetenceContentVersion publishedOrDraft(String requirementUuid, ContentKind kind) {
        CompetenceContentVersion active = CompetenceContentVersion.findActive(requirementUuid, kind);
        return active != null ? active : CompetenceContentVersion.findDraft(requirementUuid, kind);
    }

    // -----------------------------------------------------------------------
    // history
    // -----------------------------------------------------------------------

    /** Every version of both kinds, newest published first (§6.3). */
    public List<CompetenceContentVersion> versionHistory(String requirementUuid) {
        requireRequirement(requirementUuid);
        return CompetenceContentVersion.findHistory(requirementUuid);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private CompetenceRequirement requireRequirement(String requirementUuid) {
        CompetenceRequirement requirement = CompetenceRequirement.findByUuid(requirementUuid);
        if (requirement == null) {
            throw new WebApplicationException("No such requirement", Response.Status.NOT_FOUND);
        }
        return requirement;
    }

    /**
     * Parses the payload for its kind and runs the full §11.1/§11.2 rule set.
     *
     * <p>All validation errors are joined into one message rather than thrown one at a time:
     * an author fixing a thirteen-screen course should see everything wrong with it in a
     * single round trip, which is also why the validator returns a list.
     *
     * @return the parsed payload, so the caller does not have to parse it twice
     */
    private Object parseAndValidate(ContentKind kind, String versionLabel, String payloadJson) {
        List<String> errors = new ArrayList<>(
                CompetenceContentValidator.validateVersionLabel(versionLabel));
        Object payload;
        if (kind == ContentKind.COURSE) {
            CompetenceContent.CoursePayload course = CompetencePayloadCodec.readCourse(payloadJson);
            errors.addAll(CompetenceContentValidator.validateCourse(course));
            payload = course;
        } else {
            CompetenceContent.TestPayload test = CompetencePayloadCodec.readTest(payloadJson);
            errors.addAll(CompetenceContentValidator.validateTest(test));
            payload = test;
        }
        if (!errors.isEmpty()) {
            throw new WebApplicationException(String.join("; ", errors), Response.Status.BAD_REQUEST);
        }
        return payload;
    }

    /**
     * The single write path for a DRAFT, shared by {@link #upsertDraft} and by the import.
     *
     * <p>Takes an already-parsed, already-validated payload. The import path does not re-parse
     * on its way through here because {@link CompetenceImportAdapter} runs exactly the same
     * validator while converting, so a second pass would only give the two paths a way to
     * disagree.
     *
     * <p>Updates in place when a draft exists, because {@code uk_competence_content_draft}
     * permits exactly one per (requirement, kind). Deleting and re-inserting would work too,
     * but it would churn the uuid the editor is holding.
     */
    private CompetenceContentVersion storeDraft(CompetenceRequirement requirement, ContentKind kind,
                                                String versionLabel, Object payload) {
        CompetenceContentVersion draft =
                CompetenceContentVersion.findDraft(requirement.getUuid(), kind);
        if (draft == null) {
            draft = new CompetenceContentVersion();
            draft.setUuid(UUID.randomUUID().toString());
            draft.setRequirementUuid(requirement.getUuid());
            draft.setContentKind(kind);
            draft.setStatus(ContentStatus.DRAFT);
            draft.setVersionLabel(versionLabel.trim());
            draft.setPayloadJson(CompetencePayloadCodec.write(payload));
            draft.persist();
            return draft;
        }
        draft.setVersionLabel(versionLabel.trim());
        draft.setPayloadJson(CompetencePayloadCodec.write(payload));
        return draft;
    }

    /** {@code null} stays {@code null} — an absent column, not an empty target. */
    private String writeArray(List<String> values) {
        return values == null ? null : CompetencePayloadCodec.write(values);
    }

    private CompetenceContent.CoursePayload readCourseOrFail(CompetenceContentVersion version,
                                                             String compId) {
        try {
            return CompetencePayloadCodec.readCourse(version.getPayloadJson());
        } catch (WebApplicationException e) {
            // Never drop a topic silently from an export: a half-complete export that looks
            // whole is worse than a failed one.
            throw new WebApplicationException(
                    "Unreadable COURSE payload on " + compId + " version " + version.getVersionLabel()
                            + ": " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private CompetenceContent.TestPayload readTestOrFail(CompetenceContentVersion version,
                                                         String compId) {
        try {
            return CompetencePayloadCodec.readTest(version.getPayloadJson());
        } catch (WebApplicationException e) {
            throw new WebApplicationException(
                    "Unreadable TEST payload on " + compId + " version " + version.getVersionLabel()
                            + ": " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean isNewer(LocalDateTime candidate, LocalDateTime held) {
        if (candidate == null) {
            return false;
        }
        return held == null || candidate.isAfter(held);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
