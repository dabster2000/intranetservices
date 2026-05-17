package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class V348MigrationIT {
    @Inject DataSource ds;

    @Test
    void thirteenRulesSeeded() throws Exception {
        try (Connection c = ds.getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT rule_id, resolution_type FROM ai_rule_catalog WHERE active=TRUE ORDER BY priority")) {
            int count = 0;
            while (rs.next()) {
                count++;
                String id = rs.getString("rule_id");
                String type = rs.getString("resolution_type");
                if (id.equals("R_RECEIPT_READABLE") || id.equals("R_DATE_MISMATCH")) {
                    assertEquals("AUTO_FIX", type, "Wrong resolution_type for " + id);
                } else if (id.equals("R_OVERRIDE_WHITELIST_ADDRESS")) {
                    assertEquals("NONE", type);
                } else {
                    assertEquals("JUDGMENT", type, "Wrong resolution_type for " + id);
                }
            }
            assertEquals(13, count);
        }
    }

    @Test
    void parametersSeeded() throws Exception {
        try (Connection c = ds.getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ai_validation_parameter")) {
            rs.next();
            assertTrue(rs.getInt(1) >= 7);
        }
    }

    @Test
    void twoPromptsSeeded() throws Exception {
        try (Connection c = ds.getConnection();
             var st = c.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT template_key FROM ai_prompt_template ORDER BY template_key")) {
            assertTrue(rs.next()); assertEquals("POLICY_VALIDATION", rs.getString(1));
            assertTrue(rs.next()); assertEquals("VISION_EXTRACTION", rs.getString(1));
            assertFalse(rs.next());
        }
    }
}
