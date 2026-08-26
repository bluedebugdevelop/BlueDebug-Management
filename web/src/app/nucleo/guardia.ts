import { inject } from '@angular/core'
import { CanActivateFn, Router } from '@angular/router'

import { Sesion } from './sesion'

/**
 * No se entra a ninguna pantalla del panel sin sesión.
 *
 * Conviene tener claro qué protege esto y qué no: es comodidad, NO seguridad. Un
 * guard de Angular vive en el navegador y se puede saltar con la consola abierta.
 * Lo que de verdad impide leer datos es que la API exige la cookie firmada en
 * cada petición; esto solo evita enseñar un panel vacío a quien no ha entrado.
 */
export const exigeSesion: CanActivateFn = async (_ruta, estado) => {
  const sesion = inject(Sesion)
  const router = inject(Router)

  if (!sesion.cargada()) {
    await sesion.comprobar()
  }

  if (sesion.autenticado()) {
    return true
  }

  // Se guarda a dónde iba para volver ahí después de entrar: quien abre un enlace
  // directo a una app no quiere acabar en el panel general.
  return router.createUrlTree(['/acceso'], { queryParams: { volverA: estado.url } })
}

/** La pantalla de acceso no tiene sentido si ya hay sesión. */
export const soloSinSesion: CanActivateFn = async () => {
  const sesion = inject(Sesion)
  const router = inject(Router)

  if (!sesion.cargada()) {
    await sesion.comprobar()
  }

  return sesion.autenticado() ? router.createUrlTree(['/panel']) : true
}
