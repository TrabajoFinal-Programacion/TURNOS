package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.catedra.dto.ReservationActionEvent;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un evento de reserva de la catedra. Todo en UNA transaccion de MySQL:
 * registro del eventId + cambio de estado. Si algo falla, no queda ninguno de los dos
 * y Kafka vuelve a entregar el mensaje.
 */
@Service
public class ReservationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventHandler.class);

    public enum Result { APPLIED, IGNORED, DUPLICATE, UNKNOWN_PROCESS }

    private final ProcessedEventRepository processedEvents;
    private final ReservationProcessRepository repository;
    private final Clock clock;

    public ReservationEventHandler(ProcessedEventRepository processedEvents, ReservationProcessRepository repository,
                                   Clock clock) {
        this.processedEvents = processedEvents;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Result handle(ReservationActionEvent event) {
        Instant now = clock.instant();
        Optional<ProcessedEvent> seen = processedEvents.findById(event.eventId());
        if (seen.isPresent()) {
            seen.get().registerDuplicate(now);
            log.info("Evento duplicado ignorado: {} {} (proceso {})", event.eventType(), event.eventId(),
                event.reservationProcessId());
            return Result.DUPLICATE;
        }
        processedEvents.save(new ProcessedEvent(event.eventId(), event.eventType(), now));

        Optional<ReservationProcess> found = repository.findByReservationProcessId(event.reservationProcessId());
        if (found.isEmpty()) {
            log.warn("{} para un proceso desconocido ({}); se ignora", event.eventType(), event.reservationProcessId());
            return Result.UNKNOWN_PROCESS;
        }
        ReservationProcess p = found.get();
        ReservationStatus before = p.getStatus();
        boolean applied = switch (event.eventType()) {
            case ReservationActionEvent.REQUESTED ->
                p.phoneRequested(event.eventId(), event.message(), event.expiresAt(), now);
            case ReservationActionEvent.REJECTED ->
                p.phoneRejected(event.reason(), event.message(), event.expiresAt(), now);
            case ReservationActionEvent.CONFIRMED ->
                p.confirmed(event.reservationId(), event.occurredAt(), now);
            case ReservationActionEvent.EXPIRED ->
                p.expired(event.reason() == null ? "PROCESS_EXPIRED" : event.reason(), now);
            case ReservationActionEvent.INVALID ->
                p.invalid(event.reason() == null ? "PROCESS_INVALID" : event.reason(), now);
            case ReservationActionEvent.CANCELLED ->
                p.cancelled(event.reason(), event.occurredAt(), now);
            default -> false;
        };
        if (applied) {
            repository.save(p);
            log.info("{}: proceso local {} {} -> {}", event.eventType(), p.getId(), before, p.getStatus());
            return Result.APPLIED;
        }
        // Evento tardio o fuera de orden: el estado actual no lo admite (por ejemplo, un Requested
        // despues de un Expired). No se reabre ni se retrocede nada.
        log.info("{} no aplica al proceso local {} en estado {}; se ignora", event.eventType(), p.getId(), before);
        return Result.IGNORED;
    }
}
