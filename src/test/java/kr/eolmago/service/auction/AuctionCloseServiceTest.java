package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.repository.auction.*;
import kr.eolmago.repository.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.eolmago.service.auction.event.AuctionSoldEvent;
import kr.eolmago.service.auction.monitoring.AuctionCloseMetricsRecorder;
import kr.eolmago.service.auction.monitoring.AuctionLockFailureDetector;
import kr.eolmago.service.notification.publish.NotificationPublishCommand;
import kr.eolmago.service.notification.publish.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.service.auction.BidTestFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


// 경매 종료 시 낙찰/유찰 처리 로직 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionCloseService 단위 테스트")
class AuctionCloseServiceTest {

    @Mock
    private AuctionCloseRepository auctionCloseRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionItemRepository auctionItemRepository;

    @Mock
    private AuctionImageRepository auctionImageRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationPublisher notificationPublisher;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AuctionCloseService sut;

    private final UUID auctionId = generateAuctionId();
    private final UUID sellerId = generateUserId();
    private final UUID buyerId = generateUserId();

    @BeforeEach
    void setUp() {
        sut = new AuctionCloseService(
                auctionCloseRepository, bidRepository,
                auctionItemRepository, auctionImageRepository, userRepository,
                notificationPublisher, eventPublisher,
                new AuctionCloseMetricsRecorder(new SimpleMeterRegistry()),
                new AuctionLockFailureDetector()
        );
    }

    @Nested
    @DisplayName("경매 종료 처리")
    class CloseAuctionTests {

        @Test
        @DisplayName("입찰 없는 LIVE 경매 종료 시 ENDED_UNSOLD 처리 및 유찰 알림 발행")
        void givenLiveAuctionWithNoBids_whenClose_thenMarkAsUnsoldAndNotify() {
            // Given
            Auction auction = createMockLiveAuction(auctionId, sellerId);
            when(auction.getEndAt()).thenReturn(OffsetDateTime.now().minusMinutes(1)); // 이미 종료 시간 지남

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopByAuctionOrderByAmountDescCreatedAtAsc(auction))
                    .thenReturn(Optional.empty());

            // When
            sut.closeAuction(auctionId);

            // Then
            verify(auction).closeAsUnsold();
            verify(notificationPublisher).publish(any(NotificationPublishCommand.class));
        }

        @Test
        @DisplayName("입찰 있는 LIVE 경매 종료 시 ENDED_SOLD 처리 및 낙찰 알림/이벤트 발행")
        void givenLiveAuctionWithBids_whenClose_thenMarkAsSoldAndNotifyBothParties() {
            // Given
            Auction auction = createMockLiveAuction(auctionId, sellerId);
            when(auction.getEndAt()).thenReturn(OffsetDateTime.now().minusMinutes(1));

            User buyer = createMockUser(buyerId);
            int finalAmount = 50000;
            Bid highestBid = createMockBid(auction, buyer, finalAmount, "req-highest");

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopByAuctionOrderByAmountDescCreatedAtAsc(auction))
                    .thenReturn(Optional.of(highestBid));

            // When
            sut.closeAuction(auctionId);

            // Then
            verify(auction).closeAsSold(buyer, (long) finalAmount);

            // 판매자 + 구매자에게 알림 발행 (2회)
            verify(notificationPublisher, times(2)).publish(any(NotificationPublishCommand.class));

            // AuctionSoldEvent 발행
            ArgumentCaptor<AuctionSoldEvent> eventCaptor = ArgumentCaptor.forClass(AuctionSoldEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());

            AuctionSoldEvent event = eventCaptor.getValue();
            assertThat(event.auctionId()).isEqualTo(auctionId);
            assertThat(event.sellerId()).isEqualTo(sellerId);
            assertThat(event.buyerId()).isEqualTo(buyerId);
        }

        @Test
        @DisplayName("이미 종료된 경매에 중복 close 호출해도 안전")
        void givenAlreadyClosedAuction_whenClose_thenDoNothing() {
            // Given
            Auction auction = mock(Auction.class);
            when(auction.getStatus()).thenReturn(AuctionStatus.ENDED_SOLD);
            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When
            sut.closeAuction(auctionId);

            // Then: 아무 상태 변경도 없어야 함
            verify(auction, never()).closeAsSold(any(), anyLong());
            verify(auction, never()).closeAsUnsold();
            verify(notificationPublisher, never()).publish(any());
        }

        @Test
        @DisplayName("종료 시간이 아직 안 된 경매는 종료되지 않음")
        void givenAuctionNotYetEnded_whenClose_thenDoNothing() {
            // Given
            Auction auction = createMockLiveAuction(auctionId, sellerId);
            when(auction.getEndAt()).thenReturn(OffsetDateTime.now().plusHours(1)); // 아직 1시간 남음

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When
            sut.closeAuction(auctionId);

            // Then
            verify(auction, never()).closeAsSold(any(), anyLong());
            verify(auction, never()).closeAsUnsold();
        }

        @Test
        @DisplayName("존재하지 않는 경매 종료 시도 시 AUCTION_NOT_FOUND")
        void givenNonExistentAuction_whenClose_thenThrowNotFound() {
            // Given
            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sut.closeAuction(auctionId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("판매자 경매 취소")
    class CancelAuctionBySellerTests {

        @Test
        @DisplayName("입찰 없는 LIVE 경매 취소 성공")
        void givenLiveAuctionWithNoBids_whenCancel_thenSuccess() {
            // Given
            Auction auction = createMockLiveAuction(auctionId, sellerId);
            when(auction.getBidCount()).thenReturn(0);

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.existsByAuction(auction)).thenReturn(false);

            // When
            sut.cancelAuctionBySeller(auctionId, sellerId);

            // Then
            verify(auction).cancelBySeller();
            verify(notificationPublisher).publish(any(NotificationPublishCommand.class));
        }

        @Test
        @DisplayName("입찰 있는 경매 취소 시도 시 AUCTION_HAS_ACTIVE_BIDS")
        void givenAuctionWithBids_whenCancel_thenThrowHasActiveBids() {
            // Given
            Auction auction = createMockLiveAuction(auctionId, sellerId);
            when(auction.getBidCount()).thenReturn(5);

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then
            assertThatThrownBy(() -> sut.cancelAuctionBySeller(auctionId, sellerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_HAS_ACTIVE_BIDS);
        }

        @Test
        @DisplayName("다른 판매자가 취소 시도 시 AUCTION_UNAUTHORIZED")
        void givenDifferentSeller_whenCancel_thenThrowUnauthorized() {
            // Given
            UUID otherSellerId = generateUserId();
            Auction auction = createMockLiveAuction(auctionId, sellerId);

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then
            assertThatThrownBy(() -> sut.cancelAuctionBySeller(auctionId, otherSellerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_UNAUTHORIZED);
        }

        @Test
        @DisplayName("LIVE가 아닌 경매 취소 시도 시 AUCTION_NOT_LIVE")
        void givenNonLiveAuction_whenCancel_thenThrowNotLive() {
            // Given
            Auction auction = mock(Auction.class);
            User seller = createMockUser(sellerId);
            when(auction.getSeller()).thenReturn(seller);
            when(auction.getStatus()).thenReturn(AuctionStatus.DRAFT);

            when(auctionCloseRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then
            assertThatThrownBy(() -> sut.cancelAuctionBySeller(auctionId, sellerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_LIVE);
        }
    }

    private Auction createMockLiveAuction(UUID auctionId, UUID sellerId) {
        Auction auction = mock(Auction.class);
        User seller = createMockUser(sellerId);

        when(auction.getAuctionId()).thenReturn(auctionId);
        when(auction.getStatus()).thenReturn(AuctionStatus.LIVE);
        when(auction.getSeller()).thenReturn(seller);

        return auction;
    }
}
