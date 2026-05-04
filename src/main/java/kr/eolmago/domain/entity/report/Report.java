package kr.eolmago.domain.entity.report;

import jakarta.persistence.*;
import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.common.AuditableEntity;
import kr.eolmago.domain.entity.report.enums.ReportAction;
import kr.eolmago.domain.entity.report.enums.ReportReason;
import kr.eolmago.domain.entity.report.enums.ReportStatus;
import kr.eolmago.domain.entity.report.enums.ReportTargetType;
import kr.eolmago.domain.entity.user.User;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "reports",
        indexes = {
                @Index(name = "idx_reports_status_created", columnList = "status,created_at"),
                @Index(name = "idx_reports_reporter_created", columnList = "reporter_id,created_at")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long reportId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reported_user_id", nullable = false)
    private User reportedUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportTargetType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReportReason reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    /**
     * 처리 전: null
     * 처리 후:
     *  - 무혐의/무조치: ReportAction.NONE
     *  - 제재: WARN/SUSPEND_7D/SUSPEND_30D/BAN
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ReportAction action;

    @Column(columnDefinition = "TEXT")
    private String actionMemo;

    @Column
    private OffsetDateTime resolvedAt;

    public static Report create(
            User reporter,
            User reportedUser,
            Auction auction,
            ReportTargetType type,
            ReportReason reason,
            String description
    ) {
        Report report = new Report();
        report.reporter = reporter;
        report.reportedUser = reportedUser;
        report.auction = auction;
        report.type = type;
        report.reason = reason;
        report.description = description;
        report.status = ReportStatus.PENDING;

        return report;
    }

    public void updateStatus(ReportStatus newStatus) {
        this.status = newStatus;
    }

    public void updateAction(ReportAction action) {
        this.action = action;
    }

    public void UpdateActionMemo(String newActionMemo) {
        this.actionMemo = newActionMemo;
    }

    public void updateResolvedAt(OffsetDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}