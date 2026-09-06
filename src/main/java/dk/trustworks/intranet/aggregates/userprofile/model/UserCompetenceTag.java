package dk.trustworks.intranet.aggregates.userprofile.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * One competence tag on one person (V575) — JK Team 2.0 WP6 §4.6.3.
 *
 * <p>{@code MANUAL} rows are the team lead's own words; {@code CV_TOOL} rows are lifted
 * from the CV bank and always carry that provenance on the surface
 * ({@code reference_ai_subject_tags_need_human_provenance}).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@IdClass(UserCompetenceTag.Key.class)
@Table(name = "user_competence_tag")
public class UserCompetenceTag extends PanacheEntityBase {

    public enum Source { MANUAL, CV_TOOL }

    @Id
    @Column(name = "useruuid", length = 36, nullable = false)
    private String useruuid;

    @Id
    @Column(name = "tag", length = 64, nullable = false)
    private String tag;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 16, nullable = false)
    private Source source;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    public UserCompetenceTag(String useruuid, String tag, Source source, String createdBy) {
        this.useruuid = useruuid;
        this.tag = tag;
        this.source = source;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public static List<UserCompetenceTag> findForUser(String useruuid) {
        return UserCompetenceTag.<UserCompetenceTag>list("useruuid = ?1 order by source, tag", useruuid);
    }

    public static List<UserCompetenceTag> findForUsers(Collection<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) return List.of();
        return UserCompetenceTag.<UserCompetenceTag>list("useruuid in ?1 order by useruuid, source, tag", useruuids);
    }

    public static List<UserCompetenceTag> findCvTags(String useruuid) {
        return UserCompetenceTag.<UserCompetenceTag>list("useruuid = ?1 and source = ?2", useruuid, Source.CV_TOOL);
    }

    /** Composite key: one row per (person, tag) regardless of source. */
    @EqualsAndHashCode
    @NoArgsConstructor
    @Getter
    @Setter
    public static class Key implements Serializable {
        private String useruuid;
        private String tag;

        public Key(String useruuid, String tag) {
            this.useruuid = useruuid;
            this.tag = tag;
        }
    }
}
