package dk.trustworks.intranet.aggregates.userprofile.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Education & profile facts for one person (V574) — JK Team 2.0 WP6 §4.6.3.
 *
 * <p>Kept off the {@code user} table so that {@code UserScopeResponseFilter} never has to
 * learn about these fields; the resource that serves them applies its own reach rule.
 * Never a REST body: the resource takes {@code UserProfileExtensionRequest}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_profile_extension")
public class UserProfileExtension extends PanacheEntityBase {

    /** D12: the explicit study level — never inferred from the hourly rate. */
    public static final String STUDY_LEVEL_BACHELOR = "BACHELOR";
    public static final String STUDY_LEVEL_KANDIDAT = "KANDIDAT";

    @Id
    @Column(name = "useruuid", length = 36, nullable = false)
    private String useruuid;

    @Column(name = "education", length = 255)
    private String education;

    @Column(name = "expected_graduation")
    private LocalDate expectedGraduation;

    @Column(name = "study_level", length = 16)
    private String studyLevel;

    /** A practice storage code from the registry, or {@code UD} for "ikke afklaret endnu". */
    @Column(name = "primary_discipline", length = 16)
    private String primaryDiscipline;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 36)
    private String updatedBy;

    public static Optional<UserProfileExtension> findForUser(String useruuid) {
        return UserProfileExtension.<UserProfileExtension>find("useruuid", useruuid).firstResultOptional();
    }

    /** useruuid → row for a roster; missing people are simply absent. */
    public static Map<String, UserProfileExtension> mapForUsers(Collection<String> useruuids) {
        if (useruuids == null || useruuids.isEmpty()) return Map.of();
        List<UserProfileExtension> rows = UserProfileExtension.<UserProfileExtension>list("useruuid in ?1", useruuids);
        return rows.stream().collect(Collectors.toMap(UserProfileExtension::getUseruuid, Function.identity(), (a, b) -> a));
    }
}
