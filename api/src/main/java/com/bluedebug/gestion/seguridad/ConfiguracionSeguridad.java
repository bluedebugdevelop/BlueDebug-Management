package com.bluedebug.gestion.seguridad;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Quién puede llamar a qué.
 *
 * La regla es corta: {@code /api/auth/**} es público —hay que poder pedir el
 * client id y entrar— y TODO lo demás de {@code /api} exige sesión. Lo que no
 * empieza por /api es el Angular ya compilado, que se sirve tal cual: son
 * ficheros estáticos sin un solo dato dentro.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(PropiedadesSeguridad.class)
public class ConfiguracionSeguridad {

    private final PropiedadesSeguridad propiedades;
    private final FiltroSesion filtroSesion;
    private final ObjectMapper json;

    public ConfiguracionSeguridad(PropiedadesSeguridad propiedades, FiltroSesion filtroSesion, ObjectMapper json) {
        this.propiedades = propiedades;
        this.filtroSesion = filtroSesion;
        this.json = json;
    }

    @Bean
    public SecurityFilterChain cadena(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(origenes()))
                // Sin CSRF porque no hay formularios de servidor ni sesión de servlet:
                // la API solo acepta JSON y la cookie es SameSite=Lax, que ya corta el
                // envío desde otro sitio. Si algún día se añade un formulario HTML de
                // verdad, esto hay que volver a encenderlo.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reglas -> reglas
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/api/salud").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((peticion, respuesta, fallo) ->
                                escribir(respuesta, HttpStatus.UNAUTHORIZED, "Hay que iniciar sesión"))
                        .accessDeniedHandler((peticion, respuesta, fallo) ->
                                escribir(respuesta, HttpStatus.FORBIDDEN, "Sin permiso")))
                .addFilterBefore(filtroSesion, UsernamePasswordAuthenticationFilter.class)
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .contentTypeOptions(c -> {})
                        .referrerPolicy(r -> r.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy.NO_REFERRER)));

        return http.build();
    }

    /**
     * En producción el Angular lo sirve este mismo servidor, así que no hay
     * peticiones cruzadas y CORS sobra. Hace falta solo en desarrollo, cuando el
     * front va en el 4200 y la API en el 8080; por eso la lista de orígenes es
     * configurable y viene vacía por defecto.
     */
    private CorsConfigurationSource origenes() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> permitidos = propiedades.origenesWeb() == null ? List.of() : propiedades.origenesWeb();
        config.setAllowedOrigins(permitidos);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept"));
        // Sin esto el navegador no manda la cookie de sesión en desarrollo.
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource fuente = new UrlBasedCorsConfigurationSource();
        fuente.registerCorsConfiguration("/api/**", config);
        return fuente;
    }

    private void escribir(jakarta.servlet.http.HttpServletResponse respuesta, HttpStatus estado, String mensaje)
            throws java.io.IOException {
        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        json.writeValue(respuesta.getWriter(), Map.of("error", mensaje, "estado", estado.value()));
    }
}
