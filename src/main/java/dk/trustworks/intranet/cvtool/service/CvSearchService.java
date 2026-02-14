package dk.trustworks.intranet.cvtool.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.cvtool.dto.CvSearchResultDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for searching across CV data stored in cv_tool_employee_cv.
 * Uses LIKE on the JSON text blob for broad matching, then parses JSON
 * to extract which specific fields matched and a contextual snippet.
 */
@JBossLog
@ApplicationScoped
public class CvSearchService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    /**
     * Search CVs by query string across all fields.
     *
     * @param query Search term (case-insensitive, minimum 2 chars)
     * @return List of matching CVs with match context
     */
    @SuppressWarnings("unchecked")
    public List<CvSearchResultDTO> searchCvs(String query) {
        if (query == null || query.trim().length() < 2) {
            return List.of();
        }

        String searchTerm = query.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        String searchPattern = "%" + searchTerm + "%";

        String sql = """
            SELECT useruuid, employee_name, employee_title,
                   employee_profile, cv_data_json
            FROM cv_tool_employee_cv
            WHERE LOWER(employee_name) LIKE :pattern ESCAPE '\\\\'
               OR LOWER(employee_title) LIKE :pattern ESCAPE '\\\\'
               OR LOWER(employee_profile) LIKE :pattern ESCAPE '\\\\'
               OR LOWER(cv_data_json) LIKE :pattern ESCAPE '\\\\'
            ORDER BY employee_name
            LIMIT 50
            """;

        Query nativeQuery = em.createNativeQuery(sql);
        nativeQuery.setParameter("pattern", searchPattern);

        List<Object[]> rows = nativeQuery.getResultList();
        List<CvSearchResultDTO> results = new ArrayList<>();

        for (Object[] row : rows) {
            String useruuid = (String) row[0];
            String employeeName = (String) row[1];
            String employeeTitle = (String) row[2];
            String employeeProfile = (String) row[3];
            String cvDataJson = (String) row[4];

            Set<String> matchedFields = new LinkedHashSet<>();
            String snippet = null;

            if (containsIgnoreCase(employeeName, searchTerm)) {
                matchedFields.add("name");
            }
            if (containsIgnoreCase(employeeTitle, searchTerm)) {
                matchedFields.add("title");
            }
            if (containsIgnoreCase(employeeProfile, searchTerm)) {
                matchedFields.add("profile");
                snippet = extractSnippet(employeeProfile, searchTerm);
            }

            if (cvDataJson != null && !cvDataJson.isEmpty()) {
                try {
                    JsonNode root = OBJECT_MAPPER.readTree(cvDataJson);

                    if (searchArray(root, "competencies", "title", searchTerm)) {
                        matchedFields.add("competencies");
                        if (snippet == null) {
                            snippet = collectMatchingTitles(root.get("competencies"), "title", searchTerm);
                        }
                    }
                    if (searchProjectFields(root, searchTerm)) {
                        matchedFields.add("projects");
                        if (snippet == null) {
                            snippet = extractProjectSnippet(root.get("projects"), searchTerm);
                        }
                    }
                    if (searchArray(root, "educations", "title", searchTerm)
                            || searchArray(root, "educations", "institution", searchTerm)) {
                        matchedFields.add("educations");
                    }
                    if (searchArray(root, "certifications", "title", searchTerm)) {
                        matchedFields.add("certifications");
                    }
                    if (searchArray(root, "languages", "title", searchTerm)) {
                        matchedFields.add("languages");
                    }
                } catch (Exception e) {
                    log.debugf("Failed to parse CV JSON for user %s: %s", useruuid, e.getMessage());
                }
            }

            results.add(new CvSearchResultDTO(
                    useruuid,
                    employeeName,
                    employeeTitle,
                    new ArrayList<>(matchedFields),
                    snippet != null ? snippet : ""
            ));
        }

        log.infof("CV search for '%s' returned %d results", query, results.size());
        return results;
    }

    private boolean searchArray(JsonNode root, String arrayName, String fieldName, String query) {
        JsonNode array = root.get(arrayName);
        if (array == null || !array.isArray()) return false;
        for (JsonNode item : array) {
            JsonNode field = item.get(fieldName);
            if (field != null && containsIgnoreCase(field.asText(), query)) {
                return true;
            }
        }
        return false;
    }

    private boolean searchProjectFields(JsonNode root, String query) {
        JsonNode projects = root.get("projects");
        if (projects == null || !projects.isArray()) return false;
        for (JsonNode project : projects) {
            for (String field : List.of("title", "client_name", "description", "role")) {
                JsonNode node = project.get(field);
                if (node != null && containsIgnoreCase(node.asText(), query)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String collectMatchingTitles(JsonNode array, String fieldName, String query) {
        if (array == null || !array.isArray()) return null;
        List<String> titles = new ArrayList<>();
        for (JsonNode item : array) {
            JsonNode field = item.get(fieldName);
            if (field != null) {
                String value = field.asText();
                if (containsIgnoreCase(value, query)) {
                    titles.add(value);
                }
            }
            if (titles.size() >= 5) break;
        }
        return titles.isEmpty() ? null : String.join(", ", titles);
    }

    private String extractProjectSnippet(JsonNode projects, String query) {
        if (projects == null || !projects.isArray()) return null;
        for (JsonNode project : projects) {
            for (String field : List.of("title", "client_name", "description", "role")) {
                JsonNode node = project.get(field);
                if (node != null && containsIgnoreCase(node.asText(), query)) {
                    return extractSnippet(node.asText(), query);
                }
            }
        }
        return null;
    }

    private boolean containsIgnoreCase(String text, String query) {
        return text != null && text.toLowerCase().contains(query);
    }

    private String extractSnippet(String text, String query) {
        if (text == null || text.isEmpty()) return "";
        int index = text.toLowerCase().indexOf(query);
        if (index < 0) return "";
        int start = Math.max(0, index - 40);
        int end = Math.min(text.length(), index + query.length() + 40);
        String snippet = text.substring(start, end).trim();
        if (start > 0) snippet = "..." + snippet;
        if (end < text.length()) snippet = snippet + "...";
        return snippet;
    }
}
