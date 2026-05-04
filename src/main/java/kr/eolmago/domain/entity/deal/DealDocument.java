package kr.eolmago.domain.entity.deal;

import kr.eolmago.domain.entity.common.CreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "deal_documents",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_deal_documents_deal", columnNames = {"deal_id"})
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DealDocument extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long dealDocumentId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deal_id", nullable = false)
    private Deal deal;

    @Column(nullable = false, columnDefinition = "text")
    private String fileUrl;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private Long fileSizeBytes;

    public static DealDocument create(
            Deal deal,
            String fileUrl,
            String fileName,
            long fileSizeBytes
    ) {
        DealDocument doc = new DealDocument();
        doc.deal = deal;
        doc.fileUrl = fileUrl;
        doc.fileName = fileName;
        doc.fileSizeBytes = fileSizeBytes;
        return doc;
    }
}