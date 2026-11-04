package ar.edu.um.turnos.catalogo;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Respuesta de GET /api/professionals/{id} del servicio de catalogo (ver catalogo-service/docs/contrato-api.md).
 * Es la informacion VIGENTE: turnos no guarda una replica del catalogo.
 */
public record ProfessionalAgenda(
    Long id,
    String firstName,
    String lastName,
    boolean enabled,
    Long categoryId,
    CategoryRef category,
    List<Schedule> schedules,
    long catalogVersion
) {
    public ProfessionalAgenda {
        schedules = schedules == null ? List.of() : schedules;
    }

    public record CategoryRef(Long id, String name, boolean enabled) {}

    public record Schedule(Long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                           int slotDurationMinutes, boolean enabled) {}

    /** El profesional puede atender: el y su categoria estan habilitados. */
    public boolean isBookable() {
        return enabled && category != null && category.enabled();
    }

    public String categoryName() {
        return category == null ? null : category.name();
    }
}
