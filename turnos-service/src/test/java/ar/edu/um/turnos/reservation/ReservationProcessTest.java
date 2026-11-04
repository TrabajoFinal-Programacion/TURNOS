package ar.edu.um.turnos.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/** Maquina de estados local: transiciones validas, eventos tardios y fuera de orden. */
class ReservationProcessTest {

    private static final Instant T0 = Instant.parse("2026-11-01T12:00:00Z");
    private static final Instant EXP = T0.plusSeconds(600);

    private ReservationProcess created() {
        return ReservationProcess.create(1L, "ext-1", null, 15L, "Ana", "Gomez", "Clinica",
            LocalDate.of(2026, 11, 9), LocalTime.of(9, 0), LocalTime.of(9, 30), "Juan", "Perez", T0);
    }

    private ReservationProcess phoneRequested() {
        ReservationProcess p = created();
        p.holdCreated("hold-1", "process-1", EXP, T0);
        p.confirmAccepted(EXP, T0);
        p.phoneRequested("req-1", "Ingrese un telefono", EXP, T0);
        return p;
    }

    @Test
    void happyPathReachesConfirmed() {
        ReservationProcess p = created();
        assertThat(p.holdCreated("hold-1", "process-1", EXP, T0)).isTrue();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(p.confirmAccepted(EXP, T0)).isTrue();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(p.phoneRequested("req-1", "Ingrese un telefono", EXP, T0)).isTrue();
        assertThat(p.getRequestEventId()).isEqualTo("req-1");
        assertThat(p.phoneSubmitted("+5492615551234", T0)).isTrue();
        assertThat(p.confirmed(8251L, T0, T0)).isTrue();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(p.getReservationId()).isEqualTo(8251L);
    }

    /** Kafka entrega AdditionalInformationRequested antes que la respuesta 202 de la confirmacion. */
    @Test
    void phoneRequestBeforeConfirmResponseDoesNotGoBackwards() {
        ReservationProcess p = created();
        p.holdCreated("hold-1", "process-1", EXP, T0);
        assertThat(p.phoneRequested("req-1", "msg", EXP, T0)).isTrue();

        assertThat(p.confirmAccepted(EXP, T0)).isFalse();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.PHONE_REQUESTED);
    }

    @Test
    void rejectedPhoneCanBeSubmittedAgain() {
        ReservationProcess p = phoneRequested();
        p.phoneSubmitted("123", T0);
        assertThat(p.phoneRejected("INVALID_PHONE_NUMBER", "Telefono invalido", EXP, T0)).isTrue();
        assertThat(p.getStatus().acceptsPhone()).isTrue();
        assertThat(p.phoneSubmitted("+5492615551234", T0)).isTrue();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.PHONE_SUBMITTED);
    }

    @Test
    void finalStatesAreNeverReopened() {
        ReservationProcess p = phoneRequested();
        assertThat(p.expired("HOLD_EXPIRED", T0)).isTrue();

        assertThat(p.phoneRequested("req-2", "tarde", EXP, T0)).isFalse();
        assertThat(p.confirmed(1L, T0, T0)).isFalse();
        assertThat(p.invalid("X", T0)).isFalse();
        assertThat(p.phoneSubmitted("+5492615551234", T0)).isFalse();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    void confirmedReservationIgnoresLateExpirationAndOnlyAllowsCancellation() {
        ReservationProcess p = phoneRequested();
        p.confirmed(8251L, T0, T0);

        assertThat(p.expired("HOLD_EXPIRED", T0)).isFalse();
        assertThat(p.invalid("X", T0)).isFalse();
        assertThat(p.confirmed(8251L, T0, T0)).isFalse(); // duplicado: sin cambios
        assertThat(p.cancelled("Cambio", T0, T0)).isTrue();
        assertThat(p.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(p.cancelled("otra vez", T0, T0)).isFalse();
    }

    @Test
    void onlyConfirmedReservationsCanBeCancelled() {
        assertThat(phoneRequested().cancelled("x", T0, T0)).isFalse();
    }

    @Test
    void phoneIsOnlyAcceptedWhenRequested() {
        ReservationProcess p = created();
        p.holdCreated("hold-1", "process-1", EXP, T0);
        assertThat(p.phoneSubmitted("+5492615551234", T0)).isFalse();
    }

    @Test
    void expiresAtIsRespected() {
        ReservationProcess p = phoneRequested();
        assertThat(p.isExpiredAt(EXP.minusSeconds(1))).isFalse();
        assertThat(p.isExpiredAt(EXP)).isTrue();
    }
}
