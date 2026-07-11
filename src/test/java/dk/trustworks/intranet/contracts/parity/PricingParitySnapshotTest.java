package dk.trustworks.intranet.contracts.parity;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.pricing.CalculationBreakdownLine;
import dk.trustworks.intranet.aggregates.invoice.pricing.PriceResult;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingRuleCatalog;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleSet;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStep;
import dk.trustworks.intranet.aggregates.invoice.pricing.RuleStepType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.CONTRACT_WITH_TRAPPERABAT_15;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.CONTRACT_WITH_TRAPPERABAT_2;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.CONTRACT_WITH_TRAPPERABAT_NULL;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.FixtureCatalog;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.HARDCODED_BY_CODE;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.HARDCODED_SKI0215_2025;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.PROD_AGREEMENTS;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.PROD_RULES;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.Rule;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.activeForced;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.prodRulesFor;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.retypedV396;
import static dk.trustworks.intranet.contracts.parity.ProdPricingFixtures.trapperabatContextFor;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parity snapshot suite from exact production fixtures (spec §12.2). This suite is THE GATE
 * for two destructive Phase 3 steps:
 *
 * <ol>
 *   <li><b>V396 retype</b> (spec §8.2 V_b): {@code ADMIN_FEE_PERCENT} rows become
 *       {@code PERCENT_DISCOUNT_ON_SUM + purpose='ADMIN_FEE'}. Money math, totals and
 *       synthetic-item values must be byte-identical before the migration may ship.</li>
 *   <li><b>§9.7 hardcoded-fallback removal</b>: the DB-seeded rules must reproduce the
 *       historical hardcoded catalog output byte-for-byte on every date where the legacy
 *       fallback could have fired, and the dates where behavior intentionally changes must
 *       be enumerated (see the SKI0215_2025 divergence-window tests).</li>
 * </ol>
 *
 * <p>Plain JUnit against the real {@link PricingEngine} + real {@link PricingRuleCatalog#select}
 * logic, with only the Panache DB load replaced by {@link FixtureCatalog} (same convention as
 * {@code InvoiceItemRecalculatorTest}). No database, no Quarkus boot — the suite exercises the
 * exact production pipeline an invoice draft runs through.</p>
 *
 * <p>The retype is byte-identical in BOTH money fields and rendered labels: the engine's
 * label formatting is purpose-aware — {@code PERCENT_DISCOUNT_ON_SUM} rows with
 * {@code purpose=ADMIN_FEE} render their stored label verbatim (exactly like the legacy
 * {@code ADMIN_FEE_PERCENT} branch), all other percentage deductions carry the
 * {@code " (<pct>%)"} suffix. Gated by
 * {@link #retype_v396_output_including_labels_is_byte_identical(Scenario)}.</p>
 */
class PricingParitySnapshotTest {

    private static final LocalDate MID = LocalDate.of(2025, 11, 15);
    private static final double[] AMOUNTS = {100_000.00, 33_333.33, 100.00};
    private static final double[] DISCOUNTS = {0.0, 10.0};

    /** Boundary-spanning invoice dates per prod agreement (before/on/after each validFrom/validTo). */
    private static final Map<String, List<LocalDate>> DATES = Map.of(
            // ski21525-admin windowed 2025-07-01 → 2026-01-01 (validTo exclusive)
            "SKI0215_2025", List.of(
                    LocalDate.of(2025, 6, 30), LocalDate.of(2025, 7, 1), MID,
                    LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)),
            // all rules dateless
            "SKI0217_2021", List.of(LocalDate.of(2025, 6, 30), MID, LocalDate.of(2026, 1, 1)),
            "SKI0217_2025", List.of(LocalDate.of(2025, 6, 30), MID, LocalDate.of(2026, 1, 1)),
            // tw-supplier-discount validFrom 2026-07-27
            "NOVO_MSP_2025", List.of(LocalDate.of(2026, 7, 26), LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28)));

    // ═════════════════════════════════ fixture sanity ═════════════════════════════════

    @Test
    void prod_fixture_encoding_sanity() {
        Set<String> agreementCodes = PROD_AGREEMENTS.stream()
                .map(ProdPricingFixtures.Agreement::code).collect(Collectors.toSet());
        List<Rule> adminRows = PROD_RULES.stream()
                .filter(r -> r.type() == RuleStepType.ADMIN_FEE_PERCENT).toList();
        assertAll("fixture must mirror the 2026-07-10 prod extract exactly",
                () -> assertEquals(8, PROD_AGREEMENTS.size(), "contract_type_definitions rows"),
                () -> assertEquals(11, PROD_RULES.size(), "pricing_rule_steps rows"),
                () -> assertTrue(PROD_RULES.stream().allMatch(r -> agreementCodes.contains(r.code())),
                        "every rule row references a defined agreement code"),
                () -> assertEquals(3, adminRows.size(), "exactly 3 ADMIN_FEE_PERCENT rows in prod"),
                () -> assertEquals(Set.of("ski21525-admin", "ski21721-admin", "ski21725-admin"),
                        adminRows.stream().map(Rule::ruleId).collect(Collectors.toSet())),
                () -> assertEquals(2, PROD_RULES.stream().filter(r -> !r.active()).count(),
                        "the two deactivated GENERAL placement rows"),
                () -> assertEquals(2, PROD_RULES.stream().filter(r -> "trapperabat".equals(r.paramKey())).count()),
                () -> assertEquals("2", ProdPricingFixtures.PROD_TRAPPERABAT_BY_CONTRACT.get(CONTRACT_WITH_TRAPPERABAT_2)),
                () -> assertEquals("15", ProdPricingFixtures.PROD_TRAPPERABAT_BY_CONTRACT.get(CONTRACT_WITH_TRAPPERABAT_15)),
                () -> assertEquals(4, HARDCODED_BY_CODE.get("SKI0217_2021").size()),
                () -> assertEquals(3, HARDCODED_BY_CODE.get("SKI0217_2025").size()),
                () -> assertEquals(2, HARDCODED_BY_CODE.get("SKI0215_2025").size()));
    }

    // ═══════════════════════ 2a. RETYPE PARITY (gate for V396) ════════════════════════

    static Stream<Scenario> retypeScenarios() {
        List<Scenario> out = new ArrayList<>();
        for (String code : List.of("SKI0215_2025", "SKI0217_2021", "SKI0217_2025", "NOVO_MSP_2025")) {
            // For the trapperabat codes, run the retype comparison across all three
            // param-resolution variants (value 2, value 15, and no contract context) —
            // the retype only touches percent-based rows, so this pins the orthogonality
            // instead of arguing it.
            List<Map<String, String>> contexts = code.startsWith("SKI0217")
                    ? List.of(Map.<String, String>of(),
                              trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_2),
                              trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_15))
                    : List.of(Map.<String, String>of());
            for (LocalDate date : DATES.get(code)) {
                for (double amount : AMOUNTS) {
                    for (double discount : DISCOUNTS) {
                        for (Map<String, String> ctx : contexts) {
                            out.add(new Scenario(code, prodRulesFor(code), date, amount, discount, ctx));
                        }
                    }
                }
            }
        }
        return out.stream();
    }

    /**
     * V396 in-memory: the same prod rows priced as {@code ADMIN_FEE_PERCENT} vs as
     * {@code PERCENT_DISCOUNT_ON_SUM + purpose='ADMIN_FEE'} must produce byte-identical
     * money output — every breakdown line (ruleId, base, rate, delta, cumulative), every
     * synthetic invoice item (hours, rate, position, origin, ruleId) and all four totals.
     * Labels are asserted separately (see the two label tests below).
     */
    @ParameterizedTest
    @MethodSource("retypeScenarios")
    void retype_v396_money_output_is_byte_identical(Scenario sc) {
        PriceResult before = price(sc.rules(), sc);
        PriceResult after = price(retypedV396(sc.rules()), sc);
        assertEquals(canon(before, false), canon(after, false), "retype must be money-neutral — " + sc);
    }

    /**
     * FULL byte-identity across the retype, labels included (spec §12.2 / §8.2 "provably
     * output-neutral"): every breakdown line and synthetic invoice item — ruleId, label,
     * itemname, base, rate, delta, cumulative — plus all four totals must be identical.
     * This holds because the engine's label formatting is purpose-aware: retyped rows
     * carry {@code purpose=ADMIN_FEE} and render their stored label verbatim, exactly
     * like the legacy {@code ADMIN_FEE_PERCENT} branch did.
     */
    @ParameterizedTest
    @MethodSource("retypeScenarios")
    void retype_v396_output_including_labels_is_byte_identical(Scenario sc) {
        PriceResult before = price(sc.rules(), sc);
        PriceResult after = price(retypedV396(sc.rules()), sc);
        assertEquals(canon(before, true), canon(after, true),
                "retype must be fully output-neutral, labels included — " + sc);
    }

    /**
     * Pins the exact rendered label of a retyped admin-fee line: verbatim, no
     * {@code " (<pct>%)"} suffix — the purpose-aware formatting that keeps V396
     * label-neutral. Regressing the engine to unconditional suffixing fails here first.
     */
    @Test
    void v396_retyped_admin_fee_lines_keep_their_stored_label_verbatim() {
        Scenario sc = new Scenario("SKI0215_2025", prodRulesFor("SKI0215_2025"), MID, 100_000.00, 0.0, Map.of());
        PriceResult after = price(retypedV396(sc.rules()), sc);
        assertAll("purpose-aware label rendering of the retyped SKI0215_2025 admin fee",
                () -> assertEquals("4% SKI administrationsgebyr", after.breakdown.get(0).label),
                () -> assertEquals("4% SKI administrationsgebyr", after.syntheticItems.get(0).getItemname(),
                        "the verbatim label is persisted on the synthetic invoice line"));
    }

    // ══════════════ 2b. FALLBACK PARITY (gate for §9.7 hardcoded removal) ══════════════

    static Stream<Scenario> fallbackParityScenarios() {
        List<Scenario> out = new ArrayList<>();
        Map<String, List<LocalDate>> parityDates = Map.of(
                "SKI0217_2021", DATES.get("SKI0217_2021"),
                "SKI0217_2025", DATES.get("SKI0217_2025"),
                // hardcoded SKI0215_2025 admin has no validity window, the DB row does —
                // parity can only hold inside the DB window; out-of-window dates are covered
                // by the divergence-window test below.
                "SKI0215_2025", List.of(LocalDate.of(2025, 7, 1), MID, LocalDate.of(2025, 12, 31)));
        for (String code : List.of("SKI0217_2021", "SKI0217_2025", "SKI0215_2025")) {
            List<Map<String, String>> contexts = code.startsWith("SKI0217")
                    ? List.of(Map.of(),
                              trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_2),
                              trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_15))
                    : List.of(Map.<String, String>of());
            for (LocalDate date : parityDates.get(code)) {
                for (double amount : AMOUNTS) {
                    for (double discount : DISCOUNTS) {
                        for (Map<String, String> ctx : contexts) {
                            out.add(new Scenario(code, activeForced(prodRulesFor(code)), date, amount, discount, ctx));
                        }
                    }
                }
            }
        }
        return out.stream();
    }

    /**
     * With the legacy codes' DB rules ACTIVE (the V98-seed state), the DB-sourced engine
     * output must equal the historical hardcoded-catalog output byte-for-byte — including
     * labels. The expected side runs against the frozen constants in
     * {@link ProdPricingFixtures} (copied from {@code PricingRuleCatalog} before §9.7 deletes
     * them), so this gate keeps working after the deletion. Also proves BigDecimal-scale
     * insensitivity: DB stores "4.0000"/"2000.00", the hardcoded constants used "4"/"2000".
     */
    @ParameterizedTest
    @MethodSource("fallbackParityScenarios")
    void legacy_db_seeded_rules_reproduce_the_hardcoded_catalog_byte_for_byte(Scenario sc) {
        PriceResult db = price(sc.rules(), sc);
        PriceResult hardcoded = price(HARDCODED_BY_CODE.get(sc.code()), sc);
        assertEquals(canon(hardcoded, true), canon(db, true),
                "DB-seeded rules must reproduce the historical hardcoded output — " + sc);
    }

    static Stream<Scenario> ski0215DivergenceScenarios() {
        List<Scenario> out = new ArrayList<>();
        for (LocalDate date : List.of(LocalDate.of(2025, 6, 30), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))) {
            for (double amount : AMOUNTS) {
                out.add(new Scenario("SKI0215_2025", activeForced(prodRulesFor("SKI0215_2025")), date, amount, 0.0, Map.of()));
            }
        }
        return out.stream();
    }

    /**
     * <b>Enumerated §9.7 divergence window (spec §14 risk 3).</b> The DB admin-fee row is
     * windowed 2025-07-01 → 2026-01-01 (exclusive); the historical hardcoded rule was
     * dateless. Outside the window the two regimes differ by exactly the 4% admin fee —
     * nothing else. After §9.7 removal, "no active rules on the date" honestly means
     * "no deductions"; the hardcoded set would have kept charging the fee forever.
     */
    @ParameterizedTest
    @MethodSource("ski0215DivergenceScenarios")
    void ski0215_2025_outside_the_admin_window_differs_from_hardcoded_by_exactly_the_fee(Scenario sc) {
        PriceResult db = price(sc.rules(), sc);
        PriceResult hardcoded = price(HARDCODED_SKI0215_2025, sc);
        assertAll("divergence window — " + sc,
                () -> assertEquals(0, db.breakdown.size(), "DB regime: admin fee out of window ⇒ no deduction"),
                () -> assertEquals(1, hardcoded.breakdown.size(), "hardcoded regime always charged the fee"),
                () -> assertEquals("ski21525-admin", hardcoded.breakdown.get(0).ruleId),
                () -> assertEquals(0, db.sumBeforeDiscounts.compareTo(db.sumAfterDiscounts),
                        "DB regime leaves the sum untouched"),
                () -> assertEquals(0, hardcoded.breakdown.get(0).delta
                                .compareTo(hardcoded.sumAfterDiscounts.subtract(db.sumAfterDiscounts)),
                        "the entire difference is the 4% admin-fee delta"));
    }

    /**
     * Documents the pre-§9.7 resurrection quirk with the REAL prod active flags: on
     * 2026-01-01 the SKI0215_2025 admin row is date-expired and the GENERAL row is inactive
     * ⇒ zero active-on-date DB rows ⇒ {@code PricingRuleCatalog.select} silently resurrects
     * the invisible hardcoded set and keeps charging the 4% fee. Auto-skips once §9.7
     * deletes the hardcoded fallback (this test then has nothing left to document).
     */
    @Test
    void ski0215_2025_prod_flags_resurrect_the_hardcoded_set_until_fallback_removal() {
        Assumptions.assumeTrue(hardcodedFallbackPresent(),
                "§9.7 landed — the hardcoded fallback (and with it the resurrection quirk) is gone.");
        Scenario sc = new Scenario("SKI0215_2025", prodRulesFor("SKI0215_2025"),
                LocalDate.of(2026, 1, 1), 100_000.00, 0.0, Map.of());
        PriceResult resurrected = price(sc.rules(), sc);   // zero DB rows on the date → legacy takeover
        PriceResult hardcoded = price(HARDCODED_SKI0215_2025, sc);
        assertAll("hardcoded resurrection on 2026-01-01",
                () -> assertEquals(canon(hardcoded, true), canon(resurrected, true),
                        "output equals the hardcoded set, fee included"),
                () -> assertMoney("96000.00", resurrected.sumAfterDiscounts,
                        "4% fee still charged although the DB row expired 2026-01-01 (exclusive)"));
    }

    // ═══════════ 2c. GENERAL fallback — invoice.discount injection at 9000 ═════════════

    static Stream<String> codesWithoutPricingRules() {
        return Stream.of("PERIOD", "SKI0217_2025_V2", "SKI0215_2025_V2", "DAGROFA2026");
    }

    /**
     * Agreements with no pricing rules at all (4 of the 8 prod codes) still apply
     * {@code invoice.discount} through the system-injected {@code general-fallback} step at
     * priority 9000 — the injection that stays after §9.7 (pinned contract P3).
     */
    @ParameterizedTest
    @MethodSource("codesWithoutPricingRules")
    void codes_without_rules_apply_invoice_discount_via_injected_fallback_at_9000(String code) {
        RuleSet rs = new FixtureCatalog(PROD_RULES).select(code, MID);
        assertEquals(1, rs.steps.size(), code + ": only the injected fallback step");
        RuleStep fallback = rs.steps.get(0);
        assertAll("injected fallback shape — " + code,
                () -> assertEquals("general-fallback", fallback.id),
                () -> assertEquals(9000, fallback.priority),
                () -> assertEquals(RuleStepType.GENERAL_DISCOUNT_PERCENT, fallback.type));

        Scenario with = new Scenario(code, PROD_RULES, MID, 100_000.00, 10.0, Map.of());
        PriceResult discounted = price(PROD_RULES, with);
        Scenario without = new Scenario(code, PROD_RULES, MID, 100_000.00, 0.0, Map.of());
        PriceResult plain = price(PROD_RULES, without);
        assertAll("PERIOD-style discount flow — " + code,
                () -> assertEquals(1, discounted.breakdown.size()),
                () -> assertEquals("general-fallback", discounted.breakdown.get(0).ruleId),
                () -> assertEquals("Generel rabat (10%)", discounted.breakdown.get(0).label),
                () -> assertMoney("-10000.00", discounted.breakdown.get(0).delta, "10% of 100000"),
                () -> assertMoney("90000.00", discounted.sumAfterDiscounts, "total before VAT"),
                () -> assertMoney("22500.00", discounted.vatAmount, "25% VAT"),
                () -> assertMoney("112500.00", discounted.grandTotal, "grand total"),
                () -> assertEquals(0, plain.breakdown.size(), "discount 0 ⇒ zero-effect ⇒ omitted"),
                () -> assertMoney("100000.00", plain.sumAfterDiscounts, "untouched sum"));
    }

    /**
     * With the REAL prod flags the deactivated GENERAL placement rows (SKI0215_2025 /
     * SKI0217_2025, both switched off 2025-12-17) do NOT kill the invoice discount: the
     * catalog injects the system fallback at 9000 instead, which runs AFTER the agreement's
     * own rules. Golden values are hand-computed (scale 2, HALF_UP).
     */
    @Test
    void prod_flags_invoice_discount_flows_through_the_injected_fallback_after_own_rules() {
        Scenario ski0215 = new Scenario("SKI0215_2025", prodRulesFor("SKI0215_2025"), MID, 100_000.00, 10.0, Map.of());
        PriceResult a = price(ski0215.rules(), ski0215);
        assertAll("SKI0215_2025 @ 2025-11-15, 100000, discount 10",
                () -> assertEquals(List.of("ski21525-admin", "general-fallback"), ruleIds(a)),
                () -> assertMoney("-4000.00", a.breakdown.get(0).delta, "4% admin fee first"),
                () -> assertMoney("-9600.00", a.breakdown.get(1).delta, "10% of the REMAINING 96000"),
                () -> assertMoney("86400.00", a.sumAfterDiscounts, "total before VAT"),
                () -> assertMoney("21600.00", a.vatAmount, "25% VAT"),
                () -> assertMoney("108000.00", a.grandTotal, "grand total"));

        Scenario ski0217 = new Scenario("SKI0217_2025", prodRulesFor("SKI0217_2025"), MID, 100_000.00, 10.0,
                trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_15));
        PriceResult b = price(ski0217.rules(), ski0217);
        assertAll("SKI0217_2025 @ 2025-11-15, 100000, trapperabat 15, discount 10",
                () -> assertEquals(List.of("ski21725-key", "ski21725-admin", "general-fallback"), ruleIds(b)),
                () -> assertMoney("-15000.00", b.breakdown.get(0).delta, "trapperabat 15% of SUM_BEFORE"),
                () -> assertMoney("-3400.00", b.breakdown.get(1).delta, "4% admin on CURRENT 85000"),
                () -> assertMoney("-8160.00", b.breakdown.get(2).delta, "10% invoice discount on CURRENT 81600"),
                () -> assertMoney("73440.00", b.sumAfterDiscounts, "total before VAT"),
                () -> assertMoney("91800.00", b.grandTotal, "grand total incl. 25% VAT"));
    }

    // ════════════════ 3. trapperabat param resolution (contract_type_items) ═══════════

    static Stream<org.junit.jupiter.params.provider.Arguments> trapperabatGoldens() {
        return Stream.of(
                // code, contractUuid, expected key delta, expected total before VAT
                org.junit.jupiter.params.provider.Arguments.of("SKI0217_2021", CONTRACT_WITH_TRAPPERABAT_2, "2", "-2000.00", "94040.00"),
                org.junit.jupiter.params.provider.Arguments.of("SKI0217_2021", CONTRACT_WITH_TRAPPERABAT_15, "15", "-15000.00", "81300.00"),
                org.junit.jupiter.params.provider.Arguments.of("SKI0217_2025", CONTRACT_WITH_TRAPPERABAT_2, "2", "-2000.00", "94080.00"),
                org.junit.jupiter.params.provider.Arguments.of("SKI0217_2025", CONTRACT_WITH_TRAPPERABAT_15, "15", "-15000.00", "81600.00"));
    }

    /**
     * Snapshot of paramKey resolution with the two prod trapperabat extremes (values 2 and
     * 15, {@code contract_type_items} rows id 4 and id 1). Golden totals hand-computed:
     * SKI0217_2021 chains key → 2% admin on CURRENT → 2000 fixed fee; SKI0217_2025 chains
     * key → 4% admin on CURRENT (GENERAL row inactive in prod, discount 0 ⇒ no line).
     * Also pins two engine quirks: the resolved percent lands in the label
     * ({@code "SKI trapperabat (2%)"}), while {@code rateOrAmount} stays null for
     * paramKey-resolved rows ({@code step.percent} is NULL in the DB).
     */
    @ParameterizedTest
    @MethodSource("trapperabatGoldens")
    void trapperabat_param_resolution_snapshot(String code, String contractUuid, String pct,
                                               String expectedKeyDelta, String expectedTotal) {
        Map<String, String> ctx = trapperabatContextFor(contractUuid);
        assertEquals(pct, ctx.get("trapperabat"), "fixture context for contract " + contractUuid);
        Scenario sc = new Scenario(code, prodRulesFor(code), MID, 100_000.00, 0.0, ctx);
        PriceResult pr = price(sc.rules(), sc);
        CalculationBreakdownLine key = pr.breakdown.get(0);
        assertAll("trapperabat " + pct + " on " + code,
                () -> assertEquals("SKI trapperabat (" + pct + "%)", key.label),
                () -> assertMoney(expectedKeyDelta, key.delta, "key discount on SUM_BEFORE_DISCOUNTS"),
                () -> assertNull(key.rateOrAmount,
                        "paramKey-resolved rows carry no rateOrAmount (step.percent is NULL in prod)"),
                () -> assertMoney(expectedTotal, pr.sumAfterDiscounts, "total before VAT"));
    }

    /**
     * The NULL-value prod row ({@code contract_type_items} id 19) and a missing row both
     * resolve to 0% — zero delta, step silently omitted, only the admin fee remains.
     */
    @Test
    void trapperabat_null_or_missing_value_resolves_to_zero_and_emits_no_key_line() {
        for (Map<String, String> ctx : List.of(
                trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_NULL),   // {"trapperabat": null}
                trapperabatContextFor("no-such-contract"))) {            // {}
            Scenario sc = new Scenario("SKI0217_2025", prodRulesFor("SKI0217_2025"), MID, 100_000.00, 0.0, ctx);
            PriceResult pr = price(sc.rules(), sc);
            assertAll("zero-effect trapperabat, ctx=" + ctx,
                    () -> assertEquals(List.of("ski21725-admin"), ruleIds(pr), "key step omitted at 0%"),
                    () -> assertMoney("96000.00", pr.sumAfterDiscounts, "only the 4% admin fee applies"));
        }
    }

    // ═══════════════════════ rule-boundary golden snapshots ═══════════════════════════

    /**
     * NOVO_MSP_2025 validFrom boundary (tw-supplier-discount starts 2026-07-27, inclusive).
     * Golden values hand-computed at 100000 / discount 0 / VAT 25.
     */
    @Test
    void novo_supplier_discount_validfrom_boundary_snapshot() {
        List<Rule> rules = prodRulesFor("NOVO_MSP_2025");

        PriceResult before = price(rules, new Scenario("NOVO_MSP_2025", rules, LocalDate.of(2026, 7, 26), 100_000.00, 0.0, Map.of()));
        assertAll("2026-07-26 — day before validFrom: only the 1.8% MSP fee",
                () -> assertEquals(List.of("msp-fee"), ruleIds(before)),
                () -> assertEquals("MSP fee (1.8%)", before.breakdown.get(0).label),
                () -> assertMoney("-1800.00", before.breakdown.get(0).delta, "1.8% of 100000"),
                () -> assertMoney("98200.00", before.sumAfterDiscounts, "total before VAT"),
                () -> assertMoney("122750.00", before.grandTotal, "grand total"));

        for (LocalDate date : List.of(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 7, 28))) {
            PriceResult on = price(rules, new Scenario("NOVO_MSP_2025", rules, date, 100_000.00, 0.0, Map.of()));
            assertAll(date + " — on/after validFrom: 5% supplier discount, then MSP fee on the remainder",
                    () -> assertEquals(List.of("tw-supplier-discount", "msp-fee"), ruleIds(on)),
                    () -> assertEquals("Commercial Adjustment (-5%) (5%)", on.breakdown.get(0).label,
                            "engine suffixes the resolved percent onto the stored label"),
                    () -> assertMoney("-5000.00", on.breakdown.get(0).delta, "5% of SUM_BEFORE_DISCOUNTS"),
                    () -> assertMoney("-1710.00", on.breakdown.get(1).delta, "1.8% of CURRENT 95000"),
                    () -> assertMoney("93290.00", on.sumAfterDiscounts, "total before VAT: 95000 − 1710"),
                    () -> assertMoney("116612.50", on.grandTotal, "grand total incl. 25% VAT"));
        }
    }

    /**
     * SKI0215_2025 admin-fee window, {@code validTo} EXCLUSIVE: charged 2025-07-01 through
     * 2025-12-31, NOT on 2026-01-01. Runs with the rows active-forced so the (pre-§9.7)
     * hardcoded resurrection can never mask the boundary — with ≥ 1 active-on-date row the
     * catalog never falls back, before or after the removal.
     */
    @Test
    void ski0215_2025_admin_fee_validto_exclusive_boundary_snapshot() {
        List<Rule> rules = activeForced(prodRulesFor("SKI0215_2025"));
        Map<LocalDate, String> expectedTotal = new LinkedHashMap<>();
        expectedTotal.put(LocalDate.of(2025, 6, 30), "100000.00"); // before validFrom
        expectedTotal.put(LocalDate.of(2025, 7, 1), "96000.00");   // validFrom inclusive
        expectedTotal.put(LocalDate.of(2025, 12, 31), "96000.00"); // last charged day
        expectedTotal.put(LocalDate.of(2026, 1, 1), "100000.00");  // validTo EXCLUSIVE — fee gone
        expectedTotal.put(LocalDate.of(2026, 1, 2), "100000.00");  // after validTo

        expectedTotal.forEach((date, total) -> {
            PriceResult pr = price(rules, new Scenario("SKI0215_2025", rules, date, 100_000.00, 0.0, Map.of()));
            assertMoney(total, pr.sumAfterDiscounts, "SKI0215_2025 @ " + date);
            boolean feeExpected = "96000.00".equals(total);
            assertEquals(feeExpected ? List.of("ski21525-admin") : List.of(), ruleIds(pr),
                    "admin-fee presence @ " + date);
        });
    }

    /** Chained deductions may drive the running total negative — the engine clamps at 0 and VAT applies after the clamp. */
    @Test
    void clamp_at_zero_snapshot_small_invoice_with_fixed_fee() {
        Scenario sc = new Scenario("SKI0217_2021", prodRulesFor("SKI0217_2021"), MID, 100.00, 0.0,
                trapperabatContextFor(CONTRACT_WITH_TRAPPERABAT_2));
        PriceResult pr = price(sc.rules(), sc);
        assertAll("SKI0217_2021 on a 100 kr invoice (trapperabat 2)",
                () -> assertEquals(List.of("ski21721-key", "ski21721-admin", "ski21721-fee"), ruleIds(pr)),
                () -> assertMoney("-1903.96", pr.breakdown.get(2).cumulative,
                        "running total goes negative: 100 − 2 − 1.96 − 2000"),
                () -> assertMoney("0", pr.sumAfterDiscounts, "clamped at zero"),
                () -> assertMoney("0.00", pr.vatAmount, "VAT applies to the clamped total"),
                () -> assertMoney("0.00", pr.grandTotal, "grand total"));
    }

    /** Spec §12.3 P3 acceptance anchor + fraction/small amounts, SKI0215_2025 with real prod flags. */
    @Test
    void ski0215_2025_acceptance_goldens_at_2025_11_15() {
        record Golden(double amount, String total, String vat, String grand) {}
        for (Golden g : List.of(
                new Golden(100_000.00, "96000.00", "24000.00", "120000.00"),
                new Golden(33_333.33, "32000.00", "8000.00", "40000.00"),
                new Golden(100.00, "96.00", "24.00", "120.00"))) {
            Scenario sc = new Scenario("SKI0215_2025", prodRulesFor("SKI0215_2025"), MID, g.amount(), 0.0, Map.of());
            PriceResult pr = price(sc.rules(), sc);
            assertAll("SKI0215_2025 @ 2025-11-15, amount " + g.amount(),
                    () -> assertMoney(g.total(), pr.sumAfterDiscounts, "total before VAT"),
                    () -> assertMoney(g.vat(), pr.vatAmount, "25% VAT"),
                    () -> assertMoney(g.grand(), pr.grandTotal, "grand total"));
        }
    }

    // ═══════════════════════════════ test infrastructure ══════════════════════════════

    record Scenario(String code, List<Rule> rules, LocalDate date, double amount, double discount,
                    Map<String, String> ctx) {
        @Override
        public String toString() {
            return code + " @ " + date + " amount=" + amount + " discount=" + discount + " ctx=" + ctx;
        }
    }

    private static PriceResult price(List<Rule> rules, Scenario sc) {
        PricingEngine engine = engine(new FixtureCatalog(rules));
        return engine.price(draft(sc.code(), sc.date(), sc.amount(), sc.discount()), sc.ctx());
    }

    private static PricingEngine engine(PricingRuleCatalog catalog) {
        PricingEngine engine = new PricingEngine();
        inject(engine, "catalog", catalog);
        inject(engine, "registry", new SimpleMeterRegistry());
        return engine;
    }

    /** Synthetic in-memory draft, same shape the preview endpoint builds (spec §9.1): one BASE line rate=amount × hours=1, VAT 25. */
    private static Invoice draft(String code, LocalDate date, double amount, double discount) {
        Invoice inv = new Invoice();
        inv.setUuid(UUID.randomUUID().toString());
        inv.contractType = code;
        inv.setContractuuid(UUID.randomUUID().toString());
        inv.setInvoicedate(date);
        inv.setVat(25.0);
        inv.setDiscount(discount);
        inv.invoiceitems = new ArrayList<>();
        InvoiceItem base = new InvoiceItem();
        base.setUuid(UUID.randomUUID().toString());
        base.setItemname("Consultant");
        base.setRate(amount);
        base.setHours(1.0);
        base.setPosition(1);
        base.setOrigin(InvoiceItemOrigin.BASE);
        base.setInvoiceuuid(inv.getUuid());
        inv.invoiceitems.add(base);
        return inv;
    }

    /**
     * Canonical, order-preserving serialization of everything the engine emits — totals,
     * breakdown lines and synthetic invoice items. Random per-run identifiers (item uuid,
     * calculationRef, invoiceuuid) are excluded; {@code rateOrAmount} is scale-normalized
     * (DB "4.0000" vs hardcoded "4") because BigDecimal scale is presentation, not money.
     * All money fields come out of the engine at scale 2 and are compared verbatim.
     */
    private static String canon(PriceResult pr, boolean includeLabels) {
        StringBuilder sb = new StringBuilder();
        sb.append("sumBefore=").append(pr.sumBeforeDiscounts.toPlainString()).append('\n');
        sb.append("sumAfter=").append(pr.sumAfterDiscounts.stripTrailingZeros().toPlainString()).append('\n');
        sb.append("vat=").append(pr.vatAmount.toPlainString()).append('\n');
        sb.append("grand=").append(pr.grandTotal.toPlainString()).append('\n');
        int i = 0;
        for (CalculationBreakdownLine l : pr.breakdown) {
            sb.append("step[").append(i++).append("]=").append(l.ruleId)
                    .append('|').append(includeLabels ? l.label : "·")
                    .append("|base=").append(l.base.toPlainString())
                    .append("|rate=").append(l.rateOrAmount == null ? "-" : l.rateOrAmount.stripTrailingZeros().toPlainString())
                    .append("|delta=").append(l.delta.toPlainString())
                    .append("|cum=").append(l.cumulative.toPlainString())
                    .append('\n');
        }
        i = 0;
        for (InvoiceItem it : pr.syntheticItems) {
            sb.append("syn[").append(i++).append("]=").append(it.getRuleId())
                    .append('|').append(includeLabels ? it.getItemname() : "·")
                    .append('|').append(includeLabels ? it.getLabel() : "·")
                    .append("|hours=").append(it.getHours())
                    .append("|rate=").append(it.getRate())
                    .append("|pos=").append(it.getPosition())
                    .append("|origin=").append(it.getOrigin())
                    .append('\n');
        }
        return sb.toString();
    }

    private static List<String> ruleIds(PriceResult pr) {
        return pr.breakdown.stream().map(l -> l.ruleId).toList();
    }

    /** Scale-agnostic money assertion (the clamp path returns BigDecimal.ZERO at scale 0). */
    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + " — expected " + expected + " but was " + actual.toPlainString());
    }

    private static boolean hardcodedFallbackPresent() {
        try {
            var f = PricingRuleCatalog.class.getDeclaredField("rulesByType");
            f.setAccessible(true);
            Map<?, ?> m = (Map<?, ?>) f.get(new PricingRuleCatalog());
            return m != null && !m.isEmpty();
        } catch (ReflectiveOperationException e) {
            return false; // field gone ⇒ §9.7 landed
        }
    }

    private static void inject(Object target, String fieldName, Object value) {
        Class<?> cls = target.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to inject " + fieldName, e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
