import { HttpClient, HttpErrorResponse } from '@angular/common/http'
import { Injectable, inject } from '@angular/core'
import { Observable, catchError, throwError } from 'rxjs'

import type {
  AccionAdmin,
  Administrador,
  AltaGasto,
  AppEnMenu,
  Apunte,
  Arranque,
  CatalogoContabilidad,
  EdicionRol,
  Gasto,
  Ingresos,
  PanelGeneral,
  ResultadoAccion,
  ResumenApp,
  ResumenContabilidad,
  Tabla,
  UsuarioApp,
  UsuarioGlobal,
} from './tipos'

/**
 * El único sitio del front que sabe las urls de la API.
 *
 * Todas las llamadas van con `withCredentials`, que es lo que hace que el
 * navegador mande la cookie de sesión. Sin eso, en desarrollo —front en el 4200,
 * API en el 8080— cada petición saldría anónima y el panel parecería estar
 * pidiendo login en bucle.
 */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient)

  private readonly opciones = { withCredentials: true }

  // ------------------------------------------------------------- autenticación

  configuracionAcceso(): Observable<{ googleClientId: string; configurado: boolean }> {
    return this.get('/api/auth/config')
  }

  entrar(credencial: string): Observable<Administrador> {
    return this.post('/api/auth/google', { credential: credencial })
  }

  sesion(): Observable<{ autenticado: boolean; admin?: Administrador }> {
    return this.get('/api/auth/sesion')
  }

  salir(): Observable<{ ok: boolean }> {
    return this.post('/api/auth/salir', {})
  }

  // -------------------------------------------------------------------- panel

  arranque(revisar = false): Observable<Arranque> {
    return this.get(`/api/panel/arranque${revisar ? '?revisar=true' : ''}`)
  }

  panel(dias: number): Observable<PanelGeneral> {
    return this.get(`/api/panel/resumen?dias=${dias}`)
  }

  usuariosDeTodas(): Observable<UsuarioGlobal[]> {
    return this.get('/api/panel/usuarios')
  }

  actividad(): Observable<Apunte[]> {
    return this.get('/api/panel/auditoria')
  }

  // ------------------------------------------------------------- contabilidad

  catalogoContabilidad(): Observable<CatalogoContabilidad> {
    return this.get('/api/contabilidad/arranque')
  }

  /** @param anio el año que se mira. Un 0 los trae todos. */
  contabilidad(anio: number): Observable<ResumenContabilidad> {
    return this.get(`/api/contabilidad/resumen?anio=${anio}`)
  }

  crearGasto(gasto: AltaGasto): Observable<Gasto> {
    return this.post('/api/contabilidad/gastos', gasto)
  }

  editarGasto(id: string, gasto: AltaGasto): Observable<Gasto> {
    return this.http
      .put<Gasto>(`/api/contabilidad/gastos/${encodeURIComponent(id)}`, gasto, this.opciones)
      .pipe(catchError(traducirError))
  }

  borrarGasto(id: string): Observable<Gasto> {
    return this.http
      .delete<Gasto>(`/api/contabilidad/gastos/${encodeURIComponent(id)}`, this.opciones)
      .pipe(catchError(traducirError))
  }

  // --------------------------------------------------------------------- apps

  apps(): Observable<AppEnMenu[]> {
    return this.get('/api/apps')
  }

  resumen(id: string, dias: number): Observable<ResumenApp> {
    return this.get(`/api/apps/${id}?dias=${dias}`)
  }

  usuarios(id: string): Observable<UsuarioApp[]> {
    return this.get(`/api/apps/${id}/usuarios`)
  }

  ingresos(id: string, dias: number): Observable<Ingresos> {
    return this.get(`/api/apps/${id}/ingresos?dias=${dias}`)
  }

  acciones(id: string): Observable<AccionAdmin[]> {
    return this.get(`/api/apps/${id}/acciones`)
  }

  tablas(id: string): Observable<Tabla[]> {
    return this.get(`/api/apps/${id}/tablas`)
  }

  ejecutar(id: string, accionId: string, parametros: Record<string, unknown>): Observable<ResultadoAccion> {
    return this.post(`/api/apps/${id}/acciones/${accionId}`, parametros)
  }

  edicionRol(id: string): Observable<EdicionRol> {
    return this.get(`/api/apps/${id}/edicion-rol`)
  }

  cambiarRol(id: string, usuarioId: string, roles: string[]): Observable<ResultadoAccion> {
    return this.http
      .put<ResultadoAccion>(
        `/api/apps/${id}/usuarios/${encodeURIComponent(usuarioId)}/rol`,
        { roles },
        this.opciones,
      )
      .pipe(catchError(traducirError))
  }

  borrarUsuario(id: string, usuarioId: string): Observable<ResultadoAccion> {
    return this.http
      .delete<ResultadoAccion>(`/api/apps/${id}/usuarios/${encodeURIComponent(usuarioId)}`, this.opciones)
      .pipe(catchError(traducirError))
  }

  // ------------------------------------------------------------------- apoyo

  private get<T>(url: string): Observable<T> {
    return this.http.get<T>(url, this.opciones).pipe(catchError(traducirError))
  }

  private post<T>(url: string, cuerpo: unknown): Observable<T> {
    return this.http.post<T>(url, cuerpo, this.opciones).pipe(catchError(traducirError))
  }
}

/**
 * Convierte el error de HTTP en un mensaje que se pueda enseñar.
 *
 * El backend manda siempre `{ error: '...' }`, así que casi siempre hay una frase
 * escrita para humanos esperando dentro. El caso feo es el otro: cuando el
 * servidor no está levantado, Angular da status 0 y un mensaje que habla de
 * XMLHttpRequest, y eso en pantalla no ayuda a nadie.
 */
function traducirError(fallo: HttpErrorResponse) {
  const mensaje =
    fallo.status === 0
      ? 'No se pudo hablar con el servidor. ¿Está levantado?'
      : (fallo.error?.error as string) || fallo.message || 'Algo ha fallado'

  return throwError(() => ({ mensaje, estado: fallo.status }))
}

/** La forma del error que ve el resto del front. */
export interface ErrorApi {
  mensaje: string
  estado: number
}
