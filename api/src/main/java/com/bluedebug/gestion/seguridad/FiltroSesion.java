package com.bluedebug.gestion.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Lee la cookie de sesión en cada petición y deja dicho quién llama.
 *
 * Solo hay un rol, ADMIN, y no hace falta más: al panel entra la lista blanca o
 * no entra nadie. Si algún día hay que dar acceso de solo lectura a alguien, el
 * sitio donde se decide eso es aquí, mirando un claim del token.
 */
@Component
public class FiltroSesion extends OncePerRequestFilter {

    private final ServicioSesion sesiones;

    public FiltroSesion(ServicioSesion sesiones) {
        this.sesiones = sesiones;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest peticion,
                                    @NonNull HttpServletResponse respuesta,
                                    @NonNull FilterChain cadena) throws ServletException, IOException {

        cookie(peticion)
                .flatMap(sesiones::leer)
                .ifPresent(admin -> {
                    var autenticacion = new UsernamePasswordAuthenticationToken(
                            admin, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                    SecurityContextHolder.getContext().setAuthentication(autenticacion);
                });

        cadena.doFilter(peticion, respuesta);
    }

    private Optional<String> cookie(HttpServletRequest peticion) {
        Cookie[] galletas = peticion.getCookies();
        if (galletas == null) {
            return Optional.empty();
        }
        for (Cookie c : galletas) {
            if (ServicioSesion.COOKIE.equals(c.getName())) {
                return Optional.ofNullable(c.getValue());
            }
        }
        return Optional.empty();
    }
}
