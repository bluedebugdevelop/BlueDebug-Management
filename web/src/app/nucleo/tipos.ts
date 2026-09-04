/* ==========================================================================
   Lo que devuelve la API, escrito en TypeScript.

   Es un espejo a mano de los records de Java. No se genera automáticamente y
   está bien que así sea: son veinte tipos, y meter un generador de OpenAPI en el
   build por eso costaría más de mantener que estas líneas. Lo que sí hay que
   tener presente es que si se cambia un record del backend, hay que bajar aquí.
   ========================================================================== */

export interface Administrador {
  email: string
  nombre: string | null
  foto: string | null
}

export type Capacidad =
  | 'USUARIOS'
  | 'METRICAS'
  | 'INGRESOS'
  | 'ACCIONES'
  | 'BORRAR_USUARIOS'
  | 'EDITAR_ROL'

/** Cómo se edita el rol o el plan en una app concreta. Lo declara su conector. */
export interface EdicionRol {
  etiqueta: string
  /** VBStats: un plan y solo uno. El club: varios roles a la vez. */
  multiple: boolean
  opciones: OpcionCampo[]
  aviso: string | null
}

export interface CampoExtra {
  clave: string
  etiqueta: string
}

export interface DescriptorApp {
  id: string
  nombre: string
  descripcion: string
  color: string
  icono: string
  plataformas: string[]
  capacidades: Capacidad[]
  camposExtra: CampoExtra[]
}

export interface EstadoConector {
  disponible: boolean
  motivo: string | null
}

export interface AppEnMenu {
  app: DescriptorApp
  estado: EstadoConector
}

export interface Metrica {
  clave: string
  etiqueta: string
  valor: number
  formato: 'entero' | 'dinero' | 'porcentaje'
  variacion: number | null
  detalle: string | null
}

export interface Punto {
  fecha: string
  valor: number
}

export interface Serie {
  clave: string
  etiqueta: string
  formato: string
  puntos: Punto[]
}

export interface Trozo {
  etiqueta: string
  valor: number
  color: string | null
}

export interface Reparto {
  clave: string
  etiqueta: string
  trozos: Trozo[]
}

export interface ResumenApp {
  app: DescriptorApp
  estado: EstadoConector
  metricas: Metrica[]
  series: Serie[]
  repartos: Reparto[]
}

export interface UsuarioApp {
  id: string
  nombre: string | null
  email: string | null
  alta: string | null
  ultimaSesion: string | null
  plan: string | null
  activo: boolean
  dispositivos: number
  extra: Record<string, unknown>
}

export interface UsuarioGlobal {
  appId: string
  appNombre: string
  appColor: string
  usuario: UsuarioApp
}

export interface Movimiento {
  id: string
  fecha: string
  email: string | null
  importe: number
  estado: string
  origen: string
}

export interface Ingresos {
  moneda: string
  facturado: number
  devuelto: number
  recurrente: number
  suscriptores: number
  porDia: Serie
  porPlan: Reparto
  movimientos: Movimiento[]
}

export interface OpcionCampo {
  valor: string
  etiqueta: string
  detalle: string | null
}

export interface CampoAccion {
  clave: string
  etiqueta: string
  tipo: 'TEXTO' | 'AREA' | 'SELECCION' | 'MULTISELECCION' | 'NUMERO' | 'INTERRUPTOR'
  obligatorio: boolean
  maximo: number
  ayuda: string | null
  opciones: OpcionCampo[]
}

export interface AccionAdmin {
  id: string
  nombre: string
  descripcion: string
  icono: string
  peligrosa: boolean
  textoBoton: string
  campos: CampoAccion[]
  /** Epígrafe bajo el que se agrupa en la pestaña, o null para dejarla suelta. */
  grupo: string | null
}

export interface ResultadoAccion {
  correcto: boolean
  mensaje: string
  detalles: Record<string, unknown>
}

export interface ColumnaTabla {
  clave: string
  titulo: string
  formato: string
}

export interface Tabla {
  clave: string
  titulo: string
  columnas: ColumnaTabla[]
  filas: Record<string, unknown>[]
  vacia: string
}

export interface PanelGeneral {
  apps: ResumenApp[]
  totales: Metrica[]
  altas: Serie[]
  porApp: Reparto
  dinero: Ingresos
}

export interface Apunte {
  momento: string
  admin: string
  app: string
  accion: string
  detalle: string
  correcto: boolean
}

export interface Arranque {
  admin: Administrador
  apps: AppEnMenu[]
  periodos: number[]
}

/* ----------------------------------------------------------- contabilidad */

export interface Gasto {
  id: string
  fecha: string
  concepto: string
  categoria: string
  proveedor: string | null
  importe: number
  iva: number
  pagadoPor: string
  /** Id del conector al que se imputa, o null si es gasto de empresa. */
  app: string | null
  recurrencia: 'UNICO' | 'MENSUAL' | 'ANUAL'
  nota: string | null
  creadoPor: string
  creadoEn: string
}

/** Lo que se manda al guardar. Sin id ni rastro de quién lo apuntó: eso lo pone el servidor. */
export interface AltaGasto {
  fecha: string
  concepto: string
  categoria: string
  proveedor: string | null
  importe: number
  iva: number
  pagadoPor: string
  app: string | null
  recurrencia: string
  nota: string | null
}

export interface OpcionCategoria {
  valor: string
  etiqueta: string
  color: string
}

export interface OpcionRecurrencia {
  valor: string
  etiqueta: string
}

export interface AppImputable {
  id: string
  nombre: string
  color: string
}

export interface CatalogoContabilidad {
  configurado: boolean
  motivo: string | null
  socios: string[]
  categorias: OpcionCategoria[]
  recurrencias: OpcionRecurrencia[]
  apps: AppImputable[]
  anios: number[]
}

export interface SaldoSocio {
  socio: string
  pagado: number
  /** Lo puesto menos lo que le tocaba. En positivo le deben; en negativo, debe. */
  saldo: number
}

export interface AjusteLiquidacion {
  de: string
  a: string
  importe: number
}

export interface Liquidacion {
  total: number
  porCabeza: number
  saldos: SaldoSocio[]
  ajustes: AjusteLiquidacion[]
}

export interface ResumenContabilidad {
  anio: number
  gastos: Gasto[]
  metricas: Metrica[]
  porMes: Serie
  porCategoria: Reparto
  porSocio: Reparto
  porApp: Reparto
  liquidacion: Liquidacion
}
