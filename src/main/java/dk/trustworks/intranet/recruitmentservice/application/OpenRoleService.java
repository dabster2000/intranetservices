package dk.trustworks.intranet.recruitmentservice.application;

import dk.trustworks.intranet.recruitmentservice.api.dto.OpenRolePatchRequest;
import dk.trustworks.intranet.recruitmentservice.domain.entities.OpenRole;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleAssignment;
import dk.trustworks.intranet.recruitmentservice.domain.entities.RoleHistory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.HiringCategory;
import dk.trustworks.intranet.recruitmentservice.domain.enums.Practice;
import dk.trustworks.intranet.recruitmentservice.domain.enums.ResponsibilityKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.RoleStatus;
import dk.trustworks.intranet.recruitmentservice.domain.enums.WorkstreamStatus;
import dk.trustworks.intranet.recruitmentservice.domain.statemachines.OpenRoleStateMachine;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

@ApplicationScoped
public class OpenRoleService {

    private static final Set<RoleStatus> PAUSED_REQUIRES_REASON = Set.of(RoleStatus.PAUSED);
    private static final Set<RoleStatus> CANCELLED_REQUIRES_REASON = Set.of(RoleStatus.CANCELLED);

    @Transactional
    public OpenRole create(OpenRole input, String actorUuid) {
        if (input.uuid == null) input.uuid = UUID.randomUUID().toString();
        input.status = RoleStatus.DRAFT;
        if (input.advertisingStatus == null) input.advertisingStatus = WorkstreamStatus.NOT_STARTED;
        if (input.searchStatus == null) input.searchStatus = WorkstreamStatus.NOT_STARTED;
        input.createdByUuid = actorUuid;
        input.createdAt = LocalDateTime.now();
        input.updatedAt = input.createdAt;
        input.persist();

        RoleHistory.entry(input.uuid, null, RoleStatus.DRAFT, "Role created", actorUuid).persist();
        return input;
    }

    public OpenRole find(String uuid) {
        OpenRole r = OpenRole.findById(uuid);
        if (r == null) throw new NotFoundException("OpenRole " + uuid);
        return r;
    }

    @Transactional
    public OpenRole patch(String uuid, OpenRolePatchRequest req, String actorUuid) {
        OpenRole r = find(uuid);
        if (req.title() != null) r.title = req.title();
        if (req.hiringReason() != null) r.hiringReason = req.hiringReason();
        if (req.targetStartDate() != null) r.targetStartDate = req.targetStartDate();
        if (req.expectedAllocation() != null) r.expectedAllocation = req.expectedAllocation();
        if (req.expectedRateBand() != null) r.expectedRateBand = req.expectedRateBand();
        if (req.salaryMin() != null) r.salaryMin = req.salaryMin();
        if (req.salaryMax() != null) r.salaryMax = req.salaryMax();
        if (req.priority() != null) r.priority = req.priority();
        if (req.advertisingStatus() != null) r.advertisingStatus = req.advertisingStatus();
        if (req.searchStatus() != null) r.searchStatus = req.searchStatus();
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    @Transactional
    public OpenRole transition(String roleUuid, RoleStatus to, String reason, String actorUuid) {
        OpenRole role = find(roleUuid);
        if (PAUSED_REQUIRES_REASON.contains(to) && (reason == null || reason.isBlank())) {
            throw new BadRequestException("PAUSED requires a non-empty reason");
        }
        if (CANCELLED_REQUIRES_REASON.contains(to) && (reason == null || reason.isBlank())) {
            throw new BadRequestException("CANCELLED requires a non-empty reason");
        }
        OpenRoleStateMachine.assertTransitionAllowed(role.status, to);

        RoleStatus previous = role.status;
        role.status = to;
        role.updatedAt = LocalDateTime.now();
        RoleHistory.entry(role.uuid, previous, to, reason, actorUuid).persist();
        return role;
    }

    @Transactional
    public OpenRole resume(String roleUuid, String actorUuid) {
        OpenRole role = find(roleUuid);
        if (role.status != RoleStatus.PAUSED) {
            throw new BadRequestException("Resume only allowed from PAUSED");
        }
        // Slice 1: no children yet, derived -> SOURCING. Later slices can wire real predicates.
        RoleStatus resumed = OpenRoleStateMachine.deriveResumedStatus(false, false, false);
        OpenRoleStateMachine.assertTransitionAllowed(role.status, resumed);
        RoleStatus previous = role.status;
        role.status = resumed;
        role.updatedAt = LocalDateTime.now();
        RoleHistory.entry(role.uuid, previous, resumed, "Resumed", actorUuid).persist();
        return role;
    }

    @Transactional
    public RoleAssignment addAssignment(String roleUuid, String userUuid, ResponsibilityKind kind, String actorUuid) {
        OpenRole role = find(roleUuid);
        RoleAssignment a = RoleAssignment.fresh(roleUuid, userUuid, kind, actorUuid);
        a.persist();

        // Auto-advance DRAFT → SOURCING when first RECRUITMENT_OWNER is assigned
        if (kind == ResponsibilityKind.RECRUITMENT_OWNER && role.status == RoleStatus.DRAFT) {
            transition(roleUuid, RoleStatus.SOURCING, "Recruitment owner assigned", actorUuid);
        }
        return a;
    }

    @Transactional
    public void removeAssignment(String roleUuid, String userUuid) {
        long deleted = RoleAssignment.delete("roleUuid = ?1 and userUuid = ?2", roleUuid, userUuid);
        if (deleted == 0) throw new NotFoundException("Assignment not found");
    }

    public List<RoleAssignment> assignments(String roleUuid) {
        return RoleAssignment.find("roleUuid", roleUuid).list();
    }

    public List<OpenRole> list(RoleStatus status, String practice, String team, String ownerUuid,
                               String hiringCategory, int page, int size,
                               Predicate<OpenRole> recordAccess) {
        StringBuilder q = new StringBuilder("FROM OpenRole r WHERE 1=1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) { q.append(" AND r.status = :status"); params.put("status", status); }
        if (practice != null) { q.append(" AND r.practice = :practice"); params.put("practice", parseEnum(Practice.class, practice, "practice")); }
        if (team != null) { q.append(" AND r.teamUuid = :team"); params.put("team", team); }
        if (hiringCategory != null) { q.append(" AND r.hiringCategory = :hc"); params.put("hc", parseEnum(HiringCategory.class, hiringCategory, "hiringCategory")); }
        if (ownerUuid != null && !ownerUuid.isBlank()) {
            q.append(" AND EXISTS (SELECT 1 FROM RoleAssignment ra WHERE ra.roleUuid = r.uuid"
                   + " AND ra.userUuid = :owner AND ra.responsibilityKind = :ownerKind)");
            params.put("owner", ownerUuid);
            params.put("ownerKind", ResponsibilityKind.RECRUITMENT_OWNER);
        }
        List<OpenRole> rows = OpenRole.find(q.toString(), Sort.by("createdAt").descending(), params)
                .page(Page.of(page, size))
                .list();
        return rows.stream()
                .filter(recordAccess)
                .toList();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String paramName) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid " + paramName + ": " + value);
        }
    }
}
