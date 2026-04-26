package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Live implementation of {@link CvToolPort} that reads the existing
 * {@code cv_tool_employee_cv} table populated nightly by the CV-Tool sync
 * batchlet (see V176).
 *
 * <p><b>Schema mapping (V176 reality vs Slice 2 contract):</b>
 * The V176 table only stores {@code useruuid}, {@code employee_name},
 * {@code employee_title}, and the full CV blob in {@code cv_data_json}. The
 * Slice 2 contract additionally exposes {@code practice} and {@code careerLevel}.
 * Those facts live elsewhere:
 * <ul>
 *   <li>{@code practice} → {@code consultant.practice} (joined on {@code useruuid})</li>
 *   <li>{@code careerLevel} → most-recent {@code user_career_level.career_level}
 *       row whose {@code active_from <= CURDATE()}</li>
 * </ul>
 * The {@code conceptTokens} list is a best-effort extraction from
 * {@code cv_data_json} (competency / skill / certification names), tolerant
 * of malformed JSON — failures yield an empty list rather than failing the
 * whole match.
 *
 * <p><b>Resolution:</b> registered with {@code @Alternative @Priority(10)} so
 * it beats {@link NoopCvToolPort} ({@code @Priority(1)}) at runtime. Tests
 * needing a fake can register a higher-priority alternative or use
 * {@code @InjectMock CvToolPort}.
 */
@JBossLog
@ApplicationScoped
@Alternative
@Priority(10)  // beats NoopCvToolPort@Priority(1)
public class CvToolPortImpl implements CvToolPort {

    /**
     * Maximum number of concept tokens surfaced per CV. Keeps prompt context
     * compact even if a CV has dozens of competencies.
     */
    private static final int MAX_CONCEPT_TOKENS_PER_CV = 25;

    @Inject
    EntityManager em;

    private final ObjectMapper json = new ObjectMapper();

    @Override
    public List<EmployeeCvSummary> findByPractice(String practiceCode, int limit) {
        if (practiceCode == null || practiceCode.isBlank() || limit <= 0) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT cv.useruuid,
                       cv.employee_name,
                       c.practice,
                       (SELECT ucl.career_level
                          FROM user_career_level ucl
                         WHERE ucl.useruuid = cv.useruuid
                           AND ucl.active_from <= CURDATE()
                         ORDER BY ucl.active_from DESC
                         LIMIT 1) AS career_level,
                       cv.cv_data_json
                  FROM cv_tool_employee_cv cv
                  JOIN consultant c ON c.uuid = cv.useruuid
                 WHERE c.practice = :practice
                 LIMIT :max
                """)
            .setParameter("practice", practiceCode)
            .setParameter("max", limit)
            .getResultList();
        return rows.stream().map(this::toSummary).toList();
    }

    @Override
    public List<EmployeeCvSummary> findByCareerLevelUuid(String careerLevelUuid, int limit) {
        if (careerLevelUuid == null || careerLevelUuid.isBlank() || limit <= 0) {
            return List.of();
        }
        // The param is a row UUID on user_career_level; we resolve it to its
        // career_level enum literal and match consultants whose CURRENT level
        // equals that value.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
                SELECT cv.useruuid,
                       cv.employee_name,
                       c.practice,
                       current_ucl.career_level,
                       cv.cv_data_json
                  FROM cv_tool_employee_cv cv
                  JOIN consultant c ON c.uuid = cv.useruuid
                  JOIN (
                      SELECT ucl.useruuid, ucl.career_level
                        FROM user_career_level ucl
                       INNER JOIN (
                           SELECT useruuid, MAX(active_from) AS max_active_from
                             FROM user_career_level
                            WHERE active_from <= CURDATE()
                            GROUP BY useruuid
                       ) latest
                          ON latest.useruuid = ucl.useruuid
                         AND latest.max_active_from = ucl.active_from
                  ) current_ucl ON current_ucl.useruuid = cv.useruuid
                 WHERE current_ucl.career_level = (
                       SELECT career_level FROM user_career_level WHERE uuid = :clUuid
                       )
                 LIMIT :max
                """)
            .setParameter("clUuid", careerLevelUuid)
            .setParameter("max", limit)
            .getResultList();
        return rows.stream().map(this::toSummary).toList();
    }

    private EmployeeCvSummary toSummary(Object[] row) {
        String userUuid = asString(row[0]);
        String displayName = asString(row[1]);
        String practice = asString(row[2]);
        String careerLevel = asString(row[3]);
        List<String> concepts = parseConcepts(row[4]);
        return new EmployeeCvSummary(userUuid, displayName, practice, careerLevel, concepts);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Extract a compact list of concept tokens (skill/topic strings) from the
     * raw {@code cv_data_json} blob. The CV-Tool payload typically contains
     * arrays such as {@code competencies}, {@code skills},
     * {@code certifications} where each element has a {@code name} or
     * {@code title} field. We harvest those, dedupe (preserving insertion
     * order), and cap at {@link #MAX_CONCEPT_TOKENS_PER_CV}.
     *
     * <p>Always returns a non-null list. Any parse failure → empty list, so a
     * single broken record cannot poison a whole match request.
     */
    private List<String> parseConcepts(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        String payload = raw.toString();
        if (payload.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonNode root = json.readTree(payload);
            Set<String> tokens = new LinkedHashSet<>();
            harvestNamedItems(root, "competencies", tokens);
            harvestNamedItems(root, "skills", tokens);
            harvestNamedItems(root, "certifications", tokens);
            harvestNamedItems(root, "topics", tokens);
            if (tokens.size() > MAX_CONCEPT_TOKENS_PER_CV) {
                return new ArrayList<>(tokens).subList(0, MAX_CONCEPT_TOKENS_PER_CV);
            }
            return new ArrayList<>(tokens);
        } catch (Exception e) {
            log.debugf(e, "[CvToolPort] could not parse cv_data_json for concepts; returning empty list");
            return Collections.emptyList();
        }
    }

    private static void harvestNamedItems(JsonNode root, String fieldName, Set<String> out) {
        JsonNode arr = root.get(fieldName);
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode item : arr) {
            if (item.isTextual()) {
                addIfPresent(item.asText(), out);
                continue;
            }
            if (!item.isObject()) {
                continue;
            }
            JsonNode name = item.get("name");
            if (name == null || name.isNull()) {
                name = item.get("title");
            }
            if (name != null && name.isTextual()) {
                addIfPresent(name.asText(), out);
            }
        }
    }

    private static void addIfPresent(String s, Set<String> out) {
        if (s == null) return;
        String trimmed = s.trim();
        if (!trimmed.isEmpty()) {
            out.add(trimmed);
        }
    }
}
