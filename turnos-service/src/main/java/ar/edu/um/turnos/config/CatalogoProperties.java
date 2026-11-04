package ar.edu.um.turnos.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Acceso al servicio de catalogo propio. */
@Validated
@ConfigurationProperties(prefix = "app.catalogo")
public record CatalogoProperties(
    @NotBlank String baseUrl,
    @NotNull Duration connectTimeout,
    @NotNull Duration readTimeout,
    @Min(1) int maxAttempts
) {}
