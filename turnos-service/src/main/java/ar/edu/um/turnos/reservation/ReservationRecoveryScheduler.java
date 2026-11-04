package ar.edu.um.turnos.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ejecuta la recuperacion al arrancar (initial delay) y luego periodicamente. */
@Component
@ConditionalOnProperty(name = "app.reservations.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationRecoveryScheduler {

    private final ReservationRecovery recovery;

    public ReservationRecoveryScheduler(ReservationRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(initialDelayString = "${app.reservations.recovery-initial-delay}",
        fixedDelayString = "${app.reservations.recovery-interval}")
    public void run() {
        recovery.recoverPending();
    }
}
