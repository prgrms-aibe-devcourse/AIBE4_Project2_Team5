package kr.eolmago.controller.api.auction;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.eolmago.config.TestSecurityConfig;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.global.exception.GlobalExceptionHandler;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.auction.BidListService;
import kr.eolmago.service.auction.BidService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// HTTP 요청/응답, 인증, 유효성 검사 검증
@WebMvcTest(BidController.class)
@Import({TestSecurityConfig.class, GlobalExceptionHandler.class})
@DisplayName("BidApiController API 테스트")
class BidControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BidService bidService;

    @MockitoBean
    private BidListService bidListService;

    private final UUID auctionId = UUID.randomUUID();
    private final UUID buyerId = UUID.randomUUID();

    @Nested
    @DisplayName("입찰 생성 (POST /api/auctions/{auctionId}/bids)")
    class CreateBidTests {

        @Test
        @DisplayName("정상 입찰 시 201 CREATED 응답")
        void givenValidRequest_whenCreateBid_thenReturn201() throws Exception {
            // Given
            int amount = 15000;
            String clientRequestId = "req-" + UUID.randomUUID();
            BidCreateRequest request = new BidCreateRequest(amount, clientRequestId);

            BidCreateResponse response = new BidCreateResponse(
                    1L, auctionId, amount, amount, amount + 1000,
                    OffsetDateTime.now().plusHours(1), false, buyerId
            );

            when(bidService.createBid(eq(auctionId), any(BidCreateRequest.class), any(UUID.class)))
                    .thenReturn(response);

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bidId").value(1))
                    .andExpect(jsonPath("$.auctionId").value(auctionId.toString()))
                    .andExpect(jsonPath("$.acceptedAmount").value(amount));
        }

        @Test
        @DisplayName("clientRequestId 누락 시 400 BAD_REQUEST")
        void givenMissingClientRequestId_whenCreateBid_thenReturn400() throws Exception {
            // Given: clientRequestId가 null인 요청 (직접 JSON 작성)
            String invalidJson = """
                    {
                        "amount": 15000
                    }
                    """;

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("clientRequestId 길이 부족 시 400 BAD_REQUEST")
        void givenShortClientRequestId_whenCreateBid_thenReturn400() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "short");

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("인증 없이 요청 시 401/403 응답")
        void givenNoAuthentication_whenCreateBid_thenReturnUnauthorized() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().is4xxClientError()); // 401 또는 403
        }

        @Test
        @DisplayName("AUCTION_NOT_LIVE 예외 시 400 BAD_REQUEST")
        void givenAuctionNotLive_whenCreateBid_thenReturn400() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());

            when(bidService.createBid(any(), any(), any()))
                    .thenThrow(new BusinessException(ErrorCode.AUCTION_NOT_LIVE));

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("A004"));
        }

        @Test
        @DisplayName("AUCTION_LOCK_BUSY 예외 시 409 CONFLICT")
        void givenLockBusy_whenCreateBid_thenReturn409() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());

            when(bidService.createBid(any(), any(), any()))
                    .thenThrow(new BusinessException(ErrorCode.AUCTION_LOCK_BUSY));

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("B010"));
        }

        @Test
        @DisplayName("BID_IDEMPOTENCY_CONFLICT 예외 시 409 CONFLICT")
        void givenIdempotencyConflict_whenCreateBid_thenReturn409() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());

            when(bidService.createBid(any(), any(), any()))
                    .thenThrow(new BusinessException(ErrorCode.BID_IDEMPOTENCY_CONFLICT));

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("B008"));
        }
    }

    @Nested
    @DisplayName("권한 검증")
    class AuthorizationTests {

        @Test
        @WithMockUser(roles = "GUEST")
        @DisplayName("GUEST 권한으로 입찰 시도 시 403 FORBIDDEN")
        void givenGuestRole_whenCreateBid_thenReturn403() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("USER 권한으로 입찰 가능")
        void givenUserRole_whenCreateBid_thenAllowed() throws Exception {
            // Given
            BidCreateRequest request = new BidCreateRequest(15000, "req-" + UUID.randomUUID());
            BidCreateResponse response = new BidCreateResponse(
                    1L, auctionId, 15000, 15000, 16000,
                    OffsetDateTime.now().plusHours(1), false, buyerId
            );

            when(bidService.createBid(any(), any(), any())).thenReturn(response);

            // When & Then
            mockMvc.perform(post("/api/auctions/{auctionId}/bids", auctionId)
                            .with(user(createMockPrincipal(buyerId)))
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    private CustomUserDetails createMockPrincipal(UUID userId) {
        CustomUserDetails principal = mock(CustomUserDetails.class);
        when(principal.getId()).thenReturn(userId.toString());
        when(principal.getAuthorities()).thenReturn(java.util.Collections.emptyList());
        return principal;
    }
}
