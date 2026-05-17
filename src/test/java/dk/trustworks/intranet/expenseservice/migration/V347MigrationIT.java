package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class V347MigrationIT {
    @Inject DataSource ds;
    @Test
    void decisionLogTableExists() throws Exception {
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(null, null, "expense_decision_log", null)) {
            var names = new java.util.HashSet<String>();
            while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
            assertTrue(names.containsAll(java.util.List.of(
                "uuid","expense_uuid","occurred_at","actor_uuid","actor_role","action",
                "from_status","to_status","from_review_state","to_review_state",
                "ai_rule_id","reason_text")));
        }
    }
}
