package dk.trustworks.intranet.recruitmentservice.model.enums;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which internal people a template copies on a candidate email. Stored as
 * a CSV in {@code recruitment_email_templates.copy_roles}; an empty value
 * means "copy nobody", which is what every template created before V455
 * keeps.
 * <p>
 * These are <em>sources</em>, not recipients: they are resolved to actual
 * people at send time by {@code RecruitmentEmailCopyResolver}, and every
 * resolved person is filtered through
 * {@code RecruitmentVisibility#canReadCandidateProfile} — a copy can never
 * reveal a candidate the recipient is not authorized to read.
 */
public enum RecruitmentEmailCopyRole {

    /**
     * Everyone assigned to a non-cancelled interview of the candidate
     * (scoped to the application when the email has one). Resolves to
     * nobody before the first interview is scheduled — acknowledgements
     * and screening rejections therefore copy no interviewers, which is
     * correct: nobody has met the candidate yet.
     */
    INTERVIEWERS,

    /**
     * The recruiter who pressed Send (manual sends and review approvals).
     * Resolves to nobody on the reactor's automatic sends — no human acted.
     */
    SENDER,

    /**
     * {@code recruitment_positions.hiring_owner_uuid} for the email's
     * position context. Resolves to nobody when the email has no position
     * or the position has no owner set.
     */
    HIRING_OWNER;

    /** Parse a stored CSV; unknown and blank tokens are ignored. */
    public static Set<RecruitmentEmailCopyRole> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<RecruitmentEmailCopyRole> roles = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            parse(part).ifPresent(roles::add);
        }
        return roles;
    }

    /** Render a role set back to the stored CSV form (stable enum order). */
    public static String toCsv(Set<RecruitmentEmailCopyRole> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return Arrays.stream(values())
                .filter(roles::contains)
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }

    /** Lenient single-token parse — empty when the token is not a role. */
    public static Optional<RecruitmentEmailCopyRole> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(role -> role.name().equals(normalized))
                .findFirst();
    }
}
