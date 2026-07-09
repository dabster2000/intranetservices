package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusPayoutRequest;
import dk.trustworks.intranet.apigateway.model.TwBonusPoolConfig;
import dk.trustworks.intranet.apigateway.repositories.TwBonusPoolConfigRepository;
import dk.trustworks.intranet.apigateway.support.CareerMultiplierResolver;
import dk.trustworks.intranet.apigateway.support.TwBonusCalculator;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
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
@RolesAllowed({"bonus:read"})
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
    @RolesAllowed({"bonus:write"})
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

        // 2. Load all companies (name lookup + pool meta keys)
        List<Company> companies = Company.listAll();
        Map<String, String> companyNames = companies.stream()
                .collect(Collectors.toMap(Company::getUuid, Company::getName));

        // 3. Query monthly data from view
        @SuppressWarnings("unchecked")
        List<Object[]> monthlyRows = em.createNativeQuery("""
            SELECT m.useruuid, m.companyuuid, m.year, m.month, m.weighted_avg_salary, m.fiscal_year
            FROM fact_tw_bonus_monthly m
            WHERE m.fiscal_year = :fiscalYear
            ORDER BY m.useruuid, m.year, m.month
        """).setParameter("fiscalYear", fiscalYear).getResultList();

        // 4. Build per-employee monthly data grouped by (user, company)
        // Key: useruuid -> companyuuid -> month_index (0-11) -> weighted_avg_salary
        Map<String, Map<String, double[]>> userCompanyMonths = new LinkedHashMap<>();

        for (Object[] row : monthlyRows) {
            String useruuid = (String) row[0];
            String companyUuid = (String) row[1];
            int calMonth = ((Number) row[3]).intValue();
            double weightedAvg = ((Number) row[4]).doubleValue();

            // Convert calendar month to fiscal month index (0=Jul, 11=Jun)
            int monthIndex = calMonth >= 7 ? calMonth - 7 : calMonth + 5;

            userCompanyMonths
                    .computeIfAbsent(useruuid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(companyUuid, k -> new double[12])[monthIndex] = weightedAvg;
        }

        Set<String> userUuids = userCompanyMonths.keySet();

        // 5. Batch-load career levels once; resolve per-month multipliers + level names.
        Map<String, double[]> userMultipliers = new HashMap<>();
        Map<String, String[]> userLevelNames = new HashMap<>();
        Map<String, List<UserCareerLevel>> careerByUser = new HashMap<>();
        if (!userUuids.isEmpty()) {
            List<UserCareerLevel> careerLevels =
                    UserCareerLevel.list("useruuid in ?1", new ArrayList<>(userUuids));
            for (UserCareerLevel ucl : careerLevels) {
                careerByUser.computeIfAbsent(ucl.getUseruuid(), k -> new ArrayList<>()).add(ucl);
            }
        }
        for (String useruuid : userUuids) {
            List<UserCareerLevel> sorted =
                    CareerMultiplierResolver.sortAscending(careerByUser.get(useruuid));
            userMultipliers.put(useruuid,
                    CareerMultiplierResolver.monthlyMultipliers(sorted, fiscalYear));
            userLevelNames.put(useruuid,
                    CareerMultiplierResolver.monthlyLevelNames(sorted, fiscalYear));
        }

        // 6. Build pool + pool meta for every company appearing in the data.
        Map<String, Double> poolByCompany = new HashMap<>();
        Map<String, TwBonusCalculator.PoolMeta> poolMetaByCompany = new HashMap<>();
        Set<String> companyUuids = new HashSet<>();
        for (Map<String, double[]> byCompany : userCompanyMonths.values()) {
            companyUuids.addAll(byCompany.keySet());
        }
        for (String companyUuid : companyUuids) {
            TwBonusPoolConfig cfg = configMap.get(companyUuid);
            double profitBeforeTax = cfg != null ? cfg.getProfitBeforeTax() : 10_000_000;
            double bonusPercent = cfg != null ? cfg.getBonusPercent() : 10.0;
            double extraPool = cfg != null ? cfg.getExtraPool() : 0.0;
            double pool = profitBeforeTax * (bonusPercent / 100.0) + extraPool;
            poolByCompany.put(companyUuid, pool);
            poolMetaByCompany.put(companyUuid,
                    new TwBonusCalculator.PoolMeta(profitBeforeTax, bonusPercent, extraPool));
        }

        // 7. Get user names
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

        // 8. Delegate to the pure calculator.
        return TwBonusCalculator.calculate(
                fiscalYear,
                userCompanyMonths,
                userMultipliers,
                userLevelNames,
                fullNames,
                poolByCompany,
                companyNames,
                poolMetaByCompany);
    }

    // ===== Batch Payout Endpoint =====

    @POST
    @Path("/payouts")
    @RolesAllowed({"bonus:write"})
    @Transactional
    public Response createPayouts(@Valid TwBonusPayoutRequest request) {
        int fiscalYear = request.getFiscalYear();
        LocalDate payoutMonth = LocalDate.now().withDayOfMonth(1);
        int created = 0;
        int updated = 0;
        int skippedReplacedByIndividual = 0;

        // "Replaces YPOT" enforcement (Individual Bonus Rules, spec §8): an employee with an active
        // individual_bonus_rule whose replaces = "YPOT" effective for this fiscal year receives their
        // individual production bonus INSTEAD of "Din del af Trustworks" — so exclude them here to
        // guarantee nobody is paid BOTH a TW_BONUS and the individual bonus for the same FY.
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        Set<String> ypotReplacedUsers = IndividualBonusRule
                .<IndividualBonusRule>find(
                        "active = true and replaces = ?1 and effectiveFrom <= ?2 " +
                                "and (effectiveTo is null or effectiveTo >= ?3)",
                        "YPOT", fyEnd, fyStart)
                .stream()
                .map(IndividualBonusRule::getUserUuid)
                .collect(Collectors.toSet());

        for (TwBonusPayoutRequest.PayoutEntry entry : request.getPayouts()) {
            if (ypotReplacedUsers.contains(entry.getUseruuid())) {
                log.infof("Skipping TW_BONUS for %s (FY %d) — replaced by an individual bonus (replaces=YPOT)",
                        entry.getUseruuid(), fiscalYear);
                skippedReplacedByIndividual++;
                continue;
            }
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
        response.put("skippedReplacedByIndividual", skippedReplacedByIndividual);
        response.put("total", request.getPayouts().size());
        return Response.ok(response).build();
    }
}
