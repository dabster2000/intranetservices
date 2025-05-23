package dk.trustworks.intranet.aggregates.users.resources;

import dk.trustworks.intranet.achievementservice.model.Achievement;
import dk.trustworks.intranet.achievementservice.services.AchievementService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregates.users.events.CreateUserEvent;
import dk.trustworks.intranet.aggregates.users.events.UpdateUserEvent;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.aggregateservices.FinanceService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.services.BubbleService;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.workservice.model.Week;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WeekService;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.UserFinanceDocument;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.ExpenseService;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import dk.trustworks.intranet.fileservice.resources.UserDocumentResource;
import dk.trustworks.intranet.knowledgeservice.model.CKOExpense;
import dk.trustworks.intranet.knowledgeservice.model.Certification;
import dk.trustworks.intranet.knowledgeservice.model.CkoCourseParticipant;
import dk.trustworks.intranet.knowledgeservice.model.UserCertification;
import dk.trustworks.intranet.knowledgeservice.services.CertificationService;
import dk.trustworks.intranet.knowledgeservice.services.CkoExpenseService;
import dk.trustworks.intranet.knowledgeservice.services.CourseService;
import dk.trustworks.intranet.userservice.model.User;
import dk.trustworks.intranet.userservice.model.UserResume;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.annotations.GZIP;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dk.trustworks.intranet.userservice.model.enums.StatusType.*;
import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static java.net.URLDecoder.decode;
import static java.nio.charset.StandardCharsets.UTF_8;

@Tag(name = "user")
@Path("/users")
@JBossLog
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class UserResource {


    @Inject
    UserService userAPI;

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    CkoExpenseService knowledgeExpenseAPI;

    @Inject
    AchievementService achievementsAPI;

    @Inject
    WorkService workService;

    @Inject
    CertificationService certificationService;

    @Inject
    PhotoService photoAPI;

    @Inject
    UserDocumentResource documentAPI;

    @Inject
    ContractService contractService;

    @Inject
    WeekService weekService;

    @Inject
    FinanceService financeService;

    @Inject
    BubbleService bubbleService;

    @Inject
    UserService userService;

    @Inject
    ExpenseService expenseService;

    @Inject
    CourseService courseService;

    /** DTO for linking Azure fields **/
    public static class AzureLinkDto {
        public String azureOid;
        public String issuer;
    }

    /**
     * 1) Look up by azureOid + issuer
     * GET /users/search/findByAzureOidAndIssuer?azureOid=…&issuer=…
     */
    @GET
    @PermitAll
    @Path("/search/findByAzureOidAndIssuer")
    public Response findByAzureOidAndIssuer(
            @QueryParam("azureOid") String azureOid,
            @QueryParam("issuer")   String issuer) {

        User u = userService.findByAzureOidAndIssuer(azureOid, issuer);
        return u == null
                ? Response.noContent().build()
                : Response.ok(u).build();
    }

    /**
     * 2) Link (or update) Azure fields on an existing user
     * PUT /users/{uuid}/azure
     *   { "azureOid": "...", "issuer": "..." }
     */
    @PUT
    @Path("/{uuid}/azure")
    public Response linkAzureAccount(
            @PathParam("uuid") String uuid,
            AzureLinkDto dto) {

        userService.linkAzureAccount(uuid, dto.azureOid, dto.issuer);
        return Response.noContent().build();
    }

    @GET
    public List<User> listAll(@QueryParam("username") Optional<String> username, @QueryParam("shallow") Optional<String> shallow) {
        String shallowValue = shallow.orElse("false");
        return userService.clearSalaries(username.map(s -> Collections.singletonList(userAPI.findByUsername(s, Boolean.getBoolean(shallowValue)))).orElseGet(() -> userAPI.listAll(Boolean.getBoolean(shallowValue))));
    }

    @GET
    @Path("/{uuid}")
    public User findById(@PathParam("uuid") String uuid, @QueryParam("shallow") Optional<String> shallow) {
        String shallowValue = shallow.orElse("false");
        User user = userAPI.findById(uuid, Boolean.getBoolean(shallowValue));
        return userService.clearSalaries(user);
    }

    @GET
    @Path("/search/findUsersByDateAndStatusListAndTypes")
    public List<User> findUsersByDateAndStatusListAndTypes(@QueryParam("date") String date, @QueryParam("consultantStatusList") String statusList, @QueryParam("consultantTypes") String consultantTypes, @QueryParam("shallow") Optional<String> shallow) {
        String shallowValue = shallow.orElse("false");
        return userService.clearSalaries(userAPI.findUsersByDateAndStatusListAndTypes(dateIt(date), statusList, consultantTypes, Boolean.getBoolean(shallowValue)));
    }

    @GET
    @Path("/consultants/search/findByFiscalYear")
    public List<User> getActiveConsultantsByFiscalYear(@QueryParam("fiscalyear") String intFiscalYear) {
        return userService.clearSalaries(userAPI.getActiveConsultantsByFiscalYear(intFiscalYear));
    }

    @GET
    @Path("/employed/all")
    public List<User> findCurrentlyEmployedUsers(@QueryParam("shallow") Optional<String> shallow) {
        String[] statusList = {ACTIVE.toString(), PAID_LEAVE.toString(), MATERNITY_LEAVE.toString(), NON_PAY_LEAVE.toString()};
        return userService.clearSalaries(findUsersByDateAndStatusListAndTypes(
                LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                String.join(",", statusList),
                String.join(",", Stream.of(ConsultantType.CONSULTANT, ConsultantType.STAFF, ConsultantType.STUDENT).map(Enum::toString).toArray(String[]::new)), shallow)
                .stream()
                .sorted(Comparator.comparing(User::getUsername))
                .collect(Collectors.toList()));
    }

    @GET
    @Path("/{uuid}/work")
    public List<WorkFull> getUserWorkByPeriod(@PathParam("uuid") String useruuid, @QueryParam("fromdate") Optional<String> fromDate, @QueryParam("todate") Optional<String> toDate) {
        return workService.findByPeriodAndUserUUID(dateIt(fromDate.orElse("2014-02-01")), dateIt(toDate.orElse(stringIt(LocalDate.now()))), useruuid);
    }

    @GET
    @Path("/{useruuid}/contracts")
    public List<Contract> findTimeActiveConsultantContracts(@PathParam("useruuid") String useruuid, @QueryParam("activedate") String activeon) {
        return contractService.findTimeActiveConsultantContracts(useruuid, dateIt(activeon));
    }

    @GET
    @Path("/{useruuid}/weeks/{year}/{weeknumber}")
    public List<Week> findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(@PathParam("useruuid") String useruuid, @PathParam("weeknumber") String strWeeknumber, @PathParam("year") String strYear) {
        int year = Integer.parseInt(strYear);
        int weeknumber = Integer.parseInt(strWeeknumber);
        return weekService.findByWeeknumberAndYearAndUseruuidOrderBySortingAsc(weeknumber, year, useruuid);
    }

    @GET
    @Path("/{useruuid}/projects")
    public Set<Project> getProjectsByUser(@PathParam("useruuid") String useruuid, @QueryParam("date") String date) {
        List<Contract> contracts = contractService.findTimeActiveConsultantContracts(useruuid, dateIt(date));
        Set<Project> projectsList = new HashSet<>();
        for (Contract contract : contracts) {
            List<Project> projectsByContract = contractService.findProjectsByContract(contract.getUuid());
            projectsList.addAll(projectsByContract);
        }
        return projectsList;
    }

    @GET
    @Path("/{useruuid}/bubbles/active")
    public List<Bubble> findActiveBubblesByUseruuid(@PathParam("useruuid") String useruuid) {
        return bubbleService.findActiveBubblesByUseruuid(useruuid);
    }

    @GET
    @Path("/{useruuid}/knowledge/expenses")
    public List<CKOExpense> findKnowledgeExpensesByUseruuid(@PathParam("useruuid") String useruuid) {
        return knowledgeExpenseAPI.findExpensesByUseruuid(useruuid);
    }

    @GET
    @Path("/{useruuid}/achievements")
    public List<Achievement> findUserAchievements(@PathParam("useruuid") String useruuid) {
        return achievementsAPI.findByUseruuid(useruuid);
    }

    @GET
    @Path("/{useruuid}/vacation")
    public List<WorkFull> findVacationByUser(@PathParam("useruuid") String useruuid) {
        return workService.findVacationByUser(useruuid);
    }

    @GET
    @Path("/vacation/sum")
    public Map<String, Map<String, Double>> findVacationSumByMonth() {
        return workService.findVacationSumByMonth();
    }

    @GET
    @Path("/{useruuid}/rate/average")
    public GraphKeyValue calculateAverageRatePerFiscalYear(@PathParam("useruuid") String useruuid, @QueryParam("fiscalyear") int intFiscalyear) {
        LocalDate date = LocalDate.of(intFiscalyear, 7,1);

        User user = findById(useruuid, Optional.of("true"));
        AtomicReference<Double> sumHours = new AtomicReference<>(0.0);
        double sumRate = workService.findByPeriodAndUserUUID(date, date.plusYears(1), user.getUuid()).stream().filter(work -> work.getRate() > 0).peek(work -> sumHours.updateAndGet(v -> v + work.getWorkduration())).mapToDouble(work1 -> work1.getRate()*work1.getWorkduration()).sum();

        return new GraphKeyValue(user.getUuid(), user.getUsername(), sumRate / sumHours.get());
    }

    @GET
    @Path("/expenses")
    public List<UserFinanceDocument> getConsultantsExpensesByMonth(@QueryParam("month") String month) {
        List<User> users = userAPI.findUsersByDateAndStatusListAndTypes(dateIt(month), "ACTIVE, NON_PAY_LEAVE", "CONSULTANT", true);
        return financeService.getUserFinanceData().stream().filter(userFinanceDocument -> users.stream().map(User::getUuid).anyMatch(s -> s.equals(userFinanceDocument.getUser().getUuid()))).collect(Collectors.toList());
    }

    @GET
    @Path("{useruuid}/expenses")
    public List<Expense> findExpensesByUser(@PathParam("useruuid") String useruuid) {
        return expenseService.findByUserLimited(useruuid);
    }

    @GET
    @Path("/{useruuid}/sickness")
    public List<WorkFull> findSicknessByUser(@PathParam("useruuid") String useruuid) {
        return workService.findSicknessByUser(useruuid);
    }

    @GET
    @Path("/sickness/sum")
    public Map<String, Map<String, Double>> findSicknessSumByMonth() {
        return workService.findSicknessSumByMonth();
    }

    @GET
    @Path("/{useruuid}/maternityleave")
    public List<WorkFull> findMaternityLeaveByUser(@PathParam("useruuid") String useruuid) {
        return workService.findMaternityLeaveByUser(useruuid);
    }

    @GET
    @Path("/maternityleave/sum")
    public Map<String, Map<String, Double>> findMaternityLeaveSumByMonth() {
        return workService.findMaternityLeaveSumByMonth();
    }

    @PUT
    @Path("/{uuid}")
    public void updateOne(@PathParam("uuid") String uuid, User user) {
        System.out.println("UserResource.updateOne");
        System.out.println("uuid = " + uuid + ", user = " + user);
        UpdateUserEvent event = new UpdateUserEvent(user.getUuid(), user);
        userService.updateOne(user);
        aggregateEventSender.handleEvent(event);
    }

    @POST
    public void createUser(User user) {
        System.out.println("UserResource.createUser");
        System.out.println("user = " + user);
        userService.createUser(user);
        CreateUserEvent event = new CreateUserEvent(user.getUuid(), user);
        aggregateEventSender.handleEvent(event);
    }

    /*
    @SneakyThrows
    @PUT
    @PermitAll
    @Path("/{username}/password/{newpassword}")
    public void updatePasswordByUsername(@PathParam("username") String username, @PathParam("newpassword") String newPassword) {
        userAPI.updatePasswordByUsername(username, decode(newPassword, UTF_8));
    }

     */

    @POST
    @Path("/{uuid}/password/{newpassword}")
    public void updatePasswordByUUID(@PathParam("uuid") String uuid, @PathParam("newpassword") String newPassword) {
        userAPI.updatePasswordByUUID(uuid, decode(newPassword, UTF_8));
    }

    @POST
    @PermitAll
    @Path("/command/confirmpasswordchange/{key}")
    public void confirmPasswordChange(@PathParam("key") String key) {
        userAPI.confirmPasswordChange(key);
    }

    @PUT
    @Path("/{uuid}/birthday")
    public void updateBirthday(@PathParam("uuid") String uuid, User user) {
        UpdateUserEvent event = new UpdateUserEvent(user.getUuid(), user);
        aggregateEventSender.handleEvent(event);
        //userAPI.updateBirthday(uuid, user);
    }

    @GET
    @Path("/{useruuid}/photo")
    public File findPhotoByUserUUID(@PathParam("useruuid") String useruuid) {
        return photoAPI.findPhotoByRelatedUUID(useruuid);
    }

    @GET
    @Path("/{useruuid}/documents")
    public List<File> findDocumentsByUserUUID(@PathParam("useruuid") String useruuid) {
        return documentAPI.findDocumentsByUserUUID(useruuid);
    }

    @POST
    @GZIP
    @Path("/{useruuid}/documents")
    public void saveDocument(File document) {
        documentAPI.save(document);
    }

    @GET
    @Path("/{useruuid}/courses/participants")
    public List<CkoCourseParticipant> findAllParticipantsByUser(@PathParam("useruuid") String useruuid) {
        return courseService.findAllParticipantsByUser(User.findById(useruuid));
    }

    @GET
    @Path("/{useruuid}/certifications")
    public List<Certification> findCertificationsByUserUUID(@PathParam("useruuid") String useruuid) {
        return certificationService.findAllCertificationsByUseruuid(useruuid);
    }

    @POST
    @Path("/{useruuid}/certifications/{certificationuuid}")
    @Transactional
    public void addUserCertification(@PathParam("useruuid") String useruuid, @PathParam("certificationuuid") String certificationuuid, Certification body) {
        if(certificationService.findAllUserCertifications(useruuid).stream().noneMatch(userCertification -> userCertification.getCertificationuuid().equals(certificationuuid))) {
            new UserCertification(useruuid, certificationuuid).persist();
        }
    }

    @DELETE
    @Path("/{useruuid}/certifications/{certificationuuid}")
    @Transactional
    public void deleteUserCertification(@PathParam("useruuid") String useruuid, @PathParam("certificationuuid") String certificationuuid) {
        for (UserCertification userCertification : certificationService.findAllUserCertifications(useruuid)) {
            if (userCertification.getCertificationuuid().equals(certificationuuid)) {
                userCertification.delete();
            }
        }
    }

    /**
     * Returns a paginated list of hourly work items for the given user.
     * Query parameters: page (default 0) and size (default 10).
     */
    @GET
    @Path("/{useruuid}/hourlywork")
    public List<Work> getHourlyWorkPaged(@PathParam("useruuid") String useruuid,
                                         @QueryParam("page") @DefaultValue("0") int page,
                                         @QueryParam("size") @DefaultValue("10") int size) {
        return workService.findHourlyWorkPaged(useruuid, page, size);
    }

    /**
     * Returns the total count of hourly work items for the given user.
     */
    @GET
    @Path("/{useruuid}/hourlywork/count")
    public long countHourlyWork(@PathParam("useruuid") String useruuid) {
        return workService.countHourlyWork(useruuid);
    }

    /**
     * Returns all hourly work items for the given user.
     */
    @GET
    @Path("/{useruuid}/hourlywork/all")
    public List<Work> getAllHourlyWork(@PathParam("useruuid") String useruuid) {
        return workService.findHourlyWork(useruuid);
    }

    @GET
    @Path("/{useruuid}/resume")
    public UserResume findResumeByUserUUID(@PathParam("useruuid") String useruuid) {
        return userService.findUserResume(useruuid);
    }

    @POST
    @Path("/{useruuid}/resume")
    public void saveResume(@PathParam("useruuid") String useruuid, File resume) throws IOException {
        System.out.println("UserResource.saveResume");
        System.out.println("useruuid = " + useruuid + ", resume = " + resume);
        userService.updateResume(useruuid, resume);
    }
}
