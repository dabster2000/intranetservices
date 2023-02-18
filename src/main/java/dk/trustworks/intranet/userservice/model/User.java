package dk.trustworks.intranet.userservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import lombok.Data;
import org.mindrot.jbcrypt.BCrypt;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY;
import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.CONSULTANT;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.ACTIVE;

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

    public User() {
        uuid = UUID.randomUUID().toString();
        created = LocalDate.now();
    }

    public static Optional<User> findByUsername(String username) {
        return find("username", username).firstResultOptional();
    }

    public UserStatus getUserStatus(LocalDate date) {
        return getStatuses().stream().filter(value -> value.getStatusdate().isBefore(date)).max(Comparator.comparing(UserStatus::getStatusdate)).orElse(new UserStatus(ConsultantType.STAFF, StatusType.TERMINATED, date, 0, uuid));
    }

    public Salary getSalary(LocalDate date) {
        return getSalaries().stream().filter(value -> value.getActivefrom().isBefore(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(date, 0, UUID.randomUUID().toString()));
    }

    public LocalDate getEmployedDate() {
        List<UserStatus> statusdateAsc = findByUserAndTypeAndStatusOrderByStatusdateAsc(CONSULTANT, ACTIVE);
        if(statusdateAsc.size()==0) return null;
        return statusdateAsc.get(0).getStatusdate();
    }

    public List<UserStatus> findByUserAndTypeAndStatusOrderByStatusdateAsc(ConsultantType type, StatusType status) {
        return getStatuses()
                .stream()
                .filter(userStatus -> userStatus.getStatus().equals(status) && userStatus.getType().equals(type))
                .sorted(Comparator.comparing(UserStatus::getStatusdate).reversed())
                .collect(Collectors.toList());
    }

    public Boolean checkPassword(String passwordPlainText) {
        //Span span = GlobalTracer.get().buildSpan("checkPassword").start();
        //try (Scope ignored = GlobalTracer.get().scopeManager().activate(span)) {
            //span.log(ImmutableMap.of("param", "value", "password", password));
            if (password.trim().equals("")) {
                //span.log("Failed login attempt (no password) by " + getUsername());
                return false;
            }
        //span.log("Login by " + getUsername());
        //span.log("Failed login attempt (wrong password) by " + getUsername());
        return BCrypt.checkpw(passwordPlainText, password);
        //} catch (Exception e) {
        //    span.log(e.toString());
        //} finally {
          //  span.finish();
        //}
        //return false;
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
