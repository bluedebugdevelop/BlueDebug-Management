# BlueDebug Management — panel de administración

Panel único para administrar todas las apps de BlueDebug con una sola cuenta.
Hoy administra **VBStats**, la **app del CV Oviedo** y **Co-Kitchen**.
Repo `bluedebugdevelop/BlueDebug-Management`. En producción en
`bluedebug-management-production.up.railway.app` desde el 2026-08-29.

```
Spring Boot 3.4 (Java 17)  +  Angular 19  ->  un solo contenedor
```

Primera puesta en marcha: **`docs/PUESTA-EN-MARCHA.md`** (de repo clonado a panel
desplegado, con de dónde sale cada credencial).

## La regla de oro: arquitectura de conectores

**El panel no conoce ninguna aplicación concreta.** Conoce la interfaz
`ConectorApp` y los objetos que la implementan como `@Component`. El menú, las
pestañas, la tabla de usuarios, las gráficas, los formularios de acciones y el
editor de roles se construyen con lo que cada conector **declara sobre sí mismo**
en su descriptor.

La consecuencia práctica: **añadir una app es escribir una clase.** Sin tocar el
router de Angular, ni el menú, ni los controladores, ni la tabla de usuarios.

```
api/src/main/java/com/bluedebug/gestion/
├── conectores/
│   ├── ConectorApp.java          <- el contrato
│   ├── RegistroConectores.java   <- los descubre solo (Spring los inyecta)
│   ├── modelo/                   <- lenguaje común: usuarios, métricas, series...
│   ├── vbstats/                  <- MySQL + Stripe + FCM
│   ├── cvo/                      <- Firebase
│   └── cokitchen/                <- Postgres de Supabase
├── panel/
├── seguridad/
└── comun/
```

**Antes de proponer cualquier cosa que meta un `if` por aplicación** en el front o
en los controladores: parar y mirar si cabe como capacidad declarada del conector.
Casi siempre cabe. Esa es la decisión de diseño que sostiene el proyecto y hay que
respetarla.

Ejemplo de cómo se hace: las novedades de VBStats se escriben **solo en
castellano** y se traducen al inglés, francés y portugués **dentro del guardado**,
no en un botón ni en un campo por idioma. El front no se enteró: el formulario
sigue siendo el genérico del descriptor, con un textarea.

El traductor vive en `comun/` y es **opcional**. Hay dos motores tras la interfaz
`MotorTraduccion` — **Gemini (gratis, el preferido)** y Claude — y se elige solo
según qué clave haya (`GEMINI_API_KEY` / `ANTHROPIC_API_KEY`), o a mano con
`BLUEDEBUG_TRADUCCION_MOTOR`. Se traduce con un modelo y no con un traductor
porque son titulares de UI sueltos, donde uno sin contexto elige mal la acepción
(«posición», «set»). Sin ninguna clave se publica solo en castellano, el
resultado lo dice en voz alta y la app enseña el castellano a todos. Un idioma
que vuelva con distinto número de líneas se descarta entero: emparejar por
posición pondría el titular equivocado en el idioma equivocado.

## Convenciones

- **Código y comentarios en español** (`conectores`, `seguridad`, `panel`, `comun`),
  igual que CVOApp y CVOWeb. Deliberado.
- Acceso **solo con Google y con lista blanca**. No hay registro abierto.

## Estructura

- `api/` — Spring Boot 3.4 / Java 17
- `web/` — Angular 19
- `Dockerfile` en la raíz: empaqueta las dos en **un solo contenedor**
- `railway.json` — despliegue

## Qué administra hoy

| App | Por dónde entra | Qué expone |
|---|---|---|
| VBStats | MySQL + Stripe + FCM | cuentas, suscripciones, ingresos, notificaciones, novedades de versión |
| CV Oviedo | Firebase | fichas del club, equipos, roles, avisos |
| Co-Kitchen | Postgres de Supabase | cuentas, últimos accesos, espacios, despensa, borrado, plan PRO |

Está pensado para que meter la cuarta y la décima no sea rehacer nada.

**Co-Kitchen y el PRO.** La app todavía no tiene planes. El conector no lo da por
hecho ni lo espera apagado: pregunta al catálogo de Postgres si
`public.profiles` tiene una columna `plan` (con una caché de un minuto) y declara
la capacidad `EDITAR_ROL` solo cuando existe. El día que se cree esa columna
aparece el selector solo, sin desplegar nada. Es el mismo patrón de siempre —el
backend describe, el front pinta— aplicado a algo que aún no existe.
