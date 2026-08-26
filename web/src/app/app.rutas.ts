import { Routes } from '@angular/router'

import { exigeSesion, soloSinSesion } from './nucleo/guardia'

/**
 * El mapa del panel.
 *
 * Todo va con carga diferida (`loadComponent`). En un panel de administración se
 * nota: quien entra a mirar las cifras del día no descarga el formulario de
 * acciones ni la tabla de usuarios de las cuatro apps.
 *
 * Fíjate en que hay UNA sola ruta de aplicación, `apps/:id`. No hay una ruta por
 * cada app y no la habrá nunca: la pantalla se construye con lo que declare el
 * conector, así que la app número siete funciona el día que se escriba su clase
 * en Java, sin tocar este fichero.
 */
export const RUTAS: Routes = [
  {
    path: 'acceso',
    canActivate: [soloSinSesion],
    loadComponent: () => import('./paginas/acceso').then((m) => m.PaginaAcceso),
    title: 'Acceso · BlueDebug Management',
  },
  {
    path: '',
    canActivate: [exigeSesion],
    loadComponent: () => import('./paginas/marco').then((m) => m.Marco),
    children: [
      {
        path: 'panel',
        loadComponent: () => import('./paginas/panel').then((m) => m.PaginaPanel),
        title: 'Panel · BlueDebug Management',
      },
      {
        path: 'usuarios',
        loadComponent: () => import('./paginas/usuarios').then((m) => m.PaginaUsuarios),
        title: 'Usuarios · BlueDebug Management',
      },
      {
        path: 'actividad',
        loadComponent: () => import('./paginas/actividad').then((m) => m.PaginaActividad),
        title: 'Actividad · BlueDebug Management',
      },
      {
        path: 'ajustes',
        loadComponent: () => import('./paginas/ajustes').then((m) => m.PaginaAjustes),
        title: 'Ajustes · BlueDebug Management',
      },
      {
        path: 'apps/:id',
        loadComponent: () => import('./paginas/aplicacion').then((m) => m.PaginaAplicacion),
      },
      { path: '', pathMatch: 'full', redirectTo: 'panel' },
    ],
  },
  { path: '**', redirectTo: '' },
]
