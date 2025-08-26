package dk.trustworks.intranet.model;


import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.proxy.HibernateProxy;

import java.util.Objects;

@Getter
@Setter
@ToString
@NoArgsConstructor
@Entity
@Table(name = "user_contract_bonus")
public class EmployeeBonusEligibility extends PanacheEntityBase {

    @Id
    @JsonIgnore
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    private int id;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "useruuid")
    @JsonProperty("user")
    private User user;

    private String username;

    @JsonProperty("year")
    private int year;

    @JsonProperty("bonusEligible")
    @Column(name = "bonus_eligible")
    private boolean bonusEligible;

    private boolean july;
    private boolean august;
    private boolean september;
    private boolean october;
    private boolean november;
    private boolean december;
    private boolean january;
    private boolean february;
    private boolean march;
    private boolean april;
    private boolean may;
    private boolean june;

    public EmployeeBonusEligibility(User user, int year, boolean bonusEligible, boolean july, boolean august, boolean september, boolean october, boolean november, boolean december, boolean january, boolean february, boolean march, boolean april, boolean may, boolean june) {
        this.user = user;
        this.username = user.getUsername();
        this.year = year;
        this.bonusEligible = bonusEligible;
        this.july = july;
        this.august = august;
        this.september = september;
        this.october = october;
        this.november = november;
        this.december = december;
        this.january = january;
        this.february = february;
        this.march = march;
        this.april = april;
        this.may = may;
        this.june = june;
    }
}
