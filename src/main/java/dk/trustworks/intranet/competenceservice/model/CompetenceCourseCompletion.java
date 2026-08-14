package dk.trustworks.intranet.competenceservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One microcourse completion event. Append-only; the newest row for a
 * (user, requirement) pair drives status.
 *
 * <p>The table's UPDATE and DELETE triggers refuse both unconditionally, which is why
 * force-retake-off carry-forward appends a fresh row per affected completion instead of
 * re-stamping the old one.
 *
 * <p>{@code useruuid} is a soft reference with no FK, matching the convention V489 set:
 * a user row can be merged or removed without dragging the compliance history with it.
 * {@code versionLabel} is denormalised so an export still reads correctly after a content
 * version is purged.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "competence_course_completion")
public class CompetenceCourseCompletion extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String useruuid;

    @Column(name = "requirement_uuid")
    private String requirementUuid;

    @Column(name = "content_version_uuid")
    private String contentVersionUuid;

    @Column(name = "version_label")
    private String versionLabel;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** The latest completion for one user and requirement, or {@code null}. */
    public static CompetenceCourseCompletion findLatest(String useruuid, String requirementUuid) {
        return find("useruuid = ?1 and requirementUuid = ?2 order by completedAt desc",
                useruuid, requirementUuid).firstResult();
    }

    /**
     * A completion of one specific version, recorded for this user at or after
     * {@code since} — the idempotency probe behind
     * {@code POST /me/requirements/{uuid}/course/completions}.
     *
     * <p>Keyed on the content version rather than on the requirement: re-reading the same
     * version twice in a minute is a double-click or a re-mounted player, while reading a
     * newly published version a minute after the old one is a real second event and must
     * append. Newest first so a pre-existing duplicate resolves to the one whose timestamp
     * the caller will see.
     */
    public static CompetenceCourseCompletion findRecent(String useruuid,
                                                        String requirementUuid,
                                                        String contentVersionUuid,
                                                        LocalDateTime since) {
        return find("useruuid = ?1 and requirementUuid = ?2 and contentVersionUuid = ?3 "
                        + "and completedAt >= ?4 order by completedAt desc",
                useruuid, requirementUuid, contentVersionUuid, since).firstResult();
    }

    /** Every completion for a set of users — one query for the whole matrix column. */
    public static List<CompetenceCourseCompletion> listForUsers(List<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) {
            return List.of();
        }
        return list("useruuid in ?1 order by completedAt desc", useruuids);
    }

    public static List<CompetenceCourseCompletion> listForRequirementVersion(String contentVersionUuid) {
        return list("contentVersionUuid", contentVersionUuid);
    }
}
