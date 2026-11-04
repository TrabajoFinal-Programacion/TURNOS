package ar.edu.um.turnos.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/**
 * Configuracion de los JWT de usuario final.
 * Las claves se leen desde archivos PEM externos al repositorio.
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
    @NotBlank String issuer,
    @NotBlank String audience,
    @NotNull Resource privateKeyLocation,
    @NotNull Resource publicKeyLocation,
    @NotNull Duration tokenValidity,
    @NotNull Duration tokenValidityRememberMe
) {}
