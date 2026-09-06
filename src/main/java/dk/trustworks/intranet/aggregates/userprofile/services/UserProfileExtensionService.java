package dk.trustworks.intranet.aggregates.userprofile.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.userprofile.dto.CompetenceTagDTO;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionDTO;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionRequest;
import dk.trustworks.intranet.aggregates.userprofile.model.UserCompetenceTag;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;
import dk.trustworks.intranet.services.PracticeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Education & profile facts and competence tags (JK Team 2.0 WP6 §4.6.3).
 *
 * <p>Writes are audited through structured log lines, the same convention as the WP1 and
 * WP3 services. CV-derived tags are refreshed from the CV Tool sync: the set of
 * {@code CV_TOOL} rows is reconciled to the CV's current {@code competencies[].title}
 * list, while {@code MANUAL} rows — and a manual row that happens to share a title —
 * are never touched by the sync.
 */
@JBossLog
@ApplicationScoped
public class UserProfileExtensionService {

    @Inject
    PracticeService practiceService;

    @Inject
    ObjectMapper objectMapper;

    // ── reads ───────────────────────────────────────────────────────────────

    public UserProfileExtensionDTO get(String useruuid) {
        UserProfileExtension row = UserProfileExtension.findForUser(useruuid).orElse(null);
        return UserProfileExtensionDTO.of(useruuid, row, UserCompetenceTag.findForUser(useruuid));
    }

    /** One DTO per requested person, in the order given; people without data get an empty DTO. */
    public List<UserProfileExtensionDTO> getMany(Collection<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) return List.of();
        Map<String, UserProfileExtension> rows = UserProfileExtension.mapForUsers(useruuids);
        Map<String, List<UserCompetenceTag>> tags = new HashMap<>();
        for (UserCompetenceTag t : UserCompetenceTag.findForUsers(useruuids)) {
            tags.computeIfAbsent(t.getUseruuid(), k -> new java.util.ArrayList<>()).add(t);
        }
        return useruuids.stream()
                .map(u -> UserProfileExtensionDTO.of(u, rows.get(u), tags.getOrDefault(u, List.of())))
                .toList();
    }

    public List<CompetenceTagDTO> tags(String useruuid) {
        return UserCompetenceTag.findForUser(useruuid).stream().map(CompetenceTagDTO::from).toList();
    }

    // ── writes ──────────────────────────────────────────────────────────────

    @Transactional
    public UserProfileExtensionDTO upsert(String useruuid, UserProfileExtensionRequest request, String actorUuid) {
        String problem = ProfileExtensionRules.problem(request, practiceService.activePracticeCodes(), LocalDate.now());
        if (problem != null) {
            throw new WebApplicationException(problem, Response.Status.BAD_REQUEST);
        }
        UserProfileExtension row = UserProfileExtension.findForUser(useruuid).orElse(null);
        boolean created = row == null;
        if (created) {
            row = new UserProfileExtension();
            row.setUseruuid(useruuid);
        }
        row.setEducation(blankToNull(request.education()));
        row.setExpectedGraduation(request.expectedGraduation());
        row.setStudyLevel(request.studyLevel());
        row.setPrimaryDiscipline(request.primaryDiscipline());
        row.setUpdatedAt(LocalDateTime.now());
        row.setUpdatedBy(actorUuid);
        if (created) row.persist();
        log.infof("Profile extension %s: user=%s studyLevel=%s discipline=%s graduation=%s actor=%s",
                created ? "created" : "updated", useruuid, row.getStudyLevel(), row.getPrimaryDiscipline(),
                row.getExpectedGraduation(), actorUuid);
        return UserProfileExtensionDTO.of(useruuid, row, UserCompetenceTag.findForUser(useruuid));
    }

    /** Adds a MANUAL tag; a tag that already exists (any source) is left as is and returned. */
    @Transactional
    public List<CompetenceTagDTO> addManualTag(String useruuid, String rawTag, String actorUuid) {
        String tag = ProfileExtensionRules.normalizeTag(rawTag);
        if (tag == null) {
            throw new WebApplicationException("tag required", Response.Status.BAD_REQUEST);
        }
        boolean exists = UserCompetenceTag.findForUser(useruuid).stream()
                .anyMatch(t -> ProfileExtensionRules.tagKey(t.getTag()).equals(ProfileExtensionRules.tagKey(tag)));
        if (!exists) {
            new UserCompetenceTag(useruuid, tag, UserCompetenceTag.Source.MANUAL, actorUuid).persist();
            log.infof("Competence tag added: user=%s tag=%s source=MANUAL actor=%s", useruuid, tag, actorUuid);
        }
        return tags(useruuid);
    }

    /** Removes a tag of either source — a lead may override a CV suggestion by removing it. */
    @Transactional
    public List<CompetenceTagDTO> removeTag(String useruuid, String rawTag, String actorUuid) {
        String tag = ProfileExtensionRules.normalizeTag(rawTag);
        if (tag == null) {
            throw new WebApplicationException("tag required", Response.Status.BAD_REQUEST);
        }
        long removed = UserCompetenceTag.delete("useruuid = ?1 and lower(tag) = ?2", useruuid, ProfileExtensionRules.tagKey(tag));
        if (removed == 0) {
            throw new WebApplicationException("No such tag", Response.Status.NOT_FOUND);
        }
        log.infof("Competence tag removed: user=%s tag=%s actor=%s", useruuid, tag, actorUuid);
        return tags(useruuid);
    }

    // ── CV Tool sync ────────────────────────────────────────────────────────

    /**
     * Reconciles the person's {@code CV_TOOL} tags to the CV document's competence titles.
     * Runs inside the caller's transaction (the CV sync's per-employee {@code requiringNew}).
     * Returns the number of rows changed. Never throws on a malformed document — a bad CV
     * must not fail the sync of the CV itself.
     */
    public int syncCvTags(String useruuid, String cvJson) {
        if (useruuid == null || useruuid.isBlank()) return 0;
        List<String> titles;
        try {
            JsonNode root = cvJson == null ? null : objectMapper.readTree(cvJson);
            titles = ProfileExtensionRules.extractCompetenceTitles(root);
        } catch (Exception e) {
            log.warnf("CV competence extraction skipped for user %s: %s", useruuid, e.getMessage());
            return 0;
        }
        return reconcileCvTags(useruuid, titles);
    }

    /** Package-private so the reconciliation is testable without a CV document. */
    int reconcileCvTags(String useruuid, List<String> titles) {
        Set<String> wanted = new HashSet<>();
        for (String t : titles) wanted.add(ProfileExtensionRules.tagKey(t));

        Set<String> present = new HashSet<>();
        int changed = 0;
        for (UserCompetenceTag existing : UserCompetenceTag.findForUser(useruuid)) {
            String key = ProfileExtensionRules.tagKey(existing.getTag());
            present.add(key);
            if (existing.getSource() == UserCompetenceTag.Source.CV_TOOL && !wanted.contains(key)) {
                existing.delete();
                changed++;
            }
        }
        for (String title : titles) {
            if (present.contains(ProfileExtensionRules.tagKey(title))) continue;
            new UserCompetenceTag(useruuid, title, UserCompetenceTag.Source.CV_TOOL, null).persist();
            changed++;
        }
        if (changed > 0) {
            log.debugf("CV competence tags reconciled: user=%s changed=%d", useruuid, changed);
        }
        return changed;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
