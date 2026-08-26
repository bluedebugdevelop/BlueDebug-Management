package com.bluedebug.gestion.comun;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * La caché en memoria del panel.
 *
 * Es corta a propósito. Un minuto basta para que recargar la pantalla no dispare
 * otra ronda de consultas a las bases de datos de producción, y es lo bastante
 * poco como para que nadie tome una decisión mirando un número viejo. Después de
 * cada acción se vacía entera de todas formas ({@code ResumenesCacheados.olvidar}),
 * así que lo que uno acaba de hacer se ve al instante.
 *
 * En memoria y no en Redis porque el panel es un solo contenedor con un solo
 * usuario: montar un servidor de caché aparte para esto sería más pieza que
 * problema.
 */
@Configuration
public class ConfiguracionCache {

    @Bean
    public CacheManager cacheManager(@Value("${bluedebug.cache.segundos:60}") long segundos) {
        CaffeineCacheManager gestor = new CaffeineCacheManager("resumenes", "ingresos", "stripe");
        gestor.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(segundos, TimeUnit.SECONDS)
                .maximumSize(200));
        // Las consultas de ingresos devuelven Optional y a veces vienen vacías; sin
        // esto, Spring se niega a guardar el null y cada carga volvería a llamar a
        // Stripe para volver a no encontrar nada.
        gestor.setAllowNullValues(true);
        return gestor;
    }
}
