package dk.trustworks.intranet.contracts.parity;

import dk.trustworks.intranet.aggregates.invoice.pricing.PricingRuleCatalog;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStep;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import dk.trustworks.intranet.aggregates.invoice.pricing.StepBase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType.ADMIN_FEE_PERCENT;
import static dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType.FIXED_DEDUCTION;
import static dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType.GENERAL_DISCOUNT_PERCENT;
import static dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType.PERCENT_DISCOUNT_ON_SUM;
import static dk.trustworks.intranet.aggregates.invoice.pricing.StepBase.CURRENT_SUM;
import static dk.trustworks.intranet.aggregates.invoice.pricing.StepBase.SUM_BEFORE_DISCOUNTS;

/**
 * Exact production pricing fixtures for the Phase 3 parity snapshot suite (spec §12.2 —
 * the gate for the V396 retype migration and the §9.7 hardcoded-fallback removal).
 *
 * <p>Source of truth: read-only extract of twservices4 production taken 2026-07-10T20:04:06Z
 * ({@code prod-pricing-fixtures.txt}): all 8 {@code contract_type_definitions} rows, all 11
 * {@code pricing_rule_steps} rows (exact percents, amounts, param keys, validity windows,
 * priorities and active flags), and the {@code contract_type_items} trapperabat rows.
 * Values are transcribed verbatim — do not "clean up" scales or labels; byte-parity of engine
 * output depends on them.</p>
 *
 * <p>Also encodes the <b>historical hardcoded rule sets</b> copied verbatim from
 * {@code PricingRuleCatalog}'s constructor as of 2026-07-10 (branch
 * {@code feat/framework-agreements-phase3}), BEFORE the §9.7 change deletes them. These
 * constants are the frozen "expected" side of the fallback-parity comparison and must never
 * be regenerated from the (soon rule-free) catalog.</p>
 */
final class ProdPricingFixtures {

    private ProdPricingFixtures() {}

    // ── row model ──────────────────────────────────────────────────────────────

    /**
     * One {@code pricing_rule_steps} row (or one historical hardcoded rule).
     * {@code percent}/{@code amount} are kept as the exact DB strings so BigDecimal scale
     * is preserved ("4.0000" from the DB vs "4" from the old hardcoded constants).
     * {@code purpose} models the V395 column and maps onto {@link RuleStep#purpose}
     * (see {@link #toRuleStep(Rule)}) — the engine reads it for purpose-aware label
     * formatting (ADMIN_FEE labels render verbatim), never for money math.
     */
    record Rule(int dbId, String code, String ruleId, String label, RuleStepType type, StepBase base,
                String percent, String amount, String paramKey,
                LocalDate validFrom, LocalDate validTo, int priority, boolean active, String purpose) {

        Rule withActive(boolean newActive) {
            return new Rule(dbId, code, ruleId, label, type, base, percent, amount, paramKey,
                    validFrom, validTo, priority, newActive, purpose);
        }

        /**
         * The V396 retype, applied in-memory (pinned contract P2 / spec §8.2 V_b):
         * <pre>
         * UPDATE pricing_rule_steps SET purpose='ADMIN_FEE', rule_step_type='PERCENT_DISCOUNT_ON_SUM'
         *   WHERE rule_step_type='ADMIN_FEE_PERCENT';
         * UPDATE pricing_rule_steps SET purpose='DISCOUNT'
         *   WHERE rule_step_type='PERCENT_DISCOUNT_ON_SUM' AND purpose IS NULL;
         * </pre>
         */
        Rule retypedV396() {
            if (type == ADMIN_FEE_PERCENT) {
                return new Rule(dbId, code, ruleId, label, PERCENT_DISCOUNT_ON_SUM, base, percent, amount,
                        paramKey, validFrom, validTo, priority, active, "ADMIN_FEE");
            }
            if (type == PERCENT_DISCOUNT_ON_SUM && purpose == null) {
                return new Rule(dbId, code, ruleId, label, type, base, percent, amount,
                        paramKey, validFrom, validTo, priority, active, "DISCOUNT");
            }
            return this;
        }
    }

    /** One {@code contract_type_definitions} row (engine-irrelevant fields omitted). */
    record Agreement(int dbId, String code, String name, boolean active,
                     LocalDate validFrom, LocalDate validUntil) {}

    // ── contract_type_definitions (8 rows, verbatim) ───────────────────────────

    static final List<Agreement> PROD_AGREEMENTS = List.of(
            new Agreement(1, "PERIOD", "Standard T&M", true, null, null),
            new Agreement(2, "SKI0217_2021", "SKI 02.17 - 2021", false, null, LocalDate.of(2025, 9, 30)),
            new Agreement(3, "SKI0217_2025", "SKI 02.17 - 2025", true, LocalDate.of(2025, 7, 1), LocalDate.of(2025, 9, 30)),
            new Agreement(4, "SKI0215_2025", "SKI 02.15 - 2025", true, LocalDate.of(2025, 7, 1), LocalDate.of(2025, 12, 31)),
            new Agreement(5, "SKI0217_2025_V2", "SKI 02.17 - 2025 - V2", true, LocalDate.of(2025, 7, 1), null),
            new Agreement(7, "NOVO_MSP_2025", "Novo MSP Fee 2025", true, null, null),
            new Agreement(8, "SKI0215_2025_V2", "SKI 02.15 - 2025 - V2", false, LocalDate.of(2025, 7, 1), null),
            new Agreement(9, "DAGROFA2026", "Dagrofa Framework Agreement 2026", true, LocalDate.of(2026, 1, 1), null));

    // ── pricing_rule_steps (11 rows, verbatim incl. active flags and windows) ──

    static final List<Rule> PROD_RULES = List.of(
            // NOVO_MSP_2025
            new Rule(11, "NOVO_MSP_2025", "tw-supplier-discount", "Commercial Adjustment (-5%)",
                    PERCENT_DISCOUNT_ON_SUM, SUM_BEFORE_DISCOUNTS, "5.0000", null, null,
                    LocalDate.of(2026, 7, 27), null, 10, true, null),
            new Rule(10, "NOVO_MSP_2025", "msp-fee", "MSP fee",
                    PERCENT_DISCOUNT_ON_SUM, CURRENT_SUM, "1.8000", null, null,
                    null, null, 41, true, null),
            // SKI0215_2025 — admin fee windowed 2025-07-01 → 2026-01-01 (validTo EXCLUSIVE);
            // the GENERAL placement row was deactivated in prod on 2025-12-17.
            new Rule(8, "SKI0215_2025", "ski21525-admin", "4% SKI administrationsgebyr",
                    ADMIN_FEE_PERCENT, CURRENT_SUM, "4.0000", null, null,
                    LocalDate.of(2025, 7, 1), LocalDate.of(2026, 1, 1), 20, true, null),
            new Rule(9, "SKI0215_2025", "ski21525-general", "Generel rabat",
                    GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null,
                    null, null, 40, false, null),
            // SKI0217_2021 — all four rows active in prod
            new Rule(1, "SKI0217_2021", "ski21721-key", "SKI trapperabat",
                    PERCENT_DISCOUNT_ON_SUM, SUM_BEFORE_DISCOUNTS, null, null, "trapperabat",
                    null, null, 10, true, null),
            new Rule(2, "SKI0217_2021", "ski21721-admin", "2% SKI administrationsgebyr",
                    ADMIN_FEE_PERCENT, CURRENT_SUM, "2.0000", null, null,
                    null, null, 20, true, null),
            new Rule(3, "SKI0217_2021", "ski21721-fee", "Faktureringsgebyr",
                    FIXED_DEDUCTION, CURRENT_SUM, null, "2000.00", null,
                    null, null, 30, true, null),
            new Rule(4, "SKI0217_2021", "ski21721-general", "Generel rabat",
                    GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null,
                    null, null, 40, true, null),
            // SKI0217_2025 — GENERAL placement row deactivated in prod on 2025-12-17
            new Rule(5, "SKI0217_2025", "ski21725-key", "SKI trapperabat",
                    PERCENT_DISCOUNT_ON_SUM, SUM_BEFORE_DISCOUNTS, null, null, "trapperabat",
                    null, null, 10, true, null),
            new Rule(6, "SKI0217_2025", "ski21725-admin", "4% SKI administrationsgebyr",
                    ADMIN_FEE_PERCENT, CURRENT_SUM, "4.0000", null, null,
                    null, null, 20, true, null),
            new Rule(7, "SKI0217_2025", "ski21725-general", "Generel rabat",
                    GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null,
                    null, null, 40, false, null));

    // ── historical hardcoded catalog (frozen copy of PricingRuleCatalog, 2026-07-10) ──
    //
    // Copied from the PricingRuleCatalog constructor BEFORE §9.7 deletes it. Note the
    // BigDecimal scales differ from the DB rows on purpose: the hardcoded constants used
    // BigDecimal.valueOf(2) ("2"), the V98-seeded DB rows store "2.0000". The engine's
    // money math (multiply → divide(…, 4, HALF_UP) → setScale(2, HALF_UP)) is scale-input
    // insensitive, which is exactly what the fallback-parity tests prove.

    static final List<Rule> HARDCODED_SKI0217_2021 = List.of(
            hardcoded("SKI0217_2021", "ski21721-key", "SKI trapperabat", PERCENT_DISCOUNT_ON_SUM, SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", 10),
            hardcoded("SKI0217_2021", "ski21721-admin", "2% SKI administrationsgebyr", ADMIN_FEE_PERCENT, CURRENT_SUM, "2", null, null, 20),
            hardcoded("SKI0217_2021", "ski21721-fee", "Faktureringsgebyr", FIXED_DEDUCTION, CURRENT_SUM, null, "2000", null, 30),
            hardcoded("SKI0217_2021", "ski21721-general", "Generel rabat", GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null, 40));

    static final List<Rule> HARDCODED_SKI0217_2025 = List.of(
            hardcoded("SKI0217_2025", "ski21725-key", "SKI trapperabat", PERCENT_DISCOUNT_ON_SUM, SUM_BEFORE_DISCOUNTS, null, null, "trapperabat", 10),
            hardcoded("SKI0217_2025", "ski21725-admin", "4% SKI administrationsgebyr", ADMIN_FEE_PERCENT, CURRENT_SUM, "4", null, null, 20),
            hardcoded("SKI0217_2025", "ski21725-general", "Generel rabat", GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null, 40));

    static final List<Rule> HARDCODED_SKI0215_2025 = List.of(
            hardcoded("SKI0215_2025", "ski21525-admin", "4% SKI administrationsgebyr", ADMIN_FEE_PERCENT, CURRENT_SUM, "4", null, null, 20),
            hardcoded("SKI0215_2025", "ski21525-general", "Generel rabat", GENERAL_DISCOUNT_PERCENT, CURRENT_SUM, null, null, null, 40));

    static final Map<String, List<Rule>> HARDCODED_BY_CODE = Map.of(
            "SKI0217_2021", HARDCODED_SKI0217_2021,
            "SKI0217_2025", HARDCODED_SKI0217_2025,
            "SKI0215_2025", HARDCODED_SKI0215_2025);

    private static Rule hardcoded(String code, String ruleId, String label, RuleStepType type, StepBase base,
                                  String percent, String amount, String paramKey, int priority) {
        return new Rule(0, code, ruleId, label, type, base, percent, amount, paramKey, null, null, priority, true, null);
    }

    // ── contract_type_items (trapperabat, verbatim; NULL contractuuid rows 9/10/20 omitted) ──

    static final Map<String, String> PROD_TRAPPERABAT_BY_CONTRACT = new HashMap<>();
    static {
        PROD_TRAPPERABAT_BY_CONTRACT.put("5453d1f4-7a01-4465-81aa-035792a37e48", "15"); // id 1
        PROD_TRAPPERABAT_BY_CONTRACT.put("0d96a1f4-b4b3-4c84-9ea3-94ed5094fdbc", "15"); // id 2
        PROD_TRAPPERABAT_BY_CONTRACT.put("e587c5e6-32c9-4859-bd48-1d9cc794038a", "8");  // id 3
        PROD_TRAPPERABAT_BY_CONTRACT.put("3160d36b-2b9f-48c0-99a5-4a40519a4d87", "2");  // id 4
        PROD_TRAPPERABAT_BY_CONTRACT.put("3b823b2d-bc90-4239-b9a3-ffd2de2e1a4d", "8");  // id 5
        PROD_TRAPPERABAT_BY_CONTRACT.put("317a6f0c-a75e-46ff-beed-5b35344dece7", "8");  // id 6
        PROD_TRAPPERABAT_BY_CONTRACT.put("983bb8ad-bd04-48f3-bb89-eef488749189", "15"); // id 7
        PROD_TRAPPERABAT_BY_CONTRACT.put("13768bd8-e718-4983-940f-29d9a6e9f165", "2");  // id 8
        PROD_TRAPPERABAT_BY_CONTRACT.put("03be9a64-5f44-44b2-b453-2832268d91ff", "11"); // id 11
        PROD_TRAPPERABAT_BY_CONTRACT.put("2474b0a3-e773-48b7-9315-3da5dcf66e31", "8");  // id 12
        PROD_TRAPPERABAT_BY_CONTRACT.put("f9c0d7ca-8ee9-4e1e-afb9-923fbd509003", "8");  // id 13
        PROD_TRAPPERABAT_BY_CONTRACT.put("00470020-2264-479b-805f-b56864372e9a", "8");  // id 14
        PROD_TRAPPERABAT_BY_CONTRACT.put("edce0206-a985-4449-832e-ab365613c28f", "11"); // id 15
        PROD_TRAPPERABAT_BY_CONTRACT.put("fefe842b-44c0-420c-b4f5-a4539157b916", "5");  // id 16
        PROD_TRAPPERABAT_BY_CONTRACT.put("f44f7997-d31d-4ccf-b219-2b4d4496f1b5", "10"); // id 17
        PROD_TRAPPERABAT_BY_CONTRACT.put("4d612816-68b1-4633-81db-0d051007f9fd", "5");  // id 18
        PROD_TRAPPERABAT_BY_CONTRACT.put("90265fbe-ea7d-4778-8567-1e2d0a33fcb3", null); // id 19 — NULL value
    }

    /** Contract uuid whose prod {@code contract_type_items.trapperabat} = 2 (row id 4). */
    static final String CONTRACT_WITH_TRAPPERABAT_2 = "3160d36b-2b9f-48c0-99a5-4a40519a4d87";
    /** Contract uuid whose prod {@code contract_type_items.trapperabat} = 15 (row id 1). */
    static final String CONTRACT_WITH_TRAPPERABAT_15 = "5453d1f4-7a01-4465-81aa-035792a37e48";
    /** Contract uuid whose prod {@code contract_type_items.trapperabat} value is NULL (row id 19). */
    static final String CONTRACT_WITH_TRAPPERABAT_NULL = "90265fbe-ea7d-4778-8567-1e2d0a33fcb3";

    /** The {@code contractTypeItems} map the recalculator/preview would load for a contract. */
    static Map<String, String> trapperabatContextFor(String contractUuid) {
        Map<String, String> ctx = new HashMap<>();
        if (PROD_TRAPPERABAT_BY_CONTRACT.containsKey(contractUuid)) {
            ctx.put("trapperabat", PROD_TRAPPERABAT_BY_CONTRACT.get(contractUuid)); // value may be NULL (row id 19)
        }
        return ctx;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    static List<Rule> prodRulesFor(String code) {
        return PROD_RULES.stream().filter(r -> r.code().equals(code)).toList();
    }

    /** Same rows with {@code active} forced true — the V98-seed state ("DB rules ACTIVE"). */
    static List<Rule> activeForced(List<Rule> rows) {
        return rows.stream().map(r -> r.withActive(true)).toList();
    }

    /** The full V396 retype applied to a rule list (in-memory equivalent of the migration). */
    static List<Rule> retypedV396(List<Rule> rows) {
        return rows.stream().map(Rule::retypedV396).toList();
    }

    static RuleStep toRuleStep(Rule r) {
        RuleStep s = new RuleStep();
        s.id = r.ruleId();
        s.label = r.label();
        s.type = r.type();
        s.base = r.base();
        s.percent = r.percent() == null ? null : new BigDecimal(r.percent());
        s.amount = r.amount() == null ? null : new BigDecimal(r.amount());
        s.paramKey = r.paramKey();
        s.validFrom = r.validFrom();
        s.validTo = r.validTo();
        s.priority = r.priority();
        // RuleStep.purpose drives the engine's purpose-aware label formatting (ADMIN_FEE
        // labels render verbatim) — populating it here is what makes the retype-parity
        // tests exercise the exact production code path.
        s.purpose = r.purpose() == null ? null
                : dk.trustworks.intranet.aggregates.invoice.pricing.RulePurpose.valueOf(r.purpose());
        return s;
    }

    // ── catalog stub ───────────────────────────────────────────────────────────

    /**
     * {@link PricingRuleCatalog} with the Panache DB load replaced by fixture rows.
     * {@link #loadFromDatabaseByCode} reproduces {@code PricingRuleStepEntity.findByContractTypeAndDate}
     * exactly: {@code active = true AND (validFrom IS NULL OR validFrom <= date) AND
     * (validTo IS NULL OR validTo > date) ORDER BY priority} (id tie-break for determinism,
     * matching spec §9.8). Everything else — the general-discount fallback injection at
     * priority 9000 and (until §9.7 removes it) the hardcoded legacy takeover on zero rows —
     * is the REAL production {@code select()} logic.
     */
    static final class FixtureCatalog extends PricingRuleCatalog {
        private final List<Rule> rows;

        FixtureCatalog(List<Rule> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        protected List<RuleStep> loadFromDatabaseByCode(String contractTypeCode, LocalDate invoiceDate) {
            List<RuleStep> steps = new ArrayList<>();
            rows.stream()
                    .filter(r -> r.code().equals(contractTypeCode))
                    .filter(Rule::active)
                    .filter(r -> r.validFrom() == null || !r.validFrom().isAfter(invoiceDate))
                    .filter(r -> r.validTo() == null || r.validTo().isAfter(invoiceDate))
                    .sorted(Comparator.comparingInt(Rule::priority).thenComparingInt(Rule::dbId))
                    .forEach(r -> steps.add(toRuleStep(r)));
            return steps;
        }
    }
}
