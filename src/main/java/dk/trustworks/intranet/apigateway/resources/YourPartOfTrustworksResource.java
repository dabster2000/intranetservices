package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.apigateway.dto.EmployeeBonusBasisDTO;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "bonus")
@Path("/bonus/yourpartoftrustworks")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class YourPartOfTrustworksResource {

    @Inject
    EntityManager em;

    // --------- OLD ENDPOINT (kept for backward compatibility) ----------
    // Now computed from day-based data: month boolean = (eligibleShare > 0)
    @GET
    public List<EmployeeBonusEligibility> findByFiscalStartYear(@QueryParam("fiscalstartyear") int year) {
        LocalDate startDate = LocalDate.of(year, 7, 1);
        LocalDate endExclusive = startDate.plusYears(1);

        List<BiDataPerDay> days = BiDataPerDay.list(
                "documentDate >= ?1 and documentDate < ?2 and consultantType in (?3, ?4, ?5)",
                startDate, endExclusive, ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT
        );

        Map<String, List<BiDataPerDay>> byUser = days.stream()
                .collect(Collectors.groupingBy(d -> d.user.getUuid()));

        List<EmployeeBonusEligibility> result = new ArrayList<>();
        for (Map.Entry<String, List<BiDataPerDay>> e : byUser.entrySet()) {
            User u = e.getValue().getFirst().user;

            boolean jul = eligibleShare(e.getValue(), year, 7)  > 0.0;
            boolean aug = eligibleShare(e.getValue(), year, 8)  > 0.0;
            boolean sep = eligibleShare(e.getValue(), year, 9)  > 0.0;
            boolean oct = eligibleShare(e.getValue(), year, 10) > 0.0;
            boolean nov = eligibleShare(e.getValue(), year, 11) > 0.0;
            boolean dec = eligibleShare(e.getValue(), year, 12) > 0.0;
            boolean jan = eligibleShare(e.getValue(), year+1, 1) > 0.0;
            boolean feb = eligibleShare(e.getValue(), year+1, 2) > 0.0;
            boolean mar = eligibleShare(e.getValue(), year+1, 3) > 0.0;
            boolean apr = eligibleShare(e.getValue(), year+1, 4) > 0.0;
            boolean may = eligibleShare(e.getValue(), year+1, 5) > 0.0;
            boolean jun = eligibleShare(e.getValue(), year+1, 6) > 0.0;

            EmployeeBonusEligibility row = new EmployeeBonusEligibility(u, year, true,
                    jul, aug, sep, oct, nov, dec, jan, feb, mar, apr, may, jun);

            // (Existing rule preserved) Gate if AMJ all false
            if (!apr && !may && !jun) {
                row.setBonusEligible(false);
            }
            result.add(row);
        }
        return result;
    }

    private static double eligibleShare(List<BiDataPerDay> days, int y, int m) {
        YearMonth ym = YearMonth.of(y, m);
        int dim = ym.lengthOfMonth();
        long eligible = days.stream()
                .filter(d -> d.getYear() != null && d.getMonth() != null)
                .filter(d -> d.getYear() == y && d.getMonth() == m)
                .filter(YourPartOfTrustworksResource::isEligibleDay)
                .map(d -> 1L)
                .reduce(0L, Long::sum);
        return dim == 0 ? 0.0 : (eligible / (double) dim);
    }

    private static boolean isEligibleDay(BiDataPerDay d) {
        StatusType s = d.statusType;
        return d.isTwBonusEligible()
                && s != StatusType.PREBOARDING
                && s != StatusType.TERMINATED
                && s != StatusType.NON_PAY_LEAVE;
    }

    // --------- NEW ENDPOINT: view-based monthly eligibility & salary basis ----------
    @GET
    @Path("/basis")
    public List<EmployeeBonusBasisDTO> findMonthlyBasis(@QueryParam("fiscalstartyear") int year,
                                                        @QueryParam("companyuuid") String companyUuid) {
        String sql = """
            SELECT m.useruuid, m.companyuuid, m.year, m.month,
                   m.eligible_share, m.avg_salary, m.weighted_avg_salary
            FROM fact_tw_bonus_monthly m
            WHERE m.fiscal_year = :fiscalYear
            """;

        if (companyUuid != null && !companyUuid.isBlank()) {
            sql += " AND m.companyuuid = :companyUuid";
        }

        // Exclude employees terminated for entire fiscal year
        sql += """
             AND EXISTS (
                SELECT 1 FROM fact_tw_bonus_monthly m2
                WHERE m2.useruuid = m.useruuid
                  AND m2.fiscal_year = :fiscalYear
                  AND m2.eligible_days > 0
             )
            """;

        sql += " ORDER BY m.useruuid, m.year, m.month";

        Query query = em.createNativeQuery(sql)
                .setParameter("fiscalYear", year);

        if (companyUuid != null && !companyUuid.isBlank()) {
            query.setParameter("companyUuid", companyUuid);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        // Group by user
        Map<String, List<Object[]>> byUser = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String uuid = (String) row[0];
            byUser.computeIfAbsent(uuid, k -> new ArrayList<>()).add(row);
        }

        // Get user details
        Set<String> userUuids = byUser.keySet();
        Map<String, User> userMap = new HashMap<>();
        if (!userUuids.isEmpty()) {
            List<User> users = User.list("uuid in ?1", new ArrayList<>(userUuids));
            for (User u : users) {
                userMap.put(u.getUuid(), u);
            }
        }

        // Build DTOs
        List<EmployeeBonusBasisDTO> out = new ArrayList<>();

        // Define fiscal year month order: Jul(7)..Dec(12) of 'year', then Jan(1)..Jun(6) of 'year+1'
        int[][] fyMonths = {
            {year, 7}, {year, 8}, {year, 9}, {year, 10}, {year, 11}, {year, 12},
            {year+1, 1}, {year+1, 2}, {year+1, 3}, {year+1, 4}, {year+1, 5}, {year+1, 6}
        };

        for (Map.Entry<String, List<Object[]>> entry : byUser.entrySet()) {
            String userUuid = entry.getKey();
            User user = userMap.get(userUuid);
            if (user == null) continue;

            // Index view rows by (year, month) key — aggregate across companies if no company filter
            Map<String, double[]> monthData = new HashMap<>();
            for (Object[] row : entry.getValue()) {
                int calYear = ((Number) row[2]).intValue();
                int calMonth = ((Number) row[3]).intValue();
                String key = calYear + "-" + calMonth;

                double eligShare = ((Number) row[4]).doubleValue();
                double avgSal = ((Number) row[5]).doubleValue();
                double weightedAvg = ((Number) row[6]).doubleValue();

                // When viewing all companies, aggregate monthly values
                monthData.merge(key, new double[]{eligShare, avgSal, weightedAvg},
                    (a, b) -> new double[]{
                        Math.max(a[0], b[0]), // eligibleShare: take max (for display)
                        a[1] + b[1],           // avgSalary: sum across companies
                        a[2] + b[2]            // weightedAvgSalary: sum across companies
                    });
            }

            List<EmployeeBonusBasisDTO.MonthBasis> months = new ArrayList<>(12);
            for (int[] ym : fyMonths) {
                String key = ym[0] + "-" + ym[1];
                double[] vals = monthData.getOrDefault(key, new double[]{0, 0, 0});
                months.add(new EmployeeBonusBasisDTO.MonthBasis(ym[0], ym[1], vals[0], vals[1], vals[2]));
            }

            out.add(new EmployeeBonusBasisDTO(user, year, months));
        }

        return out;
    }

    private static String key(Integer y, Integer m) { return (y == null || m == null) ? "?" : (y + "-" + m); }

    @GET @Path("/reload")
    public void reload() {
        // Implement cache eviction if/when needed
    }
}
