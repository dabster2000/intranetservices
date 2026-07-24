package dk.trustworks.intranet.recruitmentservice.reporting;

import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.AdoptionRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.GdprTiles;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.HireRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.InterviewerLoadRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.ReferralLeaderboardRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.SourceMixRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.StageMoveRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.TerminalRow;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse.TimeInStageRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read side of the P20 reporting projection: GROUP-BY queries over
 * {@code recruitment_fact_monthly}, folded into the one-call
 * {@link ReportsResponse} bundle.
 * <p>
 * No query in this class selects {@code position_uuid} — per-position
 * breakdowns are deliberately absent from v1, which keeps partner-track
 * data k-safe by construction (plan §P20; locked by
 * {@code RecruitmentReportsApiTest}).
 */
@ApplicationScoped
public class RecruitmentReportingReadService {

    @Inject
    EntityManager em;

    public ReportsResponse reports(YearMonth from, YearMonth to, long watermark, long streamHead) {
        LocalDate f = from.atDay(1);
        LocalDate t = to.atDay(1);

        boolean empty = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM recruitment_fact_monthly").getSingleResult()).longValue() == 0;

        return new ReportsResponse(
                from.toString(),
                to.toString(),
                empty,
                watermark,
                streamHead,
                sourceMix(f, t),
                hires(f, t),
                funnel(f, t),
                terminals(f, t),
                timeInStage(f, t),
                interviewerLoad(f, t),
                referralLeaderboard(f, t),
                gdpr(f, t),
                adoption(f, t));
    }

    // ------------------------------------------------------------------
    // Widget queries
    // ------------------------------------------------------------------

    private List<SourceMixRow> sourceMix(LocalDate f, LocalDate t) {
        Map<String, long[]> byKey = new LinkedHashMap<>(); // "month|source" -> [candidates, applications]
        for (Object[] row : rows("""
                SELECT DATE_FORMAT(month, '%Y-%m'), source, fact, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact IN ('CANDIDATE_CREATED', 'APPLICATION_CREATED') AND month BETWEEN :f AND :t
                GROUP BY 1, 2, 3 ORDER BY 1, 2
                """, f, t)) {
            long[] counts = byKey.computeIfAbsent(row[0] + "|" + row[1], k -> new long[2]);
            counts["CANDIDATE_CREATED".equals(row[2]) ? 0 : 1] += ((Number) row[3]).longValue();
        }
        List<SourceMixRow> result = new ArrayList<>();
        byKey.forEach((key, counts) -> {
            String[] parts = key.split("\\|", -1);
            result.add(new SourceMixRow(parts[0], parts[1], counts[0], counts[1]));
        });
        return result;
    }

    private List<HireRow> hires(LocalDate f, LocalDate t) {
        List<HireRow> result = new ArrayList<>();
        for (Object[] row : rows("""
                SELECT DATE_FORMAT(month, '%Y-%m'), source, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact = 'HIRED' AND month BETWEEN :f AND :t
                GROUP BY 1, 2 ORDER BY 1, 2
                """, f, t)) {
            result.add(new HireRow((String) row[0], (String) row[1], ((Number) row[2]).longValue()));
        }
        return result;
    }

    private List<StageMoveRow> funnel(LocalDate f, LocalDate t) {
        List<StageMoveRow> result = new ArrayList<>();
        for (Object[] row : rows("""
                SELECT stage_from, stage_to, outcome, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact = 'STAGE_MOVED' AND month BETWEEN :f AND :t
                GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """, f, t)) {
            result.add(new StageMoveRow((String) row[0], (String) row[1], (String) row[2],
                    ((Number) row[3]).longValue()));
        }
        return result;
    }

    private List<TerminalRow> terminals(LocalDate f, LocalDate t) {
        List<TerminalRow> result = new ArrayList<>();
        for (Object[] row : rows("""
                SELECT stage_from, outcome, detail, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact = 'TERMINAL' AND month BETWEEN :f AND :t
                GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """, f, t)) {
            result.add(new TerminalRow((String) row[0], (String) row[1], (String) row[2],
                    ((Number) row[3]).longValue()));
        }
        return result;
    }

    private List<TimeInStageRow> timeInStage(LocalDate f, LocalDate t) {
        List<TimeInStageRow> result = new ArrayList<>();
        for (Object[] row : rows("""
                SELECT stage_from, SUM(sum_days), SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact IN ('STAGE_MOVED', 'TERMINAL') AND stage_from <> '' AND month BETWEEN :f AND :t
                GROUP BY 1 ORDER BY 1
                """, f, t)) {
            result.add(new TimeInStageRow((String) row[0],
                    ((Number) row[1]).doubleValue(), ((Number) row[2]).longValue()));
        }
        return result;
    }

    private List<InterviewerLoadRow> interviewerLoad(LocalDate f, LocalDate t) {
        List<InterviewerLoadRow> result = new ArrayList<>();
        for (Object[] row : rows("""
                SELECT person_uuid, DATE_FORMAT(month, '%Y-%m'), SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact = 'SCORECARD_SUBMITTED' AND person_uuid <> '' AND month BETWEEN :f AND :t
                GROUP BY 1, 2 ORDER BY 1, 2
                """, f, t)) {
            result.add(new InterviewerLoadRow((String) row[0], (String) row[1],
                    ((Number) row[2]).longValue()));
        }
        return result;
    }

    private List<ReferralLeaderboardRow> referralLeaderboard(LocalDate f, LocalDate t) {
        Map<String, long[]> byUser = new LinkedHashMap<>(); // uuid -> [submitted, converted, hired]
        for (Object[] row : rows("""
                SELECT person_uuid, fact, outcome, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact IN ('REFERRAL_SUBMITTED', 'REFERRAL_TRIAGED', 'HIRED')
                  AND person_uuid <> '' AND month BETWEEN :f AND :t
                GROUP BY 1, 2, 3
                """, f, t)) {
            long[] counts = byUser.computeIfAbsent((String) row[0], k -> new long[3]);
            long cnt = ((Number) row[3]).longValue();
            switch ((String) row[1]) {
                case "REFERRAL_SUBMITTED" -> counts[0] += cnt;
                case "REFERRAL_TRIAGED" -> {
                    if ("CANDIDATE_CREATED".equals(row[2])) {
                        counts[1] += cnt;
                    }
                }
                case "HIRED" -> counts[2] += cnt;
                default -> {
                }
            }
        }
        return byUser.entrySet().stream()
                .map(e -> new ReferralLeaderboardRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
                .sorted(Comparator.comparingLong(ReferralLeaderboardRow::submitted).reversed())
                .toList();
    }

    private GdprTiles gdpr(LocalDate f, LocalDate t) {
        long art14 = 0;
        long granted = 0;
        long withdrawn = 0;
        long expired = 0;
        long anonAuto = 0;
        long anonRequest = 0;
        long dsarIn = 0;
        long dsarOut = 0;
        for (Object[] row : rows("""
                SELECT fact, outcome, SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact IN ('ART14_NOTICE_SENT', 'CONSENT_GRANTED', 'CONSENT_WITHDRAWN', 'CONSENT_EXPIRED',
                               'ANONYMIZED', 'DSAR_RECEIVED', 'DSAR_EXPORTED')
                  AND month BETWEEN :f AND :t
                GROUP BY 1, 2
                """, f, t)) {
            long cnt = ((Number) row[2]).longValue();
            switch ((String) row[0]) {
                case "ART14_NOTICE_SENT" -> art14 += cnt;
                case "CONSENT_GRANTED" -> granted += cnt;
                case "CONSENT_WITHDRAWN" -> withdrawn += cnt;
                case "CONSENT_EXPIRED" -> expired += cnt;
                case "ANONYMIZED" -> {
                    if ("ON_REQUEST".equals(row[1])) {
                        anonRequest += cnt;
                    } else {
                        anonAuto += cnt;
                    }
                }
                case "DSAR_RECEIVED" -> dsarIn += cnt;
                case "DSAR_EXPORTED" -> dsarOut += cnt;
                default -> {
                }
            }
        }
        return new GdprTiles(art14, granted, withdrawn, expired, anonAuto, anonRequest, dsarIn, dsarOut);
    }

    private List<AdoptionRow> adoption(LocalDate f, LocalDate t) {
        Map<String, long[]> byKey = new LinkedHashMap<>(); // "month|action" -> [web, slack]
        for (Object[] row : rows("""
                SELECT DATE_FORMAT(month, '%Y-%m'), fact,
                       CASE WHEN fact = 'REFERRAL_TRIAGED' THEN detail ELSE outcome END AS origin,
                       SUM(cnt)
                FROM recruitment_fact_monthly
                WHERE fact IN ('SCORECARD_SUBMITTED', 'REFERRAL_SUBMITTED', 'REFERRAL_TRIAGED')
                  AND month BETWEEN :f AND :t
                GROUP BY 1, 2, 3 ORDER BY 1, 2
                """, f, t)) {
            String action = switch ((String) row[1]) {
                case "SCORECARD_SUBMITTED" -> "SCORECARD";
                case "REFERRAL_SUBMITTED" -> "REFERRAL";
                default -> "TRIAGE";
            };
            long[] counts = byKey.computeIfAbsent(row[0] + "|" + action, k -> new long[2]);
            counts["slack".equals(row[2]) ? 1 : 0] += ((Number) row[3]).longValue();
        }
        List<AdoptionRow> result = new ArrayList<>();
        byKey.forEach((key, counts) -> {
            String[] parts = key.split("\\|", -1);
            result.add(new AdoptionRow(parts[0], parts[1], counts[0], counts[1]));
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql, LocalDate f, LocalDate t) {
        return em.createNativeQuery(sql)
                .setParameter("f", f)
                .setParameter("t", t)
                .getResultList();
    }
}
