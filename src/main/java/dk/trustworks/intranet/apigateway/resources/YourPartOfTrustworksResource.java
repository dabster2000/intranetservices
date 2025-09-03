package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.bidata.model.BiDataPerDay;
import dk.trustworks.intranet.apigateway.dto.EmployeeBonusBasisDTO;
import dk.trustworks.intranet.model.EmployeeBonusEligibility;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
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

    // --------- NEW ENDPOINT: day-based monthly eligibility & salary basis ----------
    @GET
    @Path("/basis")
    public List<EmployeeBonusBasisDTO> findMonthlyBasis(@QueryParam("fiscalstartyear") int year,
                                                        @QueryParam("companyuuid") String companyUuid) {
        LocalDate startDate = LocalDate.of(year, 7, 1);
        LocalDate endExclusive = startDate.plusYears(1);


        List<BiDataPerDay> days = (companyUuid == null || companyUuid.isBlank())
                ? BiDataPerDay.<BiDataPerDay>list(
                "documentDate >= ?1 and documentDate < ?2 " +
                        "and consultantType in (?3, ?4, ?5) " +
                        "and exists (" +
                        "  select 1 from BiDataPerDay d2 " +
                        "  where d2.user = user " +
                        "    and d2.documentDate >= ?1 and d2.documentDate < ?2 " +
                        "    and d2.consultantType in (?3, ?4, ?5) " +
                        "    and d2.statusType <> ?6" +
                        ")",
                startDate, endExclusive,
                ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT,
                StatusType.TERMINATED
        )
                : BiDataPerDay.<BiDataPerDay>list(
                "documentDate >= ?1 and documentDate < ?2 " +
                        "and company.uuid = ?3 " +
                        "and consultantType in (?4, ?5, ?6) " +
                        "and exists (" +
                        "  select 1 from BiDataPerDay d2 " +
                        "  where d2.user = user " +
                        "    and d2.documentDate >= ?1 and d2.documentDate < ?2 " +
                        "    and d2.consultantType in (?4, ?5, ?6) " +
                        "    and d2.statusType <> ?7 " +
                        "    and d2.company.uuid = ?3" +
                        ")",
                startDate, endExclusive, companyUuid,
                ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT,
                StatusType.TERMINATED
        );

        // group by user -> (year,month) -> list
        Map<String, Map<String, List<BiDataPerDay>>> byUserYearMonth = days.stream()
                .collect(Collectors.groupingBy(d -> d.user.getUuid(),
                        Collectors.groupingBy(d -> key(d.getYear(), d.getMonth()))));

        List<EmployeeBonusBasisDTO> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<BiDataPerDay>>> e : byUserYearMonth.entrySet()) {
            String userUuid = e.getKey();
            User u = days.stream().filter(d -> d.user.getUuid().equals(userUuid)).findFirst().get().user;

            List<EmployeeBonusBasisDTO.MonthBasis> months = new ArrayList<>(12);

            // build Jul..Dec of fiscalStartYear, then Jan..Jun of next year
            int[] orderYears = new int[] { year, year, year, year, year, year, year+1, year+1, year+1, year+1, year+1, year+1 };
            int[] orderMonths = new int[] { 7,8,9,10,11,12, 1,2,3,4,5,6 };

            for (int i = 0; i < 12; i++) {
                int y = orderYears[i];
                int m = orderMonths[i];
                YearMonth ym = YearMonth.of(y, m);
                int dim = ym.lengthOfMonth();

                List<BiDataPerDay> monthDays = e.getValue().getOrDefault(key(y, m), List.of());

                long eligibleDays = monthDays.stream().filter(YourPartOfTrustworksResource::isEligibleDay).count();
                double eligibleShare = dim == 0 ? 0.0 : (eligibleDays / (double) dim);

                double sumSalaryAll = monthDays.stream().mapToDouble(BiDataPerDay::getSalary).sum(); // getSalary returns 0 if null
                double avgSalary = dim == 0 ? 0.0 : (sumSalaryAll / dim);

                double sumSalaryEligible = monthDays.stream()
                        .filter(YourPartOfTrustworksResource::isEligibleDay)
                        .mapToDouble(BiDataPerDay::getSalary)
                        .sum();
                double weightedAvgSalary = dim == 0 ? 0.0 : (sumSalaryEligible / dim);

                months.add(new EmployeeBonusBasisDTO.MonthBasis(y, m, eligibleShare, avgSalary, weightedAvgSalary));
            }

            out.add(new EmployeeBonusBasisDTO(u, year, months));
        }

        return out;
    }

    private static String key(Integer y, Integer m) { return (y == null || m == null) ? "?" : (y + "-" + m); }

    @GET @Path("/reload")
    public void reload() {
        // Implement cache eviction if/when needed
    }
}
