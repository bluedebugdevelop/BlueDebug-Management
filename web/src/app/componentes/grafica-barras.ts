import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'

import { PALETA, dineroCorto, segunFormato } from '../nucleo/formato'
import type { Serie } from '../nucleo/tipos'

/**
 * Una serie mes a mes, en barras.
 *
 * Barras y no líneas, aunque ya haya un componente de líneas: una línea sugiere
 * que entre dos puntos hay un continuo, y el gasto de febrero no «pasa por»
 * ningún valor intermedio camino de marzo. Con doce valores discretos, las
 * barras además se pueden comparar de un vistazo, que es justo lo que se hace
 * con los meses.
 *
 * Se dibuja con divs y no con SVG por lo mismo que el resto del panel evita
 * dependencias: son doce rectángulos y una etiqueta, y en HTML se llevan solas
 * la accesibilidad, el texto y el responsive.
 */
@Component({
  selector: 'bd-grafica-barras',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="grafica">
      <figcaption class="titulo">{{ titulo() }}</figcaption>

      @if (vacia()) {
        <p class="vacia">Todavía no hay nada apuntado</p>
      } @else {
        <div class="barras">
          @for (barra of barras(); track barra.clave) {
            <div class="columna" [title]="barra.etiqueta + ': ' + barra.texto">
              <span class="cifra" [class.cero]="barra.valor === 0">{{ barra.texto }}</span>
              <div class="canal">
                <div
                  class="barra"
                  [style.height.%]="barra.alto"
                  [style.background]="color()"
                  [class.hueca]="barra.valor === 0"
                ></div>
              </div>
              <span class="mes">{{ barra.etiqueta }}</span>
            </div>
          }
        </div>
      }
    </figure>
  `,
  styles: [
    `
      .grafica {
        background: var(--superficie);
        border: 1px solid var(--borde);
        border-radius: var(--radio);
        padding: 1.1rem 1.25rem;
        margin: 0;
        height: 100%;
      }

      .titulo {
        font-size: 0.92rem;
        font-weight: 600;
        margin-bottom: 1.1rem;
      }

      .barras {
        display: flex;
        align-items: flex-end;
        gap: 0.4rem;
        /* Con muchos meses (la vista de «todos los años») no se estrecha hasta
           ser ilegible: hace scroll dentro de su caja, como las tablas. */
        overflow-x: auto;
        padding-bottom: 0.2rem;
      }

      .columna {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.35rem;
        flex: 1 0 44px;
        min-width: 44px;
      }

      .cifra {
        font-size: 0.68rem;
        color: var(--texto-2);
        font-variant-numeric: tabular-nums;
        white-space: nowrap;
      }

      .cifra.cero {
        color: var(--texto-3);
        opacity: 0.45;
      }

      .canal {
        display: flex;
        align-items: flex-end;
        height: 130px;
        width: 100%;
      }

      .barra {
        width: 100%;
        border-radius: 5px 5px 2px 2px;
        min-height: 2px;
        transition: height var(--transicion);
      }

      /* Un mes a cero deja su hueco marcado en vez de desaparecer: así se ve que
         el mes existe y no hubo gasto, que no es lo mismo que no haber mes. */
      .barra.hueca {
        background: var(--borde) !important;
        height: 2px;
      }

      .mes {
        font-size: 0.7rem;
        color: var(--texto-3);
        white-space: nowrap;
      }

      .vacia {
        color: var(--texto-3);
        font-size: 0.85rem;
        padding: 3rem 0;
        text-align: center;
      }
    `,
  ],
})
export class GraficaBarras {
  readonly titulo = input.required<string>()
  readonly serie = input.required<Serie>()

  /** El color de las barras. Por defecto, el azul de la marca. */
  readonly color = input(PALETA[0])

  protected readonly vacia = computed(
    () => this.serie().puntos.length === 0 || this.serie().puntos.every((p) => p.valor === 0),
  )

  private readonly maximo = computed(() =>
    Math.max(0, ...this.serie().puntos.map((p) => p.valor)),
  )

  protected readonly barras = computed(() => {
    const puntos = this.serie().puntos
    const techo = this.maximo() || 1
    const formato = this.serie().formato
    // El año solo aparece cuando la serie pasa de doce meses, que es la vista de
    // «todos los años». Dentro de un año es ruido en cada una de las doce columnas.
    const conAno = puntos.length > 12

    return puntos.map((punto) => {
      const fecha = new Date(punto.fecha)
      return {
        clave: punto.fecha,
        etiqueta: (conAno ? MES_Y_ANO : MES).format(fecha),
        valor: punto.valor,
        // Un 4 % de suelo para que un mes con 3 € siga siendo una barra visible y
        // no una raya indistinguible del mes que fue cero.
        alto: punto.valor === 0 ? 0 : Math.max(4, (punto.valor / techo) * 100),
        texto: etiquetaValor(punto.valor, formato),
      }
    })
  })
}

/**
 * El número que va encima de la barra.
 *
 * En euros va sin céntimos: la columna mide 44 px y un «1.234,56 €» completo no
 * cabe ni cambiando de idea. La cifra exacta está en la tabla de abajo.
 */
function etiquetaValor(valor: number, formato: string): string {
  if (valor === 0) return '—'
  return formato === 'dinero' ? dineroCorto(valor) : segunFormato(valor, formato)
}

const MES = new Intl.DateTimeFormat('es-ES', { month: 'short' })

const MES_Y_ANO = new Intl.DateTimeFormat('es-ES', { month: 'short', year: '2-digit' })
