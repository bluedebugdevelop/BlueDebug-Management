import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { firstValueFrom } from 'rxjs'

import { Icono } from '../componentes/icono'
import { Api, type ErrorApi } from '../nucleo/api'
import { fechaHora, haceCuanto } from '../nucleo/formato'
import type { Apunte } from '../nucleo/tipos'

/**
 * Lo que se ha hecho desde el panel.
 *
 * Y hay que ser honesto con lo que esta pantalla es y lo que no: es la memoria
 * del proceso, no un registro permanente. Mientras el panel no tenga base de
 * datos propia, estos apuntes viven en memoria y se pierden cuando el contenedor
 * se reinicia —cada despliegue, sin ir más lejos—. Lo que sí perdura es el log
 * del servidor, donde cada acción queda escrita con nivel WARN y se puede buscar
 * meses después.
 *
 * Se dice en la propia pantalla, abajo, en vez de dejar que alguien confíe en un
 * historial que no está.
 */
@Component({
  selector: 'bd-pagina-actividad',
  standalone: true,
  imports: [Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cabecera">
      <div>
        <h1>Actividad</h1>
        <p>Las acciones lanzadas desde este panel.</p>
      </div>
      <button type="button" class="boton secundario" (click)="cargar()" [disabled]="cargando()">
        <bd-icono nombre="recargar" [tamano]="16" />
        {{ cargando() ? 'Cargando…' : 'Actualizar' }}
      </button>
    </header>

    @if (error(); as fallo) {
      <p class="fallo">{{ fallo }}</p>
    }

    @if (apuntes().length === 0) {
      <p class="vacia">
        @if (cargando()) {
          Cargando…
        } @else {
          Todavía no se ha hecho nada desde que arrancó el servidor.
        }
      </p>
    } @else {
      <ol class="linea">
        @for (apunte of apuntes(); track apunte.momento + apunte.accion) {
          <li>
            <span class="marca" [class.mal]="!apunte.correcto">
              <bd-icono [nombre]="apunte.correcto ? 'bien' : 'aviso'" [tamano]="15" />
            </span>
            <div class="cuerpo">
              <p class="que">
                <strong>{{ nombreAccion(apunte.accion) }}</strong>
                <span class="pastilla">{{ apunte.app }}</span>
              </p>
              <p class="detalle">{{ apunte.detalle }}</p>
              <p class="pie">
                {{ apunte.admin }} · <span [title]="exacto(apunte)">{{ cuando(apunte) }}</span>
              </p>
            </div>
          </li>
        }
      </ol>
    }

    <p class="nota">
      Esta lista vive en la memoria del servidor y se vacía en cada reinicio. La copia que
      perdura está en el log del servicio, donde cada acción queda escrita con el correo de
      quien la lanzó.
    </p>
  `,
  styles: [
    `
      .cabecera {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1.25rem;
        flex-wrap: wrap;
        margin-bottom: 1.5rem;
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

      .linea {
        list-style: none;
        display: flex;
        flex-direction: column;
      }

      .linea li {
        display: flex;
        gap: 0.9rem;
        padding-bottom: 1.1rem;
        position: relative;
      }

      /* El hilo vertical que une los apuntes; el último no lo lleva. */
      .linea li:not(:last-child)::before {
        content: '';
        position: absolute;
        left: 13px;
        top: 30px;
        bottom: 0;
        width: 1px;
        background: var(--borde);
      }

      .marca {
        display: grid;
        place-items: center;
        width: 27px;
        height: 27px;
        border-radius: 99px;
        background: rgba(34, 197, 94, 0.14);
        color: #86efac;
        flex-shrink: 0;
        z-index: 1;
      }

      .marca.mal {
        background: rgba(239, 68, 68, 0.14);
        color: #fca5a5;
      }

      .cuerpo {
        min-width: 0;
        flex: 1;
      }

      .que {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        flex-wrap: wrap;
      }

      .que strong {
        font-size: 0.9rem;
        font-weight: 600;
      }

      .detalle {
        font-size: 0.83rem;
        color: var(--texto-2);
        margin-top: 0.15rem;
      }

      .pie {
        font-size: 0.75rem;
        color: var(--texto-3);
        margin-top: 0.25rem;
      }

      .vacia {
        padding: 3rem;
        text-align: center;
        color: var(--texto-3);
        font-size: 0.88rem;
      }

      .nota {
        margin-top: 1.5rem;
        padding-top: 1rem;
        border-top: 1px solid var(--borde);
        font-size: 0.76rem;
        color: var(--texto-3);
        line-height: 1.5;
        max-width: 640px;
      }

      .fallo {
        color: #fca5a5;
        border: 1px solid rgba(239, 68, 68, 0.35);
        border-radius: var(--radio);
        background: rgba(239, 68, 68, 0.07);
        padding: 1rem;
        font-size: 0.88rem;
        margin-bottom: 1rem;
      }
    `,
  ],
})
export class PaginaActividad {
  private readonly api = inject(Api)

  protected readonly apuntes = signal<Apunte[]>([])
  protected readonly cargando = signal(false)
  protected readonly error = signal<string | null>(null)

  ngOnInit(): void {
    void this.cargar()
  }

  protected async cargar(): Promise<void> {
    this.cargando.set(true)
    this.error.set(null)
    try {
      this.apuntes.set(await firstValueFrom(this.api.actividad()))
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  /** El id técnico de la acción, escrito para leer. */
  protected nombreAccion(accion: string): string {
    if (accion.startsWith('borrar-usuario:')) {
      return `Baja de la cuenta ${accion.split(':')[1]}`
    }
    return NOMBRES[accion] ?? accion
  }

  protected cuando(apunte: Apunte): string {
    return haceCuanto(apunte.momento)
  }

  protected exacto(apunte: Apunte): string {
    return fechaHora(apunte.momento)
  }
}

const NOMBRES: Record<string, string> = {
  'enviar-notificacion': 'Notificación enviada',
  'enviar-aviso': 'Aviso enviado',
  'cambiar-alta': 'Cambio de alta',
}
