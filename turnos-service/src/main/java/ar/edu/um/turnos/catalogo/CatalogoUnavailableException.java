package ar.edu.um.turnos.catalogo;

/**
 * El servicio de catalogo no respondio, respondio 5xx o todavia no tiene datos (503).
 * Sin catalogo no se puede validar informacion vigente: no se inicia ninguna operacion nueva.
 */
public class CatalogoUnavailableException extends RuntimeException {

    public CatalogoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
