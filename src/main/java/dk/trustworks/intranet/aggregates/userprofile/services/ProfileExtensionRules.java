package dk.trustworks.intranet.aggregates.userprofile.services;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.aggregates.userprofile.dto.UserProfileExtensionRequest;
import dk.trustworks.intranet.aggregates.userprofile.model.UserProfileExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure validation and extraction rules for the profile extension (JK Team 2.0 WP6 §4.6.3).
 * Static so the fast test tier covers them without a container.
 */
public final class ProfileExtensionRules {

    /** The registry's no-practice sentinel — "ikke afklaret endnu", painted grey on the frontend. */
    public static final String NO_DISCIPLINE = "UD";
    public static final int MAX_TAG_LENGTH = 64;
    public static final int MAX_EDUCATION_LENGTH = 255;
    /** A graduation date more than this far out is a typo, not a plan. */
    public static final int MAX_GRADUATION_YEARS_AHEAD = 15;

    private ProfileExtensionRules() {
    }

    /**
     * The first problem with a request, or {@code null} when it is acceptable.
     *
     * @param activeDisciplineCodes practice storage codes accepted for {@code primaryDiscipline}
     *                              (the registry's active practices); {@link #NO_DISCIPLINE} is always accepted
     */
    public static String problem(UserProfileExtensionRequest request, Collection<String> activeDisciplineCodes, LocalDate today) {
        if (request == null) return "Body required";
        if (request.education() != null && request.education().length() > MAX_EDUCATION_LENGTH) {
            return "education must be at most " + MAX_EDUCATION_LENGTH + " characters";
        }
        if (request.studyLevel() != null
                && !UserProfileExtension.STUDY_LEVEL_BACHELOR.equals(request.studyLevel())
                && !UserProfileExtension.STUDY_LEVEL_KANDIDAT.equals(request.studyLevel())) {
            return "studyLevel must be BACHELOR or KANDIDAT";
        }
        if (request.expectedGraduation() != null) {
            LocalDate g = request.expectedGraduation();
            if (g.isBefore(today.minusYears(60)) || g.isAfter(today.plusYears(MAX_GRADUATION_YEARS_AHEAD))) {
                return "expectedGraduation is out of range";
            }
        }
        if (request.primaryDiscipline() != null && !NO_DISCIPLINE.equals(request.primaryDiscipline())
                && (activeDisciplineCodes == null || !activeDisciplineCodes.contains(request.primaryDiscipline()))) {
            return "primaryDiscipline must be an active practice code or UD";
        }
        return null;
    }

    /** Trimmed, whitespace-collapsed, length-capped; {@code null} when nothing usable is left. */
    public static String normalizeTag(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > MAX_TAG_LENGTH) cleaned = cleaned.substring(0, MAX_TAG_LENGTH).trim();
        return cleaned;
    }

    /** Case-insensitive identity for dedupe — the table's collation makes the same call. */
    public static String tagKey(String tag) {
        return tag == null ? null : tag.toLowerCase(Locale.ROOT);
    }

    /**
     * The distinct {@code competencies[].title} values of a CV Tool document, in document
     * order, normalised with {@link #normalizeTag}. Tolerates a missing or non-array node.
     * Plain extraction — the same path {@code CvSearchService} walks — no AI.
     */
    public static List<String> extractCompetenceTitles(JsonNode root) {
        List<String> titles = new ArrayList<>();
        if (root == null) return titles;
        JsonNode competencies = root.get("competencies");
        if (competencies == null || !competencies.isArray()) return titles;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : competencies) {
            JsonNode title = item == null ? null : item.get("title");
            if (title == null || !title.isTextual()) continue;
            String tag = normalizeTag(title.asText());
            if (tag == null) continue;
            if (seen.add(tagKey(tag))) titles.add(tag);
        }
        return titles;
    }
}
