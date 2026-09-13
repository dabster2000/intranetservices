package dk.trustworks.intranet.aggregates.crm.news;

import dk.trustworks.intranet.apigateway.resources.ClientResource;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClientNewsServiceTest {
    ClientNewsService service;
    ClientNewsRepository repository;
    NewsApiClient provider;
    ClientNewsConfig config;
    final Instant now = Instant.parse("2026-09-13T06:00:00Z");
    final ClientNewsRepository.Identity client = ClientNewsMatcherTest.company("Banedanmark", "18632276");
    ClientNewsRepository.Lease lease;

    @BeforeEach void setup() {
        service = new ClientNewsService();
        repository = mock(ClientNewsRepository.class); provider = mock(NewsApiClient.class);
        service.repository = repository; service.provider = provider; service.matcher = new ClientNewsMatcher();
        service.shutdown = mock(SchedulerShutdownGuard.class);
        config = new ClientNewsConfig(); config.enabled = true; config.apiKey = Optional.of("fake-test-token"); config.dailyRequestBudget = 200;
        service.config = config; service.clock = Clock.fixed(now, ZoneOffset.UTC);
        lease = new ClientNewsRepository.Lease(client, "lease-token", null, List.of());
        when(repository.eligibleClients()).thenReturn(List.of(client));
        when(repository.claim(eq(client), any(), any())).thenReturn(Optional.of(lease));
        when(repository.complete(any(), any(), anyList(), anyBoolean())).thenReturn(true);
    }

    @Test void emptySuccessfulResponseIsReadyAndDoesNotInventNews() {
        when(provider.fetch(anyList(), any(), any(), eq(1), any())).thenReturn(new NewsApiClient.Page(List.of(), 1, 0));
        service.synchronize();
        verify(repository).complete(eq(lease), eq(now), eq(List.of()), eq(false));
        verify(repository, never()).fail(any(), anyString(), anyList());
    }

    @Test void highVolumeIsBoundedReadyWithCoverageLimitInsteadOfPerpetualFailure() {
        for (int i = 1; i <= ClientNewsService.MAX_PAGES; i++)
            when(provider.fetch(anyList(), any(), any(), eq(i), any())).thenReturn(new NewsApiClient.Page(List.of(), i, 100));
        service.synchronize();
        verify(provider, times(5)).fetch(anyList(), any(), any(), anyInt(), any());
        verify(repository).complete(eq(lease), eq(now), eq(List.of()), eq(true));
        verify(repository, never()).fail(any(), anyString(), anyList());
    }

    @Test void failedProviderNeverAdvancesWatermarkAndRetainsPriorStoredItems() {
        when(provider.fetch(anyList(), any(), any(), anyInt(), any()))
                .thenThrow(new NewsApiClient.NewsFailure(NewsApiClient.Failure.AUTHENTICATION));
        service.synchronize();
        verify(repository, never()).complete(any(), any(), anyList(), anyBoolean());
        verify(repository).fail(eq(lease), eq("AUTHENTICATION"), eq(List.of()));
    }

    @Test void losingLeaseOrDisabledFlagMakesNoExternalRequests() {
        when(repository.claim(eq(client), any(), any())).thenReturn(Optional.empty());
        service.synchronize(); verifyNoInteractions(provider);
        reset(repository); config.enabled = false;
        service.synchronize(); verifyNoInteractions(repository, provider);
    }

    @Test void gettingNewsIsCacheOnlyAndInactivePlanHidesItems() {
        when(repository.read(client.uuid())).thenReturn(new ClientNewsRepository.Snapshot(false,
                ClientNewsDTO.Status.READY, true, now, now, List.of()));
        var dto = service.get(client.uuid());
        assertFalse(dto.eligible()); assertEquals(ClientNewsDTO.Status.DISABLED, dto.status());
        assertTrue(dto.items().isEmpty()); verifyNoInteractions(provider);
        assertThrows(BadRequestException.class, () -> service.get("not-a-uuid"));
    }

    @Test void lookbackIsInclusiveThirtyDatesAndOverlapsSuccessfulWatermarkOnly() {
        assertEquals(LocalDate.of(2026, 8, 15), ClientNewsService.windowStart(null, now));
        assertEquals(LocalDate.of(2026, 9, 10), ClientNewsService.windowStart(now.minusSeconds(86400), now));
        assertEquals(LocalDate.of(2026, 8, 15), ClientNewsService.windowStart(now.minus(Duration.ofDays(100)), now));
    }

    @Test void oldCacheItemsExpireEvenWhenSubsequentSuccessfulChecksFindNothing() {
        var old = new ClientNewsDTO.StoredItem(new ClientNewsDTO.Item("old", "Old news", "https://example.com/old",
                "Example", now.minus(Duration.ofDays(31)), "", ClientNewsDTO.Category.OTHER, ClientNewsDTO.Scope.COMPANY), "story-old");
        lease = new ClientNewsRepository.Lease(client, "lease-token", now.minus(Duration.ofDays(1)), List.of(old));
        when(repository.claim(eq(client), any(), any())).thenReturn(Optional.of(lease));
        when(provider.fetch(anyList(), any(), any(), eq(1), any())).thenReturn(new NewsApiClient.Page(List.of(), 1, 0));
        service.synchronize();
        verify(repository).complete(eq(lease), eq(now), eq(List.of()), eq(false));
        when(repository.read(client.uuid())).thenReturn(new ClientNewsRepository.Snapshot(true, ClientNewsDTO.Status.READY,
                false, now, now, List.of(old)));
        assertTrue(service.get(client.uuid()).items().isEmpty());
    }

    @Test void scheduleUsesCopenhagenAndEndpointUsesExistingAccountReadScope() throws Exception {
        assertArrayEquals(new String[]{"accounts:read"}, ClientResource.class.getMethod("news", String.class)
                .getAnnotation(RolesAllowed.class).value());
        var schedule = ClientNewsJob.class.getDeclaredMethod("daily").getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertEquals("Europe/Copenhagen", schedule.timeZone()); assertEquals("0 0 6 * * ?", schedule.cron());
        assertEquals(SchedulerShutdownGuard.class, schedule.skipExecutionIf());
    }
}
