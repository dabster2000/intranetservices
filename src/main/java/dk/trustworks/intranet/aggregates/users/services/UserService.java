package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.apis.ResumeParserService;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
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
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.jwt.JsonWebToken;
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

    /** V2.0 **/
    public List<User> findByDate(LocalDate date) {
        return em.createNativeQuery("SELECT u.uuid, u.active, u.created, u.email, u.firstname, u.lastname, " +
                "u.gender, u.type, u.password, u.username, u.slackusername, u.birthday, u.cpr, u.phone, u.pension, " +
                "u.healthcare, u.pensiondetails, u.defects, u.photoconsent, u.other, u.primaryskilltype, us.status, " +
                "us.allocation, us.type, s.salary " +
                "FROM user u " +
                "JOIN ( " +
                "    SELECT useruuid, " +
                "           MAX(statusdate) AS max_statusdate " +
                "    FROM userstatus " +
                "    WHERE statusdate <= '"+ stringIt(date) +"' " +
                "    GROUP BY useruuid " +
                ") max_status ON u.uuid = max_status.useruuid " +
                "JOIN userstatus us ON u.uuid = us.useruuid AND max_status.max_statusdate = us.statusdate " +
                "JOIN ( " +
                "    SELECT useruuid, " +
                "           MAX(activefrom) AS max_statusdate " +
                "    FROM salary " +
                "    WHERE activefrom <= '"+ stringIt(date) +"' " +
                "    GROUP BY useruuid " +
                ") max_salary ON u.uuid = max_salary.useruuid " +
                "JOIN salary s ON u.uuid = s.useruuid AND max_salary.max_statusdate = s.activefrom; ", User.class).getResultList();
    }

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
        // Panache auto-flushes on transaction commit
    }


    /** V1.0 **/

    public List<User> listAll(boolean shallow) {
        List<User> userList = User.listAll();
        if(!shallow) userList.forEach(UserService::addChildrenToUser);
        return userList.stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    public List<User> listAllByCompany(String companyuuid, boolean shallow) {
        Company company = Company.findById(companyuuid);
        List<User> userList = Employee.<Employee>stream("company = ?1", company).map(Employee::getUser).toList();
        if(!shallow) userList.forEach(UserService::addChildrenToUser);
        return userList.stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    //@CacheResult(cacheName = "user-cache")
    public User findById(String uuid, boolean shallow) {
        User user = User.findById(uuid);
        if(user!=null && !shallow) return UserService.addChildrenToUser(user);
        return user;
    }

    public User findByUsername(String username, boolean shallow) {
        User user = User.findByUsername(username).orElse(new User());
        if(!shallow) return UserService.addChildrenToUser(user);
        return user;
    }

    public List<User> findUsersByDateAndStatusListAndTypes(LocalDate date, String[] statusArray, String[] consultantTypesArray, boolean shallow) {
        String sql = "SELECT DISTINCT (u.uuid), u.cpr, u.phone, u.pension, u.healthcare, u.pensiondetails, u.defects, u.photoconsent, u.other, u.active, u.birthday, " +
                "u.azure_oid, u.azure_issuer, " +
                "u.created, u.email, u.primaryskilltype, u.primary_skill_level, u.firstname, u.lastname, u.gender, u.password, u.slackusername, u.username, u.type " +
                "FROM user as u " +
                "LEFT OUTER JOIN salary ON u.uuid = salary.useruuid " +
                "LEFT OUTER JOIN roles ON u.uuid = roles.useruuid " +
                "LEFT OUTER JOIN user_contactinfo ON u.uuid = user_contactinfo.useruuid " +
                "LEFT JOIN ( " +
                "SELECT yt.uuid, yt.useruuid, yt.status, yt.statusdate, yt.allocation, yt.type " +
                "FROM userstatus as yt " +
                "INNER JOIN ( " +
                "SELECT uuid, useruuid, max(statusdate) created " +
                "FROM userstatus as us " +
                "WHERE statusdate <= '"+date+"' " +
                "GROUP BY useruuid " +
                ") as ss " +
                "ON yt.statusdate = ss.created AND yt.useruuid = ss.useruuid " +
                "WHERE yt.status IN ('"+String.join("','", statusArray)+"') AND yt.type IN ('"+String.join("','", consultantTypesArray)+"') " +
                ") as kk " +
                "ON u.uuid = kk.useruuid " +
                "WHERE kk.status IN ('"+String.join("','", statusArray)+"') AND kk.type IN ('"+String.join("','", consultantTypesArray)+"') " +
                "ORDER BY u.username";
        List<User> userList = em.createNativeQuery(sql, User.class).getResultList();
        if(!shallow)
            userList.forEach(user -> addChildrenToUser(user));
        return userList.stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    public List<User> findUsersByDateAndStatusListAndTypesAndCompany(String companyuuid, LocalDate date, String[] statusArray, String[] consultantTypesArray, boolean shallow) {
        String sql = "SELECT DISTINCT (u.uuid), u.cpr, u.phone, u.pension, u.healthcare, u.pensiondetails, u.defects, u.photoconsent, u.other, u.active, u.birthday, " +
                "u.azure_oid, u.azure_issuer, " +
                "u.created, u.email, u.primaryskilltype, u.primary_skill_level, u.firstname, u.lastname, u.gender, u.password, u.slackusername, u.username, " +
                "u.type " +
                "FROM user as u " +
                "LEFT OUTER JOIN salary ON u.uuid = salary.useruuid " +
                "LEFT OUTER JOIN roles ON u.uuid = roles.useruuid " +
                "LEFT OUTER JOIN user_contactinfo ON u.uuid = user_contactinfo.useruuid " +
                "LEFT JOIN ( " +
                "SELECT yt.uuid, yt.useruuid, yt.status, yt.statusdate, yt.allocation, yt.type " +
                "FROM userstatus as yt " +
                "INNER JOIN ( " +
                "SELECT uuid, useruuid, max(statusdate) created " +
                "FROM userstatus as us " +
                "WHERE statusdate <= '"+date+"' " +
                "GROUP BY useruuid " +
                ") as ss " +
                "ON yt.statusdate = ss.created AND yt.useruuid = ss.useruuid " +
                "WHERE yt.status IN ('"+String.join("','", statusArray)+"') AND yt.type IN ('"+String.join("','", consultantTypesArray)+"') " +
                ") as kk " +
                "ON u.uuid = kk.useruuid " +
                "WHERE kk.status IN ('"+String.join("','", statusArray)+"') AND kk.type IN ('"+String.join("','", consultantTypesArray)+"') " +
                "ORDER BY u.username";
        List<User> userList = em.createNativeQuery(sql, User.class).getResultList();
        if(!shallow)
            userList.forEach(UserService::addChildrenToUser);
        return userList.stream().sorted(Comparator.comparing(User::getUsername)).collect(Collectors.toList());
    }

    public static User addChildrenToUser(User user) {
        user.getTeams().addAll(TeamRole.getTeamrolesByUser(user.getUuid()));
        user.getRoleList().addAll(Role.findByUseruuid(user.getUuid()));
        user.setUserContactinfo(UserContactinfo.findByUseruuid(user.getUuid()));
        user.getStatuses().addAll(UserStatus.findByUseruuid(user.getUuid()));
        user.getSalaries().addAll(Salary.findByUseruuid(user.getUuid()));
        user.getUserBankInfos().addAll(UserBankInfo.findByUseruuid(user.getUuid()));
        user.setUserAccount(UserAccount.findById(user.getUuid()));
        return user;
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
    public List<User> findUsersByDateAndStatusListAndTypes(LocalDate date, String statusList, String consultantTypes, boolean shallow) {
        String[] statusArray = split(statusList);
        String[] consultantTypesArray = split(consultantTypes);
        return new ArrayList<>(findUsersByDateAndStatusListAndTypes(date, statusArray, consultantTypesArray, shallow));
    }

    public List<User> findUsersByDateAndStatusListAndTypesAndCompany(String companyuuid, LocalDate date, String statusList, String consultantTypes, boolean shallow) {
        String[] statusArray = split(statusList);
        String[] consultantTypesArray = split(consultantTypes);
        Employee.<Employee>stream("company = ?1 and " +
                "date = ?2 and " +
                "status in ('"+String.join("','", statusArray)+"') and " +
                "type in ('"+String.join("','", consultantTypesArray)+"') ", Company.findById(companyuuid))
                .map(Employee::getUser)
                .forEach(UserService::addChildrenToUser);
        return new ArrayList<>(findUsersByDateAndStatusListAndTypesAndCompany(companyuuid, date, statusArray, consultantTypesArray, shallow));
    }

    //@CacheResult(cacheName = "user-cache")
    public List<User> getActiveConsultantsByFiscalYear(String intFiscalYear) {
        LocalDate fiscalYear = LocalDate.of(Integer.parseInt(intFiscalYear), 7,1);

        Map<String, User> users = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            LocalDate date = fiscalYear.plusMonths(i);
            findUsersByDateAndStatusListAndTypes(date, new String[]{ACTIVE.name()}, new String[]{ConsultantType.CONSULTANT.name()}, true).forEach(user -> users.put(user.getUuid(), user));
        }
        return new ArrayList<>(users.values());
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
        user.setActive(true);
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
            User.update("active = ?1, " +
                            "email = ?2, " +
                            "firstname = ?3, " +
                            "lastname = ?4, " +
                            "username = ?5, " +
                            "slackusername = ?6, " +
                            "birthday = ?7, " +
                            "gender = ?8, " +
                            "cpr = ?9, " +
                            "phone = ?10, " +
                            "pension = ?11, " +
                            "healthcare = ?12, " +
                            "pensiondetails = ?13, " +
                            "birthday = ?14, " +
                            "defects = ?15, " +
                            "photoconsent = ?16, " +
                            "other = ?17, " +
                            "primaryskilltype = ?18, " +
                            "primaryskilllevel = ?19 " +
                            "WHERE uuid like ?20 ",
                    user.isActive(),
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
