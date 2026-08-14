package dk.trustworks.intranet.competenceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The body of {@code POST /admin/requirements} and {@code PUT /admin/requirements/{uuid}}
 * (spec §6.3, §11.4).
 *
 * <p><strong>{@code null} means absent, {@code []} means empty — and the two must never be
 * conflated.</strong> The stored targeting columns use exactly that asymmetry (§5.2): all
 * three absent reaches everyone, all three {@code []} reaches nobody, which is a legitimate
 * way to park a krav without deactivating it. So this record does <em>not</em> normalise a
 * null list into an empty one, however tempting that is for null-safety. Normalising would
 * mean a client that omits {@code targetTeams} silently empties the team audience, the matrix
 * stays reassuringly green because nobody is in scope any more, and the failure is invisible
 * until an auditor asks who was trained. The import path documents the same rule for the same
 * reason.
 *
 * <p>{@code comp_id} is not in this body. It is slugified from the name with a numeric suffix
 * on collision (§11.4) and is the identifier content files key on, so letting a client set it
 * would let a rename break every export ever taken.
 *
 * <p>Validation belongs to the server: every entry in the three arrays must resolve to an
 * existing, active row, and an unknown uuid is a {@code 400} naming it — never a silent
 * no-match (§11.4). {@link AudiencePreviewDTO} exists so an author sees the resolved audience
 * before saving rather than after go-live.
 *
 * @param kref                the SKI reference; required
 * @param name                display name; required
 * @param description         one paragraph, ≤ 1000 chars
 * @param targetPracticeUuids practice uuids. {@code UD}/null is rejected — "no practice" is a
 *                            member token, not a practice, and targeting it is always a
 *                            mistake (the same rule {@code QuestionnaireService} follows).
 * @param targetTeams         team uuids
 * @param targetUseruuids     individually named people
 * @param cadenceDaysOverride per-requirement renewal interval; {@code null} falls back to
 *                            {@code competence.cadence-days}
 * @param sortOrder           display order in the matrix and the topic list
 * @param active              soft-retire flag. An inactive krav leaves the grid and keeps its
 *                            history; nothing is deleted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequirementUpsertRequest(String kref,
                                       String name,
                                       String description,
                                       List<String> targetPracticeUuids,
                                       List<String> targetTeams,
                                       List<String> targetUseruuids,
                                       Integer cadenceDaysOverride,
                                       Integer sortOrder,
                                       Boolean active) {

    public RequirementUpsertRequest {
        targetPracticeUuids = copyOrNull(targetPracticeUuids);
        targetTeams = copyOrNull(targetTeams);
        targetUseruuids = copyOrNull(targetUseruuids);
    }

    /**
     * Unmodifiable copy that preserves null and tolerates null entries.
     *
     * <p>{@code List.copyOf} would do neither: it throws on a null list and on a null element,
     * so {@code {"targetTeams":[null]}} would arrive as a {@code 500} instead of the
     * {@code 400} naming the unresolvable target that §11.4 requires.
     */
    private static List<String> copyOrNull(List<String> values) {
        return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
