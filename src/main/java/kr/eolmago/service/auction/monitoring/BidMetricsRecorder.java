package kr.eolmago.service.auction.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static kr.eolmago.service.auction.monitoring.BidMetricNames.*;

// 입찰 경로 메트릭 기록
@Component
public class BidMetricsRecorder {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger inFlightRequests = new AtomicInteger();

    public BidMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder(BID_INFLIGHT_METRIC, inFlightRequests, AtomicInteger::get)
            .description("Current number of in-flight bid requests")
            .register(meterRegistry);
    }

    // 총 입찰 요청 횟수 기록
    public void recordRequest() {
        Counter.builder(BID_REQUEST_METRIC)
            .description("Total bid requests received by the application")
            .register(meterRegistry)
            .increment();
    }

    public void incrementInFlight() {
        inFlightRequests.incrementAndGet();
    }

    public void decrementInFlight() {
        int current = inFlightRequests.decrementAndGet();
        if (current < 0) {
            inFlightRequests.compareAndSet(current, 0);
        }
    }

    // 입찰 요청의 결과별 횟수 기록
    public void recordOutcome(String outcome) {
        Counter.builder(BID_OUTCOME_METRIC)
            .description("Bid request outcomes by business result")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .increment();
    }

    // HTTP 요청 진입부터 응답 반환까지 전체 소요 시간을 기록
    public void recordDuration(String outcome, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        Timer.builder(BID_DURATION_METRIC)
            .description("Bid request end-to-end processing duration")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .record(duration);
    }

    // 중복 요청 조회 결과 기록
    public void recordIdempotencyLookup(String result) {
        Counter.builder(BID_IDEMPOTENCY_LOOKUP_METRIC)
            .description("Bid idempotency lookup results")
            .tag("result", sanitize(result))
            .register(meterRegistry)
            .increment();
    }

    // 락 대기 시간 기록
    public void recordLockWait(String result, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        Timer.builder(BID_LOCK_WAIT_METRIC)
            .description("Time spent waiting to acquire the auction row lock")
            .tag("result", sanitize(result))
            .register(meterRegistry)
            .record(duration);
    }

    // 락을 잡은 시점부터 트랜잭션 커밋(락 해제)까지의 시간을 기록
    public void recordLockHold(String outcome, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        Timer.builder(BID_LOCK_HOLD_METRIC)
            .description("Time from successful lock acquisition until transaction completion")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .record(duration);
    }

    // 트랜잭션의 시작부터 종료까지 전체 시간을 기록
    public void recordTransactionDuration(String outcome, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        Timer.builder(BID_TRANSACTION_DURATION_METRIC)
            .description("Transactional duration for bid writes")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .record(duration);
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
