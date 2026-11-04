package ar.edu.um.turnos.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Pedido de reserva de la app. El usuario NO se envia: sale del JWT.
 * patientFirstName / patientLastName son opcionales; si faltan se usan los de la cuenta.
 */
public record StartReservationRequest(
    @NotNull @Positive Long professionalId,
    @NotNull LocalDate date,
    /** "HH:mm" o "HH:mm:ss" (formato ISO). */
    @NotNull LocalTime startTime,
    @Size(min = 2, max = 100) String patientFirstName,
    @Size(min = 2, max = 100) String patientLastName
) {}
