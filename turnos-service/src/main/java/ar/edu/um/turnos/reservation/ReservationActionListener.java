package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.catedra.dto.ReservationActionEvent;
import ar.edu.um.turnos.config.CatedraProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consume alumnos.turnos.acciones.{groupId}. Entrega al menos una vez: el handler deduplica por eventId.
 * El offset se confirma despues de que la transaccion local termino (ack-mode record).
 */
@Component
public class ReservationActionListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationActionListener.class);

    private static final Set<String> KNOWN_TYPES = Set.of(
        ReservationActionEvent.REQUESTED, ReservationActionEvent.REJECTED, ReservationActionEvent.CONFIRMED,
        ReservationActionEvent.EXPIRED, ReservationActionEvent.INVALID, ReservationActionEvent.CANCELLED);

    private final ObjectMapper objectMapper;
    private final ReservationEventHandler handler;
    private final ReservationReconciler reconciler;
    private final CatedraProperties catedra;

    public ReservationActionListener(ObjectMapper objectMapper, ReservationEventHandler handler,
                                     ReservationReconciler reconciler, CatedraProperties catedra) {
        this.objectMapper = objectMapper;
        this.handler = handler;
        this.reconciler = reconciler;
        this.catedra = catedra;
    }

    @KafkaListener(
        id = "reservation-actions",
        topics = "${app.catedra.kafka-actions-topic}",
        groupId = "${app.catedra.kafka-consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        ReservationActionEvent event = parse(record);
        if (event == null) {
            return;
        }
        ReservationEventHandler.Result result = handler.handle(event);
        if (result == ReservationEventHandler.Result.APPLIED
            && ReservationActionEvent.INVALID.equals(event.eventType())) {
            // Contrato 15.9: tratar como invalido y reconciliar por REST el estado persistido.
            reconcileQuietly(event);
        }
    }

    /** Si la catedra no responde, no se reintenta el mensaje: la recuperacion periodica lo vuelve a intentar. */
    private void reconcileQuietly(ReservationActionEvent event) {
        try {
            reconciler.reconcileProcess(event.reservationProcessId());
        } catch (RuntimeException e) {
            log.warn("No se pudo reconciliar el proceso {} tras AppointmentProcessInvalid: {}",
                event.reservationProcessId(), e.getMessage());
        }
    }

    private ReservationActionEvent parse(ConsumerRecord<String, String> record) {
        if (record.value() == null) {
            return null;
        }
        try {
            ReservationActionEvent event = objectMapper.readValue(record.value(), ReservationActionEvent.class);
            if (event.eventType() == null || !KNOWN_TYPES.contains(event.eventType())) {
                log.info("Evento de turnos ignorado: tipo {} (offset {})", event.eventType(), record.offset());
                return null;
            }
            if (isBlank(event.eventId()) || isBlank(event.reservationProcessId())) {
                log.warn("Evento {} sin eventId o reservationProcessId (offset {})", event.eventType(), record.offset());
                return null;
            }
            if (event.groupId() != null && !event.groupId().equals(catedra.groupId())) {
                log.warn("Evento de otro groupId ({}) ignorado", event.groupId());
                return null;
            }
            if (record.key() != null && !record.key().equals(event.reservationProcessId())) {
                log.warn("Message key {} distinta de reservationProcessId {}; se procesa por el cuerpo",
                    record.key(), event.reservationProcessId());
            }
            return event;
        } catch (JsonProcessingException e) {
            log.warn("JSON invalido en {} offset {}: {}", record.topic(), record.offset(), e.getOriginalMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
