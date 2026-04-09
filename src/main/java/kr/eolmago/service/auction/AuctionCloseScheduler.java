package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.dto.view.auction.AuctionEndAtView;
import kr.eolmago.global.config.properties.AuctionRuntimeProperties;
import kr.eolmago.repository.auction.AuctionCloseRepository;
import kr.eolmago.service.auction.metrics.AuctionCloseMetricsRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuctionCloseScheduler {

    private final TaskScheduler taskScheduler;
    private final AuctionCloseService auctionCloseService;
    private final AuctionCloseRepository auctionCloseRepository;
    private final AuctionRuntimeProperties auctionRuntimeProperties;
    private final AuctionCloseMetricsRecorder auctionCloseMetricsRecorder;

    private final AtomicLong tokenSeq = new AtomicLong(0);
    private final ConcurrentMap<UUID, ScheduledRef> scheduled = new ConcurrentHashMap<>();

    public void scheduleOrReschedule(UUID auctionId, OffsetDateTime endAt) {
        if (auctionId == null || endAt == null) {
            return;
        }

        long token = tokenSeq.incrementAndGet();
        Instant runAt = normalizeRunAt(endAt);
        ScheduledRef newRef = new ScheduledRef(token);

        ScheduledRef prev = scheduled.put(auctionId, newRef);
        if (prev != null && prev.future != null) {
            prev.future.cancel(false);
        }

        try {

            newRef.future = taskScheduler.schedule(() -> runCloseSafely(auctionId, token), runAt);
            auctionCloseMetricsRecorder.recordScheduleRegistration(prev == null ? "registered" : "rescheduled");

        } catch (TaskRejectedException e) {
            scheduled.computeIfPresent(auctionId, (id, ref) -> ref.token == token ? null : ref);
            auctionCloseMetricsRecorder.recordScheduleRegistration("rejected");
            log.warn("[AUC_CLOSE_SCHEDULE_REJECTED] auctionId={}, runAt={}", auctionId, runAt, e);

        } catch (RuntimeException e) {

            scheduled.computeIfPresent(auctionId, (id, ref) -> ref.token == token ? null : ref);
            auctionCloseMetricsRecorder.recordScheduleRegistration("failed");
            log.error("[AUC_CLOSE_SCHEDULE_ERROR] auctionId={}, runAt={}", auctionId, runAt, e);

        }
    }

    // 서버 재기동 후 마감 예약이 사라지는 문제를 막기 위한 bootstrap 루트
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnStartup() {
        try {
            List<AuctionEndAtView> lives = auctionCloseRepository.findAllEndAtByStatus(AuctionStatus.LIVE);

            int count = 0;
            for (AuctionEndAtView view : lives) {
                if (view.auctionId() == null || view.endAt() == null) {
                    continue;
                }
                scheduleOrReschedule(view.auctionId(), view.endAt());
                count++;
            }
            auctionCloseMetricsRecorder.recordBootstrapRegistration(count);
            log.info("[AUC_CLOSE_BOOTSTRAP] scheduledCount={}", count);
        } catch (Exception e) {
            log.error("[AUC_CLOSE_BOOTSTRAP_ERROR]", e);
        }
    }

    // 놓친 마감을 주기적으로 복구하는 sweep 루트
    // 스케줄 등록 실패 및 예외 상황 대비
    @Scheduled(fixedDelayString = "${auction.runtime.scheduler.sweep-delay-ms:60000}")
    public void sweepOverdueAuctions() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<UUID> ids = auctionCloseRepository.findIdsToClose(
                AuctionStatus.LIVE,
                now,
                PageRequest.of(0, auctionRuntimeProperties.getScheduler().getSweepBatchSize())
            );

            if (ids.isEmpty()) {
                return;
            }

            int recovered = 0;
            for (UUID id : ids) {
                try {
                    auctionCloseService.closeAuction(id);
                    recovered++;
                } catch (Exception e) {
                    log.error("[AUC_CLOSE_SWEEP_ITEM_ERROR] auctionId={}", id, e);
                }
            }
            auctionCloseMetricsRecorder.recordOverdueRecovery(recovered);
            log.info("[AUC_CLOSE_SWEEP] scanned={}, recovered={}", ids.size(), recovered);
        } catch (Exception e) {
            log.error("[AUC_CLOSE_SWEEP_ERROR]", e);
        }
    }

    private void runCloseSafely(UUID auctionId, long token) {
        try {
            auctionCloseService.closeAuction(auctionId);
        } catch (Exception e) {
            log.error("[AUC_CLOSE_RUN_ERROR] auctionId={}, token={}", auctionId, token, e);
        } finally {
            scheduled.computeIfPresent(auctionId, (id, ref) -> ref.token == token ? null : ref);
        }
    }

    // endAt이 과거면 바로 지금 실행되도록 보정
    private Instant normalizeRunAt(OffsetDateTime endAt) {
        Instant runAt = endAt.toInstant();
        Instant now = Instant.now();
        return runAt.isBefore(now) ? now : runAt;
    }

    private static final class ScheduledRef {
        private final long token;
        private volatile ScheduledFuture<?> future;

        private ScheduledRef(long token) {
            this.token = token;
        }
    }
}
