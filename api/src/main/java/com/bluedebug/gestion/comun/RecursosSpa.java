package com.bluedebug.gestion.comun;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Sirve el Angular compilado y devuelve su {@code index.html} para las rutas del
 * panel.
 *
 * Hace falta porque el enrutado es de cliente: {@code /apps/vbstats} no es una
 * carpeta en el servidor, es un estado de la aplicación. Navegando dentro del
 * panel lo resuelve Angular sin pedir nada, pero al recargar la página o al abrir
 * un enlace pegado el navegador sí le pide esa ruta al servidor; sin esto,
 * respondería 404 y lo que parece un enlace roto es en realidad la configuración
 * que falta.
 *
 * Está escrito como un resolvedor de recursos y no como un {@code forward:} con
 * comodines en la ruta, que es lo primero que uno intenta: los patrones de Spring
 * no admiten nada después de un {@code **}, así que expresar «cualquier ruta
 * menos /api» con un patrón acaba en un error de arranque. Aquí la condición se
 * escribe en Java, que además se lee mejor.
 *
 * Lo que empieza por {@code api/} devuelve null a propósito. Los controladores se
 * resuelven antes que esto, así que solo llegan aquí las rutas de API que NO
 * existen, y para esas la respuesta correcta es un 404: un endpoint mal escrito
 * que devuelve el HTML del panel es de los errores más confusos de depurar.
 */
@Configuration
public class RecursosSpa implements WebMvcConfigurer {

    private static final Resource INDICE = new ClassPathResource("/static/index.html");

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registro) {
        registro.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String ruta, @NonNull Resource origen)
                            throws IOException {
                        Resource pedido = origen.createRelative(ruta);

                        if (pedido.exists() && pedido.isReadable()) {
                            return pedido;
                        }
                        if (ruta.startsWith("api/")) {
                            return null;
                        }
                        return INDICE.exists() ? INDICE : null;
                    }
                });
    }
}
