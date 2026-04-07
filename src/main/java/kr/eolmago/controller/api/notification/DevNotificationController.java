package kr.eolmago.controller.api.notification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;

import kr.eolmago.dto.api.notification.request.DevNotificationRequest;
import kr.eolmago.global.security.CustomUserDetails;
import kr.eolmago.service.notification.publish.NotificationPublishCommand;
import kr.eolmago.service.notification.publish.NotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Tag(name = "Dev Notification", description = "개발/테스트용 알림 발행 API (local/dev 프로필 전용)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
@Profile({"local", "dev"})
@RequestMapping("/api/dev/notifications")
public class DevNotificationController {

	private final NotificationPublisher notificationPublisher;

	@Operation(
		summary = "웰컴 알림 강제 발행",
		description = "로그인한 사용자에게 WELCOME 알림을 강제로 발행한다. (알림 저장 + SSE + 알림채팅 적재 확인용)"
	)
	@PostMapping("/welcome")
	public Long publishWelcome(@AuthenticationPrincipal CustomUserDetails me) {
		UUID userId = requireUserId(me);
		return notificationPublisher.publish(NotificationPublishCommand.welcome(userId));
	}

	@Operation(
		summary = "커스텀 알림 강제 발행",
		description = "로그인한 사용자에게 커스텀 알림을 강제로 발행한다."
	)
	@PostMapping("/custom")
	public Long publishCustom(
		@AuthenticationPrincipal CustomUserDetails me,
		@RequestBody DevNotificationRequest req
	) {
		UUID userId = requireUserId(me);

		NotificationPublishCommand cmd = new NotificationPublishCommand(
			userId,
			req.type(),
			req.title(),
			req.body(),
			req.linkUrl(),
			req.relatedEntityType(),
			req.relatedEntityId()
		);

		return notificationPublisher.publish(cmd);
	}

	private UUID requireUserId(CustomUserDetails me) {
		if (me == null || me.getId() == null || me.getId().isBlank()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
		}
		try {
			return UUID.fromString(me.getId());
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.");
		}
	}
}
