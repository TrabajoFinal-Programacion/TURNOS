package ar.edu.um.turnos.reservation;

import java.util.EnumSet;
import java.util.Set;

/**
 * Maquina de estados local de un proceso de reserva.
 *
 * <pre>
 * CREATED ──hold──▶ HELD ──confirm 202──▶ WAITING_FOR_PHONE ──Requested──▶ PHONE_REQUESTED
 *                                                                              │  ▲
 *                                                         telefono enviado ────▼  │ Rejected
 *                                                                        PHONE_SUBMITTED ─▶ PHONE_REJECTED
 *                                                                              │
 *                                                                  Confirmed ──▼
 *                                                                          CONFIRMED ──cancel──▶ CANCELLED
 *
 * Desde cualquier estado no final: Expired ▶ EXPIRED, Invalid ▶ INVALID, error de hold ▶ FAILED.
 * </pre>
 *
 * Los estados finales nunca se abandonan: un evento tardio no puede reabrir un proceso.
 * CONFIRMED solo puede pasar a CANCELLED.
 */
public enum ReservationStatus {
    /** Registro local creado; todavia no hay respuesta del hold. */
    CREATED,
    /** Hold obtenido en la catedra; falta la confirmacion inicial. */
    HELD,
    /** Confirmacion inicial aceptada (202); se espera AdditionalInformationRequested. */
    WAITING_FOR_PHONE,
    /** La catedra pidio el telefono; el usuario debe informarlo antes de expiresAt. */
    PHONE_REQUESTED,
    /** Telefono enviado por Kafka; se espera el resultado. */
    PHONE_SUBMITTED,
    /** La catedra rechazo el telefono; se puede reintentar hasta expiresAt. */
    PHONE_REJECTED,
    /** Reserva confirmada. */
    CONFIRMED,
    /** Reserva cancelada. Final. */
    CANCELLED,
    /** El hold o el proceso vencio. Final. */
    EXPIRED,
    /** La catedra informo un proceso inconsistente. Final. */
    INVALID,
    /** No se pudo obtener el hold o la confirmacion (turno tomado, slot invalido, etc.). Final. */
    FAILED;

    public static final Set<ReservationStatus> FINAL = EnumSet.of(CANCELLED, EXPIRED, INVALID, FAILED);

    /** Estados en los que el proceso sigue abierto y espera algo de la catedra o del usuario. */
    public static final Set<ReservationStatus> PENDING =
        EnumSet.of(CREATED, HELD, WAITING_FOR_PHONE, PHONE_REQUESTED, PHONE_SUBMITTED, PHONE_REJECTED);

    public boolean isFinal() {
        return FINAL.contains(this);
    }

    public boolean isPending() {
        return PENDING.contains(this);
    }

    public boolean acceptsPhone() {
        return this == PHONE_REQUESTED || this == PHONE_REJECTED;
    }
}
