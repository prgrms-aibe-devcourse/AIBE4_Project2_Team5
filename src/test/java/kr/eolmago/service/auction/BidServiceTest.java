package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.service.auction.monitoring.AuctionBidAuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.service.auction.BidTestFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// 멱등성 처리와 BidCommandService 위임 호출 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("BidService 단위 테스트")
class BidServiceTest {

    @Mock
    private BidRepository bidRepository;

    @Mock
    private BidCommandService bidCommandService;

    private BidService sut;

    private final UUID auctionId = generateAuctionId();
    private final UUID buyerId = generateUserId();

    @BeforeEach
    void setUp() {
        sut = new BidService(
                bidRepository,
                bidCommandService,
                new kr.eolmago.service.auction.monitoring.BidMetricsRecorder(new SimpleMeterRegistry()),
                new AuctionBidAuditLogger()
        );
    }

    @Nested
    @DisplayName("clientRequestId 검증")
    class ClientRequestIdValidation {

        @Test
        @DisplayName("clientRequestId가 null이면 BID_IDEMPOTENCY_REQUIRED 발생")
        void givenNullClientRequestId_whenCreateBid_thenThrowIdempotencyRequired() {
            // Given
            BidCreateRequest request = new BidCreateRequest(10000, null);

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, request, buyerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_IDEMPOTENCY_REQUIRED);

            verifyNoInteractions(bidCommandService);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("clientRequestId가 blank면 BID_IDEMPOTENCY_REQUIRED 발생")
        void givenBlankClientRequestId_whenCreateBid_thenThrowIdempotencyRequired(String clientRequestId) {
            // Given
            BidCreateRequest request = new BidCreateRequest(10000, clientRequestId);

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, request, buyerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_IDEMPOTENCY_REQUIRED);
        }
    }

    @Nested
    @DisplayName("멱등성 처리")
    class IdempotencyHandling {

        @Test
        @DisplayName("동일 clientRequestId + amount 재요청 시 기존 결과 반환, BidCommandService 호출 없음")
        void givenSameRequestIdAndAmount_whenCreateBid_thenReturnExistingBidWithoutCallingCommand() {
            // Given
            String clientRequestId = generateClientRequestId();
            int amount = 15000;
            BidCreateRequest request = createBidRequest(amount, clientRequestId);

            Auction auction = createMockAuction(auctionId, generateUserId(), AuctionStatus.LIVE);
            Bid existingBid = createMockBid(auction, createMockUser(buyerId), amount, clientRequestId);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.of(existingBid));
            when(bidRepository.findTopBidderIdByAuction(auction))
                    .thenReturn(Optional.of(buyerId));

            // When
            BidCreateResponse response = sut.createBid(auctionId, request, buyerId);

            // Then
            assertThat(response.bidId()).isEqualTo(existingBid.getBidId());
            assertThat(response.acceptedAmount()).isEqualTo(amount);

            // BidCommandService는 호출되지 않아야 함 (멱등성으로 인해 기존 결과 반환)
            verify(bidCommandService, never()).createBid(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("동일 clientRequestId + 다른 amount 재요청 시 BID_IDEMPOTENCY_CONFLICT 발생")
        void givenSameRequestIdButDifferentAmount_whenCreateBid_thenThrowIdempotencyConflict() {
            // Given
            String clientRequestId = generateClientRequestId();
            int originalAmount = 15000;
            int differentAmount = 20000;
            BidCreateRequest request = createBidRequest(differentAmount, clientRequestId);

            Auction auction = createMockAuction(auctionId, generateUserId(), AuctionStatus.LIVE);
            Bid existingBid = createMockBid(auction, createMockUser(buyerId), originalAmount, clientRequestId);
            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.of(existingBid));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, request, buyerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BID_IDEMPOTENCY_CONFLICT);

            verify(bidCommandService, never()).createBid(any(), any(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("신규 입찰 처리")
    class NewBidHandling {

        @Test
        @DisplayName("신규 요청이면 BidCommandService.createBid() 위임 호출")
        void givenNewRequest_whenCreateBid_thenDelegateToBidCommandService() {
            // Given
            String clientRequestId = generateClientRequestId();
            int amount = 15000;
            BidCreateRequest request = createBidRequest(amount, clientRequestId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());

            BidCreateResponse expectedResponse = createBidResponse(
                    1L, auctionId, amount, amount, amount + DEFAULT_BID_INCREMENT, false, buyerId
            );
            when(bidCommandService.createBid(auctionId, buyerId, amount, clientRequestId))
                    .thenReturn(expectedResponse);

            // When
            BidCreateResponse response = sut.createBid(auctionId, request, buyerId);

            // Then
            assertThat(response).isEqualTo(expectedResponse);
            verify(bidCommandService).createBid(auctionId, buyerId, amount, clientRequestId);
        }

        @Test
        @DisplayName("BidCommandService에서 예외 발생 시 그대로 전파")
        void givenCommandServiceThrows_whenCreateBid_thenPropagateException() {
            // Given
            String clientRequestId = generateClientRequestId();
            int amount = 15000;
            BidCreateRequest request = createBidRequest(amount, clientRequestId);

            when(bidRepository.findByClientRequestIdAndBidderId(clientRequestId, buyerId))
                    .thenReturn(Optional.empty());
            when(bidCommandService.createBid(auctionId, buyerId, amount, clientRequestId))
                    .thenThrow(new BusinessException(ErrorCode.AUCTION_NOT_LIVE));

            // When & Then
            assertThatThrownBy(() -> sut.createBid(auctionId, request, buyerId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUCTION_NOT_LIVE);
        }
    }
}
