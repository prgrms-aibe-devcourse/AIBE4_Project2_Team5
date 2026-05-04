package kr.eolmago.service.auction.monitoring;

import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

// 락 예외
@Component
public class AuctionLockFailureDetector {

    public boolean isLockFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof PessimisticLockException ||
                current instanceof LockTimeoutException ||
                current instanceof PessimisticLockingFailureException ||
                current instanceof CannotAcquireLockException ||
                current instanceof LockAcquisitionException) {
                return true;
            }

            String message = current.getMessage();
            if (message != null && (
                message.contains("could not obtain lock") ||
                message.contains("NOWAIT") ||
                message.contains("lock_timeout") ||
                message.contains("canceling statement due to lock timeout")
            )) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
