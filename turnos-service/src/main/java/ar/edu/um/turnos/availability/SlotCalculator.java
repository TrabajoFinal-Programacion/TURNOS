package ar.edu.um.turnos.availability;

import ar.edu.um.turnos.catalogo.ProfessionalAgenda;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.Occupancy;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Construye los turnos de un dia combinando la agenda semanal vigente (catalogo) con las ocupaciones
 * informadas por la catedra. Funcion pura: sin I/O, para poder probar cada regla.
 *
 * - Solo franjas habilitadas del dia de la semana pedido, de un profesional y categoria habilitados.
 * - Cada franja se divide en turnos de slotDurationMinutes; un turno que no entra completo se descarta.
 * - CONFIRMED tiene prioridad sobre HELD (contrato 8). Un HELD cuyo expiresAt ya paso se considera libre.
 * - Los turnos cuyo inicio ya paso (en la zona horaria de atencion) se marcan PAST.
 */
public final class SlotCalculator {

    private SlotCalculator() {}

    public static List<Slot> slots(ProfessionalAgenda agenda, LocalDate date, Collection<Occupancy> occupancies,
                                   LocalDateTime nowLocal, Instant now) {
        if (!agenda.isBookable()) {
            return List.of();
        }
        Map<LocalTime, SlotStatus> occupied = occupiedStarts(date, occupancies, now);
        TreeMap<LocalTime, Slot> result = new TreeMap<>();
        for (ProfessionalAgenda.Schedule schedule : agenda.schedules()) {
            if (!schedule.enabled() || schedule.dayOfWeek() != date.getDayOfWeek()
                || schedule.slotDurationMinutes() <= 0) {
                continue;
            }
            LocalTime start = schedule.startTime();
            while (true) {
                LocalTime end = start.plusMinutes(schedule.slotDurationMinutes());
                // end <= start indica que se paso de medianoche: se corta la franja.
                if (!end.isAfter(start) || end.isAfter(schedule.endTime())) {
                    break;
                }
                SlotStatus status;
                if (!LocalDateTime.of(date, start).isAfter(nowLocal)) {
                    status = SlotStatus.PAST;
                } else {
                    status = occupied.getOrDefault(start, SlotStatus.AVAILABLE);
                }
                result.putIfAbsent(start, new Slot(date, start, end, status));
                start = end;
            }
        }
        return new ArrayList<>(result.values());
    }

    /** Busca el turno exacto (fecha + hora de inicio) en la agenda vigente. */
    public static Optional<Slot> find(ProfessionalAgenda agenda, LocalDate date, LocalTime start,
                                      LocalDateTime nowLocal, Instant now) {
        return slots(agenda, date, List.of(), nowLocal, now).stream()
            .filter(s -> s.startTime().equals(start))
            .findFirst();
    }

    private static Map<LocalTime, SlotStatus> occupiedStarts(LocalDate date, Collection<Occupancy> occupancies,
                                                             Instant now) {
        Map<LocalTime, SlotStatus> result = new HashMap<>();
        for (Occupancy o : occupancies) {
            if (o.date() == null || o.startTime() == null || !o.date().equals(date)) {
                continue;
            }
            if ("CONFIRMED".equals(o.status())) {
                result.put(o.startTime(), SlotStatus.RESERVED);
            } else if ("HELD".equals(o.status())
                && (o.expiresAt() == null || o.expiresAt().isAfter(now))) {
                result.putIfAbsent(o.startTime(), SlotStatus.HELD);
            }
        }
        return result;
    }
}
