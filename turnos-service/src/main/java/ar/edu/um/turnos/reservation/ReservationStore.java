package ar.edu.um.turnos.reservation;

import java.util.function.Consumer;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistencia de procesos en transacciones cortas. Las llamadas remotas (catedra, catalogo, Kafka)
 * se hacen FUERA de estas transacciones: nunca se mantiene una transaccion abierta esperando la red.
 *
 * update() relee la fila, aplica el cambio y guarda. Si otro hilo la modifico en el medio
 * (conflicto de @Version), se reintenta sobre el estado nuevo; la maquina de estados decide
 * si la transicion sigue siendo valida.
 */
@Component
public class ReservationStore {

    private static final int MAX_ATTEMPTS = 3;

    private final ReservationProcessRepository repository;
    private final TransactionTemplate tx;

    public ReservationStore(ReservationProcessRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public ReservationProcess insert(ReservationProcess process) {
        return tx.execute(status -> repository.saveAndFlush(process));
    }

    public ReservationProcess update(Long id, Consumer<ReservationProcess> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    ReservationProcess p = repository.findById(id)
                        .orElseThrow(() -> new IllegalStateException("No existe el proceso " + id));
                    change.accept(p);
                    return repository.saveAndFlush(p);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
            }
        }
    }
}
