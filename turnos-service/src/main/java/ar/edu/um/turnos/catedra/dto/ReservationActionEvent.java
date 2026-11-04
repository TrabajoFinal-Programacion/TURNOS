package ar.edu.um.turnos.catedra.dto;

import java.time.Instant;

/**
 * Evento del topic alumnos.turnos.acciones.{groupId}. Un solo tipo con todos los campos opcionales:
 * cada eventType usa un subconjunto (contrato 15.4 a 15.10). Los campos desconocidos se ignoran.
 */
public record ReservationActionEvent(
    String eventId,
    String eventType,
    String groupId,
    String reservationProcessId,
    String holdId,
    String requestEventId,
    String requiredField,
    String message,
    String reason,
    Instant expiresAt,
    Instant occurredAt,
    Long reservationId,
    Long professionalId,
    String status,
    Integer schemaVersion
) {
    public static final String REQUESTED = "AdditionalInformationRequested";
    public static final String CONFIRMED = "AppointmentConfirmed";
    public static final String REJECTED = "AdditionalInformationRejected";
    public static final String EXPIRED = "AppointmentProcessExpired";
    public static final String INVALID = "AppointmentProcessInvalid";
    public static final String CANCELLED = "AppointmentCancelled";
}
