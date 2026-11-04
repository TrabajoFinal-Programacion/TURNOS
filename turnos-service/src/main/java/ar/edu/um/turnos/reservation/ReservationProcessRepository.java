package ar.edu.um.turnos.reservation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationProcessRepository extends JpaRepository<ReservationProcess, Long> {

    /** Toda consulta de un usuario pasa por aca: la propiedad se aplica en la propia query. */
    Optional<ReservationProcess> findByIdAndUserId(Long id, Long userId);

    Optional<ReservationProcess> findByUserIdAndClientRequestId(Long userId, String clientRequestId);

    Optional<ReservationProcess> findByReservationProcessId(String reservationProcessId);

    Page<ReservationProcess> findByUserId(Long userId, Pageable pageable);

    Page<ReservationProcess> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses,
                                                     Pageable pageable);

    List<ReservationProcess> findByUserIdAndStatusIn(Long userId, Collection<ReservationStatus> statuses);

    List<ReservationProcess> findByExternalPatientIdAndStatusIn(String externalPatientId,
                                                                Collection<ReservationStatus> statuses);

    /** Procesos abiertos sin cambios recientes: candidatos a recuperacion. */
    List<ReservationProcess> findByStatusInAndUpdatedAtBefore(Collection<ReservationStatus> statuses, Instant before);
}
