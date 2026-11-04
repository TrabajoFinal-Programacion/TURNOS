package ar.edu.um.turnos.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Mismo contrato que LoginVM de JHipster. */
public record LoginRequest(
    @NotBlank @Size(min = 1, max = 254) String username,
    @NotNull @Size(min = 4, max = 100) String password,
    boolean rememberMe
) {}
