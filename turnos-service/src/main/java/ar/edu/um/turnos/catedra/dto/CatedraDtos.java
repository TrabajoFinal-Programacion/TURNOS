package ar.edu.um.turnos.catedra.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** DTO del contrato REST de turnos de la catedra (seccion 8 a 12 de la referencia de integracion). */
public final class CatedraDtos {

    private CatedraDtos() {}

    public record OccupancyResponse(Long professionalId, LocalDate from, LocalDate to, List<Occupancy> occupancies) {
        public OccupancyResponse {
            occupancies = occupancies == null ? List.of() : occupancies;
        }
    }

    /** status: CONFIRMED o HELD. expiresAt solo para HELD. */
    public record Occupancy(LocalDate date, LocalTime startTime, String status, Instant expiresAt) {}

    public record HoldRequest(
        Long professionalId,
        LocalDate date,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        String externalPatientId) {}

    public record HoldResponse(String holdId, String reservationProcessId, String status, Instant expiresAt) {}

    public record ConfirmHoldRequest(String externalPatientId, String patientFirstName, String patientLastName) {}

    public record ConfirmHoldResponse(String reservationProcessId, String status, Instant expiresAt) {}

    /** Elemento de GET /api/appointments. status: PHONE_PENDING, CONFIRMED, CANCELLED o FAILED. */
    public record Appointment(
        Long reservationId,
        String reservationProcessId,
        String externalPatientId,
        LocalDate appointmentDate,
        LocalTime startTime,
        LocalTime endTime,
        Long professionalId,
        String professionalFirstName,
        String professionalLastName,
        String patientFirstName,
        String patientLastName,
        String patientPhone,
        String status,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        String cancellationReason) {}

    public record CancelRequest(String reason) {}

    public record CancelResponse(Long reservationId, String reservationProcessId, String status, Instant cancelledAt) {}
}
