package ar.edu.um.turnos.availability;

import java.time.LocalDate;
import java.util.List;

public record AvailabilityResponse(
    Long professionalId,
    String professionalFirstName,
    String professionalLastName,
    String categoryName,
    LocalDate date,
    /** false si el profesional o su categoria estan deshabilitados. */
    boolean bookable,
    long catalogVersion,
    List<Slot> slots
) {}
