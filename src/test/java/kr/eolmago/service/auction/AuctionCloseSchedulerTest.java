package kr.eolmago.service.auction;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.global.config.properties.AuctionRuntimeProperties;
import kr.eolmago.service.auction.monitoring.AuctionCloseMetricsRecorder;
import kr.eolmago.dto.view.auction.AuctionEndAtView;
import kr.eolmago.repository.auction.AuctionCloseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 경매 종료 스케줄링 로직 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionCloseScheduler 단위 테스트")
class AuctionCloseSchedulerTest {

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private AuctionCloseService auctionCloseService;

    @Mock
    private AuctionCloseRepository auctionCloseRepository;

    private AuctionCloseScheduler sut;

    @BeforeEach
    void setUp() {
        AuctionRuntimeProperties properties = new AuctionRuntimeProperties();
        sut = new AuctionCloseScheduler(
                taskScheduler,
                auctionCloseService,
                auctionCloseRepository,
                properties,
                new AuctionCloseMetricsRecorder(new SimpleMeterRegistry())
        );
    }

    @Nested
    @DisplayName("스케줄 등록/재등록")
    class ScheduleOrRescheduleTests {

        @Test
        @DisplayName("새 경매 스케줄 등록 성공")
        void givenNewAuction_whenSchedule_thenTaskScheduled() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime endAt = OffsetDateTime.now().plusHours(1);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);

            doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When
            sut.scheduleOrReschedule(auctionId, endAt);

            // Then
            ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

            Instant scheduledAt = instantCaptor.getValue();
            assertThat(scheduledAt).isEqualTo(endAt.toInstant());
        }

        @Test
        @DisplayName("기존 스케줄 재등록 시 이전 future 취소")
        void givenExistingSchedule_whenReschedule_thenCancelPreviousAndScheduleNew() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime firstEndAt = OffsetDateTime.now().plusHours(1);
            OffsetDateTime secondEndAt = OffsetDateTime.now().plusHours(2);

            @SuppressWarnings("unchecked")
            ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);

            doReturn(firstFuture, secondFuture)
                    .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When: 첫 번째 등록
            sut.scheduleOrReschedule(auctionId, firstEndAt);
            // When: 같은 경매 재등록
            sut.scheduleOrReschedule(auctionId, secondEndAt);

            // Then: 첫 번째 future가 취소되어야 함
            verify(firstFuture).cancel(false);
            verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("과거 시간으로 스케줄 시 즉시 실행 (현재 시간으로 조정)")
        void givenPastEndAt_whenSchedule_thenScheduleAtNow() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime pastEndAt = OffsetDateTime.now().minusHours(1);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);

            doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When
            sut.scheduleOrReschedule(auctionId, pastEndAt);

            // Then
            ArgumentCaptor<Instant> instantCaptor = ArgumentCaptor.forClass(Instant.class);
            verify(taskScheduler).schedule(any(Runnable.class), instantCaptor.capture());

            // 스케줄 시간이 과거가 아니어야 함
            Instant scheduledAt = instantCaptor.getValue();
            assertThat(scheduledAt).isAfterOrEqualTo(Instant.now().minusSeconds(1));
        }

        @Test
        @DisplayName("null auctionId 또는 endAt이면 무시")
        void givenNullParams_whenSchedule_thenIgnore() {
            // When
            sut.scheduleOrReschedule(null, OffsetDateTime.now());
            sut.scheduleOrReschedule(UUID.randomUUID(), null);

            // Then
            verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("TaskScheduler.schedule()이 null 반환 시 정상 처리")
        void givenSchedulerReturnsNull_whenSchedule_thenHandleGracefully() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime endAt = OffsetDateTime.now().plusHours(1);

            doReturn(null).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When & Then: 예외 없이 완료되어야 함
            assertThatCode(() -> sut.scheduleOrReschedule(auctionId, endAt))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TaskRejectedException 발생 시 정상 처리")
        void givenTaskRejected_whenSchedule_thenHandleGracefully() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime endAt = OffsetDateTime.now().plusHours(1);

            doThrow(new TaskRejectedException("Queue full"))
                    .when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When & Then
            assertThatCode(() -> sut.scheduleOrReschedule(auctionId, endAt))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("부트스트랩")
    class BootstrapTests {

        @Test
        @DisplayName("서버 시작 시 LIVE 경매들 재등록")
        void givenLiveAuctions_whenBootstrap_thenRescheduleAll() {
            // Given
            UUID auction1 = UUID.randomUUID();
            UUID auction2 = UUID.randomUUID();
            OffsetDateTime endAt1 = OffsetDateTime.now().plusHours(1);
            OffsetDateTime endAt2 = OffsetDateTime.now().plusHours(2);

            List<AuctionEndAtView> liveAuctions = Arrays.asList(
                    new AuctionEndAtView(auction1, endAt1),
                    new AuctionEndAtView(auction2, endAt2)
            );

            when(auctionCloseRepository.findAllEndAtByStatus(AuctionStatus.LIVE))
                    .thenReturn(liveAuctions);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
            doReturn(mockFuture).when(taskScheduler).schedule(any(Runnable.class), any(Instant.class));

            // When
            sut.bootstrapOnStartup();

            // Then: 2개의 경매가 스케줄됨
            verify(taskScheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("LIVE 경매 없으면 스케줄 없음")
        void givenNoLiveAuctions_whenBootstrap_thenNoScheduling() {
            // Given
            when(auctionCloseRepository.findAllEndAtByStatus(AuctionStatus.LIVE))
                    .thenReturn(Collections.emptyList());

            // When
            sut.bootstrapOnStartup();

            // Then
            verify(taskScheduler, never()).schedule(any(Runnable.class), any(Instant.class));
        }

        @Test
        @DisplayName("부트스트랩 중 예외 발생해도 서버 시작 계속")
        void givenExceptionDuringBootstrap_thenContinue() {
            // Given
            doThrow(new RuntimeException("DB connection failed"))
                    .when(auctionCloseRepository).findAllEndAtByStatus(AuctionStatus.LIVE);

            // When & Then
            assertThatCode(() -> sut.bootstrapOnStartup())
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("오버듀 스윕")
    class SweepTests {

        @Test
        @DisplayName("오버듀 경매들 종료 처리")
        void givenOverdueAuctions_whenSweep_thenCloseAll() {
            // Given
            UUID auction1 = UUID.randomUUID();
            UUID auction2 = UUID.randomUUID();
            List<UUID> overdueIds = Arrays.asList(auction1, auction2);

            when(auctionCloseRepository.findIdsToClose(eq(AuctionStatus.LIVE), any(OffsetDateTime.class), any(PageRequest.class)))
                    .thenReturn(overdueIds);

            // When
            sut.sweepOverdueAuctions();

            // Then
            verify(auctionCloseService).closeAuction(auction1);
            verify(auctionCloseService).closeAuction(auction2);
        }

        @Test
        @DisplayName("오버듀 경매 없으면 종료 호출 없음")
        void givenNoOverdueAuctions_whenSweep_thenNoClose() {
            // Given
            when(auctionCloseRepository.findIdsToClose(any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            sut.sweepOverdueAuctions();

            // Then
            verify(auctionCloseService, never()).closeAuction(any());
        }

        @Test
        @DisplayName("개별 종료 실패해도 다른 경매 처리 계속")
        void givenCloseFailsForOne_whenSweep_thenContinueWithOthers() {
            // Given
            UUID auction1 = UUID.randomUUID();
            UUID auction2 = UUID.randomUUID();
            UUID auction3 = UUID.randomUUID();
            List<UUID> overdueIds = Arrays.asList(auction1, auction2, auction3);

            when(auctionCloseRepository.findIdsToClose(any(), any(), any()))
                    .thenReturn(overdueIds);
            doThrow(new RuntimeException("Close failed"))
                    .when(auctionCloseService).closeAuction(auction2);

            // When
            sut.sweepOverdueAuctions();

            // Then: 모든 경매에 대해 closeAuction 시도
            verify(auctionCloseService).closeAuction(auction1);
            verify(auctionCloseService).closeAuction(auction2);
            verify(auctionCloseService).closeAuction(auction3);
        }
    }

    @Nested
    @DisplayName("실행된 태스크 동작 검증")
    class ScheduledTaskExecutionTests {

        @Test
        @DisplayName("스케줄된 태스크 실행 시 closeAuction 호출")
        void givenScheduledTask_whenExecuted_thenCallCloseAuction() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime endAt = OffsetDateTime.now().plusHours(1);

            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
            doReturn(mockFuture).when(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));

            sut.scheduleOrReschedule(auctionId, endAt);

            // When: 캡처된 태스크 실행
            Runnable scheduledTask = taskCaptor.getValue();
            scheduledTask.run();

            // Then
            verify(auctionCloseService).closeAuction(auctionId);
        }

        @Test
        @DisplayName("closeAuction 예외 시에도 태스크 정상 완료")
        void givenCloseAuctionThrows_whenTaskExecuted_thenCompleteGracefully() {
            // Given
            UUID auctionId = UUID.randomUUID();
            OffsetDateTime endAt = OffsetDateTime.now().plusHours(1);

            ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
            @SuppressWarnings("unchecked")
            ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
            doReturn(mockFuture).when(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
            doThrow(new RuntimeException("Close failed"))
                    .when(auctionCloseService).closeAuction(auctionId);

            sut.scheduleOrReschedule(auctionId, endAt);

            // When & Then: 예외 없이 완료
            Runnable scheduledTask = taskCaptor.getValue();
            assertThatCode(scheduledTask::run).doesNotThrowAnyException();
        }
    }
}
