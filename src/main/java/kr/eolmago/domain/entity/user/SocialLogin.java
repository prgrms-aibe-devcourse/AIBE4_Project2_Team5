package kr.eolmago.domain.entity.user;

import kr.eolmago.domain.entity.common.CreatedAtEntity;
import kr.eolmago.domain.entity.user.enums.SocialProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "social_login",
        indexes = {
                @Index(name = "idx_social_login_provider_pid", columnList = "provider,provider_id", unique = true)
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialLogin extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long socialId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SocialProvider provider;

    @Column(nullable = false, length = 100)
    private String providerId;

    @Column(nullable = false)
    private String email;

    public static SocialLogin create(
            User user,
            SocialProvider provider,
            String providerId,
            String email
    ) {
        SocialLogin login = new SocialLogin();
        login.user = user;
        login.provider = provider;
        login.providerId = providerId;
        login.email = email;
        return login;
    }
}