package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class V346MigrationIT {

    @Inject DataSource ds;

    @Test
    void newColumnsExist() throws Exception {
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(null, null, "expenses", null)) {
            var names = new java.util.HashSet<String>();
            while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
            assertTrue(names.contains("review_state"));
            assertTrue(names.contains("ai_rule_id"));
            assertTrue(names.contains("ai_rule_ids_json"));
            assertTrue(names.contains("employee_justification"));
            assertTrue(names.contains("hr_decision"));
            assertTrue(names.contains("hr_decision_by"));
            assertTrue(names.contains("hr_decision_at"));
            assertTrue(names.contains("hr_comment"));
            assertTrue(names.contains("ai_validation_count"));
            assertTrue(names.contains("version"));
        }
    }
}
