package kr.eolmago.service.auction.monitoring;

import kr.eolmago.global.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 입찰 감사 로거
@Component
public class AuctionBidAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("kr.eolmago.auction.bid.audit");

    public void log(BidAuditLog auditLog) {
        LoggingEventBuilder builder = switch (resolveLevel(auditLog.outcome(), auditLog.error())) {
            case ERROR -> log.atError();
            case WARN -> log.atWarn();
            default -> log.atInfo();
        };

        add(builder, "event", "auction_bid");
        add(builder, "phase", auditLog.phase());
        add(builder, "auctionId", stringify(auditLog.auctionId()));
        add(builder, "buyerId", stringify(auditLog.buyerId()));
        add(builder, "requestId", auditLog.requestId());
        add(builder, "amount", auditLog.amount());
        add(builder, "outcome", auditLog.outcome());
        add(builder, "totalDurationMs", auditLog.totalDurationMs());
        add(builder, "lockWaitMs", auditLog.lockWaitMs());
        add(builder, "lockHoldMs", auditLog.lockHoldMs());
        add(builder, "transactionDurationMs", auditLog.transactionDurationMs());
        add(builder, "currentHighest", auditLog.currentHighest());
        add(builder, "minAcceptable", auditLog.minAcceptable());
        add(builder, "highestBidderId", stringify(auditLog.highestBidderId()));
        add(builder, "extensionApplied", auditLog.extensionApplied());

        if (shouldAttachCause(auditLog.outcome(), auditLog.error())) {
            builder.setCause(auditLog.error());
        }

        builder.log("Auction bid processed");
    }

    private LogLevel resolveLevel(String outcome, Throwable error) {
        if ("internal_error".equals(outcome) || "business_error".equals(outcome)) {
            return LogLevel.ERROR;
        }
        if (error != null && !(error instanceof BusinessException)) {
            return LogLevel.ERROR;
        }
        if (outcome == null) {
            return LogLevel.WARN;
        }
        return switch (outcome) {
            case "accepted", "accepted_extended", "idempotency_replay" -> LogLevel.INFO;
            default -> LogLevel.WARN;
        };
    }

    private boolean shouldAttachCause(String outcome, Throwable error) {
        if (error == null) {
            return false;
        }
        return !(error instanceof BusinessException) || "internal_error".equals(outcome) || "business_error".equals(outcome);
    }

    private void add(LoggingEventBuilder builder, String key, Object value) {
        if (value != null) {
            builder.addKeyValue(key, value);
        }
    }

    private String stringify(UUID value) {
        return value == null ? null : value.toString();
    }

    private enum LogLevel {
        INFO,
        WARN,
        ERROR
    }

    public record BidAuditLog(
        String phase,
        UUID auctionId,
        UUID buyerId,
        String requestId,
        Integer amount,
        String outcome,
        Long totalDurationMs,
        Long lockWaitMs,
        Long lockHoldMs,
        Long transactionDurationMs,
        Integer currentHighest,
        Integer minAcceptable,
        UUID highestBidderId,
        Boolean extensionApplied,
        Throwable error
    ) {
    }
}
