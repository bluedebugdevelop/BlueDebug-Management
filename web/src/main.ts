import { bootstrapApplication } from '@angular/platform-browser'

import { AplicacionPanel } from './app/app'
import { configuracion } from './app/app.config'

bootstrapApplication(AplicacionPanel, configuracion).catch((error) => {
  // Si el arranque falla, la página se queda en blanco y sin una sola pista. Al
  // menos que quede en la consola.
  console.error('No se pudo arrancar el panel', error)
})
