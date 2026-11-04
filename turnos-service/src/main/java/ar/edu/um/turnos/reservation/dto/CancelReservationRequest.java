package ar.edu.um.turnos.reservation.dto;

import jakarta.validation.constraints.Size;

public record CancelReservationRequest(@Size(max = 500) String reason) {}
