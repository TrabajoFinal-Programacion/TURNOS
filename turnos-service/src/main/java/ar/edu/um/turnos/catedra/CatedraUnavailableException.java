package ar.edu.um.turnos.catedra;

/**
 * La catedra no respondio (timeout, conexion) o respondio 5xx.
 * Importante: un timeout NO prueba que la operacion haya fallado (contrato 18.4).
 */
public class CatedraUnavailableException extends RuntimeException {

    public CatedraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
