package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupKey;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview.ConsultantDelta;
import dk.trustworks.intranet.aggregates.invoice.dto.SettlementGroupPreview.IssuerDelta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure delta math for a settlement group (no DB, no CDI). Folds per-(issuer, consultant)
 * target and settled amounts into deltas (delta = target - settled), rolls up to issuer and
 * group totals. Signed throughout, so credit-note phantoms (negative target) and reversed
 * internals (settled drops) flow through as negative / re-opened deltas. Deterministic
 * ordering (issuer uuid, then consultant uuid) for stable output.
 */
public final class SettlementDeltaCalculator {

    private SettlementDeltaCalculator() {}

    public record TargetLine(String issuerCompanyUuid, String consultantUuid, BigDecimal amount) {}
    public record SettledLine(String issuerCompanyUuid, String consultantUuid, BigDecimal amount) {}

    private static String k(String issuer, String consultant) { return issuer + "" + consultant; }
    private static BigDecimal scale(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }

    public static SettlementGroupPreview compute(
            SettlementGroupKey key,
            String debtorCompanyUuid,
            List<TargetLine> targets,
            List<SettledLine> settled,
            Map<String, String> consultantNames,
            Map<String, String> companyNames,
            boolean allResolved) {

        // Fold target + settled per (issuer, consultant). Preserve first-seen order.
        Map<String, String[]> ic = new LinkedHashMap<>();               // composite -> [issuer, consultant]
        Map<String, BigDecimal> tgt = new LinkedHashMap<>();
        Map<String, BigDecimal> set = new LinkedHashMap<>();
        for (TargetLine t : targets) {
            String ck = k(t.issuerCompanyUuid(), t.consultantUuid());
            ic.putIfAbsent(ck, new String[]{t.issuerCompanyUuid(), t.consultantUuid()});
            tgt.merge(ck, t.amount(), BigDecimal::add);
        }
        for (SettledLine s : settled) {
            String ck = k(s.issuerCompanyUuid(), s.consultantUuid());
            ic.putIfAbsent(ck, new String[]{s.issuerCompanyUuid(), s.consultantUuid()});
            set.merge(ck, s.amount(), BigDecimal::add);
        }

        // Group ConsultantDeltas by issuer.
        Map<String, List<ConsultantDelta>> byIssuer = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> e : ic.entrySet()) {
            String issuer = e.getValue()[0];
            String consultant = e.getValue()[1];
            BigDecimal cT = scale(tgt.getOrDefault(e.getKey(), BigDecimal.ZERO));
            BigDecimal cS = scale(set.getOrDefault(e.getKey(), BigDecimal.ZERO));
            byIssuer.computeIfAbsent(issuer, x -> new ArrayList<>()).add(new ConsultantDelta(
                    consultant, consultantNames.getOrDefault(consultant, consultant), cT, cS, scale(cT.subtract(cS))));
        }

        List<IssuerDelta> issuers = new ArrayList<>();
        BigDecimal totT = BigDecimal.ZERO, totS = BigDecimal.ZERO;
        List<String> issuerOrder = new ArrayList<>(byIssuer.keySet());
        issuerOrder.sort(Comparator.naturalOrder());
        for (String issuer : issuerOrder) {
            List<ConsultantDelta> cons = byIssuer.get(issuer);
            cons.sort(Comparator.comparing(ConsultantDelta::consultantUuid));
            BigDecimal iT = cons.stream().map(ConsultantDelta::target).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal iS = cons.stream().map(ConsultantDelta::settled).reduce(BigDecimal.ZERO, BigDecimal::add);
            issuers.add(new IssuerDelta(issuer, companyNames.getOrDefault(issuer, issuer), cons,
                    scale(iT), scale(iS), scale(iT.subtract(iS))));
            totT = totT.add(iT);
            totS = totS.add(iS);
        }

        return new SettlementGroupPreview(key, debtorCompanyUuid,
                companyNames.getOrDefault(debtorCompanyUuid, debtorCompanyUuid),
                issuers, scale(totT), scale(totS), scale(totT.subtract(totS)), allResolved);
    }
}
