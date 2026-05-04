package kr.eolmago.domain.entity.notification;

import kr.eolmago.domain.entity.common.AuditableEntity;
import kr.eolmago.domain.entity.notification.enums.NotificationType;
import kr.eolmago.domain.entity.notification.enums.RelatedEntityType;
import kr.eolmago.domain.entity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notifications_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_notifications_user_is_read", columnList = "user_id,is_read")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long notificationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(columnDefinition = "text")
    private String linkUrl;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private RelatedEntityType relatedEntityType;

    // BIGINT, UUID 대응하기 위해 text 타입
    @Column(columnDefinition = "text")
    private String relatedEntityId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    private OffsetDateTime readAt;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted;

    private OffsetDateTime deletedAt;

    public void markRead(OffsetDateTime now) {
        if (this.read) return;
        this.read = true;
        this.readAt = now;
    }

    public void softDelete(OffsetDateTime now) {
        if (this.deleted) return;
        this.deleted = true;
        this.deletedAt = now;
    }

    public static Notification create(
            User user,
            NotificationType type,
            String title,
            String body,
            String linkUrl,
            RelatedEntityType relatedEntityType,
            String relatedEntityId
    ) {
        Notification notification = new Notification();
        notification.user = user;
        notification.type = type;
        notification.title = title;
        notification.body = body;
        notification.linkUrl = linkUrl;
        notification.relatedEntityType = relatedEntityType;
        notification.relatedEntityId = relatedEntityId;
        notification.read = false; // 초기 생성시 읽었는지 여부 false
        return notification;
    }
}