package ar.edu.um.turnos.catedra.dto;

import java.time.Instant;

/** AdditionalInformationSubmitted (alumno a catedra, contrato 15.5). Todos los campos son obligatorios. */
public record PhoneSubmittedEvent(
    String eventId,
    String eventType,
    String groupId,
    String reservationProcessId,
    String holdId,
    String requestEventId,
    Information information,
    Instant occurredAt,
    int schemaVersion
) {
    public static final String TYPE = "AdditionalInformationSubmitted";

    public record Information(String phoneNumber) {}
}
