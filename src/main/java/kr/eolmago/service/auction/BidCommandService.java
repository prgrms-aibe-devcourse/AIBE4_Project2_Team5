package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.global.util.DurationCalculator;
import kr.eolmago.repository.auction.AuctionRepository;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.repository.user.UserRepository;
import kr.eolmago.service.auction.event.AuctionEndAtChangedEvent;
import kr.eolmago.service.auction.monitoring.BidMetricsRecorder;
import kr.eolmago.service.auction.monitoring.AuctionBidAuditLogger;
import kr.eolmago.service.auction.monitoring.AuctionLockFailureDetector;
import kr.eolmago.service.notification.publish.NotificationPublishCommand;
import kr.eolmago.service.notification.publish.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.service.auction.constants.AuctionConstants.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BidCommandService {

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NotificationPublisher notificationPublisher;
    private final BidMetricsRecorder bidMetricsRecorder;
    private final AuctionLockFailureDetector auctionLockFailureDetector;
    private final AuctionBidAuditLogger auctionBidAuditLogger;
    private final TransactionTemplate bidWriteTransactionTemplate;

    public BidCreateResponse createBid(UUID auctionId, UUID buyerId, int amount, String requestId) {
        BidTransactionTrace trace = new BidTransactionTrace(auctionId, buyerId, requestId, amount);

        try {
            BidCreateResponse response = bidWriteTransactionTemplate.execute(status -> { // 쓰기 트랜잭션
                trace.transactionStartedAtNanos = System.nanoTime(); // 트랜잭션 시작 시간 측정

                // 멱등성 체크, 중복 방지
                Optional<Bid> existing = bidRepository.findByClientRequestIdAndBidderId(requestId, buyerId);
                if (existing.isPresent()) {
                    Bid bid = existing.get();

                    if (bid.getAmount() != amount) {
                        if ("unknown".equals(trace.outcome)) {
                            trace.outcome = "idempotency_conflict";
                        }
                        throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT);
                    }

                    if ("unknown".equals(trace.outcome)) {
                        trace.outcome = "idempotency_replay";
                    }

                    Auction auc = bid.getAuction();
                    UUID hBidderId = bidRepository.findTopBidderIdByAuction(auc).orElse(null);
                    BidCreateResponse res = BidCreateResponse.from(auc, bid, false, hBidderId);
                    trace.captureResponse(res);
                    return res;
                }

                long waitStartedAt = System.nanoTime(); // 락 대기 시간 측정(병목 체크)
                Auction auction;

                try {
                    // 비관적 락, 해당 경매 row 를 쓰기 잠금 상태로 가져옴, 직렬화
                    auction = auctionRepository.findByIdForUpdate(auctionId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

                    trace.lockWaitNanos = System.nanoTime() - waitStartedAt;
                    trace.lockWaitResult = "acquired";
                    trace.lockAcquiredAtNanos = System.nanoTime();

                } catch (RuntimeException e) {

                    if (auctionLockFailureDetector.isLockFailure(e)) {
                        // lock_timeout 내에 선행 트랜잭션 락을 얻지 못하면 빠르게 실패, 입찰 도메인은 최고가 한번만 유효
                        trace.lockWaitNanos = System.nanoTime() - waitStartedAt;
                        trace.lockWaitResult = "timeout";
                        throw new BusinessException(ErrorCode.AUCTION_LOCK_BUSY);
                    }
                    throw e;
                }

                // 락 획득 후 멱등성 재확인
                Optional<Bid> existingAfterLock = bidRepository.findByClientRequestIdAndBidderId(requestId, buyerId);
                if (existingAfterLock.isPresent()) {
                    Bid bid = existingAfterLock.get();

                    if (bid.getAmount() != amount) {
                        if ("unknown".equals(trace.outcome)) {
                            trace.outcome = "idempotency_conflict";
                        }
                        throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT);
                    }

                    if ("unknown".equals(trace.outcome)) {
                        trace.outcome = "idempotency_replay";
                    }

                    UUID hBidderId = bidRepository.findTopBidderIdByAuction(auction).orElse(null);
                    BidCreateResponse res = BidCreateResponse.from(auction, bid, false, hBidderId);
                    trace.captureResponse(res);
                    return res;
                }

                // 입찰자 검증
                if (auction.getStatus() != AuctionStatus.LIVE) {
                    throw new BusinessException(ErrorCode.AUCTION_NOT_LIVE);
                }
                if (auction.getSeller().getUserId().equals(buyerId)) {
                    throw new BusinessException(ErrorCode.SELLER_CANNOT_BID);
                }

                // 입찰 금액 검증
                // 현재가 기준 검증은 반드시 락을 잡은 후 같은 트랜잭션 안에서 수행
                int currentHighest = auction.getCurrentPrice();
                int increment = auction.getBidIncrement();
                int minAcceptable = currentHighest + increment;

                if (amount < minAcceptable) {
                    throw new BusinessException(ErrorCode.BID_INVALID_AMOUNT);
                }
                if (amount > MAX_BID_AMOUNT) {
                    throw new BusinessException(ErrorCode.BID_AMOUNT_EXCEEDS_LIMIT);
                }

                int diff = amount - currentHighest;
                if (increment > 0 && diff % increment != 0) {
                    throw new BusinessException(ErrorCode.BID_INVALID_INCREMENT);
                }

                // 입찰 update
                UUID prevHighestBidderId = bidRepository.findTopBidderIdByAuction(auction).orElse(null); // 최고 입찰자 추적
                User bidder = userRepository.getReferenceById(buyerId);

                Bid bid = Bid.create(auction, bidder, amount, requestId); // 입찰 생성
                auction.updateBid(amount); // 경매 상태 갱신

                // 마감 임박 시(5분 이내) 자동 연장 처리
                OffsetDateTime now = OffsetDateTime.now();
                OffsetDateTime endAt = auction.getEndAt();
                OffsetDateTime originalEndAt = auction.getOriginalEndAt();
                boolean extensionApplied = false;

                if (endAt != null && originalEndAt != null) {
                    long remainingSeconds = ChronoUnit.SECONDS.between(now, endAt);

                    if (remainingSeconds > 0 && remainingSeconds <= EXTENSION_THRESHOLD_SECONDS) {
                        OffsetDateTime candidateEndAt = endAt.plusSeconds(EXTENSION_DURATION_SECONDS);
                        OffsetDateTime capEndAt = now.plusSeconds(MAX_REMAINING_SECONDS);
                        OffsetDateTime hardCapEndAt = originalEndAt.plusHours(HARD_MAX_EXTENSION_HOURS);

                        OffsetDateTime newEndAt = candidateEndAt;
                        if (newEndAt.isAfter(capEndAt)) {
                            newEndAt = capEndAt;
                        }
                        if (newEndAt.isAfter(hardCapEndAt)) {
                            newEndAt = hardCapEndAt;
                        }

                        if (newEndAt.isAfter(endAt)) {
                            int newDurationHours = DurationCalculator.calculateHoursBetween(originalEndAt, newEndAt);
                            auction.extendEndTime(newEndAt, newDurationHours);
                            extensionApplied = true;
                        }
                    }
                }

                // saveAndFlush 로 INSERT/충돌을 트랜잭션 내부에서 조기에 표면화
                bid = bidRepository.saveAndFlush(bid);

                // 커밋 후 알림 및 이벤트 발행
                // 락이 필요한 핵심 쓰기만 트랜잭션 내부에서 처리하고,
                // 알림/이벤트 같은 부가 로직은 afterCommit 으로 분리해 임계구역을 짧게 유지
                final boolean finalExtensionApplied = extensionApplied;
                final UUID finalPrevHighestBidderId = prevHighestBidderId;
                final Auction finalAuction = auction;

                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                    @Override
                    public void afterCommit() { // 진행 중인 트랜잭션이 성공적으로 커밋된 뒤에만 작업을 실행
                        try {
                            // 입찰 알림 발행
                            notificationPublisher.publish(
                                NotificationPublishCommand.bidAccepted(buyerId, finalAuction.getAuctionId(), amount)
                            );
                        } catch (RuntimeException ex) {
                            log.error(
                                "[BID_AFTER_COMMIT_FAILED] auctionId={} action=bidAcceptedNotification",
                                finalAuction.getAuctionId(),
                                ex
                            );
                        }

                        if (finalPrevHighestBidderId != null && !finalPrevHighestBidderId.equals(buyerId)) {
                            try {
                                // 최고가 갱신 알림 발행
                                notificationPublisher.publish(
                                    NotificationPublishCommand.bidOutbid(
                                        finalPrevHighestBidderId,
                                        finalAuction.getAuctionId()
                                    )
                                );
                            } catch (RuntimeException ex) {
                                log.error(
                                    "[BID_AFTER_COMMIT_FAILED] auctionId={} action=bidOutbidNotification",
                                    finalAuction.getAuctionId(),
                                    ex
                                );
                            }
                        }

                        if (finalExtensionApplied) {
                            try {
                                // 경매 자동 연장 시, 스케줄러에 새 마감 시간 이벤트 발행 -> 종료 작업 재스케줄링
                                eventPublisher.publishEvent(
                                    new AuctionEndAtChangedEvent(finalAuction.getAuctionId(), finalAuction.getEndAt())
                                );
                            } catch (RuntimeException ex) {
                                log.error(
                                    "[BID_AFTER_COMMIT_FAILED] auctionId={} action=auctionEndAtChangedEvent",
                                    finalAuction.getAuctionId(),
                                    ex
                                );
                            }
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        // 입찰 쓰기 트랜잭션이 끝난 시점 시간 기록
                        // 트랜잭션 총 소요 시간과 락을 얼마나 오래 쥐고 있었는지를 더 정확하게 계산
                        trace.transactionCompletedAtNanos = System.nanoTime();

                        if (trace.lockAcquiredAtNanos > 0) {
                            // 락을 잡은 후 트랜잭션이 끝날 때까지의 시간 기록
                            trace.lockHoldNanos = trace.transactionCompletedAtNanos - trace.lockAcquiredAtNanos;
                        }
                    }
                });

                if ("unknown".equals(trace.outcome)) {
                    trace.outcome = extensionApplied ? "accepted_extended" : "accepted";
                }

                BidCreateResponse res = BidCreateResponse.from(auction, bid, extensionApplied, buyerId);
                trace.captureResponse(res);
                return res;
            });

            if (response == null) {
                if ("unknown".equals(trace.outcome)) {
                    trace.outcome = "internal_error";
                }
                throw new IllegalStateException("Bid transaction returned null response.");
            }

            return response;

        } catch (BusinessException e) {

            String mappedOutcome = switch (e.getErrorCode()) {
                case AUCTION_NOT_FOUND -> "auction_not_found";
                case AUCTION_NOT_LIVE -> "auction_not_live";
                case SELLER_CANNOT_BID -> "seller_cannot_bid";
                case BID_INVALID_AMOUNT -> "invalid_amount";
                case BID_INVALID_INCREMENT -> "invalid_increment";
                case BID_AMOUNT_EXCEEDS_LIMIT -> "amount_exceeds_limit";
                case BID_IDEMPOTENCY_CONFLICT -> "idempotency_conflict";
                case AUCTION_LOCK_BUSY -> "lock_busy";
                default -> "business_error";
            };

            if ("unknown".equals(trace.outcome)) {
                trace.outcome = mappedOutcome;
            }
            trace.error = e;
            throw e;

        } catch (DataIntegrityViolationException e) {
            // unique constraint (bidder_id, client_request_id) 위반 처리
            // DB가 중복을 감지했으므로, 조회 성공 여부와 관계없이 멱등성 처리 필요

            Optional<Bid> existing = bidRepository.findByClientRequestIdAndBidderId(requestId, buyerId);
            if (existing.isPresent()) {
                Bid bid = existing.get();

                if (bid.getAmount() != amount) {
                    // 같은 requestId인데 금액이 다름 = 충돌
                    if ("unknown".equals(trace.outcome)) {
                        trace.outcome = "idempotency_conflict";
                    }
                    trace.error = e;
                    throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT);
                }

                // 같은 requestId, 같은 금액 = 정상적인 재시도
                if ("unknown".equals(trace.outcome)) {
                    trace.outcome = "idempotency_replay";
                }

                Auction auc = bid.getAuction();
                UUID hBidderId = bidRepository.findTopBidderIdByAuction(auc).orElse(null);
                BidCreateResponse resolved = BidCreateResponse.from(auc, bid, false, hBidderId);
                trace.captureResponse(resolved);
                return resolved;
            }

            // 조회 실패했지만 constraint 위반 발생 = 다른 트랜잭션이 처리 중
            // READ_COMMITTED 격리 수준에서 커밋 전이라 조회 안 되는 것은 정상
            // 클라이언트에게는 "중복 요청이므로 재시도하라"는 신호를 보냄
            if ("unknown".equals(trace.outcome)) {
                trace.outcome = "idempotency_conflict";
            }
            trace.error = e;
            throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT);

        } catch (RuntimeException e) {

            if (auctionLockFailureDetector.isLockFailure(e)) {
                if ("unknown".equals(trace.outcome)) {
                    trace.outcome = "lock_busy";
                }
                trace.error = e;
                throw new BusinessException(ErrorCode.AUCTION_LOCK_BUSY);
            }

            if ("unknown".equals(trace.outcome)) {
                trace.outcome = "internal_error";
            }
            trace.error = e;
            throw e;

        } finally {

            // finish
            long completedAt = trace.transactionCompletedAtNanos > 0
                ? trace.transactionCompletedAtNanos
                : System.nanoTime();

            if (trace.transactionStartedAtNanos > 0) {
                trace.transactionDurationNanos = completedAt - trace.transactionStartedAtNanos;
            }

            if (trace.lockAcquiredAtNanos > 0 && trace.lockHoldNanos < 0) {
                trace.lockHoldNanos = completedAt - trace.lockAcquiredAtNanos;
            }

            // recordTo
            if (trace.lockWaitResult != null && trace.lockWaitNanos >= 0) {
                bidMetricsRecorder.recordLockWait(trace.lockWaitResult, Duration.ofNanos(trace.lockWaitNanos));
            }
            if (trace.lockAcquiredAtNanos > 0 && trace.lockHoldNanos >= 0) {
                bidMetricsRecorder.recordLockHold(trace.outcome, Duration.ofNanos(trace.lockHoldNanos));
            }
            if (trace.transactionStartedAtNanos > 0 && trace.transactionDurationNanos >= 0) {
                bidMetricsRecorder.recordTransactionDuration(
                    trace.outcome,
                    Duration.ofNanos(trace.transactionDurationNanos)
                );
            }

            // toAuditLog
            auctionBidAuditLogger.log(new AuctionBidAuditLogger.BidAuditLog(
                "transaction",
                trace.auctionId,
                trace.buyerId,
                trace.requestId,
                trace.amount,
                trace.outcome,
                trace.transactionDurationNanos < 0 ? null : Duration.ofNanos(trace.transactionDurationNanos).toMillis(),
                trace.lockWaitNanos < 0 ? null : Duration.ofNanos(trace.lockWaitNanos).toMillis(),
                trace.lockHoldNanos < 0 ? null : Duration.ofNanos(trace.lockHoldNanos).toMillis(),
                trace.transactionDurationNanos < 0 ? null : Duration.ofNanos(trace.transactionDurationNanos).toMillis(),
                trace.currentHighest,
                trace.minAcceptable,
                trace.highestBidderId,
                trace.extensionApplied,
                trace.error
            ));
        }
    }

    private static final class BidTransactionTrace {
        private final UUID auctionId;
        private final UUID buyerId;
        private final String requestId;
        private final int amount;

        private String outcome = "unknown";
        private Throwable error;
        private long transactionStartedAtNanos = -1;
        private long transactionCompletedAtNanos = -1;
        private long transactionDurationNanos = -1;
        private long lockAcquiredAtNanos = -1;
        private long lockWaitNanos = -1;
        private String lockWaitResult;
        private long lockHoldNanos = -1;
        private Integer currentHighest;
        private Integer minAcceptable;
        private UUID highestBidderId;
        private Boolean extensionApplied;

        private BidTransactionTrace(UUID auctionId, UUID buyerId, String requestId, int amount) {
            this.auctionId = auctionId;
            this.buyerId = buyerId;
            this.requestId = requestId;
            this.amount = amount;
        }

        private void captureResponse(BidCreateResponse response) {
            this.currentHighest = response.currentHighestAmount();
            this.minAcceptable = response.minAcceptableAmount();
            this.highestBidderId = response.highestBidderId();
            this.extensionApplied = response.extensionApplied();
        }
    }
}
