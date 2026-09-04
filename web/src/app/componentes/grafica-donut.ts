import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'

import { colorDe, dineroCorto, segunFormato } from '../nucleo/formato'
import type { Reparto } from '../nucleo/tipos'

/**
 * Un reparto por categorías, en donut.
 *
 * El donut se dibuja con UN SOLO círculo por trozo, jugando con
 * `stroke-dasharray` y `stroke-dashoffset`, en vez de calcular arcos con senos y
 * cosenos. Sale bastante más corto y, sobre todo, no hay forma de equivocarse con
 * el arco mayor: cada trozo es «tanto de circunferencia pintada, tanto en blanco,
 * girado hasta aquí».
 *
 * La leyenda va al lado con el número y el porcentaje. Un donut sin números al
 * lado obliga a estimar áreas a ojo, que es justo lo que la gente hace mal.
 */
@Component({
  selector: 'bd-grafica-donut',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="grafica">
      <figcaption class="titulo">{{ reparto().etiqueta }}</figcaption>

      @if (total() === 0) {
        <p class="vacia">Todavía no hay datos</p>
      } @else {
        <div class="cuerpo">
          <svg viewBox="0 0 120 120" class="rueda">
            @for (trozo of trozos(); track trozo.etiqueta) {
              <circle
                cx="60"
                cy="60"
                [attr.r]="RADIO"
                fill="none"
                [attr.stroke]="trozo.color"
                stroke-width="18"
                [attr.stroke-dasharray]="trozo.pintado + ' ' + trozo.resto"
                [attr.stroke-dashoffset]="trozo.giro"
                transform="rotate(-90 60 60)"
              />
            }
            <text x="60" y="57" class="centro">{{ totalTexto() }}</text>
            <text x="60" y="72" class="centro-pie">en total</text>
          </svg>

          <ul class="leyenda">
            @for (trozo of trozos(); track trozo.etiqueta) {
              <li>
                <i [style.background]="trozo.color"></i>
                <span class="nombre">{{ trozo.etiqueta }}</span>
                <span class="cifra">{{ trozo.texto }}</span>
                <span class="parte">{{ trozo.porcentaje }}%</span>
              </li>
            }
          </ul>
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
        margin-bottom: 0.9rem;
      }

      .cuerpo {
        display: flex;
        align-items: center;
        gap: 1.25rem;
        flex-wrap: wrap;
      }

      .rueda {
        width: 132px;
        height: 132px;
        flex-shrink: 0;
      }

      .centro {
        fill: var(--texto);
        font-size: 17px;
        font-weight: 700;
        text-anchor: middle;
      }

      .centro-pie {
        fill: var(--texto-3);
        font-size: 8px;
        text-anchor: middle;
      }

      .leyenda {
        list-style: none;
        display: flex;
        flex-direction: column;
        gap: 0.45rem;
        flex: 1;
        min-width: 170px;
      }

      .leyenda li {
        display: grid;
        grid-template-columns: 10px 1fr auto auto;
        align-items: center;
        gap: 0.6rem;
        font-size: 0.82rem;
      }

      .leyenda i {
        width: 10px;
        height: 10px;
        border-radius: 3px;
      }

      .nombre {
        color: var(--texto-2);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .cifra {
        font-weight: 600;
        font-variant-numeric: tabular-nums;
      }

      .parte {
        color: var(--texto-3);
        font-size: 0.75rem;
        font-variant-numeric: tabular-nums;
        min-width: 34px;
        text-align: right;
      }

      .vacia {
        color: var(--texto-3);
        font-size: 0.85rem;
        padding: 2.5rem 0;
        text-align: center;
      }
    `,
  ],
})
export class GraficaDonut {
  readonly reparto = input.required<Reparto>()

  /**
   * Cómo se escriben las cifras de la leyenda y del centro.
   *
   * Por defecto son cuentas de gente, que es lo que pide el panel general. La
   * contabilidad reparte euros, y ahí un «1.240» a secas al lado de otro «310»
   * no dice si son cuentas, apuntes o dinero.
   */
  readonly formato = input<'entero' | 'dinero' | 'porcentaje'>('entero')

  protected readonly RADIO = 45

  private readonly circunferencia = 2 * Math.PI * this.RADIO

  protected readonly total = computed(() =>
    this.reparto().trozos.reduce((suma, t) => suma + t.valor, 0),
  )

  /*
    El total del centro va SIN decimales cuando son euros. Cabe justo: el hueco
    del donut son unos 60 px de viewBox, y un «1.234,56 €» completo se sale por
    los dos lados. Los céntimos exactos están en la leyenda, aquí al lado.
  */
  protected readonly totalTexto = computed(() =>
    this.formato() === 'dinero' ? dineroCorto(this.total()) : segunFormato(this.total(), this.formato()),
  )

  protected readonly trozos = computed(() => {
    const total = this.total()
    if (total === 0) return []

    let acumulado = 0

    return this.reparto()
      .trozos.filter((t) => t.valor > 0)
      .map((trozo, i) => {
        const parte = trozo.valor / total
        const pintado = parte * this.circunferencia
        // El desfase es negativo porque el trazo avanza en sentido horario desde
        // el punto de inicio; girarlo «hacia atrás» es lo que va colocando cada
        // trozo justo donde acabó el anterior.
        const giro = -acumulado * this.circunferencia
        acumulado += parte

        return {
          etiqueta: trozo.etiqueta,
          color: colorDe(i, trozo.color),
          pintado,
          resto: this.circunferencia - pintado,
          giro,
          texto: segunFormato(trozo.valor, this.formato()),
          porcentaje: Math.round(parte * 100),
        }
      })
  })
}
