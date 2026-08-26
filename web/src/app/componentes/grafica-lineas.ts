import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core'

import { colorDe, diaCorto, segunFormato } from '../nucleo/formato'
import type { Serie } from '../nucleo/tipos'

/**
 * Gráfica de líneas dibujada a mano en SVG.
 *
 * Sin librería de gráficas, y no por purismo: Chart.js o similares añaden unos
 * 200 kB al bundle y una API entera que aprender, para pintar dos líneas y una
 * cuadrícula. Esto son cien líneas de trigonometría de instituto que además se
 * pueden estilar con las mismas variables CSS que el resto del panel, así que las
 * gráficas cambian de color solos si un día cambia la marca.
 *
 * Detalles que sí importan y que están resueltos:
 *   · El eje Y empieza SIEMPRE en cero. Un eje recortado hace que una subida del
 *     2 % parezca vertical, y en un panel de negocio eso es engañarse solo.
 *   · Cuando todos los valores son cero, se fuerza un máximo de 1 para que la
 *     línea salga plana abajo en vez de dividir entre cero.
 *   · El SVG lleva viewBox y `preserveAspectRatio`, así que escala con la caja
 *     sin necesidad de recalcular nada al cambiar el tamaño de la ventana.
 */
@Component({
  selector: 'bd-grafica-lineas',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="grafica">
      <figcaption>
        <span class="titulo">{{ titulo() }}</span>
        <span class="leyenda">
          @for (s of series(); track s.clave; let i = $index) {
            <span class="marca">
              <i [style.background]="colorSerie(i, s)"></i>{{ s.etiqueta }}
            </span>
          }
        </span>
      </figcaption>

      @if (sinDatos()) {
        <p class="vacia">No hay nada que enseñar en este periodo</p>
      } @else {
        <svg
          [attr.viewBox]="'0 0 ' + ANCHO + ' ' + ALTO"
          preserveAspectRatio="none"
          class="lienzo"
          (mouseleave)="posado.set(null)"
        >
          <!-- Cuadrícula: cuatro líneas y sus valores. Más ensucia. -->
          @for (linea of cuadricula(); track linea.y) {
            <line class="rejilla" x1="0" [attr.y1]="linea.y" [attr.x2]="ANCHO" [attr.y2]="linea.y" />
            <text class="marca-eje" x="4" [attr.y]="linea.y - 4">{{ linea.texto }}</text>
          }

          @for (s of series(); track s.clave; let i = $index) {
            <path class="area" [attr.d]="area(s)" [attr.fill]="'url(#degradado-' + i + ')'" />
            <path
              class="linea"
              [attr.d]="linea(s)"
              [attr.stroke]="colorSerie(i, s)"
            />
          }

          <!-- Las columnas invisibles capturan el ratón: así el punto se
               engancha al día más cercano en vez de exigir puntería. -->
          @for (dia of dias(); track dia; let i = $index) {
            <rect
              class="zona"
              [attr.x]="x(i) - anchoColumna() / 2"
              y="0"
              [attr.width]="anchoColumna()"
              [attr.height]="ALTO"
              (mouseenter)="posado.set(i)"
            />
          }

          @if (posado() !== null) {
            <line class="guia" [attr.x1]="x(posado()!)" y1="0" [attr.x2]="x(posado()!)" [attr.y2]="ALTO" />
            @for (s of series(); track s.clave; let i = $index) {
              <circle
                [attr.cx]="x(posado()!)"
                [attr.cy]="y(s.puntos[posado()!].valor)"
                r="4"
                [attr.fill]="colorSerie(i, s)"
                class="punto"
              />
            }
          }

          <defs>
            @for (s of series(); track s.clave; let i = $index) {
              <linearGradient [attr.id]="'degradado-' + i" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" [attr.stop-color]="colorSerie(i, s)" stop-opacity="0.28" />
                <stop offset="100%" [attr.stop-color]="colorSerie(i, s)" stop-opacity="0" />
              </linearGradient>
            }
          </defs>
        </svg>

        <div class="eje-x">
          <span>{{ primerDia() }}</span>
          <span>{{ ultimoDia() }}</span>
        </div>

        @if (posado() !== null) {
          <div class="globo">
            <strong>{{ diaLegible(posado()!) }}</strong>
            @for (s of series(); track s.clave) {
              <span>{{ s.etiqueta }}: {{ valorLegible(s, posado()!) }}</span>
            }
          </div>
        }
      }
    </figure>
  `,
  styles: [
    `
      .grafica {
        background: var(--superficie);
        border: 1px solid var(--borde);
        border-radius: var(--radio);
        padding: 1.1rem 1.25rem 0.9rem;
        margin: 0;
        position: relative;
      }

      figcaption {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 0.9rem;
      }

      .titulo {
        font-size: 0.92rem;
        font-weight: 600;
      }

      .leyenda {
        display: flex;
        gap: 0.85rem;
        flex-wrap: wrap;
      }

      .marca {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        font-size: 0.75rem;
        color: var(--texto-3);
      }

      .marca i {
        width: 9px;
        height: 3px;
        border-radius: 2px;
      }

      .lienzo {
        width: 100%;
        height: 190px;
        display: block;
        overflow: visible;
      }

      .rejilla {
        stroke: rgba(255, 255, 255, 0.06);
        stroke-width: 1;
        vector-effect: non-scaling-stroke;
      }

      .marca-eje {
        fill: var(--texto-3);
        font-size: 9px;
      }

      .linea {
        fill: none;
        stroke-width: 2;
        stroke-linecap: round;
        stroke-linejoin: round;
        vector-effect: non-scaling-stroke;
      }

      .guia {
        stroke: rgba(255, 255, 255, 0.25);
        stroke-width: 1;
        stroke-dasharray: 3 3;
        vector-effect: non-scaling-stroke;
      }

      .punto {
        stroke: var(--superficie);
        stroke-width: 2;
      }

      .zona {
        fill: transparent;
      }

      .eje-x {
        display: flex;
        justify-content: space-between;
        font-size: 0.72rem;
        color: var(--texto-3);
        margin-top: 0.4rem;
      }

      .globo {
        position: absolute;
        top: 1rem;
        right: 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
        background: var(--fondo);
        border: 1px solid var(--borde-fuerte);
        border-radius: var(--radio-chico);
        padding: 0.5rem 0.7rem;
        font-size: 0.75rem;
        color: var(--texto-2);
        pointer-events: none;
        box-shadow: var(--sombra);
      }

      .globo strong {
        color: var(--texto);
        font-size: 0.78rem;
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
export class GraficaLineas {
  readonly titulo = input.required<string>()
  readonly series = input.required<Serie[]>()

  protected readonly ANCHO = 600
  protected readonly ALTO = 190

  protected readonly posado = signal<number | null>(null)

  protected readonly dias = computed(() => this.series()[0]?.puntos.map((p) => p.fecha) ?? [])

  protected readonly sinDatos = computed(
    () => this.dias().length === 0 || this.series().every((s) => s.puntos.every((p) => p.valor === 0)),
  )

  /** El techo de la gráfica, con un 15 % de aire para que la línea no roce el borde. */
  private readonly maximo = computed(() => {
    const valores = this.series().flatMap((s) => s.puntos.map((p) => p.valor))
    const alto = Math.max(0, ...valores)
    return alto === 0 ? 1 : alto * 1.15
  })

  protected readonly cuadricula = computed(() => {
    const formato = this.series()[0]?.formato ?? 'entero'
    return [0, 0.25, 0.5, 0.75, 1].map((parte) => ({
      y: this.ALTO - parte * this.ALTO,
      texto: segunFormato(Math.round(this.maximo() * parte), formato),
    }))
  })

  protected readonly anchoColumna = computed(() => this.ANCHO / Math.max(1, this.dias().length))

  protected x(indice: number): number {
    const total = this.dias().length
    if (total <= 1) return this.ANCHO / 2
    return (indice / (total - 1)) * this.ANCHO
  }

  protected y(valor: number): number {
    return this.ALTO - (valor / this.maximo()) * this.ALTO
  }

  protected linea(serie: Serie): string {
    return serie.puntos
      .map((punto, i) => `${i === 0 ? 'M' : 'L'} ${this.x(i).toFixed(1)} ${this.y(punto.valor).toFixed(1)}`)
      .join(' ')
  }

  /** El relleno bajo la línea: la misma trayectoria, cerrada por abajo. */
  protected area(serie: Serie): string {
    if (serie.puntos.length === 0) return ''
    return `${this.linea(serie)} L ${this.ANCHO} ${this.ALTO} L 0 ${this.ALTO} Z`
  }

  protected colorSerie(indice: number, serie: Serie): string {
    // Las series que vienen del panel general traen el id de su app como clave, y
    // ahí el color lo pone la app. En las demás manda el orden.
    return colorDe(indice, COLORES_POR_CLAVE[serie.clave])
  }

  protected primerDia(): string {
    return this.dias().length ? diaCorto(this.dias()[0]) : ''
  }

  protected ultimoDia(): string {
    const dias = this.dias()
    return dias.length ? diaCorto(dias[dias.length - 1]) : ''
  }

  protected diaLegible(indice: number): string {
    return diaCorto(this.dias()[indice])
  }

  protected valorLegible(serie: Serie, indice: number): string {
    const punto = serie.puntos[indice]
    return punto ? segunFormato(punto.valor, serie.formato) : '—'
  }
}

/** Colores fijos para las series que se repiten en varias pantallas. */
const COLORES_POR_CLAVE: Record<string, string> = {
  vbstats: '#2196f3',
  cvo: '#f97316',
}
