package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.aggregates.users.services.SalarySupplementService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.SalarySupplement;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import dk.trustworks.intranet.userservice.model.enums.SalarySupplementType;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PartnerBonusPayoutService {

    @Inject
    SalaryLumpSumService salaryLumpSumService;

    @Inject
    SalarySupplementService salarySupplementService;

    /**
     * Calculate total prepaid bonuses for a user within a fiscal year.
     * FY runs July 1 (fiscalYear) to June 30 (fiscalYear+1).
     * Supplement value is a monthly rate; total = monthlyRate * overlapMonths.
     */
    public double calculatePrepaidBonuses(String userUuid, int fiscalYear) {
        try {
            LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
            LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);

            List<SalarySupplement> supplements = salarySupplementService.findByUseruuid(userUuid);

            return supplements.stream()
                    .filter(s -> s.getType() == SalarySupplementType.PREPAID)
                    .filter(s -> s.getFromMonth() != null)
                    .filter(s -> {
                        LocalDate suppStart = s.getFromMonth();
                        LocalDate suppEnd = s.getToMonth() != null ? s.getToMonth() : LocalDate.MAX;
                        return !suppEnd.isBefore(fyStart) && !suppStart.isAfter(fyEnd);
                    })
                    .mapToDouble(s -> {
                        LocalDate suppStart = s.getFromMonth();
                        LocalDate suppEnd = s.getToMonth() != null ? s.getToMonth() : LocalDate.MAX;

                        LocalDate overlapStart = suppStart.isBefore(fyStart) ? fyStart : suppStart;
                        LocalDate overlapEnd = suppEnd.isAfter(fyEnd) ? fyEnd : suppEnd;

                        long overlapMonths = ChronoUnit.MONTHS.between(
                                overlapStart.withDayOfMonth(1),
                                overlapEnd.withDayOfMonth(1)
                        ) + 1;

                        double monthlyRate = s.getValue() != null ? s.getValue() : 0.0;
                        return monthlyRate * overlapMonths;
                    })
                    .sum();
        } catch (Exception e) {
            Log.error("Failed to calculate prepaid bonuses for user: " + userUuid, e);
            return 0.0;
        }
    }

    /**
     * Check if a partner bonus payout already exists for a user.
     * Checks FY+1 because payouts for FY X are created in FY X+1.
     */
    public boolean hasExistingPayout(String userUuid, int fiscalYear) {
        try {
            LocalDate paymentFyStart = LocalDate.of(fiscalYear + 1, 7, 1);
            LocalDate paymentFyEnd = LocalDate.of(fiscalYear + 2, 6, 30);

            List<SalaryLumpSum> lumpSums = salaryLumpSumService.findByUseruuid(userUuid);

            return lumpSums.stream()
                    .filter(ls -> ls.getSalaryType() == LumpSumSalaryType.COMMERCIAL_PARTNER_BONUS ||
                                  ls.getSalaryType() == LumpSumSalaryType.PROD_BONUS)
                    .filter(ls -> ls.getMonth() != null)
                    .anyMatch(ls -> !ls.getMonth().isBefore(paymentFyStart) && !ls.getMonth().isAfter(paymentFyEnd));
        } catch (Exception e) {
            Log.error("Failed to check existing payout for user: " + userUuid, e);
            return false;
        }
    }

    /**
     * Create separate COMMERCIAL_PARTNER_BONUS and PROD_BONUS salary lump sums.
     * Descriptions: "Sales bonus YYYY/YY" and "Produktionsbonus YYYY/YY".
     */
    @Transactional
    public void createPartnerPayouts(String userUuid, double salesAmount, double productionAmount,
                                      LocalDate month, int fiscalYear) {
        String fiscalYearStr = fiscalYear + "/" + String.format("%02d", (fiscalYear + 1) % 100);

        if (productionAmount > 0.01) {
            SalaryLumpSum prodLumpSum = new SalaryLumpSum();
            prodLumpSum.setUuid(UUID.randomUUID().toString());
            prodLumpSum.setUseruuid(userUuid);
            prodLumpSum.setSalaryType(LumpSumSalaryType.PROD_BONUS);
            prodLumpSum.setLumpSum(productionAmount);
            prodLumpSum.setPension(false);
            prodLumpSum.setMonth(month);
            prodLumpSum.setDescription("Produktionsbonus " + fiscalYearStr);
            salaryLumpSumService.create(prodLumpSum);
            Log.infof("Created production bonus for partner %s: %.2f", userUuid, productionAmount);
        }

        if (salesAmount > 0.01) {
            SalaryLumpSum salesLumpSum = new SalaryLumpSum();
            salesLumpSum.setUuid(UUID.randomUUID().toString());
            salesLumpSum.setUseruuid(userUuid);
            salesLumpSum.setSalaryType(LumpSumSalaryType.COMMERCIAL_PARTNER_BONUS);
            salesLumpSum.setLumpSum(salesAmount);
            salesLumpSum.setPension(false);
            salesLumpSum.setMonth(month);
            salesLumpSum.setDescription("Sales bonus " + fiscalYearStr);
            salaryLumpSumService.create(salesLumpSum);
            Log.infof("Created sales bonus for partner %s: %.2f", userUuid, salesAmount);
        }
    }
}
