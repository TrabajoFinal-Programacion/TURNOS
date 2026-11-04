package ar.edu.um.turnos.availability;

public enum SlotStatus {
    /** Libre: se puede reservar. */
    AVAILABLE,
    /** Bloqueado temporalmente por un hold activo. */
    HELD,
    /** Reserva confirmada. */
    RESERVED,
    /** El horario ya paso. */
    PAST
}
