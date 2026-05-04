package kr.eolmago.service.auction.event;

import kr.eolmago.service.auction.AuctionCloseScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AuctionCloseEventListener {

    private final AuctionCloseScheduler auctionCloseScheduler;

     // 경매 종료 시각 변경 이벤트는 DB 커밋이 끝난 뒤에만 스케줄러에 반영
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAuctionEndAtChanged(AuctionEndAtChangedEvent event) {
        if (event == null || event.auctionId() == null || event.endAt() == null) {
            return;
        }
        auctionCloseScheduler.scheduleOrReschedule(event.auctionId(), event.endAt());
    }
}
