package dk.trustworks.intranet.apigateway.support;

import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.CompanyBonusDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.CompanyContributionDTO;
import dk.trustworks.intranet.apigateway.dto.TwBonusCalculationDTO.EmployeeBonusDTO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure (no CDI/DB) calculator for the TW-bonus with career-level multipliers
 * (§3 of the career-level-multipliers spec). Multipliers are <b>non-null-sum</b>:
 * they ADD money on top of the base rather than redistributing it.
 *
 * <p>Per-employee math (w[m] = raw monthly weight, mult[m] = μ(level[m])):
 * <pre>
 *   b[m]  = (mult[m] &gt; 0) ? w[m] : 0          // base contribution
 *   e[m]  = w[m] * mult[m]                       // effective contribution
 *   baseWeight = Σ b[m] ; effWeight = Σ e[m] ; rawWeight = Σ w[m]
 *   careerIneligible = (baseWeight == 0 &amp;&amp; rawWeight &gt; 0)
 *   payout = round(effWeight * F)
 * </pre>
 *
 * <p>Per-company math (override (g)): factor[k] = pool[k] / baseWeight[k];
 * companies with baseWeight 0 are omitted. F = max factor (winner = argmax).
 * careerExtra[k] = round(F*(eff[k]-base[k])); totalPayout[k] = round(F*eff[k]).
 * All money rounding uses {@link Math#round} (half-up).
 */
public final class TwBonusCalculator {

    private TwBonusCalculator() {
    }

    /** Pool inputs per company (profit before tax, bonus percent, extra pool). */
    public record PoolMeta(double profitBeforeTax, double bonusPercent, double extraPool) {
    }

    /**
     * Computes the full TW-bonus result.
     *
     * @param fiscalYear         fiscal-start year (e.g. 2025 for FY 2025/26)
     * @param userCompanyMonths  user -&gt; company -&gt; 12 raw monthly weights (fiscal order)
     * @param userMultipliers    user -&gt; 12 per-month multipliers
     * @param userLevelNames     user -&gt; 12 per-month level names
     * @param fullNames          user -&gt; display name
     * @param poolByCompany      company -&gt; pool (already computed)
     * @param companyNames       company -&gt; display name
     * @param poolMetaByCompany  company -&gt; pool inputs
     */
    public static TwBonusCalculationDTO calculate(
            int fiscalYear,
            Map<String, Map<String, double[]>> userCompanyMonths,
            Map<String, double[]> userMultipliers,
            Map<String, String[]> userLevelNames,
            Map<String, String> fullNames,
            Map<String, Double> poolByCompany,
            Map<String, String> companyNames,
            Map<String, PoolMeta> poolMetaByCompany) {

        // --- Step 1: per-company base / effective accumulation. -------------
        Map<String, Double> baseByCompany = new HashMap<>();
        Map<String, Double> effByCompany = new HashMap<>();
        Map<String, Set<String>> headsByCompany = new HashMap<>();

        for (Map.Entry<String, Map<String, double[]>> userEntry : userCompanyMonths.entrySet()) {
            String useruuid = userEntry.getKey();
            double[] mults = multipliersOf(userMultipliers, useruuid);

            for (Map.Entry<String, double[]> compEntry : userEntry.getValue().entrySet()) {
                String companyUuid = compEntry.getKey();
                double[] months = compEntry.getValue();

                double base = 0;
                double eff = 0;
                for (int m = 0; m < 12; m++) {
                    double w = months[m];
                    double mult = mults[m];
                    if (mult > 0) {
                        base += w;
                    }
                    eff += w * mult;
                }
                if (base > 0) {
                    baseByCompany.merge(companyUuid, base, Double::sum);
                    headsByCompany.computeIfAbsent(companyUuid, k -> new HashSet<>()).add(useruuid);
                }
                if (eff != 0) {
                    effByCompany.merge(companyUuid, eff, Double::sum);
                }
            }
        }

        // --- Step 2: included companies (baseWeight > 0) + first-pass DTOs. --
        Map<String, CompanyBonusDTO> companyBonuses = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : baseByCompany.entrySet()) {
            String companyUuid = entry.getKey();
            double base = entry.getValue();
            if (base <= 0) {
                continue; // omit zero-base companies
            }
            double pool = poolByCompany.getOrDefault(companyUuid, 0.0);
            PoolMeta meta = poolMetaByCompany.get(companyUuid);
            double factor = pool / base;

            CompanyBonusDTO dto = new CompanyBonusDTO();
            dto.setCompanyUuid(companyUuid);
            dto.setCompanyName(companyNames.getOrDefault(companyUuid, companyUuid));
            dto.setProfitBeforeTax(meta != null ? meta.profitBeforeTax() : 0.0);
            dto.setBonusPercent(meta != null ? meta.bonusPercent() : 0.0);
            dto.setExtraPool(meta != null ? meta.extraPool() : 0.0);
            dto.setPool(pool);
            dto.setTotalWeight(base);
            dto.setFactor(factor);
            dto.setEligibleCount(headsByCompany.getOrDefault(companyUuid, Set.of()).size());
            companyBonuses.put(companyUuid, dto);
        }

        // --- Step 3: applied factor = max over included companies. ----------
        double appliedFactor = 0;
        String winningCompanyUuid = null;
        for (CompanyBonusDTO c : companyBonuses.values()) {
            if (c.getFactor() > appliedFactor) {
                appliedFactor = c.getFactor();
                winningCompanyUuid = c.getCompanyUuid();
            }
        }
        final double f = appliedFactor;

        // --- Step 4: second pass — careerExtra / totalPayout per company. ---
        for (CompanyBonusDTO c : companyBonuses.values()) {
            double base = c.getTotalWeight();
            double eff = effByCompany.getOrDefault(c.getCompanyUuid(), 0.0);
            c.setCareerExtra(Math.round(f * (eff - base)));
            c.setTotalPayout(Math.round(f * eff));
        }

        // --- Step 5: per-employee DTOs. -------------------------------------
        List<EmployeeBonusDTO> employeeDTOs = new ArrayList<>();
        double projectedTotalPayout = 0;

        for (Map.Entry<String, Map<String, double[]>> userEntry : userCompanyMonths.entrySet()) {
            String useruuid = userEntry.getKey();
            Map<String, double[]> companiesMap = userEntry.getValue();
            double[] mults = multipliersOf(userMultipliers, useruuid);
            String[] levels = levelNamesOf(userLevelNames, useruuid);

            // Per-month weight summed across companies (for the representative).
            double[] summedMonthWeights = new double[12];
            double baseWeight = 0;
            double effWeight = 0;
            double rawWeight = 0;

            List<CompanyContributionDTO> contributions = new ArrayList<>();
            for (Map.Entry<String, double[]> compEntry : companiesMap.entrySet()) {
                String companyUuid = compEntry.getKey();
                double[] months = compEntry.getValue();

                double compBase = 0;
                double compEff = 0;
                for (int m = 0; m < 12; m++) {
                    double w = months[m];
                    double mult = mults[m];
                    summedMonthWeights[m] += w;
                    rawWeight += w;
                    if (mult > 0) {
                        compBase += w;
                    }
                    compEff += w * mult;
                }
                baseWeight += compBase;
                effWeight += compEff;

                CompanyContributionDTO contrib = new CompanyContributionDTO();
                contrib.setCompanyUuid(companyUuid);
                contrib.setMonths(months);
                contrib.setMultipliers(mults);
                contrib.setBaseWeightSum(compBase);
                contrib.setEffectiveWeightSum(compEff);
                contrib.setWeightSum(compBase);
                contrib.setPayout(Math.round(compEff * f));
                contributions.add(contrib);
            }

            boolean careerIneligible = baseWeight == 0 && rawWeight > 0;
            double totalPayout = Math.round(effWeight * f);
            double bonusFactor = baseWeight > 0
                    ? Math.round(totalPayout / (baseWeight / 12.0))
                    : 0;

            CareerMultiplierResolver.Representative rep =
                    CareerMultiplierResolver.representative(summedMonthWeights, mults, levels);

            EmployeeBonusDTO empDto = new EmployeeBonusDTO();
            empDto.setUseruuid(useruuid);
            empDto.setFullname(fullNames.getOrDefault(useruuid, useruuid));
            empDto.setCompanyContributions(contributions);
            empDto.setTotalWeightSum(baseWeight);
            empDto.setEffectiveWeightSum(effWeight);
            empDto.setTotalPayout(totalPayout);
            empDto.setBonusFactor(bonusFactor);
            empDto.setMultiplier(rep.multiplier());
            empDto.setRepresentativeCareerLevel(rep.levelName());
            empDto.setCareerIneligible(careerIneligible);

            employeeDTOs.add(empDto);
            projectedTotalPayout += totalPayout;
        }

        // --- Step 6: assemble result. ---------------------------------------
        TwBonusCalculationDTO result = new TwBonusCalculationDTO();
        result.setFiscalYear(fiscalYear);
        result.setCompanies(new ArrayList<>(companyBonuses.values()));
        result.setEmployees(employeeDTOs);
        result.setAppliedFactor(f);
        result.setWinningCompanyUuid(winningCompanyUuid);
        result.setProjectedTotalPayout(projectedTotalPayout);
        return result;
    }

    private static double[] multipliersOf(Map<String, double[]> userMultipliers, String useruuid) {
        double[] mults = userMultipliers.get(useruuid);
        if (mults != null && mults.length == 12) {
            return mults;
        }
        double[] defaults = new double[12];
        java.util.Arrays.fill(defaults, 1.0);
        return defaults;
    }

    private static String[] levelNamesOf(Map<String, String[]> userLevelNames, String useruuid) {
        String[] names = userLevelNames.get(useruuid);
        if (names != null && names.length == 12) {
            return names;
        }
        String[] defaults = new String[12];
        java.util.Arrays.fill(defaults, "");
        return defaults;
    }
}
