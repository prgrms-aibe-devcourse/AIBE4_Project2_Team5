package kr.eolmago.controller.api.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

import kr.eolmago.dto.api.common.PageResponse;
import kr.eolmago.dto.api.notification.response.NotificationResponse;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.notification.NotificationService;
import kr.eolmago.service.notification.NotificationValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "Notification", description = "알림 조회/읽음/삭제 및 SSE 실시간 푸시 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationService notificationService;
	private final NotificationValidator notificationValidator;

	@Operation(summary = "내 알림 목록 조회(페이징)")
	@SecurityRequirement(name = "bearerAuth")
	@GetMapping
	public PageResponse<NotificationResponse> list(
		@AuthenticationPrincipal CustomUserDetails me,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		UUID userId = notificationValidator.validateAndGetUserId(me);

		Page<NotificationResponse> result = notificationService.list(userId, page, size);
		return PageResponse.of1Based(result);
	}

	@Operation(summary = "안 읽은 알림 개수 조회")
	@SecurityRequirement(name = "bearerAuth")
	@GetMapping("/unread-count")
	public long unreadCount(@AuthenticationPrincipal CustomUserDetails me) {
		UUID userId = notificationValidator.validateAndGetUserId(me);
		return notificationService.unreadCount(userId);
	}

	@Operation(summary = "알림 개별 읽음 처리")
	@SecurityRequirement(name = "bearerAuth")
	@PatchMapping("/{notificationId}/read")
	public void readOne(
		@AuthenticationPrincipal CustomUserDetails me,
		@PathVariable Long notificationId
	) {
		UUID userId = notificationValidator.validateAndGetUserId(me);
		notificationService.readOne(userId, notificationId);
	}

	@Operation(summary = "알림 전체 읽음 처리")
	@SecurityRequirement(name = "bearerAuth")
	@PatchMapping("/read-all")
	public int readAll(@AuthenticationPrincipal CustomUserDetails me) {
		UUID userId = notificationValidator.validateAndGetUserId(me);
		return notificationService.readAll(userId);
	}

	@Operation(summary = "알림 삭제(soft delete)")
	@SecurityRequirement(name = "bearerAuth")
	@DeleteMapping("/{notificationId}")
	public void delete(
		@AuthenticationPrincipal CustomUserDetails me,
		@PathVariable Long notificationId
	) {
		UUID userId = notificationValidator.validateAndGetUserId(me);
		notificationService.delete(userId, notificationId);
	}

	@Operation(summary = "알림 SSE 스트림 연결")
	@SecurityRequirement(name = "bearerAuth")
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@AuthenticationPrincipal CustomUserDetails me) {
		UUID userId = notificationValidator.validateAndGetUserId(me);
		return notificationService.connectStream(userId);
	}
}
