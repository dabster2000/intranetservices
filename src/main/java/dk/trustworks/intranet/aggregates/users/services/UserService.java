package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.apis.ResumeParserService;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.domain.cv.entity.UserResume;
import dk.trustworks.intranet.domain.user.entity.*;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.userservice.dto.LoginTokenResult;
import dk.trustworks.intranet.userservice.model.*;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.RoleType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.userservice.services.LoginService;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.hibernate.query.NativeQuery;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.userservice.model.enums.ConsultantType.*;
import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static io.smallrye.config.common.utils.StringUtil.split;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

@JBossLog
@ApplicationScoped
public class UserService {

    @Inject
    EntityManager em;

    @Inject
    JsonWebToken jwt;

    @Inject
    MailResource mailAPI;

    @Inject
    LoginService loginService;
    @Inject
    ResumeParserService resumeParserService;

    /**
     * 1) Find user by Azure OID + issuer
     */
    public User findByAzureOidAndIssuer(String azureOid, String issuer) {
        return User.<User>find(
                        "azureOid = ?1 and issuer = ?2",
                        azureOid, issuer)
                .firstResultOptional()
                .orElse(null);
    }

    /**
     * 2) Link or update the Azure OID + issuer on an existing user
     */
    @Transactional
    public void linkAzureAccount(String userUuid, String azureOid, String issuer) {
        User user = User.findById(userUuid);
        if (user == null) {
            throw new NotFoundException("User not found: " + userUuid);
        }
        user.azureOid = azureOid;
        user.issuer   = issuer;
    }

    public List<User> listAll(boolean shallow) {
        log.infof("listAll(%s)", shallow);
        List<User> userList = User.listAll(Sort.ascending("username")); // push sort to DB
        log.infof("listAll(%s) found %d users", shallow, userList.size());
        if (!shallow) hydrateUsers(userList); // bulk instead of per-user adds
        return userList;
    }

    public List<User> listAllByCompany(String companyuuid, boolean shallow) {
        Company company = Company.findById(companyuuid);
        List<User> userList = Employee.<Employee>stream("company = ?1", company).map(Employee::getUser).toList();
        if(!shallow) userList.forEach(UserService::addChildrenToUser);
        return userList.stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    public User findById(String uuid, boolean shallow) {
        User user = User.findById(uuid);
        if (user != null && !shallow) addChildrenToUser(user); // single object is fine
        return user;
    }

    public User findByUsername(String username, boolean shallow) {
        User user = User.<User>find("username = ?1", username)  // '=' not 'like'
                .firstResultOptional()
                .orElse(new User());
        if (!shallow) addChildrenToUser(user);
        return user;
    }

    public List<User> findUsersByDateAndStatusListAndTypes(LocalDate date,
                                                           String[] statusArray,
                                                           String[] consultantTypesArray,
                                                           boolean shallow) {
        String sql = """
        WITH latest_status AS (
            SELECT us.useruuid, us.status, us.statusdate, us.allocation, us.type,
                   ROW_NUMBER() OVER (PARTITION BY us.useruuid ORDER BY us.statusdate DESC) AS rn
            FROM userstatus us
            WHERE us.statusdate <= :asOfDate
        )
        SELECT u.uuid, u.created, u.email, u.firstname, u.lastname,
               u.gender, u.type, u.password, u.username, u.slackusername, u.birthday,
               u.cpr, u.phone, u.pension, u.healthcare, u.pensiondetails, u.defects,
               u.photoconsent, u.other, u.primaryskilltype, u.primary_skill_level,
               u.azure_oid, u.azure_issuer
        FROM user u
        JOIN latest_status ls ON ls.useruuid = u.uuid AND ls.rn = 1
        WHERE ls.status IN (:statuses)
          AND ls.type   IN (:types)
        ORDER BY u.username
        """;

        NativeQuery<User> q = em.createNativeQuery(sql, User.class).unwrap(NativeQuery.class);
        q.setParameter("asOfDate", date);
        q.setParameterList("statuses", Arrays.asList(statusArray));
        q.setParameterList("types", Arrays.asList(consultantTypesArray));

        List<User> users = q.getResultList();
        if (!shallow) hydrateUsers(users);
        return users;
    }

    public List<User> findUsersByDateAndStatusListAndTypesAndCompany(String companyuuid,
                                                                     LocalDate date,
                                                                     String[] statusArray,
                                                                     String[] consultantTypesArray,
                                                                     boolean shallow) {
        String sql = """
        WITH latest_status AS (
            SELECT us.useruuid, us.status, us.statusdate, us.allocation, us.type, us.companyuuid,
                   ROW_NUMBER() OVER (PARTITION BY us.useruuid ORDER BY us.statusdate DESC) AS rn
            FROM userstatus us
            WHERE us.statusdate <= :asOfDate
              AND us.companyuuid = :companyUuid
        )
        SELECT u.uuid, u.created, u.email, u.firstname, u.lastname,
               u.gender, u.type, u.password, u.username, u.slackusername, u.birthday,
               u.cpr, u.phone, u.pension, u.healthcare, u.pensiondetails, u.defects,
               u.photoconsent, u.other, u.primaryskilltype, u.primary_skill_level,
               u.azure_oid, u.azure_issuer
        FROM user u
        JOIN latest_status ls ON ls.useruuid = u.uuid AND ls.rn = 1
        WHERE ls.status IN (:statuses)
          AND ls.type   IN (:types)
        ORDER BY u.username
        """;
        NativeQuery<User> q = em.createNativeQuery(sql, User.class)
                .unwrap(NativeQuery.class);
        q.setParameter("asOfDate", date);
        q.setParameter("companyUuid", companyuuid);
        q.setParameterList("statuses", Arrays.asList(statusArray));
        q.setParameterList("types", Arrays.asList(consultantTypesArray));

        List<User> users = q.getResultList();
        if (!shallow) hydrateUsers(users);
        return users;
    }


    public static User addChildrenToUser(User user) {
        user.getTeams().addAll(TeamRole.getTeamrolesByUser(user.getUuid()));
        user.getRoleList().addAll(Role.findByUseruuid(user.getUuid()));
        user.setUserContactinfo(UserContactinfo.findByUseruuid(user.getUuid()));
        user.getStatuses().addAll(UserStatus.findByUseruuid(user.getUuid()));
        user.getSalaries().addAll(Salary.findByUseruuid(user.getUuid()));
        user.getUserBankInfos().addAll(UserBankInfo.findByUseruuid(user.getUuid()));
        user.getCareerLevels().addAll(UserCareerLevel.findByUseruuid(user.getUuid()));
        user.setUserAccount(UserAccount.findById(user.getUuid()));
        return user;
    }

    private void hydrateUsers(List<User> users) {
        if (users == null || users.isEmpty()) return;

        List<String> ids = users.stream().map(User::getUuid).toList();

        // Teams (TeamRole)
        Map<String, List<TeamRole>> teamsByUser = TeamRole.<TeamRole>list("useruuid in ?1", ids)
                .stream().collect(groupingBy(TeamRole::getUseruuid));

        // Roles
        Map<String, List<Role>> rolesByUser = Role.<Role>list("useruuid in ?1", ids)
                .stream().collect(groupingBy(Role::getUseruuid));

        // Contact info (one per user)
        Map<String, UserContactinfo> contactByUser = UserContactinfo.<UserContactinfo>list("useruuid in ?1", ids)
                .stream().collect(toMap(UserContactinfo::getUseruuid, ci -> ci, (a,b) -> a));

        // Statuses
        Map<String, List<UserStatus>> statusesByUser = UserStatus.<UserStatus>list("useruuid in ?1 order by statusdate", ids)
                .stream().collect(groupingBy(UserStatus::getUseruuid));

        // Salaries
        Map<String, List<Salary>> salariesByUser = Salary.<Salary>list("useruuid in ?1 order by activefrom", ids)
                .stream().collect(groupingBy(Salary::getUseruuid));

        // Bank infos
        Map<String, List<UserBankInfo>> bankByUser = UserBankInfo.<UserBankInfo>list("useruuid in ?1 order by activeDate", ids)
                .stream().collect(groupingBy(UserBankInfo::getUseruuid));

        // Career levels
        Map<String, List<UserCareerLevel>> careerByUser = UserCareerLevel.<UserCareerLevel>list("useruuid in ?1 order by activeFrom", ids)
                .stream().collect(groupingBy(UserCareerLevel::getUseruuid));

        // External accounts (UserAccount)
        Map<String, UserAccount> accountByUser = UserAccount.<UserAccount>list("useruuid in ?1", ids)
                .stream().collect(toMap(UserAccount::getUseruuid, ua -> ua, (a,b) -> a));

        // Attach – use setters to avoid duplicate add()
        users.forEach(u -> {
            u.setTeams(new ArrayList<>(teamsByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setRoleList(new ArrayList<>(rolesByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setUserContactinfo(contactByUser.get(u.getUuid()));
            u.setStatuses(new ArrayList<>(statusesByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setSalaries(new ArrayList<>(salariesByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setUserBankInfos(new ArrayList<>(bankByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setCareerLevels(new ArrayList<>(careerByUser.getOrDefault(u.getUuid(), Collections.emptyList())));
            u.setUserAccount(accountByUser.get(u.getUuid()));
        });
    }

    public List<UserStatus> findUserStatuses(String useruuid) {
        return UserStatus.findByUseruuid(useruuid);
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> filterForActiveTeamMembers(LocalDate month, List<User> usersInTeam) {
        List<User> allActiveConsultants = findUsersByDateAndStatusListAndTypes(month, new String[]{ACTIVE.toString(), PAID_LEAVE.toString(), MATERNITY_LEAVE.toString(), NON_PAY_LEAVE.toString()}, new String[]{CONSULTANT.toString(), STAFF.toString(), STUDENT.toString()}, true);
        return usersInTeam.stream()
                .filter(two -> allActiveConsultants.stream()
                        .anyMatch(one -> one.getUuid().equals(two.getUuid())))
                .collect(Collectors.toList());
    }


    //@CacheResult(cacheName = "user-cache")
    public int calcMonthSalaries(LocalDate date, String... consultantTypes) {
        String[] statusList = {ACTIVE.toString(), PAID_LEAVE.toString(), MATERNITY_LEAVE.toString()};
        return findUsersByDateAndStatusListAndTypes(date, statusList, consultantTypes, false)
                .stream().mapToInt(value ->
                        value.getSalaries().stream().filter(salary -> salary.getActivefrom().isBefore(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(UUID.randomUUID().toString(), 0, date, value.getUuid())).getSalary()
                ).sum();
    }

    //@CacheResult(cacheName = "user-cache")
    public UserStatus getUserStatus(User user, LocalDate date) {
        return user.getStatuses().stream().filter(value -> value.getStatusdate().isBefore(date) || value.getStatusdate().isEqual(date)).max(Comparator.comparing(UserStatus::getStatusdate)).orElse(new UserStatus(ConsultantType.STAFF, StatusType.TERMINATED, date, 0, user.getUuid()));
    }

    public Salary getUserSalary(User user, LocalDate date) {
        return user.getSalaries().stream().filter(value -> value.getActivefrom().isBefore(date) || value.getActivefrom().isEqual(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(date, 0, UUID.randomUUID().toString()));
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> findCurrentlyEmployedUsers(boolean shallow, ConsultantType... consultantType) {
        String[] statusList = {ACTIVE.toString(), NON_PAY_LEAVE.toString()};
        return findUsersByDateAndStatusListAndTypes(LocalDate.now(), statusList,
                Arrays.stream(consultantType).map(Enum::toString).toArray(String[]::new), shallow).stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> findEmployedUsersByDate(LocalDate currentDate, boolean shallow, ConsultantType... consultantType) {
        String[] statusList = {ACTIVE.toString(), NON_PAY_LEAVE.toString(), PAID_LEAVE.toString(), MATERNITY_LEAVE.toString()};
        return findUsersByDateAndStatusListAndTypes(currentDate, statusList,
                Arrays.stream(consultantType).map(Enum::toString).toArray(String[]::new), shallow);
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> findWorkingUsersByDate(LocalDate currentDate, ConsultantType... consultantType) {
        return findUsersByDateAndStatusListAndTypes(
                currentDate,
                new String[]{ACTIVE.toString()},
                Arrays.stream(consultantType).map(Enum::toString).toArray(String[]::new), true);
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> findUsersByDateAndStatusListAndTypes(LocalDate date,
                                                           String statusList,
                                                           String consultantTypes,
                                                           boolean shallow) {
        String[] statusArray = split(statusList);
        String[] consultantTypesArray = split(consultantTypes);
        return findUsersByDateAndStatusListAndTypes(date, statusArray, consultantTypesArray, shallow);
    }

    public List<User> findUsersByDateAndStatusListAndTypesAndCompany(String companyuuid,
                                                                     LocalDate date,
                                                                     String statusList,
                                                                     String consultantTypes,
                                                                     boolean shallow) {
        String[] statusArray = split(statusList);
        String[] typesArray  = split(consultantTypes);
        return findUsersByDateAndStatusListAndTypesAndCompany(companyuuid, date, statusArray, typesArray, shallow);
    }

    public List<User> getActiveConsultantsByFiscalYear(String intFiscalYear) {
        LocalDate fyStart = LocalDate.of(Integer.parseInt(intFiscalYear), 7, 1);
        LocalDate fyEnd   = fyStart.plusYears(1);

        String sql = """
        WITH ordered AS (
            SELECT us.useruuid, us.status, us.type, us.statusdate,
                   LEAD(us.statusdate, 1, DATE '9999-12-31')
                     OVER (PARTITION BY us.useruuid ORDER BY us.statusdate) AS next_date
            FROM userstatus us
            WHERE us.type = 'CONSULTANT'
        ),
        active_overlap AS (
            SELECT DISTINCT o.useruuid
            FROM ordered o
            WHERE o.status = 'ACTIVE'
              AND o.statusdate < :fyEnd
              AND o.next_date > :fyStart
        )
        SELECT u.uuid, u.created, u.email, u.firstname, u.lastname,
               u.gender, u.type, u.password, u.username, u.slackusername, u.birthday,
               u.cpr, u.phone, u.pension, u.healthcare, u.pensiondetails, u.defects,
               u.photoconsent, u.other, u.primaryskilltype, u.primary_skill_level,
               u.azure_oid, u.azure_issuer
        FROM user u
        JOIN active_overlap ao ON ao.useruuid = u.uuid
        ORDER BY u.username
        """;

        return em.createNativeQuery(sql, User.class)
                .setParameter("fyStart", fyStart)
                .setParameter("fyEnd", fyEnd)
                .getResultList(); // shallow by design; call hydrateUsers() if you need the deep graph
    }


    public LoginTokenResult login(String username, String password) throws Exception {
        return loginService.login(username, password);
    }

    public LoginTokenResult createSystemToken(String role) throws Exception {
        return loginService.createSystemToken(role);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void createUser(User user) {
        System.out.println("UserService.createUser");
        log.info("Create user: "+user);
        if(User.find("uuid like ?1 or username like ?2", user.getUuid(), user.getUsername()).count() > 0) return;
        System.out.println("User does not exist");
        log.info("User does not exist");
        user.setCreated(LocalDate.now());
        user.setBirthday(LocalDate.of(1900, 1, 1));
        user.setType("USER");
        UserContactinfo userContactinfo = new UserContactinfo(UUID.randomUUID().toString(), "", "", "", "", user.getUuid());
        user.setUserContactinfo(userContactinfo);
        user.setSalaries(new ArrayList<>());
        User.persist(user);
        Role.persist(new Role(UUID.randomUUID().toString(), RoleType.USER, user.getUuid()));
        UserContactinfo.persist(userContactinfo);
        System.out.println("User created");
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void updateOne(User user) {
        System.out.println("UserService.updateOne");
        try {
            User.update("email = ?1, " +
                            "firstname = ?2, " +
                            "lastname = ?3, " +
                            "username = ?4, " +
                            "slackusername = ?5, " +
                            "birthday = ?6, " +
                            "gender = ?7, " +
                            "cpr = ?8, " +
                            "phone = ?9, " +
                            "pension = ?10, " +
                            "healthcare = ?11, " +
                            "pensiondetails = ?12, " +
                            "birthday = ?13, " +
                            "defects = ?14, " +
                            "photoconsent = ?15, " +
                            "other = ?16, " +
                            "primaryskilltype = ?17, " +
                            "primaryskilllevel = ?18 " +
                            "WHERE uuid like ?19 ",
                    user.getEmail(),
                    user.getFirstname(),
                    user.getLastname(),
                    user.getUsername(),
                    user.getSlackusername(),
                    user.getBirthday(),
                    user.getGender(),
                    user.getCpr(),
                    user.getPhone(),
                    user.isPension(),
                    user.isHealthcare(),
                    user.getPensiondetails(),
                    user.getBirthday(),
                    user.getDefects(),
                    user.isPhotoconsent(),
                    user.getOther(),
                    user.getPrimaryskilltype(),
                    user.getPrimaryskilllevel(),
                    user.getUuid());
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("User updated");
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void updatePasswordByUsername(String username, String newPassword) {
        log.info("UserResource.updatePasswordByUsername");
        log.info("username = " + username + ", newPassword = " + newPassword);
        User user = (User) User.find("username like ?1", username).firstResultOptional().orElseThrow(NotFoundException::new);
        String key = UUID.randomUUID().toString();
        String hashpw = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        PasswordChange.persist(new PasswordChange(key, user.getUuid(), hashpw, "'INTRA'"));
        mailAPI.sendingHTML(new TrustworksMail(UUID.randomUUID().toString(), user.getEmail(), "Confirm reset password request", "Click here to reset password: http://intra.trustworks.dk/confirmchange/"+key));
        log.info("...mail sent");
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void updatePasswordByUUID(String uuid, String newPassword) {
        log.info("UserResource.updatePasswordByUUID");
        log.info("uuid = " + uuid + ", newPassword = " + newPassword);
        User user = (User) User.find("uuid like ?1", uuid).firstResultOptional().orElseThrow(NotFoundException::new);
        String hashpw = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        user.setPassword(hashpw);
        User.update("password = ?1 WHERE uuid like ?2",
                hashpw,
                uuid);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void updatePasswordBySlackid(String slackid, String newPassword) {
        log.info("UserResource.updatePasswordBySlackid");
        log.info("slackid = " + slackid + ", newPassword = " + newPassword);
        User user = (User) User.find("slackusername like ?1", slackid).firstResultOptional().orElseThrow(NotFoundException::new);
        String key = UUID.randomUUID().toString();
        String hashpw = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        PasswordChange.persist(new PasswordChange(key, user.getUuid(), hashpw, "SLACK"));
        mailAPI.sendingHTML(new TrustworksMail(UUID.randomUUID().toString(), user.getEmail(), "Confirm reset password request", "Click here to reset password: http://intra.trustworks.dk/confirmchange/"+key));
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void confirmPasswordChange(String key) {
        System.out.println("UserService.confirmPasswordChange");
        System.out.println("key = " + key);
        PasswordChange passwordChange = PasswordChange.findById(key);
        if(passwordChange.getCreated().isAfter(LocalDateTime.now().plusHours(1))) throw new NotAllowedException("Password change too late");
        System.out.println("Updating password for user "+passwordChange.getUseruuid());
        User.update("password = ?1 where uuid like ?2",
                passwordChange.getPassword(),
                passwordChange.getUseruuid());
        System.out.println("Updated");
        PasswordChange.deleteById(key);
        System.out.println("Cleaned");
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void updateBirthday(String uuid, User user) {
        User.update("birthday = ?1 WHERE uuid like ?2 ", user.getBirthday(), uuid);
    }

    public List<User> clearSalaries(List<User> users) {
        List<RoleType> roles = jwt.getGroups().stream().map(RoleType::valueOf).toList();
        List<String> validRoles = List.of("SYSTEM","CXO", "ADMIN");
        if (roles.stream().noneMatch(roleType -> validRoles.contains(roleType.name()))) {
            // users.forEach(user -> user.getSalaries().clear());
        }
        return users;
    }

    public User clearSalaries(User user) {
        return user;
        //return clearSalaries(List.of(user)).get(0);
    }

    public UserResume findUserResume(String useruuid) {
        return UserResume.<UserResume>find("useruuid", useruuid).firstResultOptional().orElse(new UserResume());
    }

    @Transactional
    public void updateResume(String useruuid, File resume) throws IOException {
        String parsedResume = resumeParserService.parseResume(resume.getFile(), resume.getFilename());
        String htmlResumeResult = resumeParserService.convertResultToHTML(parsedResume);
        String encodedResume = Base64.getEncoder().encodeToString(htmlResumeResult.getBytes());
        UserResume.delete("useruuid", useruuid);
        new UserResume(UUID.randomUUID().toString(), useruuid, extractFirstDiv(htmlResumeResult), "", encodedResume).persist();
    }

    //@Scheduled(every = "1h") // Disabled: replaced by JBeret job 'user-resume-update' via BatchScheduler
    public void updateResumes() {
        List<UserResume> userResumes = UserResume.list("resumeVersion < "+UserResume.version);
        log.info("Updating user resumes: "+userResumes.size());
        userResumes.forEach(userResume -> {
            if(userResume.getResumeResult().isEmpty()) return;
            String parsedResume = new String(Base64.getDecoder().decode(userResume.getResumeResult()));
            String htmlResumeResult = extractFirstDiv(resumeParserService.convertResultToHTML(parsedResume));
            userResume.setResumeENG(htmlResumeResult);
            QuarkusTransaction.begin();
            UserResume.update("resumeENG = ?1, resumeVersion = ?2 where uuid like ?3", htmlResumeResult, UserResume.version, userResume.getUuid());
            QuarkusTransaction.commit();
        });
    }

    /**
     * Extracts the first complete <div>...</div> block from the input string.
     * It ignores any content outside that block.
     *
     * @param input the input string containing HTML and possibly extra text
     * @return the substring that is the first parent div container,
     *         or an empty string if no <div> is found.
     */
    public static String extractFirstDiv(String input) {
        // Find the first occurrence of a div tag.
        int start = input.indexOf("<div");
        if (start == -1) {
            return ""; // No div found
        }

        int count = 0;
        int index = start;
        while (index < input.length()) {
            // Find the next opening <div and closing </div> tags after the current index.
            int nextOpen = input.indexOf("<div", index);
            int nextClose = input.indexOf("</div>", index);

            // If no closing tag is found, break out.
            if (nextClose == -1) {
                break;
            }

            // If the next opening tag comes before the next closing tag, it means a nested div is found.
            if (nextOpen != -1 && nextOpen < nextClose) {
                count++; // Increment nesting counter.
                index = nextOpen + 4; // Move index past this opening tag.
            } else {
                // Process a closing tag.
                count--; // Decrement nesting counter.
                index = nextClose + 6; // Move index past the closing tag.
                if (count == 0) {
                    // Found the matching closing tag for the first <div>.
                    return input.substring(start, index);
                }
            }
        }
        // If no matching closing tag is found, return everything from the first <div> onward.
        return input.substring(start);
    }

    /**
     * Validates a JWT token
     *
     * @param token JWT token to validate
     * @return LoginTokenResult with validation status
     * @throws Exception if validation fails
     */
    public LoginTokenResult validateToken(String token) throws Exception {
        return loginService.validateToken(token);
    }

}
