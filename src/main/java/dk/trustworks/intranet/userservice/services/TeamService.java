package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.userservice.model.Team;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;

@JBossLog
@ApplicationScoped
public class TeamService {

    @Inject
    UserService userService;

    @Inject
    OpenAIService openAIService;

    public List<Team> listAll() {
        return Team.listAll();
    }

    public List<Team> findByRoles(String useruuid, LocalDate strDate, String... roles) {
        log.info("TeamService.findByRoles");
        log.info("useruuid = " + useruuid + ", strDate = " + strDate + ", roles = " + Arrays.deepToString(roles));
        List<Team> list = Team.find("select t from Team t " +
                "join TeamRole tu on tu.teamuuid = t.uuid " +
                "where teammembertype in ('" + String.join("','", roles) + "') " +
                "AND useruuid like ?1 " +
                "AND startdate <= ?2 AND (enddate > ?2 OR enddate is null)", useruuid, strDate).list();
        log.info("list = " + list.size());
        return list;
    }

    public List<User> getUsersByTeam(String teamuuid) {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where teammembertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", teamuuid).list();
    }

    public List<User> getUsersByTeam(String teamuuid, LocalDate month) {
        List<User> users = User.find(
                """
                select u from User u
                join TeamRole tu on u.uuid = tu.useruuid
                where teammembertype like 'MEMBER' AND
                tu.teamuuid like ?1 AND
                tu.startdate <= ?2 AND (tu.enddate > ?2 OR tu.enddate is null)
                """, teamuuid, month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeamLeadersByTeam(String teamuuid, LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where teammembertype like 'LEADER' AND " +
                "tu.teamuuid like ?1 AND " +
                "tu.startdate <= ?2 AND (tu.enddate > ?2 OR tu.enddate is null)", teamuuid, month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeammembersByTeamleadBonusEnabled() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where teammembertype like 'MEMBER' AND " +
                "t.teamleadbonus is true").list();
    }

    public List<User> getTeammembersByTeamleadBonusEnabledByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where teammembertype like 'MEMBER' AND " +
                "t.teamleadbonus is true AND " +
                "startdate <= ?1 AND " +
                "(enddate > ?1 OR enddate is null)", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeammembersByTeamleadBonusDisabled() {
        return User.find("select u from User u " +
                "join Teamrole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where teammembertype like 'MEMBER' AND " +
                "t.teamleadbonus is false AND " +
                "uuid not in ('f7602dd6-9daa-43cb-8712-e9b1b99dc3a9', 'f6e80289-2604-4a16-bcff-ee72affa3745')").list();
    }

    public List<User> getTeammembersByTeamleadBonusDisabledByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "join Team t on t.uuid = tu.teamuuid " +
                "where teammembertype like 'MEMBER' AND " +
                "t.teamleadbonus is false AND " +
                "uuid not in ('f7602dd6-9daa-43cb-8712-e9b1b99dc3a9', 'f6e80289-2604-4a16-bcff-ee72affa3745')" +
                "startdate <= ?1 AND " +
                "(enddate > ?1 OR enddate is null)", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getOwners() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where teammembertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", "f7602dd6-9daa-43cb-8712-e9b1b99dc3a9").list();
    }

    public List<User> getOwnersByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where tu.teammembertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1 AND " +
                "tu.startdate <= ?2 AND (tu.enddate > ?2 OR tu.enddate is null)", "f7602dd6-9daa-43cb-8712-e9b1b99dc3a9", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getTeamOps() {
        return User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where tu.teammembertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1", "f6e80289-2604-4a16-bcff-ee72affa3745").list();
    }

    public List<User> getTeamOpsByMonth(LocalDate month) {
        List<User> users = User.find("select u from User u " +
                "join TeamRole tu on u.uuid = tu.useruuid " +
                "where teammembertype like 'MEMBER' AND " +
                "tu.teamuuid like ?1 AND " +
                "startdate <= ?2 AND (enddate > ?2 OR enddate is null)", "f6e80289-2604-4a16-bcff-ee72affa3745", month).list();
        return userService.filterForActiveTeamMembers(month, users);
    }

    public List<User> getUsersByTeamAndFiscalYear(@PathParam("teamuuid") String teamuuid, @QueryParam("fiscalyear") int intFiscalYear) {
        log.info("TeamResource.getUsersByTeamAndFiscalYear");
        log.info("teamuuid = " + teamuuid + ", intFiscalYear = " + intFiscalYear);
        LocalDate fiscalYear = LocalDate.of(intFiscalYear, 7,1);

        Map<String, User> users = new HashMap<>();
        for (int i = 0; i < 11; i++) {
            LocalDate date = fiscalYear.plusMonths(i);
            getUsersByTeam(teamuuid, date).forEach(user -> users.put(user.getUuid(), user));
        }
        return new ArrayList<>(users.values());
    }

    public List<TeamRole> getTeamRolesByUser(String useruuid) {
        return TeamRole.find("useruuid like ?1", useruuid).list();
    }

    @Transactional
    public void addTeamroleToUser(String teamuuid, TeamRole teamrole) {
        if(teamrole.getUuid()!=null && TeamRole.findByIdOptional(teamrole.getUuid()).isPresent()) TeamRole.deleteById(teamrole.getUuid());
        TeamRole.persist(new TeamRole(UUID.randomUUID().toString(), teamuuid, teamrole.getUseruuid(), teamrole.getStartdate(), teamrole.getEnddate(), teamrole.getTeammembertype()));
    }

    @Transactional
    public void removeUserFromTeam(String teamroleuuid) {
        TeamRole.deleteById(teamroleuuid);
    }

    /**
     * Updates the description of all teams using the UserResumes for the employees in the team.
     * Updates the 10th of each month.
     */
    @Scheduled(cron = "0 0 10 10 * ?")
    public void updateTeamDescription() {
        log.info("TeamService.updateTeamDescription");
        List<Team> teams = Team.<Team>streamAll().filter(Team::isTeamleadbonus).toList();
        Map<Team, StringBuilder> teamResumes = new HashMap<>();
        for (Team team : teams) {
            StringBuilder teamResume = new StringBuilder();
            teamResumes.put(team, teamResume);
            teamResume.append("--- Team:\n\n");
            List<User> users = getUsersByTeam(team.getUuid(), LocalDate.now());
            for (User user : users) {
                String resumeENG = userService.findUserResume(user.getUuid()).getResumeENG();
                teamResume.append(resumeENG).append("\n\n end of team ---\n\n\n\n\n");
            }
        }
        //log.info(teamResumes.toString());
        for (Team team : teams) {
            String teamDescription = openAIService.askQuestion(
                            "I have a teams of employees. " +
                            "I have listed a short resume for each consultant in the team. " +
                            "Write a very short description (maximum of 120 characters) " +
                            "about the Team and what its collective offering are. " +
                            "Use the team members respective resume descriptions and the most dominant competencies along with Trustworks offerings as inspiration. " +
                                    "Focus on the competencies that make this team unique using one of the six Trustworks main offerings as part of the description. " +
                            "Do not use the word 'Team'. Do not mention specific consultants or their names. \n\n" + teamResumes.get(team) + "\n\n"+
                                    trustworksDescription);

            log.info(teamDescription);
            team.setDescription(teamDescription);
            QuarkusTransaction.begin();
            Team.update("description = ?1 where uuid like ?2", team.getDescription(), team.getUuid());
            QuarkusTransaction.commit();
        }
    }

    private static final String trustworksDescription = """
            Background information:\n
            I have a management consultant company. We have six main offerings:
            Project Management, Business Architecture, Solution Architecture, Integrations, Application Development, and Cyber Security.

            Project Management
            Project management is the process and activity of planning, organizing, motivating, and controlling resources to achieve specific project goals. A project is a temporary endeavor designed to produce a unique product, service or result with a defined beginning and end. The project must meet unique goals and objectives, typically to bring about beneficial change or added value. The Project Manager is responsible for making sure a project is completed within a certain set of restraints. These restraints usually involve time, money, people and materials and the project must be completed to a certain level of quality.
            
            Examples of deliveries:
            - Tender strategy
            - Offer evaluation
            - Project Initiation Document
            - Business case
            - Project management
            - Scrum master
            - Organisational change management
            - Agile coach
            
            Business Architecture
            Business architecture is the process and activity of ensuring your project reach relevant business goals. It is the bridge between the business model (strategy) and the functionality. It involves mapping the needs of the stakeholders and end users to a set of relevant requirements in order to create a system that suits the task at hand – as well as bringing the organisation forward towards the intended goals.
            At Trustworks Business Architect and UX Specialist work closely together when specifying both the system and the surrounding processes so that they may be used by intended users to achieve specified goals with effectiveness, efficiency and satisfaction in a specified context of use.
            
            Examples of deliveries:
            - Requirement specification
            - Innovation workshop
            - Design sprints
            - Business strategy
            - User experience strategy
            - Business process modelling
            - User journeys
            - Personas
            - Product owner proxy
            - Usability test
            - Early proof of concept
            
            Solution Architecture
            Trustworks’ line of solution architects are ready to assist clients in shaping architectures of all sizes. No job too small. No job too complex.
            We engage in the full life cycle: from conception to operations and maintenance. Our solution architects guide our clients through enterprise architecture layers, and gracefully unify business, information and technical aspects.
            
            Solution architecture activities are needed throughout the development process from establishing the business context and vision in cooperation with the Business Architects during ideation, over elaborating potential options during design to developing the road map for the selected solution. Actually, all the way to implementation, where the Solution Architect guides the implementation team and communicates the architecture to stakeholders.
            We excel in finding the right solution architecture for each customer. A solution that provides long lasting value.
            Several of our talented and passionate solution architects have developer backgrounds, which instils a deep pride and respect of the craft. This is complemented by down to earth people skills, and an extensive analytical methodology, which we tailor to the unique customer and project.
            Do you have our next challenge?
            
            Examples of deliveries:
            - Solution architecture strategy
            - Service blue print
            - Implementation plan
            
            Integrations
            Efficient and governed integrations are the foundation of scalability and agility.
            Many CIO’s struggle with a – at times wildly – branched integration landscape. Operating legacy platforms, Paas and Saas based systems is a constant headache. Building a scalable and secure integration and API management platform will be the solution to these problems.
            A choice needs to be made on ‘build’ or ‘buy’. There are good and battle-proven integration platforms available which may be the right choice for some. Others prefer to stay in control of the code and implement elements from e.g. Azure Integrations Services.
            Trustworks helps you find the right solution for your organisation – and we implement it with you.
            
            Examples of deliveries:
            - Integration strategy
            - Integrations build and deployment
            - Operations process framework
            - Devops setup supporting continuous deployment
            - Testing strategy and implementation
            
            Application Development
            Modernisation of existing systems and building new digital assets.
            Modernising a system landscape is not only about building new applications. Trustworks would be your choice if you want to take a sustainable approach with focus on how existing systems can be adapted to cloud – or poor performing elements can be replaced to offer entirely new possibilities in digital products and services.
            Being able to compete or keep up by reducing cost is often tied to different digital initiatives. Trustworks is an end-to-end supplier of digital transformation and we bring the user stories to life in satisfying software and cloud infrastructure soultions.
            
            Examples of deliveries:
            - Software engineers in the customer’s scrum team
            - Complete scrum teams on or off site
            - Complete product delivery including deployment
            - DevOps infrastructure and operations
            - Kubernetes deployment
            
            Cyber Security
            Trustworks’ cyber security team is ready to assist clients in shaping architectures of all sizes. No job too small. No job too complex.
            We engage in the full life cycle: from conception to operations and maintenance. Our cyber security architects guide our clients through enterprise architecture layers, and gracefully unify business, information and technical aspects.
            
            Cyber security activities are needed throughout the development process from establishing the business context and vision in cooperation with the Business Architects during ideation, over elaborating potential options during design to developing the road map for the selected solution. Actually, all the way to implementation, where the Cyber Security Architect guides the implementation team and communicates the architecture to stakeholders.
            We excel in finding the right cyber security architecture for each customer. A solution that provides long lasting value.
            Several of our talented and passionate cyber security architects have developer backgrounds, which instils a deep pride and respect of the craft. This is complemented by down to earth people skills, and an extensive analytical methodology, which we tailor to the unique customer and project.
            Do you have our next challenge?
            
            Examples of deliveries:
            - Cyber security strategy
            - Security architecture
            - Implementation plan
            - Security operations
            - Security testing
            - Security audit
            - Security compliance
            - Security incident response
            - Security awareness training
            - Security governance
            
            """;
}
