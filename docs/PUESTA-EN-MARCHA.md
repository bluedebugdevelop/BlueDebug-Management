# Puesta en marcha

De un repositorio recién clonado a un panel desplegado y funcionando.

El orden importa: primero se consiguen las credenciales, luego se prueba en local
—donde equivocarse no cuesta nada— y solo al final se despliega. Cada paso dice
cómo comprobar que ha salido bien antes de pasar al siguiente.

Cuando algo falla, salta al final: **Problemas típicos** cubre los cuatro que se
dan de verdad.

---

## Parte 1 · Las credenciales

Son seis. Solo la primera y la segunda son imprescindibles para entrar; las demás
encienden cada aplicación por separado, y el panel funciona perfectamente sin
ellas —las apps sin credenciales aparecen apagadas con su motivo escrito.

### 1.1 · Acceso con Google (imprescindible)

Ya tienes el client id creado:

```
483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
```

Falta asegurarse de dos cosas en la
[consola de Google Cloud](https://console.cloud.google.com/apis/credentials):

**a) Orígenes autorizados.** Entra en *Credenciales → tu ID de cliente OAuth* y
comprueba que en **Orígenes autorizados de JavaScript** están:

```
http://localhost:4200
http://localhost:8080
```

Y, cuando tengas el dominio de Railway, también ese (Parte 3). No hace falta URI
de redirección: el acceso va con Google Identity Services, que devuelve el token
al navegador sin redirigir.

**b) La pantalla de consentimiento.** Esto es lo que más despista, así que
míralo aunque parezca que no hace falta. En *APIs y servicios → Pantalla de
consentimiento de OAuth*:

- Si el **estado de publicación** es **«En pruebas»**, solo pueden iniciar sesión
  las cuentas que estén en la lista de **usuarios de prueba**. Añade ahí
  `bluedebug.develop@gmail.com` (y cualquier otro correo que vayas a autorizar).
- La alternativa es pulsar **Publicar aplicación**. Para un panel interno no hace
  falta, y publicar acaba pidiendo verificación de Google si algún día añades
  permisos sensibles. Con usuarios de prueba vas sobrado.

Sin esto, el botón de Google se pinta, lo pulsas, y no pasa nada.

### 1.2 · El secreto de sesión (imprescindible)

Con lo que se firma la cookie. Genera uno nuevo **para producción**, distinto del
que uses en local:

```bash
openssl rand -base64 48
```

Si falta, el panel arranca igualmente pero con un secreto aleatorio distinto en
cada reinicio, y eso echa fuera la sesión en cada despliegue.

### 1.3 · La base de datos de VBStats

Es la que enciende casi todo el conector: cuentas, planes, altas, partidos,
dispositivos y el historial de avisos.

1. Entra en el proyecto de **Railway donde vive el MySQL de VBStats**.
2. Servicio **MySQL → Variables**.
3. Copia el valor de **`MYSQL_PUBLIC_URL`** entero, tal cual, con el
   `mysql://usuario:clave@host:puerto/base`.

El panel entiende ese formato directamente y lo traduce a JDBC él solo, así que
no hay que trocearlo ni reescribirlo.

> **Si acabas desplegando el panel en el mismo proyecto de Railway que el MySQL**,
> puedes usar `MYSQL_URL` (la privada) en vez de la pública: no sale a internet y
> Railway no te cobra el tráfico. Si van en proyectos distintos —lo normal—, tiene
> que ser la pública.

### 1.4 · Stripe (los ingresos de VBStats)

En el [dashboard de Stripe](https://dashboard.stripe.com/apikeys), con el modo
**Live** activado (arriba a la derecha):

1. *Desarrolladores → Claves de API → **Crear clave restringida***.
2. Ponle de nombre `BlueDebug Management (lectura)`.
3. De toda la lista de permisos, activa **solo** este:
   - **Charges** → `Read`
4. Crear, y copia la clave (`rk_live_...`). Solo se enseña una vez.

Que sea restringida y de solo lectura no es una formalidad: el panel únicamente
lista cobros, y una clave secreta completa en un servidor más es una superficie de
riesgo que no compensa.

> **Ojo con lo que esta cifra NO incluye.** Las compras hechas dentro de la app de
> iPhone las cobra Apple y no pasan por Stripe. El panel las cuenta como
> suscriptores —la base de datos guarda su transacción de Apple— pero su importe
> no aparece en la facturación. No es un fallo: es que ese dinero está en App
> Store Connect, que sería otra integración entera.

### 1.5 · Firebase de VBStats (las notificaciones)

Aquí no hay que crear nada: **usa exactamente el mismo valor que ya tiene el
backend de VBStats**. Así te aseguras de que es el proyecto correcto y de que los
avisos salen igual que los suyos.

1. Ve al servicio del backend de VBStats en Render → **Environment**.
2. Copia el valor de **`FIREBASE_SERVICE_ACCOUNT_BASE64`**.

Si no lo tienes a mano, se regenera desde la consola de Firebase del proyecto de
VBStats → *Configuración del proyecto → Cuentas de servicio → Generar nueva clave
privada*, y se codifica:

```bash
base64 -w0 clave-descargada.json
```

(En macOS el comando es `base64 -i clave-descargada.json | tr -d '\n'`.)

### 1.6 · Firebase del CV Oviedo

El proyecto es **`cv-oviedo`**. Aquí sí hay que generar una clave:

1. [Consola de Firebase](https://console.firebase.google.com/) → proyecto
   **cv-oviedo**.
2. *Configuración del proyecto (la rueda) → **Cuentas de servicio***.
3. **Generar nueva clave privada** → descarga un `.json`.
4. Codifícalo:

```bash
base64 -w0 cv-oviedo-firebase-adminsdk-xxxxx.json
```

Esa cadena larga es el valor de la variable.

**Borra el `.json` descargado en cuanto lo hayas codificado.** Esa clave da acceso
completo a Firestore y a las cuentas del club, y suele quedarse olvidada en la
carpeta de Descargas.

La cuenta de servicio por defecto de Firebase ya trae los permisos que hacen
falta: leer Firestore y listar usuarios de Authentication.

---

## Parte 2 · Probar en local

Antes de desplegar, comprueba que con esas credenciales el panel enciende las dos
apps. Es mucho más rápido depurar aquí.

Edita `api/.env` (ya existe; no va al repositorio) y rellena lo que has reunido:

```bash
BLUEDEBUG_GOOGLE_CLIENT_ID=483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
BLUEDEBUG_SECRETO=<uno cualquiera, local>
BLUEDEBUG_PERMITIDOS=bluedebug.develop@gmail.com
BLUEDEBUG_COOKIE_SEGURA=false
BLUEDEBUG_ORIGENES_WEB=http://localhost:4200

BLUEDEBUG_VBSTATS_URL=<MYSQL_PUBLIC_URL de Railway>
BLUEDEBUG_VBSTATS_STRIPE=<rk_live_...>
BLUEDEBUG_VBSTATS_FIREBASE=<base64 de VBStats>
BLUEDEBUG_CVO_FIREBASE=<base64 de cv-oviedo>
```

`BLUEDEBUG_COOKIE_SEGURA` **tiene que ser `false` en local**: sobre http, el
navegador descarta una cookie marcada como `Secure` y el acceso entra en bucle.

Arranca:

```bash
cd api && ./mvnw spring-boot:run
```

Y comprueba desde otra terminal:

```bash
curl http://localhost:8080/api/salud
```

Lo que tiene que salir cuando está todo bien:

```json
{"ok":true,"apps":2,"disponibles":2}
```

**`disponibles` es el número que importa.** Si es menor que `apps`, alguna
credencial no vale. Abre `http://localhost:8080`, entra con Google y mira
**Ajustes**: cada app apagada dice ahí qué le falta.

Entra y recorre las cuatro pantallas: Panel, Usuarios, cada aplicación y
Actividad. Si las gráficas tienen datos y la tabla de usuarios lista cuentas
reales, las credenciales son correctas.

> **No pruebes el envío de notificaciones desde local a la ligera.** Estás contra
> la base de datos y los dispositivos de PRODUCCIÓN: si pulsas «Enviar», les suena
> el móvil a los usuarios de verdad. No hay modo de pruebas.

---

## Parte 3 · Desplegar en Railway

### 3.1 · Crear el servicio

1. [Railway](https://railway.app) → **New Project** → **Deploy from GitHub repo**.
2. Elige **bluedebugdevelop/BlueDebug-Management**. Si no aparece, en
   *Configure GitHub App* dale acceso a la organización.
3. Railway lee `railway.json`, ve que el builder es `DOCKERFILE` y construye solo.
   **No configures comando de arranque ni puerto**: el `Dockerfile` ya lo resuelve
   y la aplicación lee el `PORT` que Railway inyecta.

El primer build tarda unos minutos: compila Angular con Node y luego el backend
con Maven. Los siguientes van más rápido porque las capas de dependencias se
reutilizan.

### 3.2 · Las variables

*Settings → Variables → **Raw Editor***, y pega esto de una vez, sustituyendo:

```bash
BLUEDEBUG_GOOGLE_CLIENT_ID=483023567899-9atrekath4fnmtbvkel4ntfhlqame57j.apps.googleusercontent.com
BLUEDEBUG_SECRETO=<el generado en 1.2, distinto del de local>
BLUEDEBUG_PERMITIDOS=bluedebug.develop@gmail.com
BLUEDEBUG_COOKIE_SEGURA=true
BLUEDEBUG_HORAS_SESION=12
BLUEDEBUG_VBSTATS_URL=<MYSQL_PUBLIC_URL>
BLUEDEBUG_VBSTATS_STRIPE=<rk_live_...>
BLUEDEBUG_VBSTATS_FIREBASE=<base64 de VBStats>
BLUEDEBUG_CVO_FIREBASE=<base64 de cv-oviedo>
```

Dos diferencias con local, y las dos importan:

- **`BLUEDEBUG_COOKIE_SEGURA=true`.** En producción hay https, y sin esto la cookie
  de sesión viajaría sin la marca `Secure`.
- **No pongas `BLUEDEBUG_ORIGENES_WEB`.** En producción el mismo servidor sirve el
  Angular y la API, así que no hay petición cruzada y CORS sobra. Dejarlo vacío es
  lo correcto y lo más cerrado.

### 3.3 · El dominio

*Settings → Networking → **Generate Domain***. Te da algo como
`bluedebug-management-production.up.railway.app`.

**Cópialo y vuelve a la consola de Google Cloud** (paso 1.1) para añadirlo a
**Orígenes autorizados de JavaScript**, con `https://` y sin barra final:

```
https://bluedebug-management-production.up.railway.app
```

Google tarda a veces unos minutos en aplicar el cambio.

### 3.4 · Comprobar que ha subido bien

```bash
curl https://TU-DOMINIO.up.railway.app/api/salud
```

Tiene que responder `{"ok":true,"apps":2,"disponibles":2}`.

Railway usa esa misma ruta como healthcheck (está en `railway.json`), así que si
el despliegue se queda en «unhealthy», el problema está en el arranque: mira los
**Deploy Logs**.

Luego abre el dominio en el navegador, entra con Google y repasa que las dos apps
salen en verde en el menú lateral.

---

## Parte 4 · Detalles que conviene saber

**Quién puede entrar.** `BLUEDEBUG_PERMITIDOS` admite varios correos separados por
comas. Se comprueba en **cada petición**, no solo al iniciar sesión: quitar un
correo y reiniciar el servicio echa fuera a esa persona al momento, sin esperar a
que caduque su sesión.

**A quién se le atribuyen los avisos de VBStats.** Cuando mandas una notificación,
el panel la apunta en el historial que la propia app enseña. Ese historial exige
un id de usuario de VBStats, así que el panel busca tu correo entre sus cuentas;
si no lo encuentra, usa el primer superadmin. Si `bluedebug.develop@gmail.com`
tiene cuenta en VBStats (y está en su `SUPERADMIN_EMAILS`), los avisos saldrán a
tu nombre. Si no hubiera ningún superadmin, el aviso se envía igual pero no queda
registrado, y el resultado en pantalla te lo dice.

**Actualizar el panel.** Cada `git push` a `main` dispara un despliegue nuevo en
Railway. No hay más pasos.

**Añadir una tercera aplicación.** Está en el README: una clase en
`conectores/` anotada con `@Component` que implemente `ConectorApp`. Aparece sola
en el menú, con sus pestañas y sus botones, sin tocar Angular.

---

## Problemas típicos

**El botón de Google aparece pero al pulsarlo no pasa nada.**
La pantalla de consentimiento está «En pruebas» y tu correo no está en los
usuarios de prueba. Paso 1.1.b.

**«Esta cuenta no tiene acceso al panel».**
El acceso con Google funcionó; es la lista blanca la que te rechaza. Revisa
`BLUEDEBUG_PERMITIDOS` y que el correo con el que entras sea exactamente ese. Las
mayúsculas y los espacios no importan —el panel los normaliza—, pero un correo
distinto sí.

**Entras, te redirige al acceso, y vuelta a empezar.**
La cookie de sesión no se está guardando. En local es casi siempre
`BLUEDEBUG_COOKIE_SEGURA=true` sobre http: ponlo a `false`. En producción es lo
contrario, y suele significar que estás entrando por http en vez de https.

**VBStats sale apagada con «la base de datos no responde».**
La URL es correcta en su forma pero la conexión falla. Comprueba, por este orden:
que has copiado `MYSQL_PUBLIC_URL` y no `MYSQL_URL` (la privada no se ve desde
fuera de su proyecto), que el servicio MySQL está levantado en Railway, y que la
contraseña no se ha regenerado. Los **Deploy Logs** del panel traen el motivo real.

**El primer build en Railway falla por memoria o tiempo.**
Compilar Angular y Maven en el mismo contenedor es lo más pesado que hace este
proyecto. Reintenta el despliegue: la segunda vez reutiliza las capas de
dependencias y pasa sin problema.
