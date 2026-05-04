package kr.eolmago.service.auction;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.repository.auction.AuctionRepository;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.repository.user.UserRepository;
import kr.eolmago.service.auction.event.AuctionEndAtChangedEvent;
import kr.eolmago.service.auction.monitoring.BidMetricsRecorder;
import kr.eolmago.service.auction.monitoring.AuctionBidAuditLogger;
import kr.eolmago.service.auction.monitoring.AuctionLockFailureDetector;
import kr.eolmago.service.notification.publish.NotificationPublishCommand;
import kr.eolmago.service.notification.publish.NotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.service.auction.BidTestFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


// 비즈니스 규칙 검증, 락 예외 처리, 알림/이벤트 발행 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("BidCommandService 단위 테스트")
class BidCommandServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private NotificationPublisher notificationPublisher;

    private BidCommandService sut;

    private final UUID auctionId = generateAuctionId();
    private final UUID buyerId = generateUserId();
    private final UUID sellerId = generateUserId();
    private final String clientRequestId = generateClientRequestId();

    @BeforeEach
    void setUp() {
        TransactionTemplate bidWriteTransactionTemplate = new TransactionTemplate(new TestTransactionManager());
        bidWriteTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        bidWriteTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        bidWriteTransactionTemplate.setReadOnly(false);
        bidWriteTransactionTemplate.setTimeout(3);
        bidWriteTransactionTemplate.setName("test-auction-bid-write-tx");

        sut = new BidCommandService(
                auctionRepository, bidRepository, userRepository,
                eventPublisher, notificationPublisher,
                new BidMetricsRecorder(new SimpleMeterRegistry()),
                new AuctionLockFailureDetector(),
                new AuctionBidAuditLogger(),
                bidWriteTransactionTemplate
        );

        lenient().when(bidRepository.saveAndFlush(any(Bid.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("경매 존재 여부 검증")
    class AuctionExistenceValidation {

        @Test
        @DisplayName("경매가 없으면 AUCTION_NOT_FOUND 발생")
        void givenAuctionNotFound_whenCreateBid_thenThrowNotFound() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("경매 상태 검증")
    class AuctionStatusValidation {

        @ParameterizedTest
        @EnumSource(value = AuctionStatus.class, names = {"DRAFT", "ENDED_SOLD", "ENDED_UNSOLD"})
        @DisplayName("LIVE가 아닌 경매에 입찰하면 AUCTION_NOT_LIVE 발생")
        void givenAuctionNotLive_whenCreateBid_thenThrowNotLive(AuctionStatus status) {
            // Given
            Auction auction = createMockAuction(auctionId, sellerId, status);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_LIVE);
        }
    }

    @Nested
    @DisplayName("판매자 입찰 금지")
    class SellerBidProhibition {

        @Test
        @DisplayName("판매자가 자기 경매에 입찰하면 SELLER_CANNOT_BID 발생")
        void givenSellerBid_whenCreateBid_thenThrowSellerCannotBid() {
            // Given
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, sellerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then (sellerId == buyerId)
            assertThatThrownBy(() -> sut.createBid(auctionId, sellerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SELLER_CANNOT_BID);
        }
    }

    @Nested
    @DisplayName("입찰 금액 검증")
    class BidAmountValidation {

        @Test
        @DisplayName("최소 입찰가 미만이면 BID_INVALID_AMOUNT 발생")
        void givenAmountBelowMinimum_whenCreateBid_thenThrowInvalidAmount() {
            // Given: currentPrice=10000, increment=1000 -> minAcceptable=11000
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE,
                    DEFAULT_START_PRICE, DEFAULT_BID_INCREMENT);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then: 10500 < 11000 (minAcceptable)
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 10500, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_INVALID_AMOUNT);
        }

        @Test
        @DisplayName("입찰 단위에 맞지 않으면 BID_INVALID_INCREMENT 발생")
        void givenInvalidIncrement_whenCreateBid_thenThrowInvalidIncrement() {
            // Given: currentPrice=10000, increment=1000
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE,
                    DEFAULT_START_PRICE, DEFAULT_BID_INCREMENT);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then: 11500 - 10000 = 1500, 1500 % 1000 != 0
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 11500, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_INVALID_INCREMENT);
        }

        @Test
        @DisplayName("최대 입찰가 초과면 BID_AMOUNT_EXCEEDS_LIMIT 발생")
        void givenAmountExceedsLimit_whenCreateBid_thenThrowAmountExceedsLimit() {
            // Given
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));

            // When & Then: MAX_BID_AMOUNT = 10,000,000
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15_000_000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_AMOUNT_EXCEEDS_LIMIT);
        }
    }

    @Nested
    @DisplayName("락 획득 실패 처리")
    class LockAcquisitionFailure {

        @Test
        @DisplayName("PessimisticLockException 발생 시 AUCTION_LOCK_BUSY")
        void givenPessimisticLockException_whenCreateBid_thenThrowLockBusy() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenThrow(new PessimisticLockException());

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);
        }

        @Test
        @DisplayName("LockTimeoutException 발생 시 AUCTION_LOCK_BUSY")
        void givenLockTimeoutException_whenCreateBid_thenThrowLockBusy() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenThrow(new LockTimeoutException());

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);
        }

        @Test
        @DisplayName("PessimisticLockingFailureException 발생 시 AUCTION_LOCK_BUSY")
        void givenPessimisticLockingFailureException_whenCreateBid_thenThrowLockBusy() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenThrow(new PessimisticLockingFailureException("Lock failed"));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);
        }

        @Test
        @DisplayName("CannotAcquireLockException 발생 시 AUCTION_LOCK_BUSY")
        void givenCannotAcquireLockException_whenCreateBid_thenThrowLockBusy() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenThrow(new CannotAcquireLockException("Lock failed"));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);
        }

        @Test
        @DisplayName("PostgreSQL 'could not obtain lock' 메시지 예외 시 AUCTION_LOCK_BUSY")
        void givenPostgresLockMessage_whenCreateBid_thenThrowLockBusy() {
            // Given
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenThrow(new RuntimeException("could not obtain lock on row in relation"));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, 15000, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);
        }
    }

    @Nested
    @DisplayName("정상 입찰 처리")
    class SuccessfulBidProcessing {

        @Test
        @DisplayName("유효한 입찰 시 Bid 저장 및 Auction currentPrice/bidCount 갱신")
        void givenValidBid_whenCreateBid_thenSaveBidAndUpdateAuction() {
            // Given
            int amount = 11000; // currentPrice(10000) + increment(1000)
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            User bidder = createMockUser(buyerId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.empty());
            when(userRepository.getReferenceById(buyerId))
                    .thenReturn(bidder);

            // When
            BidCreateResponse response = sut.createBid(auctionId, buyerId, amount, clientRequestId);

            // Then
            assertThat(response).isNotNull();
            verify(bidRepository).saveAndFlush(any(Bid.class));
            verify(auction).updateBid(amount);
        }

        @Test
        @DisplayName("입찰 성공 시 bidAccepted 알림 발행")
        void givenValidBid_whenCreateBid_thenPublishBidAcceptedNotification() {
            // Given
            int amount = 11000;
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            User bidder = createMockUser(buyerId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.empty());
            when(userRepository.getReferenceById(buyerId))
                    .thenReturn(bidder);

            // When
            sut.createBid(auctionId, buyerId, amount, clientRequestId);

            // Then
            verify(notificationPublisher, atLeastOnce()).publish(any(NotificationPublishCommand.class));
        }

        @Test
        @DisplayName("이전 최고 입찰자가 있으면 outbid 알림 발행")
        void givenPreviousHighestBidder_whenCreateBid_thenPublishOutbidNotification() {
            // Given
            UUID prevBidderId = generateUserId();
            int amount = 11000;
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            User bidder = createMockUser(buyerId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.of(prevBidderId));
            when(userRepository.getReferenceById(buyerId))
                    .thenReturn(bidder);

            // When
            sut.createBid(auctionId, buyerId, amount, clientRequestId);

            // Then: bidAccepted + outbid = 2회 호출
            verify(notificationPublisher, times(2)).publish(any(NotificationPublishCommand.class));
        }
    }

    @Nested
    @DisplayName("멱등성 처리 (BidCommandService 레벨)")
    class CommandServiceIdempotency {

        @Test
        @DisplayName("동일 요청이 이미 처리되었으면 기존 결과 반환")
        void givenExistingBid_whenCreateBid_thenReturnExistingWithoutProcessing() {
            // Given
            int amount = 11000;
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            Bid existingBid = createMockBid(auction, createMockUser(buyerId), amount, clientRequestId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.of(existingBid));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.of(buyerId));

            // When
            BidCreateResponse response = sut.createBid(auctionId, buyerId, amount, clientRequestId);

            // Then
            assertThat(response.acceptedAmount()).isEqualTo(amount);
            verify(auctionRepository, never()).findByIdForUpdate(any());
            verify(bidRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("동일 요청인데 금액이 다르면 CONFLICT")
        void givenExistingBidWithDifferentAmount_whenCreateBid_thenThrowConflict() {
            // Given
            int originalAmount = 11000;
            int newAmount = 12000;
            Auction auction = createMockAuction(auctionId, sellerId, AuctionStatus.LIVE);
            Bid existingBid = createMockBid(auction, createMockUser(buyerId), originalAmount, clientRequestId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.of(existingBid));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, buyerId, newAmount, clientRequestId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_IDEMPOTENCY_CONFLICT);
        }
    }

    @Nested
    @DisplayName("자동 연장")
    class AutoExtension {

        @Test
        @DisplayName("종료 5분 이하 남은 상태에서 입찰 시 연장되고 이벤트 발행")
        void givenAuctionAboutToEnd_whenCreateBid_thenExtendAndPublishEvent() {
            // Given
            int amount = 11000;
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime endAt = now.plusMinutes(3); // 3분 남음 (< 5분)

            Auction auction = mock(Auction.class);
            User seller = createMockUser(sellerId);
            User bidder = createMockUser(buyerId);

            when(auction.getAuctionId()).thenReturn(auctionId);
            when(auction.getStatus()).thenReturn(AuctionStatus.LIVE);
            when(auction.getSeller()).thenReturn(seller);
            when(auction.getCurrentPrice()).thenReturn(DEFAULT_START_PRICE);
            when(auction.getBidIncrement()).thenReturn(DEFAULT_BID_INCREMENT);
            when(auction.getEndAt()).thenReturn(endAt);
            when(auction.getOriginalEndAt()).thenReturn(now.minusHours(1).plusHours(DEFAULT_DURATION_HOURS));

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(auctionRepository.findByIdForUpdate(auctionId))
                    .thenReturn(Optional.of(auction));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.empty());
            when(userRepository.getReferenceById(buyerId))
                    .thenReturn(bidder);

            // When
            BidCreateResponse response = sut.createBid(auctionId, buyerId, amount, clientRequestId);

            // Then
            assertThat(response.extensionApplied()).isTrue();
            verify(auction).extendEndTime(any(OffsetDateTime.class), anyInt());

            ArgumentCaptor<AuctionEndAtChangedEvent> eventCaptor =
                    ArgumentCaptor.forClass(AuctionEndAtChangedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().auctionId()).isEqualTo(auctionId);
        }
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }

    }
}
