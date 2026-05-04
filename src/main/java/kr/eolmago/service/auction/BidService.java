package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.service.auction.monitoring.BidMetricsRecorder;
import kr.eolmago.service.auction.monitoring.AuctionBidAuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BidService {

    private final BidRepository bidRepository;
    private final BidCommandService bidCommandService;
    private final BidMetricsRecorder bidMetricsRecorder;
    private final AuctionBidAuditLogger auctionBidAuditLogger;

    // 입찰 생성
    public BidCreateResponse createBid(UUID auctionId, BidCreateRequest request, UUID buyerId) {
        // 메트릭 수집
        bidMetricsRecorder.recordRequest(); // 총 요청 수 증가
        bidMetricsRecorder.incrementInFlight(); // 현재 처리 중 요청 수 증가

        long startedAt = System.nanoTime(); // 입찰 전체 소요 시간 측정
        String outcome = "unknown";
        String requestId = request.clientRequestId();
        int amount = request.amount();
        Throwable error = null;

        try {

            // 멱등성 체크, 동일 요청이 존재하는지 검증
            if (requestId == null || requestId.isBlank()) {
                outcome = "missing_request_id";
                bidMetricsRecorder.recordIdempotencyLookup("missing_request_id");
                throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_REQUIRED);
            }

            Optional<Bid> existing = bidRepository.findByClientRequestIdAndBidderId(requestId, buyerId);
            if (existing.isPresent()) {
                Bid bid = existing.get();

                if (bid.getAmount() != amount) { // 같은 요청인데 금액이 다를 때 충돌 처리
                    outcome = "idempotency_conflict"; // 충돌
                    bidMetricsRecorder.recordIdempotencyLookup("conflict");
                    throw new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT);
                }

                outcome = "idempotency_replay"; // 재시도
                bidMetricsRecorder.recordIdempotencyLookup("hit");

                var auction = bid.getAuction();
                int currentHighest = auction.getCurrentPrice();
                int minAcceptable = currentHighest + auction.getBidIncrement();
                UUID highestBidderId = bidRepository.findTopBidderIdByAuction(auction).orElse(null);

                BidCreateResponse response = new BidCreateResponse(
                    bid.getBidId(),
                    auction.getAuctionId(),
                    bid.getAmount(),
                    currentHighest,
                    minAcceptable,
                    auction.getEndAt(),
                    false,
                    highestBidderId
                );

                auctionBidAuditLogger.log(new AuctionBidAuditLogger.BidAuditLog(
                    "quick_path",
                    auctionId,
                    buyerId,
                    requestId,
                    amount,
                    outcome,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    null,
                    null,
                    null,
                    response.currentHighestAmount(),
                    response.minAcceptableAmount(),
                    response.highestBidderId(),
                    response.extensionApplied(),
                    null
                ));

                return response; // 같은 requestId + 같은 금액 → 기존 결과 반환
            }

            bidMetricsRecorder.recordIdempotencyLookup("miss");

            // 같은 requestId 존재하지 않을 경우 새로운 입찰 처리
            // 실제 쓰기 로직은 BidCommandService 의 쓰기 트랜잭션으로 위임
            BidCreateResponse response = bidCommandService.createBid(auctionId, buyerId, amount, requestId);
            outcome = response.extensionApplied() ? "accepted_extended" : "accepted"; // 자동 연장 여부 기록
            return response;

        } catch (BusinessException e) {

            if ("unknown".equals(outcome)) {
                outcome = switch (e.getErrorCode()) {
                    case AUCTION_NOT_FOUND -> "auction_not_found";
                    case AUCTION_NOT_LIVE -> "auction_not_live";
                    case SELLER_CANNOT_BID -> "seller_cannot_bid";
                    case BID_INVALID_AMOUNT -> "invalid_amount";
                    case BID_INVALID_INCREMENT -> "invalid_increment";
                    case BID_AMOUNT_EXCEEDS_LIMIT -> "amount_exceeds_limit";
                    case BID_IDEMPOTENCY_REQUIRED -> "missing_request_id";
                    case BID_IDEMPOTENCY_CONFLICT -> "idempotency_conflict";
                    case AUCTION_LOCK_BUSY -> "lock_busy";
                    default -> "business_error";
                };
            }
            error = e;
            throw e;

        } catch (RuntimeException e) {

            outcome = "internal_error";
            error = e;
            throw e;

        } finally {

            boolean requiresAudit = switch (outcome) {
                case "missing_request_id", "idempotency_conflict", "internal_error" -> true;
                default -> false;
            };

            if (requiresAudit) {
                auctionBidAuditLogger.log(new AuctionBidAuditLogger.BidAuditLog(
                    "quick_path",
                    auctionId,
                    buyerId,
                    requestId,
                    amount,
                    outcome,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    error
                ));
            }

            bidMetricsRecorder.recordOutcome(outcome); // 최종 결과
            bidMetricsRecorder.recordDuration(
                outcome,
                Duration.ofNanos(System.nanoTime() - startedAt)
            ); // 입찰 전체 처리 시간(병목 체크)
            bidMetricsRecorder.decrementInFlight(); // 현재 처리 중 수 감소
        }
    }
}
