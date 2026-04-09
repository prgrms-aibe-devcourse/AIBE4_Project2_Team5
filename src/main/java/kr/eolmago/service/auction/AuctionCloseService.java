package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.AuctionImage;
import kr.eolmago.domain.entity.auction.AuctionItem;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.dto.api.auction.response.AuctionRepublishResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.repository.auction.AuctionCloseRepository;
import kr.eolmago.repository.auction.AuctionImageRepository;
import kr.eolmago.repository.auction.AuctionItemRepository;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.repository.user.UserRepository;
import kr.eolmago.service.auction.event.AuctionSoldEvent;
import kr.eolmago.service.auction.metrics.AuctionCloseMetricsRecorder;
import kr.eolmago.service.auction.support.AuctionLockFailureDetector;
import kr.eolmago.service.notification.publish.NotificationPublishCommand;
import kr.eolmago.service.notification.publish.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuctionCloseService {

    private final AuctionCloseRepository auctionCloseRepository;
    private final BidRepository bidRepository;
    private final AuctionItemRepository auctionItemRepository;
    private final AuctionImageRepository auctionImageRepository;
    private final UserRepository userRepository;
    private final NotificationPublisher notificationPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final AuctionCloseMetricsRecorder auctionCloseMetricsRecorder;
    private final AuctionLockFailureDetector auctionLockFailureDetector;

    // 마감 작업은 별도 트랜잭션에서 수행
    // 락 경합 시 오래 대기하지 않고 빠르게 빠져나와 sweep/재기동 복구 루트에 맡김
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        timeoutString = "${auction.runtime.close.transaction-timeout-sec:5}"
    )
    public void closeAuction(UUID auctionId) {
        long startedAt = System.nanoTime();
        String outcome = "unknown";

        try {
            Optional<Auction> lockedAuction;
            try {
                // 비관적 락(row lock)
                lockedAuction = auctionCloseRepository.findByIdForUpdate(auctionId);
            } catch (RuntimeException e) {
                if (auctionLockFailureDetector.isLockFailure(e)) {
                    log.warn("[AUC_CLOSE_LOCK_BUSY] auctionId={}", auctionId, e);
                    lockedAuction = Optional.empty();
                } else {
                    throw e;
                }
            }
            if (lockedAuction.isEmpty()) {
                outcome = "lock_busy";
                return;
            }

            Auction auction = lockedAuction.get();
            if (auction.getStatus() != AuctionStatus.LIVE) {
                outcome = "skip_not_live";
                return;
            }

            OffsetDateTime now = OffsetDateTime.now();
            if (auction.getEndAt() == null || auction.getEndAt().isAfter(now)) {
                outcome = "skip_not_due";
                return;
            }

            auctionCloseMetricsRecorder.recordCloseDelay(Duration.between(auction.getEndAt(), now));

            // 최고 입찰자 확인
            Bid highestBid = bidRepository
                .findTopByAuctionOrderByAmountDescCreatedAtAsc(auction)
                .orElse(null);

            // 유찰 처리
            if (highestBid == null) {
                auction.closeAsUnsold();
                notificationPublisher.publish(
                    NotificationPublishCommand.auctionUnsold(
                        auction.getSeller().getUserId(),
                        auction.getAuctionId()
                    )
                );
                outcome = "closed_unsold";
                return;
            }

            User buyer = highestBid.getBidder();
            Long finalPrice = (long) highestBid.getAmount();
            auction.closeAsSold(buyer, finalPrice);

            // 낙찰 처리
            notificationPublisher.publish(
                NotificationPublishCommand.auctionSold(
                    auction.getSeller().getUserId(),
                    auction.getAuctionId(),
                    finalPrice
                )
            );
            notificationPublisher.publish(
                NotificationPublishCommand.auctionWon(
                    buyer.getUserId(),
                    auction.getAuctionId(),
                    finalPrice
                )
            );

            AuctionSoldEvent event = new AuctionSoldEvent(
                auction.getAuctionId(),
                auction.getSeller().getUserId(),
                buyer.getUserId()
            );
            eventPublisher.publishEvent(event);

            log.info(
                "[AUC_CLOSE_SOLD] auctionId={}, sellerId={}, buyerId={}, finalPrice={}",
                event.auctionId(), event.sellerId(), event.buyerId(), finalPrice
            );
            outcome = "closed_sold";
        } catch (BusinessException e) {
            outcome = switch (e.getErrorCode()) {
                case AUCTION_NOT_FOUND -> "auction_not_found";
                case AUCTION_UNAUTHORIZED -> "unauthorized";
                case AUCTION_NOT_UNSOLD -> "not_unsold";
                case AUCTION_NOT_LIVE -> "not_live";
                case AUCTION_HAS_ACTIVE_BIDS -> "has_active_bids";
                default -> "business_error";
            };
            throw e;
        } finally { // 마감은 finally에서 결과와 처리 시간을 반드시 남긴다.
            auctionCloseMetricsRecorder.recordCloseOutcome(outcome);
            auctionCloseMetricsRecorder.recordCloseDuration(
                outcome,
                Duration.ofNanos(System.nanoTime() - startedAt)
            );
        }
    }

    // 아래 메서드들은 마감 관련 도메인 서비스이긴 하지만,
    // 이번 관측의 핵심은 closeAuction이므로 주석은 짧게만 둔다.

    @Transactional
    public AuctionRepublishResponse republishUnsoldAuction(UUID auctionId, UUID sellerId) {
        Auction originalAuction = auctionCloseRepository.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        if (!originalAuction.getSeller().getUserId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.AUCTION_UNAUTHORIZED);
        }
        if (originalAuction.getStatus() != AuctionStatus.ENDED_UNSOLD) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_UNSOLD);
        }

        AuctionItem originalItem = originalAuction.getAuctionItem();
        List<AuctionImage> originalImages =
            auctionImageRepository.findByAuctionItemOrderByDisplayOrder(originalItem);

        AuctionItem newItem = AuctionItem.create(
            originalItem.getItemName(),
            originalItem.getCategory(),
            originalItem.getCondition(),
            new HashMap<>(originalItem.getSpecs())
        );
        auctionItemRepository.save(newItem);

        List<AuctionImage> newImages = new ArrayList<>();
        for (AuctionImage originalImage : originalImages) {
            newImages.add(AuctionImage.create(
                newItem,
                originalImage.getImageUrl(),
                originalImage.getDisplayOrder()
            ));
        }
        auctionImageRepository.saveAll(newImages);

        User seller = userRepository.getReferenceById(sellerId);
        Auction newAuction = Auction.create(
            newItem,
            seller,
            originalAuction.getTitle(),
            originalAuction.getDescription(),
            AuctionStatus.DRAFT,
            originalAuction.getStartPrice(),
            originalAuction.getBidIncrement(),
            originalAuction.getDurationHours(),
            null,
            null
        );
        auctionCloseRepository.save(newAuction);

        return new AuctionRepublishResponse(
            newAuction.getAuctionId(),
            originalAuction.getAuctionId()
        );
    }

    public void cancelAuctionBySeller(UUID auctionId, UUID sellerId) {
        Auction auction = auctionCloseRepository.findByIdForUpdate(auctionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.AUCTION_NOT_FOUND));

        if (!auction.getSeller().getUserId().equals(sellerId)) {
            throw new BusinessException(ErrorCode.AUCTION_UNAUTHORIZED);
        }
        if (auction.getStatus() != AuctionStatus.LIVE) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_LIVE);
        }
        if (auction.getBidCount() > 0 || bidRepository.existsByAuction(auction)) {
            throw new BusinessException(ErrorCode.AUCTION_HAS_ACTIVE_BIDS);
        }

        auction.cancelBySeller();
        notificationPublisher.publish(
            NotificationPublishCommand.auctionCanceled(sellerId, auction.getAuctionId())
        );
    }
}
