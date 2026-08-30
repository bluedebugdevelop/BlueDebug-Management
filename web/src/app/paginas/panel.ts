import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { RouterLink } from '@angular/router'
import { firstValueFrom } from 'rxjs'

import { GraficaDonut } from '../componentes/grafica-donut'
import { GraficaLineas } from '../componentes/grafica-lineas'
import { Icono } from '../componentes/icono'
import { TarjetaMetrica } from '../componentes/metrica'
import { Api, type ErrorApi } from '../nucleo/api'
import { dinero, entero } from '../nucleo/formato'
import { Sesion } from '../nucleo/sesion'
import type { PanelGeneral, ResumenApp } from '../nucleo/tipos'

/**
 * La pantalla de inicio: todo junto.
 *
 * Es la respuesta a «¿cómo va la cosa?» sin tener que entrar en cada app: cuánta
 * gente hay en total, cuánta ha entrado, cuánto ha entrado de dinero y qué apps
 * no están respondiendo.
 */
@Component({
  selector: 'bd-pagina-panel',
  standalone: true,
  imports: [RouterLink, TarjetaMetrica, GraficaLineas, GraficaDonut, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cabecera">
      <div>
        <h1>Panel</h1>
        <p>Todas las aplicaciones de BlueDebug, de un vistazo.</p>
      </div>

      <div class="controles">
        <div class="periodos">
          @for (dias of sesion.periodos(); track dias) {
            <button type="button" [class.activo]="dias === periodo()" (click)="cambiarPeriodo(dias)">
              {{ etiquetaPeriodo(dias) }}
            </button>
          }
        </div>
        <button type="button" class="boton secundario" (click)="cargar()" [disabled]="cargando()">
          <bd-icono nombre="recargar" [tamano]="16" />
          {{ cargando() ? 'Cargando…' : 'Actualizar' }}
        </button>
      </div>
    </header>

    @if (error(); as fallo) {
      <p class="fallo">{{ fallo }}</p>
    }

    @if (datos(); as panel) {
      <section class="metricas">
        @for (metrica of panel.totales; track metrica.clave) {
          <bd-metrica [metrica]="metrica" />
        }
      </section>

      <section class="graficas">
        <bd-grafica-lineas titulo="Altas por aplicación" [series]="panel.altas" />
        <bd-grafica-donut [reparto]="panel.porApp" />
      </section>

      @if (panel.dinero.facturado > 0 || panel.dinero.suscriptores > 0) {
        <section class="graficas">
          <bd-grafica-lineas titulo="Facturación diaria" [series]="[panel.dinero.porDia]" />
          <article class="tarjeta dinero">
            <h3 class="titulo-seccion">Suscripciones</h3>
            <dl>
              <div>
                <dt>Facturado en el periodo</dt>
                <dd>{{ euros(panel.dinero.facturado) }}</dd>
              </div>
              <div>
                <dt>Recurrente estimado</dt>
                <dd>{{ euros(panel.dinero.recurrente) }}<small>/mes</small></dd>
              </div>
              <div>
                <dt>Suscripciones activas</dt>
                <dd>{{ numero(panel.dinero.suscriptores) }}</dd>
              </div>
              @if (panel.dinero.devuelto > 0) {
                <div>
                  <dt>Devuelto</dt>
                  <dd class="malo">−{{ euros(panel.dinero.devuelto) }}</dd>
                </div>
              }
            </dl>
            <p class="nota">
              Stripe y App Store juntos. En las dos es el precio que pagó el cliente, antes de
              las comisiones de la pasarela.
            </p>
          </article>
        </section>
      }

      <section>
        <h2 class="titulo-seccion">Aplicaciones</h2>
        <div class="apps">
          @for (app of panel.apps; track app.app.id) {
            <a class="tarjeta app" [routerLink]="['/apps', app.app.id]">
              <header>
                <span class="icono" [style.color]="app.app.color" [style.background]="fondoDe(app.app.color)">
                  <bd-icono [nombre]="app.app.icono" [tamano]="20" />
                </span>
                <div>
                  <h3>{{ app.app.nombre }}</h3>
                  <p>{{ app.app.descripcion }}</p>
                </div>
                <span class="pastilla" [class.bien]="app.estado.disponible" [class.aviso]="!app.estado.disponible">
                  {{ app.estado.disponible ? 'En línea' : 'Sin datos' }}
                </span>
              </header>

              @if (app.estado.disponible) {
                <dl class="cifras">
                  @for (metrica of tresPrimeras(app); track metrica.clave) {
                    <div>
                      <dt>{{ metrica.etiqueta }}</dt>
                      <dd>{{ numero(metrica.valor) }}</dd>
                    </div>
                  }
                </dl>
              } @else {
                <p class="motivo">{{ app.estado.motivo }}</p>
              }
            </a>
          }
        </div>
      </section>
    } @else if (cargando()) {
      <p class="cargando">Leyendo las aplicaciones…</p>
    }
  `,
  styles: [
    `
      .cabecera {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1.25rem;
        flex-wrap: wrap;
        margin-bottom: 1.75rem;
      }

      h1 {
        font-size: 1.6rem;
        font-weight: 800;
        letter-spacing: -0.03em;
      }

      .cabecera p {
        color: var(--texto-3);
        font-size: 0.87rem;
        margin-top: 0.2rem;
      }

      .controles {
        display: flex;
        gap: 0.6rem;
        align-items: center;
        flex-wrap: wrap;
      }

      .periodos {
        display: flex;
        background: var(--fondo-2);
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        padding: 3px;
        gap: 2px;
      }

      .periodos button {
        padding: 0.35rem 0.8rem;
        border-radius: 6px;
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--texto-3);
        transition: background var(--transicion), color var(--transicion);
      }

      .periodos button:hover {
        color: var(--texto);
      }

      .periodos button.activo {
        background: var(--acento-suave);
        color: var(--acento-claro);
      }

      .metricas {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 0.9rem;
        margin-bottom: 1.25rem;
      }

      .graficas {
        display: grid;
        grid-template-columns: minmax(0, 1.7fr) minmax(0, 1fr);
        gap: 0.9rem;
        margin-bottom: 1.25rem;
      }

      @media (max-width: 950px) {
        .graficas {
          grid-template-columns: 1fr;
        }
      }

      .dinero dl {
        display: flex;
        flex-direction: column;
        gap: 0.7rem;
      }

      .dinero dl div {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        gap: 1rem;
      }

      .dinero dt {
        font-size: 0.83rem;
        color: var(--texto-3);
      }

      .dinero dd {
        font-size: 1.05rem;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
      }

      .dinero dd small {
        font-size: 0.72rem;
        font-weight: 500;
        color: var(--texto-3);
      }

      .dinero dd.malo {
        color: #fca5a5;
      }

      .nota {
        margin-top: 1rem;
        padding-top: 0.85rem;
        border-top: 1px solid var(--borde);
        font-size: 0.74rem;
        color: var(--texto-3);
        line-height: 1.45;
      }

      .apps {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
        gap: 0.9rem;
      }

      .app {
        display: block;
        transition: border-color var(--transicion), transform var(--transicion);
      }

      .app:hover {
        border-color: var(--borde-fuerte);
        transform: translateY(-2px);
      }

      .app header {
        display: flex;
        align-items: flex-start;
        gap: 0.8rem;
      }

      .app .icono {
        display: grid;
        place-items: center;
        width: 38px;
        height: 38px;
        border-radius: 11px;
        flex-shrink: 0;
      }

      .app h3 {
        font-size: 0.95rem;
        font-weight: 700;
      }

      .app header p {
        font-size: 0.78rem;
        color: var(--texto-3);
        margin-top: 0.1rem;
      }

      .app header .pastilla {
        margin-left: auto;
      }

      .cifras {
        display: flex;
        gap: 1.5rem;
        margin-top: 1.1rem;
        padding-top: 0.9rem;
        border-top: 1px solid var(--borde);
      }

      .cifras dt {
        font-size: 0.72rem;
        color: var(--texto-3);
      }

      .cifras dd {
        font-size: 1.15rem;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
        margin-top: 0.1rem;
      }

      .motivo {
        margin-top: 1rem;
        padding-top: 0.9rem;
        border-top: 1px solid var(--borde);
        font-size: 0.78rem;
        color: var(--aviso);
        line-height: 1.45;
      }

      .cargando,
      .fallo {
        padding: 2.5rem;
        text-align: center;
        color: var(--texto-3);
        font-size: 0.88rem;
      }

      .fallo {
        color: #fca5a5;
        border: 1px solid rgba(239, 68, 68, 0.35);
        border-radius: var(--radio);
        background: rgba(239, 68, 68, 0.07);
        padding: 1rem;
      }
    `,
  ],
})
export class PaginaPanel {
  private readonly api = inject(Api)
  protected readonly sesion = inject(Sesion)

  protected readonly datos = signal<PanelGeneral | null>(null)
  protected readonly cargando = signal(false)
  protected readonly error = signal<string | null>(null)
  protected readonly periodo = signal(leerPeriodoGuardado())

  ngOnInit(): void {
    void this.cargar()
  }

  protected async cargar(): Promise<void> {
    this.cargando.set(true)
    this.error.set(null)
    try {
      this.datos.set(await firstValueFrom(this.api.panel(this.periodo())))
      // El estado de las apps puede haber cambiado (una que vuelve a responder):
      // se refresca el menú de paso.
      void this.sesion.cargarApps()
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  protected cambiarPeriodo(dias: number): void {
    this.periodo.set(dias)
    guardarPeriodo(dias)
    void this.cargar()
  }

  protected etiquetaPeriodo(dias: number): string {
    return dias === 365 ? '1 año' : `${dias} días`
  }

  /** Las tres primeras métricas de una app, para la tarjeta del listado. */
  protected tresPrimeras(app: ResumenApp) {
    return app.metricas.slice(0, 3)
  }

  protected numero(valor: number): string {
    return entero(valor)
  }

  protected euros(valor: number): string {
    return dinero(valor)
  }

  /** El color de la app al 12 %, para el fondo de su icono. */
  protected fondoDe(color: string): string {
    return `${color}1f`
  }
}

/*
  El periodo elegido se recuerda entre visitas. Es un detalle pequeño que se
  agradece a diario: quien mira siempre los últimos 7 días no quiere volver a
  pulsarlo cada vez que abre el panel.

  Va con try/catch porque en una ventana privada o con las cookies bloqueadas,
  el simple hecho de tocar localStorage lanza una excepción, y eso no puede
  tumbar la pantalla de inicio.
*/
const CLAVE_PERIODO = 'bdm.periodo'

function leerPeriodoGuardado(): number {
  try {
    const guardado = Number(localStorage.getItem(CLAVE_PERIODO))
    return [7, 30, 90, 365].includes(guardado) ? guardado : 30
  } catch {
    return 30
  }
}

function guardarPeriodo(dias: number): void {
  try {
    localStorage.setItem(CLAVE_PERIODO, String(dias))
  } catch {
    /* da igual: es una comodidad, no un dato */
  }
}
