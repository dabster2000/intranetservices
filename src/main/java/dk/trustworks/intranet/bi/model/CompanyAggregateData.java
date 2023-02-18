package dk.trustworks.intranet.bi.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "company_data")
@NoArgsConstructor
@RequiredArgsConstructor
public class CompanyAggregateData extends PanacheEntityBase {

    @Id
    @NonNull
    private String uuid;

    @NonNull
    @JsonSerialize(using = LocalDateSerializer.class)
    @JsonDeserialize(using = LocalDateDeserializer.class)
    private LocalDate month;

    @Column(name = "registered_hours")
    private int registeredHours; // done

    @Column(name = "net_available_hours")
    private double netAvailableHours; // done

    @Column(name = "gross_available_hours")
    private double grossAvailableHours; // done

    @Column(name = "budget_amount")
    private int budgetAmount; // done

    @Column(name = "budget_hours")
    private int budgetHours; // done

    @Column(name = "net_availability")
    private double netAvailability; // done

    @Column(name = "gross_availability")
    private double grossAvailability; // done

    @Column(name = "budget_availability")
    private double budgetAvailability; // done

    @Column(name = "registered_amount")
    private int registeredAmount; // done

    @Column(name = "invoiced_amount")
    private int invoicedAmount; // done

    @Column(name = "consultant_salaries")
    private int consultantSalaries; // done

    @Column(name = "staff_salaries")
    private int staffSalaries; // done

    @Column(name = "employee_expenses")
    private int employeeExpenses; // done

    @Column(name = "office_expenses")
    private int officeExpenses; // done

    @Column(name = "sales_expenses")
    private int salesExpenses; // done

    @Column(name = "production_expenses")
    private int productionExpenses; // done

    @Column(name = "administration_expenses")
    private int administrationExpenses; // done

    @Column(name = "num_of_employees")
    private int numOfEmployees; // done

    @Column(name = "num_of_consultants")
    private int numOfConsultants; // done

    public int calcExpensesSum() {
        return consultantSalaries+staffSalaries+employeeExpenses+officeExpenses+salesExpenses+productionExpenses+administrationExpenses;
    }

    public void addWorkDuration(double workduration) {
        this.registeredHours += workduration;
    }

    public void addRegisteredAmount(double registeredAmount) {
        this.registeredAmount += registeredAmount;
    }
}
