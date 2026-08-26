import { ChangeDetectionStrategy, Component, input } from '@angular/core'

import { dinero, entero, fechaHora } from '../nucleo/formato'
import type { ColumnaTabla, Tabla } from '../nucleo/tipos'

/**
 * Pinta una tabla cualquiera de las que declara un conector.
 *
 * Es el compañero de {@code Tabla} en el backend: gracias a este par, una app
 * puede enseñar algo suyo —un historial de avisos, una lista de equipos— sin que
 * haya que escribir una pantalla nueva en Angular. Se declaran columnas y filas
 * en Java y aparece.
 */
@Component({
  selector: 'bd-tabla',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section>
      <h3 class="titulo-seccion">{{ tabla().titulo }}</h3>

      @if (tabla().filas.length === 0) {
        <p class="vacia">{{ tabla().vacia }}</p>
      } @else {
        <div class="marco-tabla">
          <table>
            <thead>
              <tr>
                @for (columna of tabla().columnas; track columna.clave) {
                  <th [class.numero]="esNumero(columna)">{{ columna.titulo }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (fila of tabla().filas; track $index) {
                <tr>
                  @for (columna of tabla().columnas; track columna.clave) {
                    <td [class.numero]="esNumero(columna)">{{ celda(fila, columna) }}</td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: [
    `
      .vacia {
        color: var(--texto-3);
        font-size: 0.87rem;
        padding: 1.5rem;
        text-align: center;
        border: 1px dashed var(--borde);
        border-radius: var(--radio);
      }
    `,
  ],
})
export class TablaGenerica {
  readonly tabla = input.required<Tabla>()

  protected esNumero(columna: ColumnaTabla): boolean {
    return columna.formato === 'entero' || columna.formato === 'dinero'
  }

  protected celda(fila: Record<string, unknown>, columna: ColumnaTabla): string {
    const valor = fila[columna.clave]

    if (valor === null || valor === undefined || valor === '') {
      return '—'
    }

    switch (columna.formato) {
      case 'entero':
        return entero(Number(valor))
      case 'dinero':
        return dinero(Number(valor))
      case 'fecha':
        return fechaHora(String(valor))
      default:
        return String(valor)
    }
  }
}
