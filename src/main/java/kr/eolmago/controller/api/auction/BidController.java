package kr.eolmago.controller.api.auction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.dto.api.auction.response.BidHistoryResponse;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.auction.BidListService;
import kr.eolmago.service.auction.BidService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/auctions")
@Tag(name = "Bid", description = "입찰")
@RequiredArgsConstructor
public class BidController {

    private final BidService bidService;
    private final BidListService bidListService;

    @Operation(summary = "입찰 생성")
    @PostMapping("/{auctionId}/bids")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<BidCreateResponse> createBid (
            @PathVariable UUID auctionId,
            @Valid @RequestBody BidCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID buyerId = UUID.fromString(principal.getId());
        BidCreateResponse response = bidService.createBid(auctionId, request, buyerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "입찰자 목록 조회")
    @GetMapping("/{auctionId}/bids")
    @PreAuthorize("hasAnyRole('GUEST', 'USER', 'ADMIN')")
    public ResponseEntity<BidHistoryResponse> getBidHistory(
            @PathVariable UUID auctionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        BidHistoryResponse response = bidListService.getBidHistory(auctionId, principal, page, size);
        return ResponseEntity.ok(response);
    }
}
