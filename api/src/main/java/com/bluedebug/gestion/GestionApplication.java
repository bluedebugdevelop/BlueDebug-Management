package com.bluedebug.gestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;

/**
 * BlueDebug Management: el panel desde el que se administran todas las
 * aplicaciones de la casa.
 *
 * La idea de fondo es que esta aplicación no sabe nada concreto de ninguna app.
 * Sabe que existe una interfaz {@code ConectorApp} y que hay unos cuantos beans
 * que la implementan; todo lo que se ve en pantalla —el menú, las fichas de
 * usuarios, las gráficas, los botones de acción— se construye a partir de lo que
 * esos conectores declaran. Añadir una app nueva es escribir un conector y nada
 * más: ni tocar el router, ni el menú, ni los controladores.
 *
 * Las dos autoconfiguraciones que se excluyen tienen su motivo:
 *
 *   · DataSource — aquí no hay «una» base de datos: cada conector monta la suya
 *     (VBStats trae un MySQL propio) y algunos no usan ninguna (CVO va contra
 *     Firestore). Si se dejara puesta, el arranque fallaría pidiendo una url que
 *     no existe.
 *   · UserDetailsService — Spring Security, al no encontrar usuarios definidos,
 *     se inventa uno con una contraseña aleatoria y la escribe en el log de
 *     arranque. Aquí no se entra con usuario y contraseña sino con Google, así
 *     que ese usuario no sirve para nada; lo único que hace es dejar en el log
 *     una credencial que parece importante y sembrar la duda de si hay una
 *     puerta trasera abierta. No la hay, y quitando esto tampoco lo parece.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
})
@EnableCaching
public class GestionApplication {

    public static void main(String[] args) {
        SpringApplication.run(GestionApplication.class, args);
    }
}
