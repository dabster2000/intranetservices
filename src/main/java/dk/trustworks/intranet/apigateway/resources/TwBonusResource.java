package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.CompanyBonusDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.CompanyContributionDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.EmployeeBonusDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusPayoutRequest;
import dk.trustworks.intranet.apigateway.model.TwBonusPoolConfig;
import dk.trustworks.intranet.apigateway.repositories.TwBonusPoolConfigRepository;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "bonus")
@Path("/bonus/tw")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM", "ADMIN"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class TwBonusResource {

    @Inject
    TwBonusPoolConfigRepository poolConfigRepository;

    @Inject
    EntityManager em;

    // ===== Pool Configuration Endpoints =====

    @GET
    @Path("/pools")
    public List<TwBonusPoolConfig> getPoolConfigs(@QueryParam("fiscalYear") int fiscalYear) {
        return poolConfigRepository.findByFiscalYear(fiscalYear);
    }

    @PUT
    @Path("/pools/{companyUuid}")
    @Transactional
    public TwBonusPoolConfig upsertPoolConfig(
            @PathParam("companyUuid") String companyUuid,
            TwBonusPoolConfig config) {

        if (config.getFiscalYear() == null) {
            throw new BadRequestException("fiscalYear is required");
        }
        if (config.getBonusPercent() != null && (config.getBonusPercent() < 0 || config.getBonusPercent() > 100)) {
            throw new BadRequestException("bonusPercent must be between 0 and 100");
        }

        Optional<TwBonusPoolConfig> existing = poolConfigRepository
                .findByFiscalYearAndCompany(config.getFiscalYear(), companyUuid);

        if (existing.isPresent()) {
            TwBonusPoolConfig entity = existing.get();
            if (config.getProfitBeforeTax() != null) entity.setProfitBeforeTax(config.getProfitBeforeTax());
            if (config.getBonusPercent() != null) entity.setBonusPercent(config.getBonusPercent());
            if (config.getExtraPool() != null) entity.setExtraPool(config.getExtraPool());
            return entity;
        } else {
            TwBonusPoolConfig entity = new TwBonusPoolConfig();
            entity.setFiscalYear(config.getFiscalYear());
            entity.setCompanyuuid(companyUuid);
            entity.setProfitBeforeTax(config.getProfitBeforeTax() != null ? config.getProfitBeforeTax() : 0.0);
            entity.setBonusPercent(config.getBonusPercent() != null ? config.getBonusPercent() : 10.0);
            entity.setExtraPool(config.getExtraPool() != null ? config.getExtraPool() : 0.0);
            poolConfigRepository.persist(entity);
            return entity;
        }
    }

    // ===== Calculation Endpoint =====

    @GET
    @Path("/calculate")
    public TwBonusCalculationDTO calculate(@QueryParam("fiscalYear") int fiscalYear) {
        // 1. Load pool configs
        List<TwBonusPoolConfig> configs = poolConfigRepository.findByFiscalYear(fiscalYear);
        Map<String, TwBonusPoolConfig> configMap = configs.stream()
                .collect(Collectors.toMap(TwBonusPoolConfig::getCompanyuuid, c -> c));

        // 2. Load all active companies
        List<Company> companies = Company.listAll();

        // 3. Query monthly data from view
        @SuppressWarnings("unchecked")
        List<Object[]> monthlyRows = em.createNativeQuery("""
            SELECT m.useruuid, m.companyuuid, m.year, m.month, m.weighted_avg_salary, m.fiscal_year
            FROM fact_tw_bonus_monthly m
            WHERE m.fiscal_year = :fiscalYear
            ORDER BY m.useruuid, m.year, m.month
        """).setParameter("fiscalYear", fiscalYear).getResultList();

        // 4. Query annual data from view
        @SuppressWarnings("unchecked")
        List<Object[]> annualRows = em.createNativeQuery("""
            SELECT a.useruuid, a.companyuuid, a.weight_sum
            FROM fact_tw_bonus_annual a
            WHERE a.fiscal_year = :fiscalYear
        """).setParameter("fiscalYear", fiscalYear).getResultList();

        // 5. Build company name lookup
        Map<String, String> companyNames = companies.stream()
                .collect(Collectors.toMap(Company::getUuid, Company::getName));

        // 6. Compute total weight per company
        Map<String, Double> totalWeightByCompany = new HashMap<>();
        for (Object[] row : annualRows) {
            String companyUuid = (String) row[1];
            double weightSum = ((Number) row[2]).doubleValue();
            totalWeightByCompany.merge(companyUuid, weightSum, Double::sum);
        }

        // 7. Compute per-company pool and factor
        Map<String, CompanyBonusDTO> companyBonuses = new HashMap<>();
        for (String companyUuid : totalWeightByCompany.keySet()) {
            TwBonusPoolConfig cfg = configMap.get(companyUuid);
            double profitBeforeTax = cfg != null ? cfg.getProfitBeforeTax() : 10_000_000;
            double bonusPercent = cfg != null ? cfg.getBonusPercent() : 10.0;
            double extraPool = cfg != null ? cfg.getExtraPool() : 0.0;

            double pool = profitBeforeTax * (bonusPercent / 100.0) + extraPool;
            double totalWeight = totalWeightByCompany.get(companyUuid);
            double factor = totalWeight > 0 ? pool / totalWeight : 0;

            // Count eligible employees for this company
            long eligibleCount = annualRows.stream()
                    .filter(r -> companyUuid.equals(r[1]) && ((Number) r[2]).doubleValue() > 0)
                    .map(r -> r[0])
                    .distinct()
                    .count();

            CompanyBonusDTO dto = new CompanyBonusDTO();
            dto.setCompanyUuid(companyUuid);
            dto.setCompanyName(companyNames.getOrDefault(companyUuid, companyUuid));
            dto.setProfitBeforeTax(profitBeforeTax);
            dto.setBonusPercent(bonusPercent);
            dto.setExtraPool(extraPool);
            dto.setPool(pool);
            dto.setTotalWeight(totalWeight);
            dto.setFactor(factor);
            dto.setEligibleCount((int) eligibleCount);

            companyBonuses.put(companyUuid, dto);
        }

        // 8. Build per-employee monthly data grouped by (user, company)
        // Key: useruuid -> companyuuid -> month_index (0-11) -> weighted_avg_salary
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();

        for (Object[] row : monthlyRows) {
            String useruuid = (String) row[0];
            String companyUuid = (String) row[1];
            int calYear = ((Number) row[2]).intValue();
            int calMonth = ((Number) row[3]).intValue();
            double weightedAvg = ((Number) row[4]).doubleValue();

            // Convert calendar (year, month) to fiscal month index (0=Jul, 11=Jun)
            int monthIndex;
            if (calMonth >= 7) {
                monthIndex = calMonth - 7; // Jul=0, Aug=1, ..., Dec=5
            } else {
                monthIndex = calMonth + 5; // Jan=6, Feb=7, ..., Jun=11
            }

            userCompanyMonths
                    .computeIfAbsent(useruuid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(companyUuid, k -> new double[12])[monthIndex] = weightedAvg;
        }

        // 9. Get user names
        Set<String> userUuids = userCompanyMonths.keySet();
        Map<String, String> fullNames = new HashMap<>();
        if (!userUuids.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Object[]> users = em.createNativeQuery(
                    "SELECT uuid, CONCAT(firstname, ' ', lastname) AS fullname FROM user WHERE uuid IN :uuids")
                    .setParameter("uuids", new ArrayList<>(userUuids))
                    .getResultList();
            for (Object[] u : users) {
                fullNames.put((String) u[0], (String) u[1]);
            }
        }

        // 10. Build employee DTOs with cross-company pro-rating
        List<EmployeeBonusDTO> employeeDTOs = new ArrayList<>();
        for (Map.Entry<String, Map<String, double[]>> userEntry : userCompanyMonths.entrySet()) {
            String useruuid = userEntry.getKey();
            Map<String, double[]> companiesMap = userEntry.getValue();

            List<CompanyContributionDTO> contributions = new ArrayList<>();
            double totalWeightSum = 0;
            double totalPayout = 0;

            for (Map.Entry<String, double[]> compEntry : companiesMap.entrySet()) {
                String compUuid = compEntry.getKey();
                double[] months = compEntry.getValue();
                double weightSum = 0;
                for (double m : months) weightSum += m;

                CompanyBonusDTO compBonus = companyBonuses.get(compUuid);
                double factor = compBonus != null ? compBonus.getFactor() : 0;

                // payout = sum(month_weight * company_factor)
                double payout = 0;
                for (double m : months) {
                    payout += m * factor;
                }

                CompanyContributionDTO contrib = new CompanyContributionDTO();
                contrib.setCompanyUuid(compUuid);
                contrib.setMonths(months);
                contrib.setWeightSum(weightSum);
                contrib.setPayout(Math.round(payout));

                contributions.add(contrib);
                totalWeightSum += weightSum;
                totalPayout += Math.round(payout);
            }

            double bonusFactor = totalWeightSum > 0
                    ? totalPayout / (totalWeightSum / 12.0)
                    : 0;

            EmployeeBonusDTO empDto = new EmployeeBonusDTO();
            empDto.setUseruuid(useruuid);
            empDto.setFullname(fullNames.getOrDefault(useruuid, useruuid));
            empDto.setCompanyContributions(contributions);
            empDto.setTotalWeightSum(totalWeightSum);
            empDto.setTotalPayout(totalPayout);
            empDto.setBonusFactor(Math.round(bonusFactor * 100.0) / 100.0);

            employeeDTOs.add(empDto);
        }

        TwBonusCalculationDTO result = new TwBonusCalculationDTO();
        result.setFiscalYear(fiscalYear);
        result.setCompanies(new ArrayList<>(companyBonuses.values()));
        result.setEmployees(employeeDTOs);
        return result;
    }

    // ===== Batch Payout Endpoint =====

    @POST
    @Path("/payouts")
    @Transactional
    public Response createPayouts(@Valid TwBonusPayoutRequest request) {
        int fiscalYear = request.getFiscalYear();
        LocalDate payoutMonth = LocalDate.now().withDayOfMonth(1);
        int created = 0;
        int updated = 0;

        for (TwBonusPayoutRequest.PayoutEntry entry : request.getPayouts()) {
            String sourceRef = "tw_bonus_" + fiscalYear + "_" + entry.getUseruuid();

            Optional<SalaryLumpSum> existing = SalaryLumpSum
                    .find("sourceReference", sourceRef)
                    .firstResultOptional();

            if (existing.isPresent()) {
                SalaryLumpSum lump = existing.get();
                lump.setLumpSum((double) Math.round(entry.getAmount()));
                updated++;
            } else {
                SalaryLumpSum lump = new SalaryLumpSum();
                lump.setUuid(UUID.randomUUID().toString());
                lump.setUseruuid(entry.getUseruuid());
                lump.setLumpSum((double) Math.round(entry.getAmount()));
                lump.setSalaryType(LumpSumSalaryType.TW_BONUS);
                lump.setPension(false);
                lump.setMonth(payoutMonth);
                lump.setDescription("Din del af Trustworks");
                lump.setSourceReference(sourceRef);
                lump.persist();
                created++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("created", created);
        response.put("updated", updated);
        response.put("total", request.getPayouts().size());
        return Response.ok(response).build();
    }
}
