package dk.trustworks.intranet.dao.bubbleservice.services;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dao.bubbleservice.model.Bubble;
import dk.trustworks.intranet.dao.bubbleservice.model.BubbleMember;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Database-free regression coverage for bubble member addition (missing user/bubble → 404, not NPE 500). */
class BubbleServiceAddMemberTest {

    private static final String BUBBLE_UUID = "7fd5d947-37ba-4e55-b094-b9f24df7016d";
    private static final String USER_UUID = "be24bbfc-97f2-4078-8096-2122dd457df8";

    private BubbleService service;
    private Bubble bubble;

    @BeforeEach
    void setUp() {
        service = new BubbleService();
        service.userService = mock(UserService.class);
        service.slackService = mock(SlackService.class);

        bubble = new Bubble();
        bubble.setUuid(BUBBLE_UUID);
        bubble.setSlackchannel("C0SLACK");
    }

    @Test
    void addBubbleMember_unknownUser_throwsNotFoundBeforeAnySideEffects() {
        when(service.userService.findById(USER_UUID, true)).thenReturn(null);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.addBubbleMember(bubble, USER_UUID));
            assertTrue(ex.getMessage().contains(USER_UUID));

            panache.verifyNoInteractions();
        }
        verifyNoInteractions(service.slackService);
    }

    @Test
    void addBubbleMember_unknownBubble_throwsNotFound() {
        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<Bubble> query = mock(PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find("uuid", BUBBLE_UUID)).thenReturn(query);
            when(query.firstResult()).thenReturn(null);

            NotFoundException ex = assertThrows(NotFoundException.class,
                    () -> service.addBubbleMember(BUBBLE_UUID, USER_UUID));
            assertTrue(ex.getMessage().contains(BUBBLE_UUID));
        }
        verifyNoInteractions(service.slackService);
        verifyNoInteractions(service.userService);
    }

    @Test
    void addBubbleMember_existingMember_returnsWithoutSideEffects() {
        User user = new User();
        user.setUuid(USER_UUID);
        when(service.userService.findById(USER_UUID, true)).thenReturn(user);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<BubbleMember> query = mock(PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find("useruuid like ?1 and bubble = ?2", USER_UUID, bubble))
                    .thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.of(new BubbleMember()));

            assertDoesNotThrow(() -> service.addBubbleMember(bubble, USER_UUID));

            panache.verify(() -> PanacheEntityBase.persist(any(Object.class)), never());
        }
        verifyNoInteractions(service.slackService);
    }

    @Test
    void addBubbleMember_newMember_invitesToSlackAndPersists() {
        User user = new User();
        user.setUuid(USER_UUID);
        when(service.userService.findById(USER_UUID, true)).thenReturn(user);

        try (MockedStatic<PanacheEntityBase> panache = mockStatic(PanacheEntityBase.class)) {
            @SuppressWarnings("unchecked")
            PanacheQuery<BubbleMember> query = mock(PanacheQuery.class);
            panache.when(() -> PanacheEntityBase.find("useruuid like ?1 and bubble = ?2", USER_UUID, bubble))
                    .thenReturn(query);
            when(query.singleResultOptional()).thenReturn(Optional.empty());

            service.addBubbleMember(bubble, USER_UUID);

            panache.verify(() -> PanacheEntityBase.persist(any(Object.class)));
        }
        verify(service.slackService).addUserToChannel(user, "C0SLACK");
    }
}
