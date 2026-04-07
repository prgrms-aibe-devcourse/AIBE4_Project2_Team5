package kr.eolmago.controller.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.eolmago.domain.entity.report.enums.ReportAction;
import kr.eolmago.domain.entity.report.enums.ReportStatus;
import kr.eolmago.domain.entity.user.enums.PenaltyType;
import kr.eolmago.domain.entity.user.enums.UserStatus;
import kr.eolmago.dto.api.admin.response.PenaltyHistoryResponse;
import kr.eolmago.dto.api.admin.response.ReportAdminResponse;
import kr.eolmago.dto.api.admin.response.UserAdminResponse;
import kr.eolmago.dto.api.common.PageResponse;
import kr.eolmago.service.admin.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "관리자 전용 API")
public class AdminController {

    private final AdminService adminService;

    @Operation(summary = "사용자 목록 조회 (필터링 + 페이지네이션)")
    @GetMapping("/users")
    public ResponseEntity<PageResponse<UserAdminResponse>> getUsers(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        PageResponse<UserAdminResponse> response = adminService.getUsers(name, email, status, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "신고 목록 조회 (필터링 + 페이지네이션)")
    @GetMapping("/reports")
    public ResponseEntity<PageResponse<ReportAdminResponse>> getReports(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        PageResponse<ReportAdminResponse> response = adminService.getReports(status, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "사용자 상태 변경")
    @PatchMapping("/users/{userId}/status")
    public ResponseEntity<Void> updateUserStatus(
            @PathVariable UUID userId,
            @RequestParam UserStatus status,
            @RequestParam(required = false) String reason
    ) {
        adminService.updateUserStatus(userId, status, reason);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "전체 제재 이력 조회 (필터링 + 페이지네이션)")
    @GetMapping("/penalties")
    public ResponseEntity<PageResponse<PenaltyHistoryResponse>> getAllPenalties(
            @RequestParam(required = false) PenaltyType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("startedAt").descending());
        PageResponse<PenaltyHistoryResponse> response = adminService.getAllPenalties(type, pageable);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "특정 사용자의 제재 이력 조회")
    @GetMapping("/users/{userId}/penalties")
    public ResponseEntity<List<PenaltyHistoryResponse>> getPenaltyHistory(
            @PathVariable UUID userId
    ) {
        List<PenaltyHistoryResponse> response = adminService.getPenaltyHistory(userId);
        return ResponseEntity.ok(response);
    }

    // 신고 상세 조회
    @Operation(summary = "신고 상세 조회")
    @GetMapping("/reports/{reportId}")
    public ResponseEntity<ReportAdminResponse> getReportDetail(
            @PathVariable Long reportId
    ) {
        ReportAdminResponse response = adminService.getReportDetail(reportId);
        return ResponseEntity.ok(response);
    }

    // 신고 처리 (상태 변경 + 제재 조치)
    @Operation(summary = "신고 처리")
    @PatchMapping("/reports/{reportId}/resolve")
    public ResponseEntity<Void> resolveReport(
            @PathVariable Long reportId,
            @RequestParam ReportAction action,
            @RequestParam(required = false) String adminNote
    ) {
        adminService.resolveReport(reportId, action, adminNote);
        return ResponseEntity.ok().build();
    }
}
