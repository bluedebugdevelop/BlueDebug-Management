package com.bluedebug.gestion.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Que la puerta esté cerrada.
 *
 * Es la prueba que más importa de todo el proyecto: al otro lado de esta API
 * están las cuentas de VBStats, las fichas del club y la facturación. Un cambio
 * en la configuración de seguridad que deje un endpoint abierto no da ningún
 * error visible —simplemente, cualquiera puede leerlo— así que tiene que saltar
 * aquí.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeguridadTest {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("sin sesión no se lee el panel")
    void panelExigeSesion() throws Exception {
        mvc.perform(get("/api/panel/resumen"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin sesión no se listan los usuarios de ninguna app")
    void usuariosExigeSesion() throws Exception {
        mvc.perform(get("/api/apps/vbstats/usuarios"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/panel/usuarios"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sin sesión no se ejecuta ninguna acción")
    void accionesExigenSesion() throws Exception {
        mvc.perform(post("/api/apps/vbstats/acciones/enviar-notificacion")
                        .contentType("application/json")
                        .content("{\"titulo\":\"hola\",\"cuerpo\":\"prueba\",\"audiencia\":\"all\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("una cookie inventada no vale como sesión")
    void cookieFalsaNoEntra() throws Exception {
        mvc.perform(get("/api/panel/resumen")
                        .cookie(new jakarta.servlet.http.Cookie(ServicioSesion.COOKIE, "esto.no.esta.firmado")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("la salud se puede consultar sin sesión, y no cuenta nada de más")
    void saludEsPublica() throws Exception {
        mvc.perform(get("/api/salud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                // Ni nombres de apps, ni cifras de negocio, ni credenciales.
                .andExpect(jsonPath("$.apps").isNumber());
    }

    @Test
    @DisplayName("la configuración de acceso es pública pero no publica la lista blanca")
    void configuracionNoFiltraCorreos() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permitidos").doesNotExist())
                .andExpect(jsonPath("$.configurado").exists());
    }

    @Test
    @DisplayName("preguntar por la sesión sin tenerla responde «no», no un error")
    void sesionVaciaNoEsError() throws Exception {
        // Tiene que ser 200: el front la llama al arrancar y un 401 aquí lo
        // interpretaría como sesión caducada, entrando en un bucle de redirección.
        mvc.perform(get("/api/auth/sesion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autenticado").value(false));
    }
}
