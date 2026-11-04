package ar.edu.um.turnos.availability;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.turnos.catalogo.ProfessionalAgenda;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda.CategoryRef;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda.Schedule;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.Occupancy;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Disponibilidad = agenda semanal vigente - ocupaciones de la catedra - horarios pasados. */
class SlotCalculatorTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);
    private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 11, 1, 8, 0);
    private static final Instant NOW = Instant.parse("2026-11-01T11:00:00Z");

    private static ProfessionalAgenda agenda(boolean enabled, boolean categoryEnabled, Schedule... schedules) {
        return new ProfessionalAgenda(15L, "Ana", "Gomez", enabled, 1L, new CategoryRef(1L, "Clinica", categoryEnabled),
            List.of(schedules), 7);
    }

    private static Schedule schedule(DayOfWeek day, String start, String end, int minutes, boolean enabled) {
        return new Schedule(21L, day, LocalTime.parse(start), LocalTime.parse(end), minutes, enabled);
    }

    @Test
    void splitsEachScheduleIntoSlotsOfItsDuration() {
        List<Slot> slots = SlotCalculator.slots(
            agenda(true, true, schedule(DayOfWeek.MONDAY, "09:00", "11:00", 30, true)), MONDAY, List.of(), BEFORE, NOW);

        assertThat(slots).extracting(Slot::startTime).containsExactly(
            LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(10, 30));
        assertThat(slots).allMatch(Slot::isAvailable);
        assertThat(slots.get(3).endTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void slotThatDoesNotFitCompletelyIsDiscarded() {
        List<Slot> slots = SlotCalculator.slots(
            agenda(true, true, schedule(DayOfWeek.MONDAY, "09:00", "10:10", 30, true)), MONDAY, List.of(), BEFORE, NOW);
        assertThat(slots).hasSize(2);
    }

    @Test
    void ignoresOtherDaysAndDisabledSchedules() {
        List<Slot> slots = SlotCalculator.slots(agenda(true, true,
                schedule(DayOfWeek.TUESDAY, "09:00", "10:00", 30, true),
                schedule(DayOfWeek.MONDAY, "14:00", "15:00", 30, false)),
            MONDAY, List.of(), BEFORE, NOW);
        assertThat(slots).isEmpty();
    }

    @Test
    void disabledProfessionalOrCategoryHasNoSlots() {
        Schedule monday = schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true);
        assertThat(SlotCalculator.slots(agenda(false, true, monday), MONDAY, List.of(), BEFORE, NOW)).isEmpty();
        assertThat(SlotCalculator.slots(agenda(true, false, monday), MONDAY, List.of(), BEFORE, NOW)).isEmpty();
    }

    @Test
    void occupanciesAreSubtractedAndConfirmedWinsOverHeld() {
        List<Occupancy> occupancies = List.of(
            new Occupancy(MONDAY, LocalTime.of(9, 0), "CONFIRMED", null),
            new Occupancy(MONDAY, LocalTime.of(9, 0), "HELD", NOW.plusSeconds(300)),
            new Occupancy(MONDAY, LocalTime.of(9, 30), "HELD", NOW.plusSeconds(300)),
            new Occupancy(MONDAY, LocalTime.of(10, 0), "HELD", NOW.minusSeconds(5))); // hold vencido

        List<Slot> slots = SlotCalculator.slots(
            agenda(true, true, schedule(DayOfWeek.MONDAY, "09:00", "10:30", 30, true)), MONDAY, occupancies, BEFORE, NOW);

        assertThat(slots).extracting(Slot::status).containsExactly(
            SlotStatus.RESERVED, SlotStatus.HELD, SlotStatus.AVAILABLE);
    }

    @Test
    void pastSlotsAreMarkedPast() {
        LocalDateTime mondayTen = LocalDateTime.of(MONDAY, LocalTime.of(10, 0));
        List<Slot> slots = SlotCalculator.slots(
            agenda(true, true, schedule(DayOfWeek.MONDAY, "09:00", "11:00", 30, true)), MONDAY, List.of(), mondayTen, NOW);

        assertThat(slots).extracting(Slot::status).containsExactly(
            SlotStatus.PAST, SlotStatus.PAST, SlotStatus.PAST, SlotStatus.AVAILABLE);
    }

    @Test
    void findsOnlyExactSlotStarts() {
        ProfessionalAgenda a = agenda(true, true, schedule(DayOfWeek.MONDAY, "09:00", "10:00", 30, true));
        assertThat(SlotCalculator.find(a, MONDAY, LocalTime.of(9, 30), BEFORE, NOW)).isPresent();
        assertThat(SlotCalculator.find(a, MONDAY, LocalTime.of(9, 15), BEFORE, NOW)).isEmpty();
    }
}
