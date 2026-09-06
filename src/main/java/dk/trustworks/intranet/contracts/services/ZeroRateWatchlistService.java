package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ZeroRateWatchlistDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The zero-rate step-up watchlist (JK Team 2.0 WP4b, spec §4.4.3): every declared 0 kr
 * consultant line whose review date falls within the window — or has already passed while
 * the line is still 0 kr — with the value given away so far.
 *
 * <p>"A date with nothing watching it is not a deadline." Surfaced on the Team Dashboard
 * (WP6), Junior Talent (WP7) and the account manager's own page (WP8); this one query feeds
 * all three.
 */
@JBossLog
@ApplicationScoped
public class ZeroRateWatchlistService {

    @Inject
    EntityManager em;

    /**
     * @param today      the reference day
     * @param withinDays lines whose review date is on or before {@code today + withinDays};
     *                   overdue lines are always included
     * @param onlyStudents restrict to lines whose junior is a {@code STUDENT} on {@code today}
     */
    public List<ZeroRateWatchlistDTO> watchlist(LocalDate today, int withinDays, boolean onlyStudents) {
        LocalDate cutoff = today.plusDays(Math.max(0, withinDays));
        String sql = """
            SELECT cc.uuid, cc.contractuuid, c.name AS contract_name,
                   c.clientuuid, cl.name AS client_name,
                   c.leaduuid, CONCAT(am.firstname, ' ', am.lastname) AS am_name,
                   cc.useruuid, CONCAT(u.firstname, ' ', u.lastname) AS junior_name,
                   cc.activefrom, cc.activeto, cc.rate_review_date, cc.zero_rate_reason,
                   cc.pricing_model_code, cc.list_rate,
                   COALESCE((SELECT SUM(w.workduration) FROM work w
                             WHERE w.useruuid = cc.useruuid
                               AND w.contractuuid = cc.contractuuid
                               AND w.registered BETWEEN cc.activefrom AND cc.activeto), 0) AS hours_registered,
                   (SELECT us.type FROM userstatus us
                     WHERE us.useruuid = cc.useruuid AND us.statusdate <= :today
                     ORDER BY us.statusdate DESC LIMIT 1) AS consultant_type
            FROM contract_consultants cc
            JOIN contracts c ON c.uuid = cc.contractuuid
            LEFT JOIN client cl ON cl.uuid = c.clientuuid
            LEFT JOIN user am ON am.uuid = c.leaduuid
            LEFT JOIN user u ON u.uuid = cc.useruuid
            WHERE cc.rate = 0
              AND cc.zero_rate_reason IS NOT NULL
              AND cc.rate_review_date IS NOT NULL
              AND cc.rate_review_date <= :cutoff
              AND cc.activeto >= :today
              AND c.status NOT IN ('INACTIVE', 'CLOSED')
            ORDER BY cc.rate_review_date, junior_name
            """;
        Query query = em.createNativeQuery(sql);
        query.setParameter("today", today);
        query.setParameter("cutoff", cutoff);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ZeroRateWatchlistDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String consultantType = r[16] == null ? null : r[16].toString();
            if (onlyStudents && !"STUDENT".equals(consultantType)) {
                continue;
            }
            LocalDate reviewDate = toLocalDate(r[11]);
            double listRate = toDouble(r[14]);
            double hours = toDouble(r[15]);
            out.add(new ZeroRateWatchlistDTO(
                    (String) r[0], (String) r[1], (String) r[2],
                    (String) r[3], (String) r[4],
                    (String) r[5], (String) r[6],
                    (String) r[7], (String) r[8],
                    toLocalDate(r[9]), toLocalDate(r[10]),
                    reviewDate,
                    daysRemaining(today, reviewDate),
                    isOverdue(today, reviewDate),
                    (String) r[12], (String) r[13],
                    listRate, hours, valueGivenAway(hours, listRate)));
        }
        return out;
    }

    /** Days until the review date; negative once it has passed. */
    public static long daysRemaining(LocalDate today, LocalDate reviewDate) {
        if (today == null || reviewDate == null) return 0;
        return ChronoUnit.DAYS.between(today, reviewDate);
    }

    public static boolean isOverdue(LocalDate today, LocalDate reviewDate) {
        return today != null && reviewDate != null && reviewDate.isBefore(today);
    }

    /** Registered hours × list rate, rounded to øre. Never revenue. */
    public static double valueGivenAway(double hoursRegistered, double listRate) {
        if (hoursRegistered <= 0 || listRate <= 0) return 0.0;
        return Math.round(hoursRegistered * listRate * 100.0) / 100.0;
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof BigDecimal b) return b.doubleValue();
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }
}
