package com.bluedebug.gestion.seguridad;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Entrar, saber quién soy y salir.
 *
 * El flujo completo, que conviene tener en la cabeza al leer esto:
 *
 *   1. El front pide {@code GET /api/auth/config} y saca el client id de Google.
 *   2. Pinta el botón de Google. La persona lo pulsa y Google le devuelve al
 *      navegador un {@code id_token} firmado.
 *   3. El front lo manda a {@code POST /api/auth/google}. Aquí se comprueba la
 *      firma, el destinatario y la lista blanca, y si todo cuadra se devuelve una
 *      cookie de sesión propia.
 *   4. A partir de ahí, el token de Google ya no se usa nunca más.
 */
@RestController
@RequestMapping("/api/auth")
public class ControladorAutenticacion {

    private final VerificadorGoogle verificador;
    private final ServicioSesion sesiones;
    private final PropiedadesSeguridad propiedades;

    public ControladorAutenticacion(VerificadorGoogle verificador,
                                    ServicioSesion sesiones,
                                    PropiedadesSeguridad propiedades) {
        this.verificador = verificador;
        this.sesiones = sesiones;
        this.propiedades = propiedades;
    }

    /**
     * Lo que el front necesita para pintar la pantalla de acceso.
     *
     * Aquí NO va la lista blanca. Es tentador mandarla para poder decir «esta
     * cuenta no está autorizada» antes de llamar a Google, pero eso publicaría
     * los correos de los administradores a quien abra la página de login.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "googleClientId", propiedades.googleConfigurado() ? propiedades.googleClientId() : "",
                "configurado", propiedades.googleConfigurado());
    }

    public record PeticionGoogle(String credential) {}

    @PostMapping("/google")
    public ResponseEntity<Administrador> entrar(@RequestBody PeticionGoogle peticion) {
        Administrador admin = verificador.verificar(peticion.credential());
        ResponseCookie cookie = sesiones.abrirSesion(admin);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(admin);
    }

    /**
     * Quién soy. El front la llama al arrancar para saber si ya hay sesión.
     *
     * Va sin autenticación obligatoria a propósito: si no hay sesión, la respuesta
     * correcta es «no hay nadie», no un 401 que el interceptor confundiría con una
     * sesión caducada y convertiría en una redirección.
     */
    @GetMapping("/sesion")
    public ResponseEntity<Map<String, Object>> sesion(@AuthenticationPrincipal Administrador admin) {
        if (admin == null) {
            return ResponseEntity.ok(Map.of("autenticado", false));
        }
        return ResponseEntity.ok(Map.of("autenticado", true, "admin", admin));
    }

    @PostMapping("/salir")
    public ResponseEntity<Map<String, Object>> salir() {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sesiones.cerrarSesion().toString())
                .body(Map.of("ok", true));
    }
}
