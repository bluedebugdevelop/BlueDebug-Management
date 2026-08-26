import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core'
import { provideHttpClient, withFetch } from '@angular/common/http'
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router'

import { RUTAS } from './app.rutas'

export const configuracion: ApplicationConfig = {
  providers: [
    // Agrupar los eventos evita una ronda de detección de cambios por cada
    // movimiento del ratón sobre las gráficas, que tienen una zona sensible por
    // día del periodo.
    provideZoneChangeDetection({ eventCoalescing: true }),

    provideRouter(
      RUTAS,
      // Los parámetros de la url llegan a los componentes como inputs, sin tener
      // que inyectar ActivatedRoute y suscribirse en cada pantalla.
      withComponentInputBinding(),
      // Al cambiar de pantalla se vuelve arriba; sin esto, saltar de una tabla
      // larga a otra pantalla te deja a media página.
      withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
    ),

    provideHttpClient(withFetch()),
  ],
}
