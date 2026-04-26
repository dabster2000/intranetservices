package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only integration test for {@link CvToolPortImpl}.
 *
 * <p>Seeds the prerequisite {@code consultant} (which is the {@code Employee}
 * table per V176) and {@code cv_tool_employee_cv} rows directly via
 * {@link EntityManager#createNativeQuery}, then exercises the port and
 * asserts the rows + filter limit.
 *
 * <p>Each test uses {@link TestTransaction} so seed data rolls back at the
 * end and the suite stays repeatable.
 */
@QuarkusTest
class CvToolPortImplTest {

    @Inject CvToolPort port;
    @Inject EntityManager em;

    @Test
    @TestTransaction
    void findByPractice_returnsRowsForMatchingPractice() {
        String alice = seedConsultantAndCv("Alice A", "DEV", "[\"Java\",\"AWS\"]");
        String bob   = seedConsultantAndCv("Bob B",   "DEV", "[\"TypeScript\"]");
        String eve   = seedConsultantAndCv("Eve E",   "SA",  "[\"Networking\"]");

        List<CvToolPort.EmployeeCvSummary> rows = port.findByPractice("DEV", 10);

        assertEquals(2, rows.size(), "expected only DEV-practice rows");
        assertTrue(rows.stream().anyMatch(r -> alice.equals(r.userUuid())));
        assertTrue(rows.stream().anyMatch(r -> bob.equals(r.userUuid())));
        assertTrue(rows.stream().noneMatch(r -> eve.equals(r.userUuid())));
        // Smoke-check that practice is surfaced from the consultant join.
        assertTrue(rows.stream().allMatch(r -> "DEV".equals(r.practice())));
    }

    @Test
    @TestTransaction
    void findByPractice_respectsLimit() {
        for (int i = 0; i < 20; i++) {
            seedConsultantAndCv("name-" + i, "DEV", "[]");
        }
        List<CvToolPort.EmployeeCvSummary> rows = port.findByPractice("DEV", 5);
        assertEquals(5, rows.size());
    }

    /**
     * Seeds a matching {@code consultant} row and a {@code cv_tool_employee_cv}
     * row that points back at it.
     *
     * <p>Column shape is per V176 schema: {@code uuid}, {@code useruuid},
     * {@code cvtool_employee_id}, {@code cvtool_cv_id}, {@code employee_name},
     * {@code employee_title}, {@code employee_profile}, {@code cv_data_json},
     * {@code cv_language}, {@code last_synced_at}, {@code cv_last_updated_at}.
     *
     * @param employeeName name as it appears in the CV-Tool payload
     * @param practice     enum literal stored on {@code consultant.practice}
     * @param cvDataJson   raw JSON blob; {@code competencies} / {@code skills}
     *                     arrays inside become concept tokens
     * @return the user UUID shared by both seeded rows
     */
    private String seedConsultantAndCv(String employeeName, String practice, String cvDataJson) {
        String userUuid = UUID.randomUUID().toString();

        // Minimal consultant row — only practice + uuid are needed by the port.
        // Other NOT-NULL columns are populated with safe defaults.
        em.createNativeQuery("""
                INSERT INTO consultant (uuid, practice, status, allocation, salary, type)
                VALUES (:uuid, :practice, 'ACTIVE', 0, 0, 'CONSULTANT')
                """)
            .setParameter("uuid", userUuid)
            .setParameter("practice", practice)
            .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO cv_tool_employee_cv
                    (uuid, useruuid, cvtool_employee_id, cvtool_cv_id,
                     employee_name, employee_title, employee_profile,
                     cv_data_json, cv_language, last_synced_at, cv_last_updated_at)
                VALUES
                    (:uuid, :useruuid, :empId, :cvId,
                     :name, 'Senior Consultant', 'profile',
                     :cvJson, 1, NOW(), NOW())
                """)
            .setParameter("uuid", UUID.randomUUID().toString())
            .setParameter("useruuid", userUuid)
            .setParameter("empId", Math.abs(userUuid.hashCode()))
            .setParameter("cvId", Math.abs(userUuid.hashCode()) + 1)
            .setParameter("name", employeeName)
            .setParameter("cvJson", cvDataJson)
            .executeUpdate();

        return userUuid;
    }
}
