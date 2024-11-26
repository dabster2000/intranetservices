package dk.trustworks.intranet.userservice.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
public class VacationPool {

    @Id
    @Column(length = 36)
    @EqualsAndHashCode.Include
    private String uuid; // UUID as the primary key

    @Column(length = 36, nullable = false)
    private String userUuid; // Foreign key to User

    @Column(nullable = false)
    private double vacationEarned;

    @Column(nullable = false)
    private double vacationUsed;

    @Column(nullable = false)
    private double transferredDays;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    public boolean deductVacationHours(double hours, double hoursPerDay) {
        double daysToDeduct = hours / hoursPerDay;

        if (getAvailableVacation() >= daysToDeduct) {
            this.vacationUsed += daysToDeduct;
            return true;
        }
        return false;
    }

    public double getAvailableVacation() {
        return vacationEarned - vacationUsed + transferredDays;
    }
}