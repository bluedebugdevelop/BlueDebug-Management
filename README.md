# BlueDebug Management

Panel de administración de todas las aplicaciones de BlueDebug, desde un solo
sitio y con una sola cuenta.

Ahora mismo administra **VBStats** (estadísticas de voleibol: cuentas,
suscripciones, ingresos de Stripe y notificaciones push), la app del **CV
Oviedo** (fichas del club, equipos, roles y avisos) y **Co-Kitchen** (la despensa
compartida: cuentas, últimos accesos, espacios y el plan PRO). Está pensado para
que meter la cuarta y la décima no sea rehacer nada.

> **¿Montándolo por primera vez?** Ve a
> **[docs/PUESTA-EN-MARCHA.md](docs/PUESTA-EN-MARCHA.md)**: lleva paso a paso de un
> repositorio recién clonado a un panel desplegado, con de dónde sale cada
> credencial y cómo comprobar cada paso antes de seguir.

```
Spring Boot 3.4 (Java 17)  +  Angular 19  ->  un solo contenedor
```

---

## La idea: conectores

El panel **no sabe nada de ninguna aplicación concreta**. Sabe que existe una
interfaz, `ConectorApp`, y que hay unos cuantos objetos que la implementan. Todo
lo que se ve en pantalla —el menú, las pestañas, la tabla de usuarios, las
gráficas, los formularios de acciones— se construye con lo que esos conectores
declaran sobre sí mismos.

La consecuencia práctica es la que importa: **añadir una app es escribir una
clase**. Ni tocar el router de Angular, ni el menú, ni los controladores, ni la
tabla de usuarios.

```
api/src/main/java/com/bluedebug/gestion/
├── conectores/
│   ├── ConectorApp.java          <- el contrato
│   ├── RegistroConectores.java   <- los encuentra solos (Spring los inyecta)
│   ├── modelo/                   <- el lenguaje común: usuarios, métricas, series…
│   ├── vbstats/                  <- MySQL + Stripe + FCM
│   ├── cvo/                      <- Firestore + Firebase Auth + Expo Push
│   └── cokitchen/                <- Postgres de Supabase (auth.users + public)
├── panel/                        <- la vista de conjunto y los controladores REST
├── seguridad/                    <- Google, lista blanca y cookie de sesión
└── comun/                        <- errores, caché, recursos estáticos
```

Cada conector declara sus **capacidades** (`USUARIOS`, `METRICAS`, `INGRESOS`,
`ACCIONES`, `BORRAR_USUARIOS`) y el front esconde las pestañas que no estén
declaradas, en vez de enseñar secciones vacías.

### Añadir una aplicación nueva

1. Crea `conectores/loquesea/ConectorLoquesea.java`, anotado con `@Component`,
   implementando `ConectorApp`.
2. Devuelve un `DescriptorApp` con su id, nombre, color, icono y las capacidades
   que sepas cumplir.
3. Implementa solo los métodos de esas capacidades. El resto ya tiene un valor
   por defecto razonable.

Y ya está: aparece en el menú, con su pantalla y sus botones.

Dos reglas de la casa para quien escriba uno:

- **Nada de excepciones por falta de configuración.** Si faltan credenciales,
  `estado()` devuelve el motivo y los métodos devuelven vacío. Una app rota no
  puede tumbar el panel de las otras.
- **Las lecturas son de solo lectura.** Todo lo que escribe pasa por `ejecutar()`,
  que es lo único que se audita.

---

## Cómo se accede

Solo con Google, y solo las cuentas de una lista blanca del servidor. No hay
usuario y contraseña, ni registro, ni recuperación: las contraseñas son la parte
más frágil de un panel de administración, y quitarlas de en medio deja la
autenticación en manos de una cuenta de Google con su segundo factor.

El recorrido completo:

1. El navegador pide el `client_id` a `/api/auth/config` y pinta el botón de
   Google.
2. Google devuelve un `id_token` firmado.
3. El servidor comprueba **la firma** (contra las claves públicas de Google), **el
   destinatario** (que el token sea para nuestro client id y no para otra web) y
   **la lista blanca**.
4. Si todo cuadra, emite su propia sesión: un JWT firmado en una cookie
   `HttpOnly`. A partir de ahí, el token de Google no se vuelve a usar.

La cookie es `HttpOnly` a propósito: es el único sitio del navegador al que el
JavaScript de la página no llega. La lista blanca se comprueba **en cada
petición**, así que quitar un correo de la configuración y reiniciar echa fuera a
esa persona al momento.

### Configurar el acceso con Google

En la [consola de Google Cloud](https://console.cloud.google.com/apis/credentials):

1. **Credenciales → Crear credenciales → ID de cliente de OAuth → Aplicación
   web**.
2. En *Orígenes autorizados de JavaScript*, añade:
   - `http://localhost:4200` y `http://localhost:8080` (desarrollo)
   - la url del despliegue (producción)
3. Copia el *ID de cliente* en `BLUEDEBUG_GOOGLE_CLIENT_ID`.

No hace falta *URI de redirección*: el acceso va con el botón de Google
Identity Services, que devuelve el token al navegador sin redirigir.

---

## Variables de entorno

Todas en `.env.example`. Copia ese fichero a `api/.env` para desarrollo local
(`.gitignore` lo excluye) o pégalas en Railway → *Variables*.

| Variable | Para qué | Sin ella |
|---|---|---|
| `BLUEDEBUG_GOOGLE_CLIENT_ID` | El botón de acceso | No se puede entrar |
| `BLUEDEBUG_SECRETO` | Firmar la cookie de sesión | Se genera al azar: las sesiones se caen en cada reinicio |
| `BLUEDEBUG_PERMITIDOS` | Quién entra (correos por comas) | No entra nadie |
| `BLUEDEBUG_COOKIE_SEGURA` | `true` en producción, `false` en local sobre http | En local, bucle de acceso |
| `BLUEDEBUG_VBSTATS_URL` | Su MySQL (vale la url de Railway tal cual) | VBStats sale apagada |
| `BLUEDEBUG_VBSTATS_STRIPE` | Los ingresos | Sin pestaña de ingresos |
| `BLUEDEBUG_VBSTATS_FIREBASE` | Notificaciones push (FCM) | No se pueden mandar avisos |
| `BLUEDEBUG_CVO_FIREBASE` | Firestore del club | CVO sale apagada |
| `BLUEDEBUG_COKITCHEN_URL` | Su Postgres de Supabase (vale la cadena del botón «Connect») | Co-Kitchen sale apagada |
| `GEMINI_API_KEY` | Traducir a en/fr/pt lo escrito en castellano (motor **gratis**, el que se usa por defecto) | Se prueba con Anthropic, y si tampoco está, se publica solo en castellano |
| `ANTHROPIC_API_KEY` | Lo mismo, con Claude. Solo hace falta si no hay clave de Gemini | Nada, mientras haya la de Gemini |

El secreto se genera con:

```bash
openssl rand -base64 48
```

Las cuentas de servicio de Firebase van en **base64**, el mismo formato que ya
usa el backend de VBStats:

```bash
base64 -w0 cuenta-de-servicio.json
```

Una nota sobre Stripe: usa una **clave restringida de solo lectura**. El panel lee
cobros, nunca cobra.

---

## Correr en local

Hacen falta **JDK 17** (o 21) y **Node 20**. Maven lo descarga el wrapper solo.

Dos terminales, que es lo cómodo mientras se desarrolla:

```bash
cd api && ./mvnw spring-boot:run
```

```bash
cd web && npm install && npm start
```

El front queda en `http://localhost:4200` y habla con la API del 8080 a través
del proxy de `web/proxy.json`, así que el navegador ve un solo origen y la cookie
de sesión se comporta igual que en producción.

Para probarlo todo junto como en producción —un solo puerto, sin proxy—:

```bash
cd web && npm run build
cp -r web/dist/* api/src/main/resources/static/
cd api && ./mvnw -DskipTests package && java -jar target/bluedebug-management.jar
```

Las pruebas:

```bash
cd api && ./mvnw test
```

---

## Desplegar

Hay un `Dockerfile` multietapa que compila el front, lo mete dentro del jar y deja
una imagen con solo el JRE (unos 250 MB en vez de 900). Sale **un solo
contenedor** que sirve las dos mitades en el mismo puerto: por eso en producción
no hace falta configurar CORS ni dominios cruzados.

En Railway:

1. *New Project → Deploy from GitHub repo*, apuntando a este repositorio.
2. Railway detecta el `Dockerfile` solo. No hay que configurar comando de
   arranque: `PORT` se inyecta y la aplicación lo lee.
3. Pega las variables de entorno.
4. Pon la url del despliegue en los orígenes autorizados de Google.

El healthcheck va a `/api/salud`, que responde sin sesión y no cuenta nada de
más: cuántas apps hay configuradas y cuántas responden, nada de nombres ni
cifras.

---

## Lo que este panel todavía no hace

Escrito aquí para que no haya que descubrirlo por sorpresa:

- **Los ingresos de la App Store no aparecen.** Las compras dentro de la app de
  iPhone las cobra Apple y no pasan por Stripe. Se ven como suscriptores (la base
  de datos guarda su transacción de Apple), pero su importe no está en la
  facturación. Traerlo pide enchufar App Store Connect, que es otra integración
  entera.
- **La pantalla de Actividad se vacía en cada reinicio.** Los apuntes viven en la
  memoria del proceso. Lo que sí perdura es el log del servicio, donde cada
  acción queda escrita con el correo de quien la lanzó. El sitio donde enchufar
  una tabla de verdad es `RegistroAuditoria`, y no hay que tocar nada más.
- **«Accesos por día» no es exactamente eso.** Ninguna de las tres apps guarda un
  historial de accesos, solo el último de cada cuenta. Para los días recientes se
  parece bastante; hacia atrás se queda corto. Está dicho en la etiqueta de la
  propia gráfica.
- **El PRO de Co-Kitchen todavía no se puede dar**, porque Co-Kitchen todavía no
  tiene planes: no hay ninguna columna donde guardarlo. El conector lo pregunta
  cada minuto y el día que exista aparecen solos el selector de la tabla de
  usuarios y el reparto de cuentas por plan, sin desplegar nada. La columna que
  espera es exactamente esta:

  ```sql
  alter table public.profiles
    add column plan text not null default 'free'
    check (plan in ('free', 'pro'));
  ```
- **Co-Kitchen no manda avisos desde el panel.** Sus tokens de Expo están en
  `device_tokens` y el panel ya sabe hablar con Expo (`ServicioPushExpo`, hoy
  dentro de `cvo/`). Meterlo es mover ese servicio a `comun/` y declarar la
  capacidad `ACCIONES`; no se ha hecho todavía para no tocar el conector del club
  sin necesidad.
- **Las bajas del club no se pueden deshacer desde el panel.** La acción existe en
  el backend (`cambiar-alta` admite volver a activar), pero todavía no hay botón
  para reactivar en la tabla.

---

## Estructura

```
BlueDebugManagement/
├── api/           Spring Boot: conectores, seguridad, API REST
├── web/           Angular: el panel
├── Dockerfile     las dos mitades en un contenedor
└── .env.example   todas las variables, explicadas
```
