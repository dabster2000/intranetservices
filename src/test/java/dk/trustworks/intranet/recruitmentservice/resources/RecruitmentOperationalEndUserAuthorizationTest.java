package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomPolicyRequest;
import dk.trustworks.intranet.recruitmentservice.dto.MeetingRoomPolicyResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ReportsResponse;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingProjector;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingReadService;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCalendarService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentMeetingRoomPolicyService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct-resource regressions for endpoints whose JWT scope identifies the
 * trusted client, while {@code X-Requested-By} identifies the person.
 */
class RecruitmentOperationalEndUserAuthorizationTest {

    @Test
    void reportsRequireRecruiterAndRebuildRequiresAdminEndUser() {
        RecruitmentReportsResource resource = new RecruitmentReportsResource();
        resource.featureFlag = mock(RecruitmentFeatureFlag.class);
        resource.scopeContext = mock(ScopeContext.class);
        resource.readService = mock(RecruitmentReportingReadService.class);
        resource.projector = mock(RecruitmentReportingProjector.class);
        resource.em = mock(EntityManager.class);
        resource.requestHeaderHolder = mock(RequestHeaderHolder.class);
        resource.visibility = mock(RecruitmentVisibility.class);

        String assistant = UUID.randomUUID().toString();
        when(resource.requestHeaderHolder.getUserUuid()).thenReturn(assistant);
        when(resource.visibility.isRecruiterTier(assistant)).thenReturn(false);
        when(resource.visibility.rolesOf(assistant)).thenReturn(Set.of("ASSISTANT_TEAMLEAD"));

        assertForbidden(() -> resource.reports("2026-07", "2026-08"));
        assertForbidden(resource::rebuild);
        verify(resource.readService, never()).reports(any(), any(), anyLong(), anyLong());
        verify(resource.projector, never()).rebuild();

        String recruiter = UUID.randomUUID().toString();
        ReportsResponse expected = emptyReports();
        Query streamHead = mock(Query.class);
        when(resource.requestHeaderHolder.getUserUuid()).thenReturn(recruiter);
        when(resource.visibility.isRecruiterTier(recruiter)).thenReturn(true);
        when(resource.featureFlag.isGdprEnabled()).thenReturn(true);
        when(resource.projector.watermark()).thenReturn(0L);
        when(resource.em.createNativeQuery(anyString())).thenReturn(streamHead);
        when(streamHead.getSingleResult()).thenReturn(0L);
        when(resource.readService.reports(
                eq(YearMonth.of(2026, 7)), eq(YearMonth.of(2026, 8)), eq(0L), eq(0L)))
                .thenReturn(expected);
        assertSame(expected, resource.reports("2026-07", "2026-08"));

        String admin = UUID.randomUUID().toString();
        RecruitmentReportingProjector.RebuildSummary summary =
                new RecruitmentReportingProjector.RebuildSummary(1, 2, false);
        when(resource.requestHeaderHolder.getUserUuid()).thenReturn(admin);
        when(resource.visibility.rolesOf(admin)).thenReturn(Set.of("ADMIN"));
        when(resource.projector.rebuild()).thenReturn(summary);
        assertSame(summary, resource.rebuild().getEntity());
    }

    @Test
    void roomPolicyRequiresAdminEndUserForReadAndWrite() {
        RecruitmentInterviewResource resource = new RecruitmentInterviewResource();
        resource.featureFlag = mock(RecruitmentFeatureFlag.class);
        resource.scopeContext = mock(ScopeContext.class);
        resource.requestHeaderHolder = mock(RequestHeaderHolder.class);
        resource.visibility = mock(RecruitmentVisibility.class);
        resource.calendarService = mock(RecruitmentCalendarService.class);
        resource.roomPolicyService = mock(RecruitmentMeetingRoomPolicyService.class);

        String assistant = UUID.randomUUID().toString();
        when(resource.requestHeaderHolder.getUserUuid()).thenReturn(assistant);
        when(resource.visibility.rolesOf(assistant)).thenReturn(Set.of("ASSISTANT_TEAMLEAD"));
        MeetingRoomPolicyRequest request = new MeetingRoomPolicyRequest(List.of(), List.of());

        assertForbidden(resource::roomPolicy);
        assertForbidden(() -> resource.saveRoomPolicy(request));
        verify(resource.calendarService, never()).roomLookup();

        String admin = UUID.randomUUID().toString();
        MeetingRoomPolicyResponse expected = new MeetingRoomPolicyResponse(List.of(), true);
        when(resource.requestHeaderHolder.getUserUuid()).thenReturn(admin);
        when(resource.visibility.rolesOf(admin)).thenReturn(Set.of("ADMIN"));
        when(resource.featureFlag.isInterviewsEnabled()).thenReturn(true);
        when(resource.calendarService.roomLookup())
                .thenReturn(new RecruitmentCalendarService.RoomLookup(List.of(), true));
        when(resource.roomPolicyService.currentPolicy(anyList(), eq(true))).thenReturn(expected);
        when(resource.roomPolicyService.replacePolicy(anyList(), anySet(), anyList(), eq(true)))
                .thenReturn(expected);

        assertSame(expected, resource.roomPolicy());
        assertSame(expected, resource.saveRoomPolicy(request));
    }

    private static ReportsResponse emptyReports() {
        return new ReportsResponse(
                "2026-07", "2026-08", true, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                new ReportsResponse.GdprTiles(0, 0, 0, 0, 0, 0, 0, 0), List.of());
    }

    private static void assertForbidden(Runnable action) {
        WebApplicationException thrown = assertThrows(WebApplicationException.class, action::run);
        assertEquals(403, thrown.getResponse().getStatus());
    }
}
