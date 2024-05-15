package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.PrimarySkillType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.mindrot.jbcrypt.BCrypt;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY;

/**
 * Created by hans on 23/06/2017.
 */

@Data
@Entity
@Table(name = "user")
public class User extends PanacheEntityBase {

    @Id
    public String uuid;
    private boolean active;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate created;
    private String email;
    private String firstname;
    private String lastname;
    private String gender;
    @JsonIgnore
    private String type;
    @JsonProperty(access = READ_ONLY)
    private String password;
    @NotBlank(message="Username may not be blank")
    private String username;
    private String slackusername;
    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate birthday;

    private String cpr;
    private String phone;
    private boolean pension;
    private boolean healthcare;
    private String pensiondetails;
    private String defects;
    private boolean photoconsent;
    private String other;
    @Enumerated(EnumType.STRING)
    private PrimarySkillType primaryskilltype;

    @Transient
    private List<Salary> salaries = new ArrayList<>();

    @Transient
    private List<TeamRole> teams = new ArrayList<>();

    @Transient
    private List<UserStatus> statuses = new ArrayList<>();

    @Transient
    private List<Role> roleList = new ArrayList<>();

    @Transient
    private UserContactinfo userContactinfo;

    @Transient
    private List<UserBankInfo> userBankInfos = new ArrayList<>();

    @Transient
    private UserAccount userAccount;

    public User() {
        uuid = UUID.randomUUID().toString();
        created = LocalDate.now();
    }

    public static Optional<User> findByUsername(String username) {
        return find("username like ?1", username).firstResultOptional();
    }

    public UserStatus getUserStatus(LocalDate date) {
        return getStatuses().stream().filter(value -> value.getStatusdate().isBefore(date) || value.getStatusdate().isEqual(date)).max(Comparator.comparing(UserStatus::getStatusdate)).orElse(new UserStatus(ConsultantType.STAFF, StatusType.TERMINATED, date, 0, uuid));
    }

    public Salary getSalary(LocalDate date) {
        return getSalaries().stream().filter(value -> value.getActivefrom().isBefore(date) || value.getActivefrom().isEqual(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(date, 0, UUID.randomUUID().toString()));
    }

    public UserBankInfo getUserBankInfo(LocalDate date) {
        return getUserBankInfos().stream().filter(value -> value.getActiveDate().isBefore(date) || value.getActiveDate().isEqual(date)).max(Comparator.comparing(UserBankInfo::getActiveDate)).orElse(new UserBankInfo());
    }

    public LocalDate getHireDate() {
        LocalDate hireDate = null;
        //System.out.println("hireDate = " + hireDate);
        boolean terminated = false;
        //System.out.println("terminated = " + terminated);
        for (UserStatus status : getStatuses().stream().sorted(Comparator.comparing(UserStatus::getStatusdate)).toList()) {
            //System.out.println("status = " + status);
            if(status.getStatus().equals(StatusType.ACTIVE) && (hireDate==null || terminated)) {
                //System.out.println(1);
                hireDate = status.getStatusdate();
                //System.out.println("hireDate = " + hireDate);
            }
            if(status.getStatus().equals(StatusType.TERMINATED)) {
                //System.out.println(2);
                terminated = true;
                //System.out.println("terminated = " + terminated);
            }
            //System.out.println(3);
        }
        //System.out.println("Done terminated = " + terminated);
        //System.out.println("1/2 Done hireDate = " + hireDate);
        if(hireDate==null) hireDate = LocalDate.now();

        //System.out.println("Done hireDate = " + hireDate);
        return hireDate;
    }

    public List<UserStatus> findByUserAndTypeAndStatusOrderByStatusdateAsc(ConsultantType type, StatusType status) {
        return getStatuses()
                .stream()
                .filter(userStatus -> userStatus.getStatus().equals(status) && userStatus.getType().equals(type))
                .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                .collect(Collectors.toList());
    }

    public String getFullname() {
        return firstname + " " + lastname;
    }

    public Boolean checkPassword(String passwordPlainText) {
            if (password.trim().equals("")) {
                return false;
            }
        return BCrypt.checkpw(passwordPlainText, password);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return getUuid().equals(user.getUuid());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getUuid());
    }
}
