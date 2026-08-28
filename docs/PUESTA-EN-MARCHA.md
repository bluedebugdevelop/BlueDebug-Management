# Puesta en marcha

De un repositorio recién clonado a un panel desplegado y operativo. Seis
credenciales, dos entornos y una comprobación después de cada paso, para que
cuando algo falle sepas exactamente cuál fue el último punto en el que todo iba
bien.

Cada paso acaba con una **comprobación**. Hazla antes de pasar al siguiente: es lo
que convierte un «no funciona» al final de todo en un «falla en el paso 3», que es
un problema mucho más pequeño.

---

## 00 · Antes de empezar

Vas a moverte por cinco consolas. Ábrelas ahora en pestañas separadas:

- [Google Cloud → Credenciales](https://console.cloud.google.com/apis/credentials) — el acceso al panel
- [Railway](https://railway.app/dashboard) — la base de datos de VBStats, y donde vivirá el panel
- [Stripe → Claves de API](https://dashboard.stripe.com/apikeys) — los ingresos
- [Firebase](https://console.firebase.google.com/) — dos proyectos: el de VBStats y `cv-oviedo`
- [Render](https://dashboard.render.com/) — el backend de VBStats, para copiar una credencial que ya existe

Y una terminal. Las órdenes están en dos sabores, **Git Bash** y **PowerShell**;
las dos producen exactamente el mismo resultado.

---

# Parte 1 · Reunir las credenciales

## 01 · Acceso con Google — **imprescindible**

El client id ya existe:

```
483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
```

Falta autorizar *desde dónde* se usa y *quién* puede iniciar sesión. Son dos
pantallas distintas y las dos hacen falta.

### a) Autorizar los orígenes

1. Abre [console.cloud.google.com/apis/credentials](https://console.cloud.google.com/apis/credentials).
2. Arriba a la izquierda, elige el proyecto que contiene ese client id.
3. En **ID de clientes de OAuth 2.0**, haz clic en el nombre de tu cliente web.
4. En **Orígenes autorizados de JavaScript**, añade con *+ Añadir URI*:
   - `http://localhost:4200`
   - `http://localhost:8080`
5. **Guardar**. El dominio de Railway se añade en el paso 08, cuando exista.

> **Sin barra final y con el puerto.** Un origen es esquema + dominio + puerto y
> nada más. `http://localhost:8080/` con barra, o `localhost:8080` sin esquema, no
> valen. Y **no hace falta URI de redirección**: el panel usa Google Identity
> Services, que devuelve el token al navegador sin redirigir.

### b) Autorizar a la persona

Este es el paso que más se olvida y el que peor síntoma tiene.

1. Ve a [la pantalla de consentimiento de OAuth](https://console.cloud.google.com/apis/credentials/consent).
   En consolas recientes aparece como **Google Auth Platform → Público**: es la
   misma pantalla con otro nombre.
2. Mira el **estado de publicación**. Si dice **«En pruebas»** (*Testing*), solo
   entran las cuentas de la lista de usuarios de prueba.
3. En **Usuarios de prueba** → *+ Add users*, añade `bluedebug.develop@gmail.com`
   y cualquier otro correo que vayas a autorizar.
4. Guardar.

Publicar la aplicación es la alternativa, pero para un panel interno no compensa:
te mete en el circuito de verificación de Google en cuanto añadas permisos
sensibles.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_GOOGLE_CLIENT_ID` |
| **Dónde** | Local y Railway, el mismo valor |
| **¿Secreto?** | No. Viaja al navegador; está pensado para ser público |

---

## 02 · El secreto de sesión — **imprescindible**

Necesitas **dos valores distintos**: uno para local y otro para producción. Si el
de local se filtrara, con él se pueden fabricar sesiones válidas de administrador
en el panel desplegado.

**Git Bash**

```bash
openssl rand -base64 48
```

**PowerShell**

```powershell
$b = New-Object byte[] 48
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
[Convert]::ToBase64String($b)
```

Ejecútalo dos veces y guarda las dos cadenas. Salen de 64 caracteres, por encima
del mínimo de 32 que exige la firma.

> **Si no lo pones**, el panel arranca igual pero genera uno al azar en cada
> arranque, y tu sesión se cae en cada despliegue.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_SECRETO` |
| **Dónde** | Un valor en `api/.env`, otro distinto en Railway |
| **¿Secreto?** | **Sí, de los importantes.** Quien lo tenga se fabrica una sesión de admin sin pasar por Google |

---

## 03 · La base de datos de VBStats

1. En [Railway](https://railway.app/dashboard), abre el proyecto donde vive el
   **MySQL de VBStats**.
2. Haz clic en el servicio **MySQL** (el de la base de datos, no el backend).
3. Pestaña **Variables**.
4. Copia **`MYSQL_PUBLIC_URL`** entera.

La forma que tiene:

```
mysql://root:TU_CLAVE@interchange.proxy.rlwy.net:41234/railway
```

> **Pública, no privada.** `MYSQL_URL` solo se ve desde dentro de su propio
> proyecto de Railway; `MYSQL_PUBLIC_URL` es la que funciona desde fuera. Como el
> panel irá en otro proyecto, necesitas la pública. Coger la privada da un «la
> base de datos no responde» que parece un problema de credenciales y no lo es.

> **No la «limpies».** Si la contraseña tiene símbolos vienen codificados en la
> URL (`%40` por una arroba, etc.). El panel los descodifica solo; reescribirla a
> mano la rompe.

Con esto se encienden las cuentas y sus planes, las altas por día, los partidos,
los dispositivos, el historial de avisos y el borrado de cuentas.

> **Sobre tocar producción.** El panel abre tres conexiones **de solo lectura**
> para sus consultas y una cuarta, aparte, para lo único que escribe: la fila del
> historial al mandar un aviso y los borrados que pidas. El backend de VBStats usa
> un pool de diez contra el mismo servidor, así que no le quitas sitio.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_VBSTATS_URL` |
| **¿Secreto?** | **Sí.** Lleva usuario y contraseña de producción |
| **Sin ella** | VBStats sale apagada; el resto del panel funciona |

---

## 04 · Stripe — opcional

1. Abre [dashboard.stripe.com/apikeys](https://dashboard.stripe.com/apikeys).
2. **Desactiva el modo de pruebas** (interruptor arriba a la derecha). Es el error
   más caro: con *Test mode* puesto, la clave solo ve cobros de prueba y el panel
   enseñará cero euros sin dar ningún error.
3. **+ Crear clave restringida**.
4. Nómbrala `BlueDebug Management (lectura)`.
5. De toda la lista, deja todo en **None** y cambia **uno solo**:
   **Charges → Read**.
6. **Crear clave** → **Revelar** → copiar.

Forma: `rk_live_51N...`

> **Solo se enseña una vez.** Si la pierdes se crea otra, pero pégala antes de
> cerrar la pestaña.

**Por qué restringida y solo con `Charges: Read`**: el panel únicamente lista
cobros. No crea suscripciones, no reembolsa, no toca clientes.

> **Lo que estas cifras nunca incluirán.** Las compras dentro de la app de iPhone
> las cobra Apple y no pasan por Stripe. El panel **sí** cuenta a esa gente como
> suscriptores —la base de datos guarda su `apple_original_transaction_id`— pero su
> importe no aparece. Ese dinero está en App Store Connect, que sería otra
> integración entera.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_VBSTATS_STRIPE` |
| **Sin ella** | No aparece la pestaña «Ingresos»; el resto de VBStats funciona |

---

## 05 · Firebase de VBStats — opcional

**Camino corto: copiar la que ya existe.** El backend de VBStats ya manda
notificaciones, así que ya la tiene. Reusarla garantiza que es el proyecto
correcto.

1. En [Render](https://dashboard.render.com/), abre el servicio del backend de VBStats.
2. Pestaña **Environment**.
3. Revela y copia `FIREBASE_SERVICE_ACCOUNT_BASE64`.

**Camino largo: generarla de nuevo.**

1. [Consola de Firebase](https://console.firebase.google.com/) → proyecto de VBStats.
2. Rueda dentada → **Configuración del proyecto** → pestaña **Cuentas de servicio**.
3. **Generar nueva clave privada** → **Generar clave**. Se descarga un `.json`.
4. Codifícalo:

**Git Bash**

```bash
base64 -w0 ruta/al/fichero.json
```

**PowerShell**

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\ruta\al\fichero.json"))
```

> **Comprobación del base64.** La cadena tiene que ser **una sola línea** de unos
> 3.100 caracteres y **empezar por `ewo` o por `eyJ`**. No es casualidad: son las
> dos formas en que empieza un JSON codificado en base64 — `ewo` si el fichero
> viene indentado (`{` y salto de línea, que es como los descarga Google) y `eyJ`
> si viene en una sola línea (`{"`). Si empieza por otra cosa, has codificado algo
> que no es el JSON.

> **Borra el JSON descargado** en cuanto lo pegues. Da acceso completo al proyecto
> de Firebase y es el despiste más habitual con las cuentas de servicio.

> **Cuidado al probar los envíos.** No hay modo de pruebas. Estés en local o en
> producción, los tokens salen de la base de datos real y el aviso llega a los
> móviles de tus usuarios.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_VBSTATS_FIREBASE` |
| **¿Secreto?** | **Sí, de los máximos** |
| **Sin ella** | No se pueden mandar notificaciones; los datos y gráficas funcionan |

---

## 06 · Firebase del CV Oviedo

Aquí no hay nada que copiar: la app del club habla con Firestore directamente
desde el móvil, sin servidor, así que el panel es el primero que necesita una
cuenta de servicio. El proyecto es `cv-oviedo`.

1. Abre [la pestaña de cuentas de servicio de cv-oviedo](https://console.firebase.google.com/project/cv-oviedo/settings/serviceaccounts/adminsdk).
2. Comprueba arriba que el proyecto es **cv-oviedo**.
3. **Generar nueva clave privada** → **Generar clave**.
4. Se descarga `cv-oviedo-firebase-adminsdk-xxxxx.json`.
5. Codifícalo con el comando del paso 05.
6. **Borra el JSON.**

No hay que asignar permisos a mano: la cuenta de servicio por defecto ya lee
Firestore y lista las cuentas de Authentication, que es lo único que el panel
necesita salvo cuando das una baja.

Con esto se encienden las fichas con sus roles y equipos, las plantillas, el
**último acceso real** de cada persona (sale de Firebase Auth, porque Firestore no
lo guarda) y los avisos por Expo Push.

> **Por qué aparecen «registrados sin ficha».** En el club, tener cuenta en
> Firebase no es ser del club: lo que da acceso es tener ficha en `usuarios/` con
> `activo: true`, y esas las crea un administrador. El panel cruza las dos listas y
> te enseña cuánta gente se registró y se quedó esperando. Si ese número sube, hay
> alguien sin atender.

| | |
|---|---|
| **Variable** | `BLUEDEBUG_CVO_FIREBASE` |
| **¿Secreto?** | **Sí, de los máximos** |
| **Sin ella** | CV Oviedo sale apagada |

---

# Parte 2 · Montarlo

## 07 · Probar en local

Abre `api/.env` —ya existe y está excluido del repositorio— y déjalo así:

```bash
# --- acceso ---
BLUEDEBUG_GOOGLE_CLIENT_ID=483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
BLUEDEBUG_SECRETO=<el secreto de LOCAL del paso 02>
BLUEDEBUG_PERMITIDOS=bluedebug.develop@gmail.com
BLUEDEBUG_COOKIE_SEGURA=false
BLUEDEBUG_ORIGENES_WEB=http://localhost:4200

# --- VBStats ---
BLUEDEBUG_VBSTATS_URL=<MYSQL_PUBLIC_URL del paso 03>
BLUEDEBUG_VBSTATS_STRIPE=<rk_live_... del paso 04>
BLUEDEBUG_VBSTATS_FIREBASE=<base64 del paso 05>

# --- CV Oviedo ---
BLUEDEBUG_CVO_FIREBASE=<base64 del paso 06>
```

> **En local, `COOKIE_SEGURA` va en `false`.** Sobre `http` el navegador descarta
> cualquier cookie marcada como `Secure`: entras con Google, el panel te devuelve
> al acceso, y vuelta a empezar sin ningún mensaje de error. En producción es al
> revés.

Arranca:

```bash
cd api
./mvnw spring-boot:run
```

Busca esta línea en el arranque. Si no aparece, el fichero no se está leyendo:

```
Configuración local leída de C:\Projects\BlueDebugManagement\api\.env
```

**Comprobación**, desde otra terminal:

```bash
curl http://localhost:8080/api/salud
```

```json
{"ok":true,"apps":2,"disponibles":2}
```

**`disponibles` es el número que importa.** Si es menor que `apps`, alguna
credencial no vale: entra en el panel y abre **Ajustes**, donde cada app apagada
dice exactamente qué le falta.

Luego abre `localhost:8080`, entra con Google y recorre Panel, Usuarios, cada
aplicación y Actividad.

---

## 08 · Desplegar en Railway

### a) Crear el servicio

1. **New Project** → **Deploy from GitHub repo**.
2. Elige **bluedebugdevelop/BlueDebug-Management**. Si no aparece, pulsa
   *Configure GitHub App* y da acceso a la organización.
3. Railway lee `railway.json`, ve el builder `DOCKERFILE` y construye solo.
   **No configures comando de arranque ni puerto**: el `Dockerfile` lo resuelve y
   la aplicación lee el `PORT` que Railway inyecta.

El primer build tarda varios minutos: compila Angular con Node y luego el backend
con Maven. Los siguientes reutilizan capas y van más rápido.

### b) Las variables

*Variables* → **Raw Editor**, y pega:

```bash
BLUEDEBUG_GOOGLE_CLIENT_ID=483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
BLUEDEBUG_SECRETO=<el secreto de PRODUCCIÓN, distinto del de local>
BLUEDEBUG_PERMITIDOS=bluedebug.develop@gmail.com
BLUEDEBUG_COOKIE_SEGURA=true
BLUEDEBUG_HORAS_SESION=12
BLUEDEBUG_VBSTATS_URL=<MYSQL_PUBLIC_URL>
BLUEDEBUG_VBSTATS_STRIPE=<rk_live_...>
BLUEDEBUG_VBSTATS_FIREBASE=<base64 de VBStats>
BLUEDEBUG_CVO_FIREBASE=<base64 de cv-oviedo>
```

Dos diferencias con local, las dos deliberadas:

- **`BLUEDEBUG_COOKIE_SEGURA=true`** — en producción hay `https`, y sin esto la
  cookie viajaría sin la marca `Secure`.
- **No pongas `BLUEDEBUG_ORIGENES_WEB`** — el mismo servidor sirve el Angular y la
  API, así que no hay petición cruzada y CORS sobra.

### c) El dominio

1. **Settings** → **Networking** → **Generate Domain**.
2. Copia el dominio que te dé.
3. Vuelve a Google Cloud (paso 01) y añádelo a **Orígenes autorizados de
   JavaScript**, con `https://` y sin barra final.
4. Guardar. Google puede tardar unos minutos.

**Comprobación:**

```bash
curl https://TU-DOMINIO.up.railway.app/api/salud
```

Railway usa esa misma ruta como healthcheck (`railway.json`), así que si el
despliegue se queda en *unhealthy* el fallo está en el arranque: mira los **Deploy
Logs**.

---

## 09 · Comprobación final

1. `/api/salud` responde `disponibles: 2` en el dominio de Railway.
2. El dominio abre la pantalla de acceso con el botón **Continuar con Google**.
3. Entras con `bluedebug.develop@gmail.com` y llegas al panel.
4. En el menú lateral, las dos apps tienen el punto **verde**.
5. En **VBStats**: Usuarios lista cuentas reales e Ingresos enseña cifras.
6. En **CV Oviedo**: salen las fichas, y en Detalle los equipos.
7. Cierras sesión y te devuelve al acceso.

> **Lo que NO conviene «probar»:** mandar una notificación. Le suena el móvil a
> toda la audiencia que elijas y no se puede deshacer. Si necesitas comprobarlo, en
> CV Oviedo puedes dirigirlo a un solo equipo, que es el destinatario más estrecho
> que existe en el panel.

---

# Referencia · todas las variables

| Variable | ¿Obligatoria? | De dónde sale | Qué pasa si falta |
|---|---|---|---|
| `BLUEDEBUG_GOOGLE_CLIENT_ID` | **Sí** | Google Cloud → Credenciales | No se puede entrar |
| `BLUEDEBUG_SECRETO` | **Sí** | `openssl rand -base64 48` | Se genera al azar; la sesión se cae en cada reinicio |
| `BLUEDEBUG_PERMITIDOS` | **Sí** | Lo escribes tú, correos por comas | En blanco, no entra nadie |
| `BLUEDEBUG_COOKIE_SEGURA` | **Sí** | `false` local, `true` Railway | Mal puesta: bucle de acceso sin mensaje |
| `BLUEDEBUG_ORIGENES_WEB` | No | Solo local: `http://localhost:4200` | Nada. En producción debe ir vacía |
| `BLUEDEBUG_HORAS_SESION` | No | Lo eliges tú; por defecto 12 | Nada |
| `BLUEDEBUG_VBSTATS_URL` | No | Railway → MySQL → `MYSQL_PUBLIC_URL` | VBStats sale apagada |
| `BLUEDEBUG_VBSTATS_STRIPE` | No | Stripe → restringida, `Charges: Read` | Sin pestaña de ingresos |
| `BLUEDEBUG_VBSTATS_FIREBASE` | No | Render → `FIREBASE_SERVICE_ACCOUNT_BASE64` | No se pueden mandar notificaciones |
| `BLUEDEBUG_CVO_FIREBASE` | No | Firebase → cv-oviedo → Cuentas de servicio | CV Oviedo sale apagada |
| `BLUEDEBUG_CACHE_SEGUNDOS` | No | Lo eliges tú; por defecto 60 | Nada |

---

# Mantenimiento

**Autorizar a otra persona.** Añade su correo separado por comas:

```bash
BLUEDEBUG_PERMITIDOS=bluedebug.develop@gmail.com,otra@ejemplo.com
```

Y añádela también como **usuario de prueba** en la pantalla de consentimiento de
Google (paso 01b), o el botón no le hará nada. La lista se comprueba en **cada
petición**: quitar un correo y reiniciar echa a esa persona al momento.

**Echar a todo el mundo ahora mismo.** Cambia `BLUEDEBUG_SECRETO` y redespliega:
todas las sesiones dejan de valer al instante.

**Actualizar el panel.** Cada `git push` a `main` dispara un despliegue.

**Ver qué se ha hecho.** La pantalla de Actividad vive en memoria y se vacía al
reiniciar. Lo que perdura está en los **Deploy Logs** de Railway: busca las líneas
que empiezan por `AUDITORÍA:`.

**A quién se atribuyen los avisos de VBStats.** El panel los apunta en el historial
que la propia app enseña, y eso exige un id de usuario de VBStats: busca tu correo
entre sus cuentas y, si no lo encuentra, usa el primer superadmin. Si
`bluedebug.develop@gmail.com` tiene cuenta y está en su `SUPERADMIN_EMAILS`, los
avisos salen a tu nombre. Si no hubiera ningún superadmin, el aviso se manda igual
pero sin registrar, y el resultado en pantalla lo dice.

**Añadir una tercera aplicación.** Una clase en `conectores/` anotada con
`@Component` que implemente `ConectorApp`. Aparece sola en el menú, sin tocar
Angular. Lo detalla el [README](../README.md).

---

# Problemas típicos

**El botón de Google aparece, lo pulso y no pasa nada.**
La pantalla de consentimiento está «En pruebas» y tu correo no está en los usuarios
de prueba → paso 01b. Es, con diferencia, el fallo más común.

**«Esta cuenta no tiene acceso al panel».**
Google te autenticó bien; es la lista blanca la que te rechaza. Revisa
`BLUEDEBUG_PERMITIDOS`. Las mayúsculas y los espacios no importan —el panel los
normaliza— pero una cuenta distinta sí.

**Entro, me devuelve al acceso, y vuelta a empezar.**
La cookie no se guarda. En local es casi siempre `BLUEDEBUG_COOKIE_SEGURA=true`
sobre `http`; en producción, entrar por `http` en vez de `https`.

**VBStats apagada: «la base de datos no responde».**
Por este orden: que sea `MYSQL_PUBLIC_URL` y no `MYSQL_URL`; que el servicio MySQL
esté levantado; que la contraseña no se haya regenerado. Los Deploy Logs traen el
motivo real.

**Los ingresos salen a cero pero sé que hay cobros.**
Comprueba que la clave empieza por `rk_live_` y no `rk_test_`. La otra posibilidad
es que sean cobros de la App Store, que nunca aparecerán aquí.

**El primer build en Railway falla por memoria o tiempo.**
Compilar Angular y Maven en el mismo contenedor es lo más pesado del proyecto.
Reintenta: la segunda vez reutiliza capas y pasa.

**502 «Application failed to respond» y en los Deploy Logs un `SIGSEGV`.**
Si la traza menciona `netty_tcnative`, la imagen base es de Alpine. Alpine usa
musl en vez de glibc, y la biblioteca nativa de TLS que gRPC trae dentro —la que
usa el SDK de Firestore— está compilada contra glibc: al cargarla, se cae la JVM
entera. El `Dockerfile` usa `eclipse-temurin:17-jre-jammy` (Ubuntu) justo por
esto, y **no debe volver a Alpine**.

Es un fallo con muy mala pinta porque la aplicación arranca bien y muere unos
segundos después, al abrir la primera conexión TLS. Sin las credenciales de CVO
puestas eso no llega a pasar nunca, así que puede estar latente mucho tiempo y
aparecer justo el día del despliegue con todo configurado.

**502 sin ningún error en los logs, y el healthcheck marcado como fallido.**
Comprueba que `/api/salud` responde rápido. Esa ruta no debe consultar bases de
datos: si lo hace, cualquier lentitud de MySQL o de Firestore hace que Railway dé
el servicio por caído y apague el dominio entero. Las sondas las hace
`VigilanteEstado` en segundo plano precisamente para que el latido no dependa de
nadie de fuera.

**Una app sale apagada y no sé por qué.**
No adivines: entra en **Ajustes**, donde cada app apagada tiene escrito su motivo
concreto junto al nombre de la variable que le falta.
