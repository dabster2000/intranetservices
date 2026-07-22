package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.Team;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.recruitmentservice.dto.PositionRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCircleMember;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentCircleRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentDemandRag;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPositionStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Command handlers for the positions aggregate (ATS plan §P2). Every
 * mutation persists state and appends its {@code POSITION_*} /
 * {@code CIRCLE_*} event through {@link RecruitmentEventRecorder} in the
 * same transaction — state tables and the event stream can never diverge.
 * <p>
 * Validation split: field shape (bean validation) lives on
 * {@link PositionRequest}; track-conditional and registry rules live here
 * (400 {@link WebApplicationException} for invalid input, 409
 * {@link BusinessRuleViolation} for state conflicts).
 * <p>
 * Partner-track events carry {@code visibility=CIRCLE} so later timeline
 * queries can apply the same per-viewer filter as the state tables.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentPositionService {

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ---- Create --------------------------------------------------------------

    @Transactional
    public RecruitmentPosition create(PositionRequest request, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        validateTrackRules(request);
        validatePracticeForCreate(request.practiceUuid());
        validateTeamExists(request.teamUuid());
        validateSlugUnique(request.publicSlug(), null);

        RecruitmentPosition position = new RecruitmentPosition();
        position.setTitle(request.title().trim());
        position.setHiringTrack(request.hiringTrack());
        position.setPracticeUuid(request.practiceUuid());
        position.setTeamUuid(request.teamUuid());
        position.setHiringOwnerUuid(request.hiringOwnerUuid());
        position.setPublicSlug(normalizeSlug(request.publicSlug()));
        position.setDemandRag(request.demandRag() != null ? request.demandRag() : RecruitmentDemandRag.GREEN);
        position.setStatus(RecruitmentPositionStatus.OPEN);

        // Stage set & scorecard: per-position snapshot — explicit override
        // (validated) or the track default.
        if (request.stageSet() != null && !request.stageSet().isEmpty()) {
            validateOr400(() -> RecruitmentPositionDefaults.validateStageSet(request.stageSet()));
            position.setStageSet(new ArrayList<>(request.stageSet()));
        } else {
            position.setStageSet(RecruitmentPositionDefaults.defaultStageSet(request.hiringTrack()));
        }
        if (request.scorecardTemplate() != null && !request.scorecardTemplate().isEmpty()) {
            validateOr400(() -> RecruitmentPositionDefaults.validateScorecardTemplate(request.scorecardTemplate()));
            position.setScorecardTemplate(new ArrayList<>(request.scorecardTemplate()));
        } else {
            position.setScorecardTemplate(RecruitmentPositionDefaults.defaultScorecardTemplate());
        }

        position.persist();

        eventRecorder.record(positionEvent(RecruitmentEventType.POSITION_OPENED, position, actor)
                .payload("title", position.getTitle())
                .payload("hiring_track", position.getHiringTrack().name())
                .payload("practice_uuid", position.getPracticeUuid())
                .payload("team_uuid", position.getTeamUuid())
                .payload("hiring_owner_uuid", position.getHiringOwnerUuid())
                .payload("public_slug", position.getPublicSlug())
                .payload("demand_rag", position.getDemandRag().name())
                .payload("stage_set", position.getStageSet()));

        // A partner-track position must never be born invisible: its creator
        // becomes circle OWNER, recorded as a proper circle event.
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            addCircleMemberInternal(position, actor.toString(), RecruitmentCircleRole.OWNER, actor);
        }

        log.infof("Recruitment position created: %s (%s, track=%s)",
                position.getUuid(), position.getTitle(), position.getHiringTrack());
        return position;
    }

    // ---- Update --------------------------------------------------------------

    @Transactional
    public RecruitmentPosition update(RecruitmentPosition position, PositionRequest request, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (position.getStatus() == RecruitmentPositionStatus.CLOSED) {
            throw new BusinessRuleViolation(
                    "Position %s is CLOSED and can no longer be edited".formatted(position.getUuid()));
        }
        if (request.hiringTrack() != null && request.hiringTrack() != position.getHiringTrack()) {
            throw new WebApplicationException(
                    "hiringTrack is immutable — create a new position for a different track",
                    Response.Status.BAD_REQUEST);
        }
        if (request.status() == RecruitmentPositionStatus.CLOSED) {
            throw new WebApplicationException(
                    "Use POST /recruitment/positions/{uuid}/close to close a position",
                    Response.Status.BAD_REQUEST);
        }
        validateTrackRules(request);
        // Registry mutability (spec §4.1): keeping an existing — possibly
        // since-deactivated — practice must keep working; switching to a NEW
        // practice requires an active registry row.
        if (request.practiceUuid() != null && !request.practiceUuid().equals(position.getPracticeUuid())) {
            validatePracticeForCreate(request.practiceUuid());
        }
        validateTeamExists(request.teamUuid());
        validateSlugUnique(request.publicSlug(), position.getUuid());

        Map<String, Object> changes = new LinkedHashMap<>();
        applyChange(changes, "title", position.getTitle(), request.title().trim(), position::setTitle);
        applyChange(changes, "practice_uuid", position.getPracticeUuid(), request.practiceUuid(), position::setPracticeUuid);
        applyChange(changes, "team_uuid", position.getTeamUuid(), request.teamUuid(), position::setTeamUuid);
        applyChange(changes, "hiring_owner_uuid", position.getHiringOwnerUuid(), request.hiringOwnerUuid(), position::setHiringOwnerUuid);
        applyChange(changes, "public_slug", position.getPublicSlug(), normalizeSlug(request.publicSlug()), position::setPublicSlug);

        RecruitmentDemandRag newRag = request.demandRag() != null ? request.demandRag() : position.getDemandRag();
        if (newRag != position.getDemandRag()) {
            changes.put("demand_rag", newRag.name());
            position.setDemandRag(newRag);
        }
        RecruitmentPositionStatus newStatus = request.status() != null ? request.status() : position.getStatus();
        if (newStatus != position.getStatus()) {
            changes.put("status", newStatus.name());
            position.setStatus(newStatus);
        }
        if (request.stageSet() != null && !request.stageSet().isEmpty()
                && !request.stageSet().equals(position.getStageSet())) {
            validateOr400(() -> RecruitmentPositionDefaults.validateStageSet(request.stageSet()));
            changes.put("stage_set", request.stageSet());
            position.setStageSet(new ArrayList<>(request.stageSet()));
        }
        if (request.scorecardTemplate() != null && !request.scorecardTemplate().isEmpty()
                && !request.scorecardTemplate().equals(position.getScorecardTemplate())) {
            validateOr400(() -> RecruitmentPositionDefaults.validateScorecardTemplate(request.scorecardTemplate()));
            changes.put("scorecard_template_size", request.scorecardTemplate().size());
            position.setScorecardTemplate(new ArrayList<>(request.scorecardTemplate()));
        }

        if (changes.isEmpty()) {
            return position; // No-op update: no event, no audit churn.
        }

        RecruitmentEventBuilder event = positionEvent(RecruitmentEventType.POSITION_UPDATED, position, actor);
        changes.forEach(event::payload);
        eventRecorder.record(event);
        return position;
    }

    // ---- Close ---------------------------------------------------------------

    @Transactional
    public RecruitmentPosition close(RecruitmentPosition position, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        if (position.getStatus() == RecruitmentPositionStatus.CLOSED) {
            throw new BusinessRuleViolation(
                    "Position %s is already CLOSED".formatted(position.getUuid()));
        }
        position.setStatus(RecruitmentPositionStatus.CLOSED);
        position.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));

        eventRecorder.record(positionEvent(RecruitmentEventType.POSITION_CLOSED, position, actor)
                .payload("title", position.getTitle())
                .payload("hiring_track", position.getHiringTrack().name()));
        log.infof("Recruitment position closed: %s", position.getUuid());
        return position;
    }

    // ---- Circle management -----------------------------------------------------

    public List<RecruitmentCircleMember> circleMembers(String positionUuid) {
        return RecruitmentCircleMember.list("positionUuid = ?1 order by addedAt", positionUuid);
    }

    @Transactional
    public RecruitmentCircleMember addCircleMember(RecruitmentPosition position, String userUuid,
                                                   RecruitmentCircleRole role, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        requirePartnerTrack(position);
        RecruitmentCircleMember existing = RecruitmentCircleMember.findById(
                new RecruitmentCircleMember.Key(position.getUuid(), userUuid));
        if (existing != null) {
            throw new BusinessRuleViolation(
                    "User %s is already in the circle of position %s".formatted(userUuid, position.getUuid()));
        }
        return addCircleMemberInternal(position, userUuid,
                role != null ? role : RecruitmentCircleRole.PARTICIPANT, actor);
    }

    @Transactional
    public void removeCircleMember(RecruitmentPosition position, String userUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");
        requirePartnerTrack(position);
        RecruitmentCircleMember member = RecruitmentCircleMember.findById(
                new RecruitmentCircleMember.Key(position.getUuid(), userUuid));
        if (member == null) {
            throw new WebApplicationException(
                    "User is not a member of this circle", Response.Status.NOT_FOUND);
        }
        // Never orphan a partner position: the last OWNER cannot leave.
        if (member.getRoleInCircle() == RecruitmentCircleRole.OWNER
                && RecruitmentCircleMember.count(
                        "positionUuid = ?1 and roleInCircle = ?2",
                        position.getUuid(), RecruitmentCircleRole.OWNER) <= 1) {
            throw new BusinessRuleViolation(
                    "Cannot remove the last OWNER of the circle — add another owner first");
        }
        member.delete();
        eventRecorder.record(positionEvent(RecruitmentEventType.CIRCLE_MEMBER_REMOVED, position, actor)
                .payload("member_uuid", userUuid));
    }

    private RecruitmentCircleMember addCircleMemberInternal(RecruitmentPosition position, String userUuid,
                                                            RecruitmentCircleRole role, UUID actor) {
        RecruitmentCircleMember member = new RecruitmentCircleMember(
                position.getUuid(), userUuid, role, actor.toString());
        member.persist();
        eventRecorder.record(positionEvent(RecruitmentEventType.CIRCLE_MEMBER_ADDED, position, actor)
                .payload("member_uuid", userUuid)
                .payload("role_in_circle", role.name()));
        return member;
    }

    // ---- Validation helpers ------------------------------------------------------

    private void validateTrackRules(PositionRequest request) {
        if (request.hiringTrack() == RecruitmentHiringTrack.PRACTICE_TEAM
                && (request.practiceUuid() == null || request.practiceUuid().isBlank())) {
            throw new WebApplicationException(
                    "practiceUuid is required for PRACTICE_TEAM positions",
                    Response.Status.BAD_REQUEST);
        }
        if (request.hiringTrack() == RecruitmentHiringTrack.STAFF_ROLE
                && (request.hiringOwnerUuid() == null || request.hiringOwnerUuid().isBlank())) {
            throw new WebApplicationException(
                    "hiringOwnerUuid is required for STAFF_ROLE positions",
                    Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Registry rule (spec §4.1): a NEW practice reference must resolve to an
     * <em>active</em> registry row — pickers offer active practices only and
     * the API enforces the same. Existing references on stored positions are
     * deliberately never re-validated (graceful degradation on deactivation).
     */
    private void validatePracticeForCreate(String practiceUuid) {
        if (practiceUuid == null || practiceUuid.isBlank()) {
            return;
        }
        Practice practice = Practice.find("uuid", practiceUuid).firstResult();
        if (practice == null) {
            throw new WebApplicationException(
                    "Unknown practice: " + practiceUuid, Response.Status.BAD_REQUEST);
        }
        if (!practice.isActive()) {
            throw new WebApplicationException(
                    "Practice %s (%s) is inactive — positions can only be created on active practices"
                            .formatted(practice.getName(), practice.getCode()),
                    Response.Status.BAD_REQUEST);
        }
    }

    private void validateTeamExists(String teamUuid) {
        if (teamUuid == null || teamUuid.isBlank()) {
            return;
        }
        if (Team.findById(teamUuid) == null) {
            throw new WebApplicationException(
                    "Unknown team: " + teamUuid, Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Slugs a position may never claim because they would shadow the
     * literal {@code /apply/*} routes (P5: {@code /apply/unsolicited}) or
     * routes reserved for the public surface's future pages.
     */
    static final java.util.Set<String> RESERVED_SLUGS = java.util.Set.of(
            "unsolicited", "privacy", "config");

    /** @throws WebApplicationException 400 when the normalized slug is reserved */
    static void validateSlugNotReserved(String normalizedSlug) {
        if (normalizedSlug != null && RESERVED_SLUGS.contains(normalizedSlug)) {
            throw new WebApplicationException(
                    "'%s' is reserved for the public application surface and cannot be used as a position slug"
                            .formatted(normalizedSlug),
                    Response.Status.BAD_REQUEST);
        }
    }

    private void validateSlugUnique(String slug, String selfUuid) {
        String normalized = normalizeSlug(slug);
        if (normalized == null) {
            return;
        }
        validateSlugNotReserved(normalized);
        RecruitmentPosition other = RecruitmentPosition
                .<RecruitmentPosition>find("publicSlug", normalized)
                .firstResult();
        if (other != null && !other.getUuid().equals(selfUuid)) {
            throw new BusinessRuleViolation(
                    "The public link '%s' is already used by another position".formatted(normalized));
        }
    }

    private static String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return slug.trim().toLowerCase();
    }

    private static void requirePartnerTrack(RecruitmentPosition position) {
        if (position.getHiringTrack() != RecruitmentHiringTrack.PARTNER) {
            throw new WebApplicationException(
                    "Circles only apply to PARTNER-track positions",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static void validateOr400(Runnable validation) {
        try {
            validation.run();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Event skeleton for this position: subject + actor, and
     * {@code visibility=CIRCLE} for partner track so timeline queries can
     * apply the same hard filter as the state tables.
     */
    private static RecruitmentEventBuilder positionEvent(RecruitmentEventType type,
                                                         RecruitmentPosition position, UUID actor) {
        RecruitmentEventBuilder builder = RecruitmentEventBuilder.event(type)
                .position(position.getUuid())
                .actorUser(actor.toString());
        if (position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            builder.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        return builder;
    }

    private static void applyChange(Map<String, Object> changes, String field,
                                    String oldValue, String newValue,
                                    java.util.function.Consumer<String> setter) {
        if (!Objects.equals(oldValue, newValue)) {
            changes.put(field, newValue);
            setter.accept(newValue);
        }
    }
}
