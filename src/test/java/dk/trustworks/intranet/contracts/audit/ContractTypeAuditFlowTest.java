package dk.trustworks.intranet.contracts.audit;

import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;
import dk.trustworks.intranet.contracts.model.ContractTypeAudit;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.ContractValidationRuleEntity;
import dk.trustworks.intranet.contracts.model.PricingRuleStepEntity;
import dk.trustworks.intranet.contracts.model.enums.ValidationType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the framework agreement audit trail (§9.9 / contract C4):
 * REST mutations produce audit rows with X-Requested-By attribution, the read endpoint
 * returns them newest-first with a working limit, and the entity listener classifies
 * CREATE / UPDATE / DELETE (soft-disable) / RESTORE correctly.
 *
 * <p>Requires the standard local test database (MariaDB on 127.0.0.1:3306) like every
 * other {@code @QuarkusTest} in this module. REST-created rows are removed in
 * {@link #cleanup()} via JPQL bulk deletes (which bypass the audit listener).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestSecurity(user = "audit-tester", roles = {"contracts:read", "contracts:write"})
class ContractTypeAuditFlowTest {

    private static final String USER_UUID = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0001";
    private static final String CODE = "AUDITTEST_" + (System.currentTimeMillis() % 100_000_000);
    private static final String RULE_ID = "audit-admin-fee";

    @Inject
    EntityManager em;

    @AfterAll
    static void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            PricingRuleStepEntity.delete("contractTypeCode", CODE);
            ContractValidationRuleEntity.delete("contractTypeCode", CODE);
            ContractTypeDefinition.delete("code", CODE);
            ContractTypeAudit.delete("contractTypeCode", CODE);
        });
    }

    // --- REST flow: attribution + operation classification ---

    @Test
    @Order(1)
    void createAgreement_withHeader_recordsCreateWithUser() {
        given()
            .header("X-Requested-By", USER_UUID)
            .contentType("application/json")
            .body(Map.of(
                "code", CODE,
                "name", "Audit Test Agreement",
                "description", "Created by ContractTypeAuditFlowTest",
                "active", true))
        .when()
            .post("/api/contract-types")
        .then()
            .statusCode(201);

        JsonPath audit = getAudit(100);
        assertEquals("AGREEMENT", audit.getString("entries[0].entityType"));
        assertEquals("CREATE", audit.getString("entries[0].operation"));
        assertEquals(USER_UUID, audit.getString("entries[0].changedBy"));
        assertNull(audit.getString("entries[0].ruleId"));
        assertNotNull(audit.getString("entries[0].changedAt"));
    }

    @Test
    @Order(2)
    void createPricingRule_recordsCreateWithRuleId() {
        given()
            .header("X-Requested-By", USER_UUID)
            .contentType("application/json")
            .body(Map.of(
                "ruleId", RULE_ID,
                "label", "5% audit admin fee",
                "ruleStepType", "PERCENT_DISCOUNT_ON_SUM",
                "stepBase", "CURRENT_SUM",
                "percent", 5.0,
                "priority", 10))
        .when()
            .post("/api/contract-types/" + CODE + "/rules")
        .then()
            .statusCode(201);

        JsonPath audit = getAudit(100);
        assertEquals("PRICING_RULE", audit.getString("entries[0].entityType"));
        assertEquals("CREATE", audit.getString("entries[0].operation"));
        assertEquals(RULE_ID, audit.getString("entries[0].ruleId"));
        assertEquals(USER_UUID, audit.getString("entries[0].changedBy"));
    }

    @Test
    @Order(3)
    void updateAgreement_withHeader_recordsUpdateWithFieldDiff() {
        given()
            .header("X-Requested-By", USER_UUID)
            .contentType("application/json")
            .body(Map.of(
                "name", "Audit Test Agreement v2",
                "description", "Created by ContractTypeAuditFlowTest",
                "active", true))
        .when()
            .put("/api/contract-types/" + CODE)
        .then()
            .statusCode(200);

        JsonPath audit = getAudit(100);
        assertEquals("AGREEMENT", audit.getString("entries[0].entityType"));
        assertEquals("UPDATE", audit.getString("entries[0].operation"));
        assertEquals(USER_UUID, audit.getString("entries[0].changedBy"));
        String summary = audit.getString("entries[0].summary");
        assertNotNull(summary);
        assertTrue(summary.contains("name:"), "summary should contain the name diff but was: " + summary);
    }

    @Test
    @Order(4)
    void updateAgreement_withoutHeader_recordsNullChangedBy() {
        given()
            .contentType("application/json")
            .body(Map.of(
                "name", "Audit Test Agreement v3",
                "description", "Created by ContractTypeAuditFlowTest",
                "active", true))
        .when()
            .put("/api/contract-types/" + CODE)
        .then()
            .statusCode(200);

        JsonPath audit = getAudit(100);
        assertEquals("UPDATE", audit.getString("entries[0].operation"));
        assertNull(audit.getString("entries[0].changedBy"),
                "X-Requested-By was absent, so changedBy must be null");
        assertNull(audit.getString("entries[0].changedByName"));
    }

    @Test
    @Order(5)
    void softDeletePricingRule_recordsDelete() {
        given()
            .header("X-Requested-By", USER_UUID)
        .when()
            .delete("/api/contract-types/" + CODE + "/rules/" + RULE_ID)
        .then()
            .statusCode(204);

        JsonPath audit = getAudit(100);
        assertEquals("PRICING_RULE", audit.getString("entries[0].entityType"));
        assertEquals("DELETE", audit.getString("entries[0].operation"));
        assertEquals(RULE_ID, audit.getString("entries[0].ruleId"));
        String summary = audit.getString("entries[0].summary");
        assertNotNull(summary);
        assertTrue(summary.contains("active: true -> false"),
                "soft-delete summary should record the active flip but was: " + summary);
    }

    @Test
    @Order(6)
    void softDeleteAgreement_recordsDelete() {
        given()
            .header("X-Requested-By", USER_UUID)
        .when()
            .delete("/api/contract-types/" + CODE)
        .then()
            .statusCode(204);

        JsonPath audit = getAudit(100);
        assertEquals("AGREEMENT", audit.getString("entries[0].entityType"));
        assertEquals("DELETE", audit.getString("entries[0].operation"));
        assertEquals(USER_UUID, audit.getString("entries[0].changedBy"));
    }

    @Test
    @Order(7)
    void activateAgreement_recordsRestore() {
        given()
            .header("X-Requested-By", USER_UUID)
            // bodyless POST: RestAssured would default to x-www-form-urlencoded, which the
            // resource's class-level @Consumes(APPLICATION_JSON) rejects with 415
            .contentType("application/json")
        .when()
            .post("/api/contract-types/" + CODE + "/activate")
        .then()
            .statusCode(204);

        JsonPath audit = getAudit(100);
        assertEquals("AGREEMENT", audit.getString("entries[0].entityType"));
        assertEquals("RESTORE", audit.getString("entries[0].operation"));
        assertEquals(USER_UUID, audit.getString("entries[0].changedBy"));
    }

    @Test
    @Order(8)
    void createValidationRule_recordsCreate() {
        given()
            .header("X-Requested-By", USER_UUID)
            .contentType("application/json")
            .body(Map.of(
                "ruleId", "audit-notes-required",
                "label", "Notes required (audit test)",
                "validationType", "NOTES_REQUIRED",
                "required", true,
                "priority", 10))
        .when()
            .post("/api/contract-types/" + CODE + "/validation-rules")
        .then()
            .statusCode(201);

        JsonPath audit = getAudit(100);
        assertEquals("VALIDATION_RULE", audit.getString("entries[0].entityType"));
        assertEquals("CREATE", audit.getString("entries[0].operation"));
        assertEquals("audit-notes-required", audit.getString("entries[0].ruleId"));
    }

    // --- Read endpoint: ordering + limit ---

    @Test
    @Order(9)
    void auditTrail_isNewestFirst_andLimitWorks() {
        JsonPath audit = getAudit(100);
        List<String> operations = audit.getList("entries.operation");
        assertEquals(List.of(
                "CREATE",   // validation rule (step 8)
                "RESTORE",  // agreement activate (step 7)
                "DELETE",   // agreement soft-delete (step 6)
                "DELETE",   // pricing rule soft-delete (step 5)
                "UPDATE",   // agreement update, no header (step 4)
                "UPDATE",   // agreement update (step 3)
                "CREATE",   // pricing rule (step 2)
                "CREATE"    // agreement (step 1)
        ), operations);

        List<Long> ids = audit.getList("entries.id", Long.class);
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1) > ids.get(i), "audit ids must be strictly descending (newest first)");
        }

        JsonPath limited = getAudit(3);
        assertEquals(3, limited.getList("entries").size());
        assertEquals(ids.subList(0, 3), limited.getList("entries.id", Long.class));
    }

    // --- Entity-level listener tests (rolled back; cover rule RESTORE without the C5 endpoints) ---

    @Test
    @TestTransaction
    void listener_recordsPricingRuleLifecycle_atEntityLevel() {
        String code = newTxAgreement(); // FK: pricing_rule_steps.contract_type_code -> contract_type_definitions.code

        PricingRuleStepEntity rule = new PricingRuleStepEntity();
        rule.setContractTypeCode(code);
        rule.setRuleId("tx-audit-rule");
        rule.setLabel("Tx rule");
        rule.setRuleStepType(RuleStepType.ADMIN_FEE_PERCENT);
        rule.setStepBase(StepBase.CURRENT_SUM);
        rule.setPercent(BigDecimal.valueOf(5.0));
        rule.setPriority(10);
        rule.persist();
        em.flush();

        rule.setLabel("Tx rule v2");
        em.flush();

        rule.softDelete();
        em.flush();

        rule.activate();
        em.flush();

        List<ContractTypeAudit> entries = ContractTypeAudit.findByContractTypeCode(code, 100);
        assertEquals(5, entries.size()); // oldest entry is the agreement CREATE from newTxAgreement()
        assertEquals(ContractTypeAudit.Operation.RESTORE, entries.get(0).getOperation());
        assertEquals(ContractTypeAudit.Operation.DELETE, entries.get(1).getOperation());
        assertEquals(ContractTypeAudit.Operation.UPDATE, entries.get(2).getOperation());
        assertEquals(ContractTypeAudit.Operation.CREATE, entries.get(3).getOperation());
        assertEquals(ContractTypeAudit.EntityType.AGREEMENT, entries.get(4).getEntityType());
        entries.subList(0, 4).forEach(entry -> {
            assertEquals(ContractTypeAudit.EntityType.PRICING_RULE, entry.getEntityType());
            assertEquals("tx-audit-rule", entry.getRuleId());
        });
        entries.forEach(entry ->
            assertNull(entry.getChangedBy(), "no X-Requested-By in this context, so changedBy must be null"));
        assertEquals("label: 'Tx rule' -> 'Tx rule v2'", entries.get(2).getSummary());
        assertEquals("active: false -> true", entries.get(0).getSummary());
    }

    @Test
    @TestTransaction
    void listener_recordsValidationRuleLifecycle_atEntityLevel() {
        String code = newTxAgreement();

        ContractValidationRuleEntity rule = new ContractValidationRuleEntity();
        rule.setContractTypeCode(code);
        rule.setRuleId("tx-audit-validation");
        rule.setLabel("Tx validation rule");
        rule.setValidationType(ValidationType.NOTES_REQUIRED);
        rule.setRequired(true);
        rule.setPriority(10);
        rule.persist();
        em.flush();

        rule.softDelete();
        em.flush();

        rule.activate();
        em.flush();

        List<ContractTypeAudit> entries = ContractTypeAudit.findByContractTypeCode(code, 100);
        assertEquals(4, entries.size()); // oldest entry is the agreement CREATE from newTxAgreement()
        assertEquals(ContractTypeAudit.Operation.RESTORE, entries.get(0).getOperation());
        assertEquals(ContractTypeAudit.Operation.DELETE, entries.get(1).getOperation());
        assertEquals(ContractTypeAudit.Operation.CREATE, entries.get(2).getOperation());
        entries.subList(0, 3).forEach(entry ->
            assertEquals(ContractTypeAudit.EntityType.VALIDATION_RULE, entry.getEntityType()));
        assertEquals(ContractTypeAudit.EntityType.AGREEMENT, entries.get(3).getEntityType());
    }

    // --- Helpers ---

    /**
     * Persist a throwaway agreement inside the current (rolled back) test transaction so
     * the rule tables' FK to contract_type_definitions.code is satisfied.
     */
    private String newTxAgreement() {
        String code = "AUDITTX_" + (System.nanoTime() % 100_000_000);
        ContractTypeDefinition agreement = new ContractTypeDefinition();
        agreement.setCode(code);
        agreement.setName("Tx Audit Agreement");
        agreement.setActive(true);
        agreement.persist();
        em.flush();
        return code;
    }

    private static JsonPath getAudit(int limit) {
        return given()
            .header("X-Requested-By", USER_UUID)
        .when()
            .get("/api/contract-types/" + CODE + "/audit?limit=" + limit)
        .then()
            .statusCode(200)
            .extract().jsonPath();
    }
}
