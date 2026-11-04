package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.catedra.CatedraTurnosClient;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.Appointment;
import ar.edu.um.turnos.config.ReservationProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reconciliacion con el estado persistido en la catedra (GET /api/appointments filtrado por
 * externalPatientId). Se usa cuando REST y Kafka pueden no coincidir: respuestas perdidas,
 * eventos que no llegaron, AppointmentProcessInvalid, reinicios con procesos pendientes.
 *
 * Reglas (nunca retroceden un estado final, porque las transiciones de ReservationProcess lo impiden):
 * - catedra CONFIRMED  -> confirmed
 * - catedra CANCELLED  -> confirmed (si estaba pendiente) y luego cancelled
 * - catedra FAILED     -> expired (la catedra cerro el proceso sin confirmar)
 * - catedra PHONE_PENDING o sin reserva: si expiresAt + margen ya paso, expired; si no, se espera.
 */
@Component
public class ReservationReconciler {

    private static final Logger log = LoggerFactory.getLogger(ReservationReconciler.class);

    private static final EnumSet<ReservationStatus> RECONCILABLE = EnumSet.of(
        ReservationStatus.HELD, ReservationStatus.WAITING_FOR_PHONE, ReservationStatus.PHONE_REQUESTED,
        ReservationStatus.PHONE_SUBMITTED, ReservationStatus.PHONE_REJECTED, ReservationStatus.CONFIRMED);

    private final CatedraTurnosClient catedra;
    private final ReservationProcessRepository repository;
    private final ReservationStore store;
    private final ReservationProperties properties;
    private final Clock clock;

    public ReservationReconciler(CatedraTurnosClient catedra, ReservationProcessRepository repository,
                                 ReservationStore store, ReservationProperties properties, Clock clock) {
        this.catedra = catedra;
        this.repository = repository;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reconcilia los procesos abiertos y confirmados de un usuario con una sola consulta a la catedra.
     * @return cantidad de procesos que cambiaron de estado.
     */
    public int reconcileUser(String externalPatientId) {
        List<ReservationProcess> local = repository.findByExternalPatientIdAndStatusIn(externalPatientId, RECONCILABLE)
            .stream()
            .filter(p -> p.getReservationProcessId() != null)
            .toList();
        if (local.isEmpty()) {
            return 0;
        }
        Map<String, Appointment> remote = catedra.appointmentsOf(externalPatientId).stream()
            .filter(a -> a.reservationProcessId() != null)
            .collect(Collectors.toMap(Appointment::reservationProcessId, Function.identity(), (a, b) -> a));
        int changed = 0;
        for (ReservationProcess p : local) {
            if (apply(p, remote.get(p.getReservationProcessId()))) {
                changed++;
            }
        }
        if (changed > 0) {
            log.info("Reconciliacion: {} proceso(s) actualizados para un usuario", changed);
        }
        return changed;
    }

    /** Reconcilia el usuario duenio de un proceso de la catedra (por ejemplo, tras AppointmentProcessInvalid). */
    public int reconcileProcess(String reservationProcessId) {
        return repository.findByReservationProcessId(reservationProcessId)
            .map(p -> reconcileUser(p.getExternalPatientId()))
            .orElse(0);
    }

    private boolean apply(ReservationProcess p, Appointment remote) {
        Instant now = clock.instant();
        ReservationStatus before = p.getStatus();
        ReservationProcess after;
        if (remote == null) {
            if (p.getStatus().isPending() && isPastDeadline(p, now)) {
                after = store.update(p.getId(), x -> x.expired("NOT_FOUND_AT_CATEDRA", clock.instant()));
            } else {
                return false;
            }
        } else {
            after = switch (remote.status() == null ? "" : remote.status()) {
                case "CONFIRMED" -> store.update(p.getId(),
                    x -> x.confirmed(remote.reservationId(), remote.confirmedAt(), clock.instant()));
                case "CANCELLED" -> store.update(p.getId(), x -> {
                    x.confirmed(remote.reservationId(), remote.confirmedAt(), clock.instant());
                    x.cancelled(remote.cancellationReason(), remote.cancelledAt(), clock.instant());
                });
                case "FAILED" -> store.update(p.getId(), x -> x.expired("CATEDRA_FAILED", clock.instant()));
                case "PHONE_PENDING" -> {
                    if (isPastDeadline(p, now)) {
                        yield store.update(p.getId(), x -> x.expired("LOCAL_TIMEOUT", clock.instant()));
                    }
                    // La confirmacion inicial llego aunque la respuesta se perdio.
                    yield store.update(p.getId(), x -> x.confirmAccepted(null, clock.instant()));
                }
                default -> p;
            };
        }
        return after.getStatus() != before;
    }

    private boolean isPastDeadline(ReservationProcess p, Instant now) {
        return p.getExpiresAt() != null && now.isAfter(p.getExpiresAt().plus(properties.expirationGrace()));
    }
}
