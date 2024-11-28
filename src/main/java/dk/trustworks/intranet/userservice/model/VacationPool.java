package dk.trustworks.intranet.userservice.model;

import java.time.LocalDate;

/**
 * The VacationPool class represents a period in which vacation days are earned and used.
 * Each VacationPool corresponds to a specific vacation year and manages earned and used vacation days
 * for that period. Vacation days can roll over to subsequent pools if they are not fully used.
 */
public class VacationPool {

    /** Total vacation days earned in this pool. */
    private double vacationEarned;

    /** Total vacation days used in this pool. */
    private double vacationUsed;

    /** Start date of the vacation pool, typically September 1st of the vacation year. */
    private final LocalDate startDate;

    /** End date of the vacation earning period, typically August 31st of the following year. */
    private final LocalDate endDate;

    /** End date of the vacation usage period, typically December 31st of the following year. */
    private final LocalDate endUseDate;

    /** Reference to the next VacationPool, used for rolling over vacation days. */
    private VacationPool next;

    /**
     * Creates a new VacationPool for a specific start date.
     * The pool begins on the 1st of September of the vacation year and ends after 16 months (including usage period).
     *
     * @param startDate The start date to initialize the vacation pool.
     */
    public VacationPool(LocalDate startDate) {
        vacationEarned = 0.0;
        vacationUsed = 0.0;
        this.startDate = startDate.getMonthValue() >= 9
                ? startDate.withDayOfMonth(1).withMonth(9)
                : startDate.withDayOfMonth(1).withMonth(9).minusYears(1);
        this.endDate = this.startDate.plusYears(1);
        this.endUseDate = this.startDate.plusYears(1).plusMonths(4).minusDays(1);
        System.out.println("VacationPool created: " + this.startDate + " - " + endDate + " (use by: " + endUseDate + ")");
    }

    // Find and return the VacationPool given a specific start date
    public VacationPool findPool(LocalDate startDate) {
        if (!startDate.equals(this.startDate)) {
            return next == null ? null : next.findPool(startDate);
        }
        return this;
    }

    // Return the remaining vacation days in the pool
    public double getRemainingVacation() {
        return vacationEarned - vacationUsed;
    }


    /**
     * Creates and returns the next VacationPool for the following vacation year.
     * If the next pool already exists, it returns the existing one.
     *
     * @return The next VacationPool.
     */
    public VacationPool createNextPool() {
        if (next == null) {
            next = new VacationPool(startDate.plusYears(1));
        }
        return next;
    }

    /**
     * Adds vacation days earned to the appropriate pool.
     * If the vacation date falls within this pool's period, the earned days are added.
     * Otherwise, it is passed to the next pool.
     *
     * @param vacation The Vacation object containing the date and earned vacation days.
     */
    public void addVacationEarned(Vacation vacation) {
        if (vacation.getDate().isBefore(startDate) || !vacation.getDate().isBefore(endDate)) {
            if (next != null) {
                next.addVacationEarned(vacation);
            } else {
                createNextPool().addVacationEarned(vacation);
            }
            return;
        }
        vacationEarned += vacation.getVacationEarned();
    }

    /**
     * Deducts vacation days used from the appropriate pool.
     * If the vacation date falls within this pool's usage period, the used days are deducted.
     * Any excess usage is rolled over to the next pool if available.
     *
     * @param date          The date of the vacation usage.
     * @param usedVacation  The number of vacation days used.
     */
    public void addVacationUsed(LocalDate date, double usedVacation) {
        // Check if the vacation usage date falls within the current pool's usage period
        if (date.isBefore(startDate) || date.isAfter(endUseDate)) {
            // If the date is outside the current pool, delegate the usage to the next pool if it exists
            if (next != null) {
                next.addVacationUsed(date, usedVacation);
            }
            // Exit as the usage cannot be applied to this pool
            return;
        }

        // Calculate the remaining available vacation days in the current pool
        double availableVacation = vacationEarned - vacationUsed;

        if (usedVacation <= availableVacation) {
            // Fully handle the usage in the current pool
            vacationUsed += usedVacation;
        } else {
            // Deduct what is available in this pool
            vacationUsed += availableVacation;

            // Pass the remaining usage to the next pool
            double remainingVacation = usedVacation - availableVacation;
            if (next != null) {
                next.addVacationUsed(date, remainingVacation);
            } else {
                System.err.printf("Error: No next pool to handle remaining vacation usage of %.2f on %s%n",
                        remainingVacation, date);
            }
        }
    }


    /**
     * Returns a string representation of the VacationPool and any linked pools.
     *
     * @return A string describing the vacation pool.
     */
    @Override
    public String toString() {
        return "VacationPool{" +
                "vacationEarned=" + vacationEarned +
                ", vacationUsed=" + vacationUsed +
                ", year=" + startDate +
                "}\n" + (next == null ? "" : next.toString());
    }
}