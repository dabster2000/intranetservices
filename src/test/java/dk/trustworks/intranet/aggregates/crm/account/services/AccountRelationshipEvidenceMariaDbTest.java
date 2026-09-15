package dk.trustworks.intranet.aggregates.crm.account.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO.RelationEdgeDTO;
import dk.trustworks.intranet.aggregates.crm.merge.services.ClientMergeService;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Executes real SQL aggregation and multi-account meeting collapse on isolated MariaDB. */
@Tag("calendar-mariadb")
@EnabledIfEnvironmentVariable(named = "CRM_CALENDAR_IT_JDBC_URL", matches = ".+")
class AccountRelationshipEvidenceMariaDbTest {
    static SessionFactory factory;
    Session session;

    @BeforeAll static void database() throws Exception {
        String base = System.getenv("CRM_CALENDAR_IT_JDBC_URL");
        if (!base.matches("jdbc:mariadb://127\\.0\\.0\\.1:[0-9]+/crm_calendar_test(?:_[a-z0-9]+)?")) {
            throw new IllegalArgumentException("A disposable localhost crm_calendar_test database is required");
        }
        String url = base + "_relationships";
        String password = System.getenv().getOrDefault("CRM_CALENDAR_IT_PASSWORD", "calendar-test-only");
        try (var c = DriverManager.getConnection(base, "root", password); var s = c.createStatement()) {
            s.execute("create database if not exists `" + url.substring(url.lastIndexOf('/') + 1) + "` character set utf8mb4 collate utf8mb4_general_ci");
        }
        Configuration cfg = new Configuration();
        cfg.setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver");
        cfg.setProperty("hibernate.connection.url", url);
        cfg.setProperty("hibernate.connection.username", "root");
        cfg.setProperty("hibernate.connection.password", password);
        cfg.setProperty("hibernate.connection.pool_size", "2");
        factory = cfg.buildSessionFactory();
        try (Session schema = factory.openSession()) {
            var tx = schema.beginTransaction();
            for (String sql : List.of(
                    "drop table if exists account_meeting_attendee,account_meeting,account_person_identity",
                    "create table account_meeting (uuid char(36) primary key,client_uuid char(36),user_uuid char(36),graph_event_id varchar(600),ical_uid varchar(255),occurred_at datetime,unique key (client_uuid,user_uuid,graph_event_id))",
                    "create table account_meeting_attendee (uuid char(36) primary key,meeting_uuid char(36),email varchar(320),unique key (meeting_uuid,email),foreign key (meeting_uuid) references account_meeting(uuid) on delete cascade)",
                    "create table account_person_identity (client_uuid char(36),person_uuid char(36),kind varchar(10),value varchar(320))")) {
                schema.createNativeMutationQuery(sql).executeUpdate();
            }
            tx.commit();
        }
    }
    @AfterAll static void close() { if (factory != null) factory.close(); }
    @BeforeEach void start() {
        session = factory.openSession();
        session.beginTransaction();
        for (String table : List.of("account_meeting_attendee", "account_meeting", "account_person_identity")) sql("delete from " + table);
    }
    @AfterEach void rollback() { if (session != null) { session.getTransaction().rollback(); session.close(); } }

    @Test void attendeeAliasesAndMailboxCopiesProduceOnePersonOccurrence() {
        sql("insert into account_meeting values ('m1','client','u1','event1','shared','2026-09-01'),('m2','client','u2','event2','shared','2026-09-01'),('m3','client','u1','event3',null,'2026-09-02'),('m4','client','u1','event4','SHARED','2026-09-01')");
        sql("insert into account_meeting_attendee values ('a1','m1','a@client.example'),('a2','m1','alias@client.example'),('a3','m2','A@client.example'),('a4','m3','a@client.example'),('a5','m4','a@client.example')");
        sql("insert into account_person_identity values ('client','p','EMAIL','a@client.example'),('client','p','EMAIL','alias@client.example')");
        var service = new AccountRelationshipService(); service.em = session;
        var index = new AccountRelationshipService.PersonIndex(Map.of("p", new AccountRelationshipService.RegisteredPerson(
                "p", "Sara Client", null, null, "CONTACT", null, null)), Map.of());
        var directory = new AccountRelationshipService.Directory(Map.of("u1", "Colleague One", "u2", "Colleague Two"), Set.of("u1", "u2"), Map.of());
        var colleagues = new AccountRelationshipService.Colleagues(directory);
        List<RelationEdgeDTO> edges = new ArrayList<>();
        var totals = service.collectMeetingEdges("client", index, colleagues, edges);
        assertEquals(3, totals.get("p"));
        assertEquals(2, edges.size());
        assertEquals(4, edges.stream().mapToInt(RelationEdgeDTO::meetings).sum(), "individual evidence overlaps across colleagues");
        assertEquals(3, AccountRelationshipService.people(index, AccountRelationshipService.groupByPerson(edges), Map.of(), directory,
                LocalDate.of(2026, 9, 15), totals).getFirst().meetings());
    }

    @Test void mergingAccountsPreservesBothSetsOfEventAttendees() throws Exception {
        sql("insert into account_meeting values ('winner','client','u1','same-event','ical','2026-09-01'),('loser','duplicate','u1','same-event','ical','2026-09-01')");
        sql("insert into account_meeting_attendee values ('a1','winner','one@client.example'),('a2','loser','two@client.example'),('a3','loser','one@client.example')");
        ClientMergeService service = new ClientMergeService();
        var field = ClientMergeService.class.getDeclaredField("em"); field.setAccessible(true); field.set(service, session);
        var method = ClientMergeService.class.getDeclaredMethod("collapseMeetings", String.class, String.class, Set.class); method.setAccessible(true);
        assertEquals(1L, method.invoke(service, "client", "duplicate", Set.of("account_meeting", "account_meeting_attendee")));
        assertEquals(1L, count("account_meeting"));
        assertEquals(2L, count("account_meeting_attendee"));
        assertEquals(0L, ((Number) session.createNativeQuery("select count(*) from account_meeting_attendee where meeting_uuid<>'winner'").getSingleResult()).longValue());
        assertEquals(0L, method.invoke(service, "client", "duplicate", Set.of("account_meeting", "account_meeting_attendee")));
    }
    void sql(String sql) { session.createNativeMutationQuery(sql).executeUpdate(); }
    long count(String table) { return ((Number) session.createNativeQuery("select count(*) from " + table).getSingleResult()).longValue(); }
}
