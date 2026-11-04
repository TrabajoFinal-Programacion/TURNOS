package ar.edu.um.turnos.availability;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.time.LocalTime;

public record Slot(
    LocalDate date,
    @JsonFormat(pattern = "HH:mm") LocalTime startTime,
    @JsonFormat(pattern = "HH:mm") LocalTime endTime,
    SlotStatus status
) {
    public boolean isAvailable() {
        return status == SlotStatus.AVAILABLE;
    }
}
