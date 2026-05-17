package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class V350MigrationIT {
    @Inject DataSource ds;

    @Test
    void thresholdRuleDescriptionsAreParameterized() throws Exception {
        Map<String, String> expected = Map.of(
                "R_MEAL_COST_PER_PERSON",
                "Food or drink above {{meal_cost_per_person_dkk}} DKK per person requires a documented business reason.",
                "R_IT_EQUIPMENT_LIMIT",
                "IT equipment purchases above {{it_equipment_pre_approval_dkk}} DKK require pre-approval.",
                "R_DATE_MISMATCH",
                "Receipt date and expense date must be within {{date_mismatch_tolerance_days}} calendar days of each other."
        );

        try (var c = ds.getConnection();
             var ps = c.prepareStatement("""
                     SELECT rule_id, description
                     FROM ai_rule_catalog
                     WHERE rule_id IN (?, ?, ?)
                     """)) {
            ps.setString(1, "R_MEAL_COST_PER_PERSON");
            ps.setString(2, "R_IT_EQUIPMENT_LIMIT");
            ps.setString(3, "R_DATE_MISMATCH");

            int count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    String ruleId = rs.getString("rule_id");
                    assertEquals(expected.get(ruleId), rs.getString("description"),
                            "Wrong parameterized description for " + ruleId);
                }
            }
            assertEquals(3, count);
        }
    }
}
