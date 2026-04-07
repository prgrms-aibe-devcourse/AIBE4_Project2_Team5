package kr.eolmago.controller.api.auction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.eolmago.dto.api.auction.response.AuctionRepublishResponse;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.auction.AuctionCloseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/auctions")
@Tag(name = "AuctionClose", description = "경매 마감")

@RequiredArgsConstructor
public class AuctionCloseController {

    private final AuctionCloseService auctionCloseService;

    @Operation(summary = "경매 마감 확정")
    @PostMapping("/{auctionId}/close")
    public ResponseEntity<Void> closeAuction (
            @PathVariable UUID auctionId
    ) {
        auctionCloseService.closeAuction(auctionId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "유찰 경매 재등록")
    @PostMapping("/{auctionId}/republish")
    public ResponseEntity<AuctionRepublishResponse> republishUnsoldAuction (
            @PathVariable UUID auctionId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        AuctionRepublishResponse response = auctionCloseService.republishUnsoldAuction(auctionId, sellerId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "판매자 경매 취소")
    @PostMapping("/{auctionId}/stop")
    public ResponseEntity<Void> cancelAuctionBySeller (
            @PathVariable UUID auctionId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        UUID sellerId = UUID.fromString(principal.getId());
        auctionCloseService.cancelAuctionBySeller(auctionId, sellerId);
        return ResponseEntity.ok().build();
    }
}
