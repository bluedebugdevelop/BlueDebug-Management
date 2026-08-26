package com.bluedebug.gestion.comun;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Convierte las excepciones en respuestas JSON con la misma forma siempre, para
 * que el front tenga un solo sitio del que sacar el mensaje de error.
 *
 * Lo importante está en el último método: cualquier fallo inesperado se registra
 * ENTERO en el log del servidor y se devuelve GENÉRICO al navegador. Los
 * mensajes de excepción de aquí dentro llevan cadenas de conexión, nombres de
 * tabla y trozos de credenciales; enseñarlos al cliente es regalarle a quien
 * pruebe suerte el mapa de la casa.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(NoEncontrado.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(NoEncontrado e) {
        return respuesta(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PeticionInvalida.class)
    public ResponseEntity<Map<String, Object>> peticionInvalida(PeticionInvalida e) {
        return respuesta(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AccesoDenegado.class)
    public ResponseEntity<Map<String, Object>> accesoDenegado(AccesoDenegado e) {
        return respuesta(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> imprevisto(Exception e) {
        log.error("Fallo no controlado", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR,
                "Algo ha fallado en el servidor. Está en el log con la traza completa.");
    }

    private ResponseEntity<Map<String, Object>> respuesta(HttpStatus estado, String mensaje) {
        return ResponseEntity.status(estado).body(Map.of(
                "error", mensaje,
                "estado", estado.value(),
                "momento", Instant.now().toString()
        ));
    }
}
