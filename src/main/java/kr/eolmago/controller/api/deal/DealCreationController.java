package kr.eolmago.controller.api.deal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.eolmago.dto.api.deal.request.CreateDealFromAuctionRequest;
import kr.eolmago.dto.api.deal.response.DealCreationResponse;
import kr.eolmago.service.deal.DealCreationService;
import kr.eolmago.service.deal.DealPdfService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Deal 생성 API 컨트롤러
 */
@Tag(name = "Deal Creation", description = "거래 생성 API")
@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealCreationController {

    private final DealCreationService dealCreationService;
    private final DealPdfService pdfService;

    @Operation(summary = "경매 종료 후 거래 생성")
    @PostMapping("/from-auction")
    public ResponseEntity<DealCreationResponse> createDealFromAuction(
            @Valid @RequestBody CreateDealFromAuctionRequest request
    ) {
        DealCreationResponse response = dealCreationService.createDealFromAuction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "특정 경매의 거래 생성")
    @PostMapping("/auctions/{auctionId}")
    public ResponseEntity<DealCreationResponse> createDealFromAuctionPath(
            @PathVariable UUID auctionId,
            @Valid @RequestBody CreateDealFromAuctionRequest request
    ) {
        DealCreationResponse response = dealCreationService.createDealFromAuctionPath(auctionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "경매의 거래 생성 여부 확인")
    @GetMapping("/check/auction/{auctionId}")
    public ResponseEntity<Boolean> checkDealExists(
            @PathVariable UUID auctionId
    ) {
        boolean exists = dealCreationService.isDealExistsForAuction(auctionId);
        return ResponseEntity.ok(exists);
    }
}
