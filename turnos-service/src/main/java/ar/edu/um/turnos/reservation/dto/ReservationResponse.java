package ar.edu.um.turnos.reservation.dto;

import ar.edu.um.turnos.reservation.ReservationProcess;
import ar.edu.um.turnos.reservation.ReservationStatus;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Vista de un proceso de reserva para la app. No expone identificadores internos de la catedra
 * (holdId, requestEventId) ni el userId: la propiedad ya esta aplicada en el servidor.
 */
public record ReservationResponse(
    Long id,
    ReservationStatus status,
    Long professionalId,
    String professionalFirstName,
    String professionalLastName,
    String categoryName,
    LocalDate date,
    @JsonFormat(pattern = "HH:mm") LocalTime startTime,
    @JsonFormat(pattern = "HH:mm") LocalTime endTime,
    String patientFirstName,
    String patientLastName,
    String patientPhone,
    Instant expiresAt,
    /** Mensaje de la catedra: pedido de telefono o motivo del rechazo. */
    String message,
    String failureReason,
    Long reservationId,
    Instant createdAt,
    Instant confirmedAt,
    Instant cancelledAt,
    String cancellationReason,
    boolean canSubmitPhone,
    boolean canCancel
) {
    public static ReservationResponse from(ReservationProcess p, Instant now) {
        boolean open = !p.isExpiredAt(now);
        return new ReservationResponse(
            p.getId(), p.getStatus(), p.getProfessionalId(), p.getProfessionalFirstName(),
            p.getProfessionalLastName(), p.getCategoryName(), p.getAppointmentDate(), p.getStartTime(),
            p.getEndTime(), p.getPatientFirstName(), p.getPatientLastName(), p.getPatientPhone(), p.getExpiresAt(),
            p.getStatusMessage(), p.getFailureReason(), p.getReservationId(), p.getCreatedAt(), p.getConfirmedAt(),
            p.getCancelledAt(), p.getCancellationReason(),
            p.getStatus().acceptsPhone() && open,
            p.getStatus() == ReservationStatus.CONFIRMED);
    }
}
