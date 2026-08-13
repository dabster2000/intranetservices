package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * Database-free coverage of the contactinfo/current read path. Prod incident
 * 2026-08-12 (traceId a235be93…): a GET for a user missing from the user table
 * lazily persisted a default row, hit fk_user_contactinfo_user, and answered
 * 409. The read must verify the parent user first and answer 404 without
 * writing anything.
 */
class UserContactInfoServiceFindOneTest {

    private static final String USER_UUID = "65557592-7c20-42c3-b103-687a705ad761";

    private UserContactInfoService service;

    @BeforeEach
    void setUp() {
        service = new UserContactInfoService();
    }

    @Test
    void missingUserIs404AndNothingIsPersisted() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<UserContactinfo> contactinfo = mockStatic(UserContactinfo.class)) {
            panache.when(() -> PanacheEntityBase.findById(USER_UUID)).thenReturn(null);

            assertThrows(NotFoundException.class, () -> service.findOne(USER_UUID));

            contactinfo.verifyNoInteractions();
            panache.verify(() -> PanacheEntityBase.persist(any(UserContactinfo.class)), never());
        }
    }

    @Test
    void existingUserWithContactinfoReturnsItWithoutPersisting() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<UserContactinfo> contactinfo = mockStatic(UserContactinfo.class)) {
            panache.when(() -> PanacheEntityBase.findById(USER_UUID)).thenReturn(new User());
            UserContactinfo existing = new UserContactinfo();
            existing.setUuid("existing-row");
            existing.setUseruuid(USER_UUID);
            contactinfo.when(() -> UserContactinfo.findCurrentByUseruuid(USER_UUID)).thenReturn(existing);

            UserContactinfo result = service.findOne(USER_UUID);

            assertSame(existing, result);
            panache.verify(() -> PanacheEntityBase.persist(any(UserContactinfo.class)), never());
        }
    }

    @Test
    void existingUserWithoutContactinfoGetsDefaultRowPersisted() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class);
             MockedStatic<UserContactinfo> contactinfo = mockStatic(UserContactinfo.class)) {
            panache.when(() -> PanacheEntityBase.findById(USER_UUID)).thenReturn(new User());
            contactinfo.when(() -> UserContactinfo.findCurrentByUseruuid(USER_UUID)).thenReturn(null);

            UserContactinfo result = service.findOne(USER_UUID);

            assertNotNull(result.getUuid());
            assertEquals(USER_UUID, result.getUseruuid());
            assertEquals(LocalDate.now(), result.getActiveDate());
            assertEquals("", result.getStreetname());
            panache.verify(() -> PanacheEntityBase.persist(result));
        }
    }
}
