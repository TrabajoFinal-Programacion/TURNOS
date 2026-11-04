package ar.edu.um.turnos.reservation;

import ar.edu.um.turnos.catedra.dto.PhoneSubmittedEvent;
import ar.edu.um.turnos.config.CatedraProperties;
import ar.edu.um.turnos.config.ReservationProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publica AdditionalInformationSubmitted en catedra.turnos.telefono.{groupId}.
 * - message key = reservationProcessId (orden dentro de la particion, contrato 15.1)
 * - eventId nuevo en cada envio: un reenvio despues de un rechazo es otro evento (contrato 15.7)
 * - envio sincronico con timeout: si Kafka no confirma, el usuario recibe un error y puede reintentar.
 */
@Component
public class PhoneSubmissionPublisher {

    private static final Logger log = LoggerFactory.getLogger(PhoneSubmissionPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final CatedraProperties catedra;
    private final ReservationProperties properties;
    private final Clock clock;

    public PhoneSubmissionPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                    CatedraProperties catedra, ReservationProperties properties, Clock clock) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.catedra = catedra;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return el eventId publicado. */
    public String publish(ReservationProcess process, String phoneNumber) {
        PhoneSubmittedEvent event = new PhoneSubmittedEvent(
            UUID.randomUUID().toString(),
            PhoneSubmittedEvent.TYPE,
            catedra.groupId(),
            process.getReservationProcessId(),
            process.getHoldId(),
            process.getRequestEventId(),
            new PhoneSubmittedEvent.Information(phoneNumber),
            clock.instant(),
            1);
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar AdditionalInformationSubmitted", e);
        }
        try {
            kafka.send(catedra.kafkaPhoneTopic(), process.getReservationProcessId(), json)
                .get(properties.kafkaSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            log.info("Telefono enviado (proceso {}, eventId {})", process.getReservationProcessId(), event.eventId());
            return event.eventId();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagingUnavailableException("Interrumpido enviando el telefono", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new MessagingUnavailableException("Kafka no confirmo el envio del telefono", e);
        }
    }

    public static class MessagingUnavailableException extends RuntimeException {
        public MessagingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
