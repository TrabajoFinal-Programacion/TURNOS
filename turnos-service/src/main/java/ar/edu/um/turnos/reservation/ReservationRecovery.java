package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.config.ReservationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recuperacion de procesos pendientes o interrumpidos (al arrancar y luego periodicamente).
 * Toma los procesos abiertos sin cambios durante staleAfter y:
 * - CREATED: nunca se supo si el hold se creo y no se puede consultar -> FAILED (HOLD_UNKNOWN).
 * - HELD: reintenta la confirmacion inicial mientras el hold este vigente.
 * - Resto: reconcilia con GET /api/appointments (una consulta por usuario).
 * No llama al catalogo (no hay JWT de usuario en segundo plano; ver ADR 0001).
 * Termina siempre: cada proceso pendiente vence a lo sumo en expiresAt + expirationGrace.
 */
@Component
public class ReservationRecovery {

    private static final Logger log = LoggerFactory.getLogger(ReservationRecovery.class);

    private final ReservationProcessRepository repository;
    private final ReservationStore store;
    private final ReservationService service;
    private final ReservationReconciler reconciler;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationRecovery(ReservationProcessRepository repository, ReservationStore store,
                               ReservationService service, ReservationReconciler reconciler,
                               ReservationProperties properties, Clock clock) {
        this.repository = repository;
        this.store = store;
        this.service = service;
        this.reconciler = reconciler;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return cantidad de procesos revisados. Publico para poder probarlo y ejecutarlo a demanda. */
    public int recoverPending() {
        Instant now = clock.instant();
        List<ReservationProcess> stale = repository.findByStatusInAndUpdatedAtBefore(ReservationStatus.PENDING,
            now.minus(properties.staleAfter()));
        if (stale.isEmpty()) {
            return 0;
        }
        log.info("Recuperacion: {} proceso(s) pendientes para revisar", stale.size());
        for (ReservationProcess p : stale) {
            try {
                if (p.getStatus() == ReservationStatus.CREATED) {
                    store.update(p.getId(), x -> x.failed("HOLD_UNKNOWN",
                        "No se obtuvo respuesta al pedir el turno", clock.instant()));
                } else if (p.getStatus() == ReservationStatus.HELD && !p.isExpiredAt(now)) {
                    service.confirmInitial(p.getId());
                }
            } catch (RuntimeException e) {
                log.warn("Recuperacion del proceso {} fallida: {}", p.getId(), e.getMessage());
            }
        }
        Map<String, List<ReservationProcess>> byUser = stale.stream()
            .collect(Collectors.groupingBy(ReservationProcess::getExternalPatientId));
        for (String externalPatientId : byUser.keySet()) {
            try {
                reconciler.reconcileUser(externalPatientId);
            } catch (RuntimeException e) {
                // Catedra no disponible: se reintenta en el proximo ciclo.
                log.warn("Recuperacion: no se pudo reconciliar con la catedra ({})", e.getMessage());
            }
        }
        return stale.size();
    }
}
