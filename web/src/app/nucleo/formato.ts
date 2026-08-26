/* ==========================================================================
   Cómo se escriben los números y las fechas.

   Todo en español de España: separador de miles con punto, decimales con coma,
   euros detrás. Está centralizado aquí porque un panel donde la misma cifra se
   ve de dos maneras distintas según la pantalla se lee fatal.
   ========================================================================== */

const ENTEROS = new Intl.NumberFormat('es-ES', { maximumFractionDigits: 0 })

const DINERO = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const DINERO_CORTO = new Intl.NumberFormat('es-ES', {
  style: 'currency',
  currency: 'EUR',
  maximumFractionDigits: 0,
})

const FECHA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short', year: 'numeric' })

const FECHA_HORA = new Intl.DateTimeFormat('es-ES', {
  day: 'numeric',
  month: 'short',
  hour: '2-digit',
  minute: '2-digit',
})

const DIA_CORTO = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short' })

export function entero(valor: number): string {
  return ENTEROS.format(valor ?? 0)
}

export function dinero(valor: number): string {
  return DINERO.format(valor ?? 0)
}

export function dineroCorto(valor: number): string {
  return DINERO_CORTO.format(valor ?? 0)
}

export function porcentaje(valor: number): string {
  return `${ENTEROS.format(valor ?? 0)} %`
}

/** Da formato a un número según lo que diga el backend que es. */
export function segunFormato(valor: number, formato: string): string {
  switch (formato) {
    case 'dinero':
      return dinero(valor)
    case 'porcentaje':
      return porcentaje(valor)
    default:
      return entero(valor)
  }
}

export function fecha(iso: string | null | undefined): string {
  if (!iso) return '—'
  return FECHA.format(new Date(iso))
}

export function fechaHora(iso: string | null | undefined): string {
  if (!iso) return '—'
  return FECHA_HORA.format(new Date(iso))
}

export function diaCorto(iso: string): string {
  return DIA_CORTO.format(new Date(iso))
}

/**
 * «hace 3 días», «hace 2 h».
 *
 * En una tabla de últimos accesos esto se lee mucho mejor que una fecha: lo que
 * se busca ahí es si alguien entró hoy o hace medio año, no el día exacto.
 */
export function haceCuanto(iso: string | null | undefined): string {
  if (!iso) return 'nunca'

  const segundos = Math.floor((Date.now() - new Date(iso).getTime()) / 1000)

  if (segundos < 60) return 'ahora mismo'
  if (segundos < 3600) return `hace ${Math.floor(segundos / 60)} min`
  if (segundos < 86400) return `hace ${Math.floor(segundos / 3600)} h`

  const dias = Math.floor(segundos / 86400)
  if (dias === 1) return 'ayer'
  if (dias < 30) return `hace ${dias} días`
  if (dias < 365) return `hace ${Math.floor(dias / 30)} meses`
  return `hace ${Math.floor(dias / 365)} años`
}

/** Cuánto hace, pero solo si es reciente; si no, la fecha. */
export function ultimoAcceso(iso: string | null | undefined): string {
  if (!iso) return 'nunca'
  const dias = (Date.now() - new Date(iso).getTime()) / 86400000
  return dias < 30 ? haceCuanto(iso) : fecha(iso)
}

/**
 * La paleta de las gráficas cuando el backend no manda color.
 *
 * Son tonos que se distinguen entre sí sobre fondo oscuro y, los tres primeros,
 * los de la marca. El orden importa: la primera serie de cualquier gráfica sale
 * en azul BlueDebug.
 */
export const PALETA = [
  '#2196f3',
  '#0ea5e9',
  '#f97316',
  '#22c55e',
  '#a78bfa',
  '#f43f5e',
  '#eab308',
  '#14b8a6',
]

export function colorDe(indice: number, propio?: string | null): string {
  return propio || PALETA[indice % PALETA.length]
}
