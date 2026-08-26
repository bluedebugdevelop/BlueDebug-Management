import { ChangeDetectionStrategy, Component } from '@angular/core'
import { RouterOutlet } from '@angular/router'

/**
 * La raíz. No hace nada más que albergar al router.
 *
 * El armazón con la barra lateral está en {@link Marco} y no aquí, porque la
 * pantalla de acceso ocupa el ancho entero y no debe llevar menú: son dos
 * disposiciones distintas y el router elige.
 */
@Component({
  selector: 'bd-panel',
  standalone: true,
  imports: [RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<router-outlet />',
})
export class AplicacionPanel {}
