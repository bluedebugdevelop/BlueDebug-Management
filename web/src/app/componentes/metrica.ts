import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core'

import { segunFormato } from '../nucleo/formato'
import type { Metrica } from '../nucleo/tipos'

/**
 * Una tarjeta con un número grande.
 *
 * La variación, cuando la hay, se pinta en verde o en rojo según suba o baje.
 * Ojo con eso: aquí «arriba es bueno» siempre, lo cual vale para altas y para
 * dinero pero no valdría para una métrica de errores. El día que haya una así,
 * hará falta una marca en el backend que diga en qué dirección está lo bueno,
 * en vez de darlo por hecho aquí.
 */
@Component({
  selector: 'bd-metrica',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="tarjeta">
      <p class="etiqueta">{{ metrica().etiqueta }}</p>
      <p class="valor">{{ texto() }}</p>

      <div class="pie">
        @if (metrica().variacion !== null) {
          <span class="variacion" [class.sube]="sube()" [class.baja]="!sube()">
            {{ sube() ? '▲' : '▼' }} {{ variacion() }}
          </span>
        }
        @if (metrica().detalle) {
          <span class="detalle">{{ metrica().detalle }}</span>
        }
      </div>
    </article>
  `,
  styles: [
    `
      .tarjeta {
        padding: 1.1rem 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        min-height: 100%;
      }

      .etiqueta {
        font-size: 0.78rem;
        font-weight: 600;
        color: var(--texto-3);
        letter-spacing: 0.02em;
        margin: 0;
      }

      .valor {
        font-size: 1.85rem;
        font-weight: 700;
        letter-spacing: -0.03em;
        line-height: 1.15;
        font-variant-numeric: tabular-nums;
        margin: 0.15rem 0 0;
      }

      .pie {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
        margin-top: 0.2rem;
      }

      .variacion {
        font-size: 0.75rem;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
      }

      .sube {
        color: #86efac;
      }

      .baja {
        color: #fca5a5;
      }

      .detalle {
        font-size: 0.75rem;
        color: var(--texto-3);
      }
    `,
  ],
})
export class TarjetaMetrica {
  readonly metrica = input.required<Metrica>()

  protected readonly texto = computed(() =>
    segunFormato(this.metrica().valor, this.metrica().formato),
  )

  protected readonly sube = computed(() => (this.metrica().variacion ?? 0) >= 0)

  protected readonly variacion = computed(() => {
    const v = this.metrica().variacion ?? 0
    return `${Math.abs(Math.round(v * 100))} %`
  })
}
