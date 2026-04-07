package kr.eolmago.controller.api.auction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.eolmago.dto.api.auction.request.AuctionDraftRequest;
import kr.eolmago.dto.api.auction.request.AuctionSearchRequest;
import kr.eolmago.dto.api.auction.response.*;
import kr.eolmago.dto.api.common.PageResponse;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.auction.AuctionSearchService;
import kr.eolmago.service.auction.AuctionService;
import kr.eolmago.service.auction.SellerCredibilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auctions")
@Tag(name = "Auction", description = "경매")
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctionService;
    private final AuctionSearchService auctionSearchService;

    private final SellerCredibilityService sellerCredibilityService;

    @Operation(summary = "경매 임시저장 생성")
    @PostMapping("/drafts")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<AuctionDraftResponse> createDraft (
            @Valid @RequestBody AuctionDraftRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionDraftResponse response = auctionService.createDraft(request, sellerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "경매 임시저장 수정")
    @PutMapping("/drafts/{auctionId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<AuctionDraftResponse> updateDraft (
            @PathVariable UUID auctionId,
            @Valid @RequestBody AuctionDraftRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionDraftResponse response = auctionService.updateDraft(auctionId, request, sellerId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 임시저장 초기화")
    @PostMapping("/drafts/init")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<AuctionDraftResponse> initDraft (
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionDraftResponse response = auctionService.initDraft(sellerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "경매 삭제")
    @DeleteMapping("/{auctionId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<Void> deleteAuction (
            @PathVariable UUID auctionId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        auctionService.deleteAuction(auctionId, sellerId);
        return ResponseEntity.noContent().build();
    }
    
    @Operation(summary = "경매 게시")
    @PostMapping("/{auctionId}/publish")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<AuctionDraftResponse> publish (
            @PathVariable UUID auctionId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionDraftResponse response = auctionService.publishAuction(auctionId, sellerId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 목록 조회")
    @GetMapping("/list")
    public ResponseEntity<PageResponse<AuctionListResponse>> getAuctionList (
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "latest") String sortKey,
            AuctionSearchRequest searchRequest
    ) {
        PageResponse<AuctionListResponse> response = auctionService.getAuction(page, size, sortKey, searchRequest);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 목록 조회 - 필터 적용")
    @GetMapping
    public ResponseEntity<PageResponse<AuctionListResponse>> getAuctions (
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "latest") String sort,
            AuctionSearchRequest searchRequest
    ) {
        Pageable pageable = PageRequest.of(page, size);
        PageResponse<AuctionListResponse> response = auctionSearchService.search(searchRequest, sort, pageable);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 임시저장 단건 조회")
    @GetMapping("/drafts/{auctionId}")
    public ResponseEntity<AuctionDraftDetailResponse> getDraft (
            @PathVariable UUID auctionId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionDraftDetailResponse response = auctionService.getDraft(auctionId, sellerId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "경매 상세 조회")
    @GetMapping("/{auctionId}")
    public ResponseEntity<AuctionDetailResponse> getAuctionDetail (
            @PathVariable UUID auctionId
    ) {
        AuctionDetailResponse response = auctionService.getAuctionDetail(auctionId);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "판매자 신용도 조회")
    @GetMapping("/{auctionId}/seller-credibility")
    public ResponseEntity<SellerCredibilityResponse> getSellerCredibility(
            @PathVariable UUID auctionId
    ) {
        SellerCredibilityResponse response = sellerCredibilityService.getSellerCredibility(auctionId);
        return ResponseEntity.ok(response);
    }
}