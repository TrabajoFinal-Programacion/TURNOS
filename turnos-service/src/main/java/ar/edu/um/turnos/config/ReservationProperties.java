package ar.edu.um.turnos.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Parametros del proceso de reserva y de su recuperacion. */
@Validated
@ConfigurationProperties(prefix = "app.reservations")
public record ReservationProperties(
    /** Zona horaria de atencion: las horas de agenda son locales (contrato 4). */
    @NotNull ZoneId zone,
    /** Hasta cuantos dias hacia adelante se puede consultar disponibilidad o reservar. */
    @Min(1) int maxDaysAhead,
    boolean recoveryEnabled,
    @NotNull Duration recoveryInitialDelay,
    @NotNull Duration recoveryInterval,
    /** Un proceso abierto sin cambios durante este tiempo se revisa. */
    @NotNull Duration staleAfter,
    /** Margen despues de expiresAt antes de dar un proceso por vencido sin evento de la catedra. */
    @NotNull Duration expirationGrace,
    /** Tiempo maximo de espera al publicar el telefono en Kafka. */
    @NotNull Duration kafkaSendTimeout
) {}
