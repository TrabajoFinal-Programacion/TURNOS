package ar.edu.um.turnos.reservation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitPhoneRequest(@NotBlank @Size(max = 30) String phoneNumber) {}
