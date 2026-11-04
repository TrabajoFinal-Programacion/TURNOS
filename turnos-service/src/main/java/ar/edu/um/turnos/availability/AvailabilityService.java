package ar.edu.um.turnos.availability;

import ar.edu.um.turnos.catalogo.CatalogoClient;
import ar.edu.um.turnos.catalogo.ProfessionalAgenda;
import ar.edu.um.turnos.catedra.CatedraTurnosClient;
import ar.edu.um.turnos.catedra.dto.CatedraDtos.OccupancyResponse;
import ar.edu.um.turnos.config.ReservationProperties;
import ar.edu.um.turnos.web.error.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AvailabilityService {

    private final CatalogoClient catalogo;
    private final CatedraTurnosClient catedra;
    private final ReservationProperties properties;
    private final Clock clock;

    public AvailabilityService(CatalogoClient catalogo, CatedraTurnosClient catedra, ReservationProperties properties,
                               Clock clock) {
        this.catalogo = catalogo;
        this.catedra = catedra;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Turnos de un profesional en una fecha. La agenda sale del catalogo (vigente) y las ocupaciones,
     * de la catedra. Si alguno no responde, no se informa una disponibilidad que podria ser falsa.
     */
    public AvailabilityResponse availability(Long professionalId, LocalDate date) {
        validateDate(date);
        ProfessionalAgenda agenda = requireProfessional(professionalId);
        if (!agenda.isBookable()) {
            return response(agenda, date, List.of());
        }
        OccupancyResponse occupancies = catedra.occupancies(professionalId, date, date);
        LocalDateTime nowLocal = LocalDateTime.now(clock.withZone(properties.zone()));
        return response(agenda, date, SlotCalculator.slots(agenda, date, occupancies.occupancies(), nowLocal,
            clock.instant()));
    }

    public ProfessionalAgenda requireProfessional(Long professionalId) {
        return catalogo.professional(professionalId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND",
                "No existe el profesional " + professionalId));
    }

    public void validateDate(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        if (date.isBefore(today)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_IN_PAST", "La fecha ya paso");
        }
        if (date.isAfter(today.plusDays(properties.maxDaysAhead()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DATE_TOO_FAR",
                "Solo se puede consultar hasta " + properties.maxDaysAhead() + " dias hacia adelante");
        }
    }

    private static AvailabilityResponse response(ProfessionalAgenda agenda, LocalDate date, List<Slot> slots) {
        return new AvailabilityResponse(agenda.id(), agenda.firstName(), agenda.lastName(), agenda.categoryName(),
            date, agenda.isBookable(), agenda.catalogVersion(), slots);
    }
}
