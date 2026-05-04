package kr.eolmago.service.auction.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

import static kr.eolmago.service.auction.monitoring.AuctionCloseMetricNames.*;

// 마감 경로 메트릭 기록
@Component
public class AuctionCloseMetricsRecorder {

    private final MeterRegistry meterRegistry;

    public AuctionCloseMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // 경매 마감 결과를 기록
    public void recordCloseOutcome(String outcome) {
        Counter.builder(CLOSE_OUTCOME_METRIC)
            .description("Auction close outcomes")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .increment();
    }

    // 경매 마감 처리 시간을 기록
    public void recordCloseDuration(String outcome, Duration duration) {
        Timer.builder(CLOSE_DURATION_METRIC)
            .description("Auction close processing duration")
            .tag("outcome", sanitize(outcome))
            .register(meterRegistry)
            .record(duration);
    }

    // 예정된 경매 종료 시간과 실제 마감 실행 사이의 지연 시간을 기록
    public void recordCloseDelay(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        Timer.builder(CLOSE_DELAY_METRIC)
            .description("Delay between scheduled auction end time and actual close execution")
            .register(meterRegistry)
            .record(duration);
    }

    // 경매 마감 스케줄 등록 결과를 기록
    public void recordScheduleRegistration(String result) {
        Counter.builder(SCHEDULE_REGISTRATION_METRIC)
            .description("Auction close schedule registration results")
            .tag("result", sanitize(result))
            .register(meterRegistry)
            .increment();
    }

    // 진행 중인 경매에 대한 부트스트랩 재등록 횟수를 기록
    public void recordBootstrapRegistration(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(BOOTSTRAP_REGISTRATION_METRIC)
            .description("Bootstrap re-registrations for live auctions")
            .register(meterRegistry)
            .increment(count);
    }

    // 스케줄된 스윕에 의해 처리된 지연 경매 복구 횟수를 기록
    public void recordOverdueRecovery(int count) {
        if (count <= 0) {
            return;
        }
        Counter.builder(OVERDUE_RECOVERY_METRIC)
            .description("Overdue auction recoveries handled by scheduled sweep")
            .register(meterRegistry)
            .increment(count);
    }

    private String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
