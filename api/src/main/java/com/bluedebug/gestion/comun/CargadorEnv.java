package com.bluedebug.gestion.comun;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lee un fichero `.env` al arrancar, para desarrollo local.
 *
 * En Railway las variables se ponen en su panel y esto no hace nada; en local, en
 * cambio, la alternativa es exportar diez variables a mano en cada terminal o
 * configurarlas en el IDE, y ese es justo el tipo de fricción que acaba con
 * alguien pegando una clave de producción en `application.yml` «un momento, para
 * probar». Mejor darle un sitio a esas claves que .gitignore ya excluye.
 *
 * Se registra por debajo del entorno del sistema a propósito: una variable de
 * verdad SIEMPRE gana al fichero. Si no fuera así, un `.env` viejo olvidado en la
 * carpeta pisaría la configuración de producción sin que nadie se enterase.
 */
public class CargadorEnv implements EnvironmentPostProcessor {

    /** Se busca en los dos sitios razonables según desde dónde se lance. */
    private static final List<String> CANDIDATOS = List.of(".env", "api/.env", "../.env");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment entorno, SpringApplication aplicacion) {
        for (String candidato : CANDIDATOS) {
            Path fichero = Path.of(candidato);
            if (!Files.isRegularFile(fichero)) {
                continue;
            }

            Map<String, Object> valores = leer(fichero);
            if (!valores.isEmpty()) {
                // addLast: el último de la lista es el que menos manda. Las variables
                // del sistema y de la línea de órdenes van antes y ganan.
                entorno.getPropertySources().addLast(new MapPropertySource("fichero-env", valores));
                System.out.println("Configuración local leída de " + fichero.toAbsolutePath());
            }
            return;
        }
    }

    private Map<String, Object> leer(Path fichero) {
        Map<String, Object> valores = new HashMap<>();
        try {
            for (String linea : Files.readAllLines(fichero, StandardCharsets.UTF_8)) {
                String limpia = linea.trim();
                if (limpia.isEmpty() || limpia.startsWith("#")) {
                    continue;
                }

                int igual = limpia.indexOf('=');
                if (igual <= 0) {
                    continue;
                }

                String clave = limpia.substring(0, igual).trim();
                String valor = limpia.substring(igual + 1).trim();

                // Las comillas son de quien escribe el fichero, no del valor: una clave
                // entrecomillada intentaría conectarse con las comillas dentro.
                if (valor.length() >= 2
                        && ((valor.startsWith("\"") && valor.endsWith("\""))
                            || (valor.startsWith("'") && valor.endsWith("'")))) {
                    valor = valor.substring(1, valor.length() - 1);
                }

                if (!valor.isEmpty()) {
                    valores.put(clave, valor);
                }
            }
        } catch (IOException e) {
            System.err.println("No se pudo leer " + fichero + ": " + e.getMessage());
        }
        return valores;
    }
}
