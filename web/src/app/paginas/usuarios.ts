import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { RouterLink } from '@angular/router'
import { firstValueFrom } from 'rxjs'

import { Icono } from '../componentes/icono'
import { Api, type ErrorApi } from '../nucleo/api'
import { entero, fecha, ultimoAcceso } from '../nucleo/formato'
import type { UsuarioGlobal } from '../nucleo/tipos'

/**
 * Todas las cuentas de todas las apps, en una sola lista.
 *
 * Lo que resuelve esta pantalla es una pregunta concreta que en las tablas de
 * cada app no se puede contestar: «me escribe esta persona, ¿en qué app está y
 * desde cuándo?». Por eso se busca por correo sobre el conjunto y la columna de
 * aplicación va la primera.
 *
 * También hace visible una cosa que de otro modo pasa desapercibida: la misma
 * persona con cuenta en dos apps aparece dos veces, con su alta y su último
 * acceso en cada una.
 */
@Component({
  selector: 'bd-pagina-usuarios',
  standalone: true,
  imports: [FormsModule, RouterLink, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cabecera">
      <div>
        <h1>Usuarios</h1>
        <p>Las cuentas de todas las aplicaciones, juntas.</p>
      </div>
      <button type="button" class="boton secundario" (click)="cargar()" [disabled]="cargando()">
        <bd-icono nombre="recargar" [tamano]="16" />
        {{ cargando() ? 'Cargando…' : 'Actualizar' }}
      </button>
    </header>

    @if (error(); as fallo) {
      <p class="fallo">{{ fallo }}</p>
    }

    <div class="filtros">
      <label class="buscador">
        <bd-icono nombre="buscar" [tamano]="16" />
        <input
          type="search"
          placeholder="Buscar por nombre o correo en todas las apps…"
          [ngModel]="busqueda()"
          (ngModelChange)="busqueda.set($event)"
          aria-label="Buscar"
        />
      </label>

      <div class="chips">
        <button type="button" [class.activo]="filtroApp() === null" (click)="filtroApp.set(null)">
          Todas
        </button>
        @for (app of apps(); track app.id) {
          <button type="button" [class.activo]="filtroApp() === app.id" (click)="filtroApp.set(app.id)">
            <i [style.background]="app.color"></i>{{ app.nombre }}
            <span>{{ app.cuantos }}</span>
          </button>
        }
      </div>
    </div>

    @if (cargando() && todos().length === 0) {
      <p class="cargando">Reuniendo las cuentas de cada aplicación…</p>
    } @else if (filtrados().length === 0) {
      <p class="vacia">
        {{ todos().length === 0 ? 'No hay ninguna cuenta que enseñar' : 'Nadie coincide con esa búsqueda' }}
      </p>
    } @else {
      <p class="cuenta">{{ resumen() }}</p>
      <div class="marco-tabla">
        <table>
          <thead>
            <tr>
              <th>Aplicación</th>
              <th>Cuenta</th>
              <th>Plan o rol</th>
              <th>Alta</th>
              <th>Último acceso</th>
              <th class="numero">Móviles</th>
            </tr>
          </thead>
          <tbody>
            @for (fila of filtrados(); track fila.appId + ':' + fila.usuario.id) {
              <tr [class.inactivo]="!fila.usuario.activo">
                <td>
                  <a [routerLink]="['/apps', fila.appId]" class="app">
                    <i [style.background]="fila.appColor"></i>{{ fila.appNombre }}
                  </a>
                </td>
                <td>
                  <div class="quien">
                    <strong>{{ fila.usuario.nombre || 'Sin nombre' }}</strong>
                    <small>{{ fila.usuario.email || '—' }}</small>
                  </div>
                </td>
                <td>
                  <span class="pastilla">{{ fila.usuario.plan || '—' }}</span>
                  @if (!fila.usuario.activo) {
                    <span class="pastilla mal">de baja</span>
                  }
                </td>
                <td>{{ alta(fila) }}</td>
                <td [title]="fila.usuario.ultimaSesion || 'nunca ha entrado'">{{ acceso(fila) }}</td>
                <td class="numero">{{ fila.usuario.dispositivos || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
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

      .filtros {
        display: flex;
        gap: 0.85rem;
        flex-wrap: wrap;
        align-items: center;
        margin-bottom: 1rem;
      }

      .buscador {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        background: var(--fondo-2);
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        padding: 0 0.75rem;
        width: min(380px, 100%);
        color: var(--texto-3);
      }

      .buscador input {
        border: none;
        background: none;
        padding: 0.55rem 0;
      }

      .buscador input:focus {
        outline: none;
        box-shadow: none;
      }

      .buscador:focus-within {
        border-color: var(--acento);
      }

      .chips {
        display: flex;
        gap: 0.4rem;
        flex-wrap: wrap;
      }

      .chips button {
        display: inline-flex;
        align-items: center;
        gap: 0.45rem;
        padding: 0.35rem 0.75rem;
        border-radius: 99px;
        border: 1px solid var(--borde);
        font-size: 0.79rem;
        font-weight: 600;
        color: var(--texto-3);
        transition: border-color var(--transicion), color var(--transicion);
      }

      .chips button:hover {
        color: var(--texto);
        border-color: var(--borde-fuerte);
      }

      .chips button.activo {
        border-color: var(--acento);
        color: var(--acento-claro);
        background: var(--acento-suave);
      }

      .chips i {
        width: 8px;
        height: 8px;
        border-radius: 99px;
      }

      .chips span {
        opacity: 0.65;
        font-variant-numeric: tabular-nums;
      }

      .cuenta {
        font-size: 0.78rem;
        color: var(--texto-3);
        margin-bottom: 0.6rem;
      }

      .app {
        display: inline-flex;
        align-items: center;
        gap: 0.45rem;
        font-weight: 600;
        color: var(--texto-2);
        white-space: nowrap;
      }

      .app:hover {
        color: var(--texto);
      }

      .app i {
        width: 8px;
        height: 8px;
        border-radius: 99px;
      }

      .quien {
        display: flex;
        flex-direction: column;
      }

      .quien strong {
        color: var(--texto);
        font-size: 0.87rem;
        font-weight: 600;
      }

      .quien small {
        color: var(--texto-3);
        font-size: 0.76rem;
      }

      tr.inactivo td {
        opacity: 0.55;
      }

      .cargando,
      .vacia {
        padding: 3rem;
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
        font-size: 0.88rem;
        margin-bottom: 1rem;
      }
    `,
  ],
})
export class PaginaUsuarios {
  private readonly api = inject(Api)

  protected readonly todos = signal<UsuarioGlobal[]>([])
  protected readonly cargando = signal(false)
  protected readonly error = signal<string | null>(null)
  protected readonly busqueda = signal('')
  protected readonly filtroApp = signal<string | null>(null)

  ngOnInit(): void {
    void this.cargar()
  }

  protected async cargar(): Promise<void> {
    this.cargando.set(true)
    this.error.set(null)
    try {
      this.todos.set(await firstValueFrom(this.api.usuariosDeTodas()))
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  /** Las apps que han aportado cuentas, con cuántas trae cada una. */
  protected readonly apps = computed(() => {
    const mapa = new Map<string, { id: string; nombre: string; color: string; cuantos: number }>()
    for (const fila of this.todos()) {
      const actual = mapa.get(fila.appId)
      if (actual) {
        actual.cuantos++
      } else {
        mapa.set(fila.appId, { id: fila.appId, nombre: fila.appNombre, color: fila.appColor, cuantos: 1 })
      }
    }
    return [...mapa.values()]
  })

  protected readonly filtrados = computed(() => {
    const texto = this.busqueda().trim().toLowerCase()
    const app = this.filtroApp()

    return this.todos().filter((fila) => {
      if (app && fila.appId !== app) return false
      if (!texto) return true
      return `${fila.usuario.nombre ?? ''} ${fila.usuario.email ?? ''}`.toLowerCase().includes(texto)
    })
  })

  protected readonly resumen = computed(() => {
    const total = this.todos().length
    const vistos = this.filtrados().length
    return vistos === total
      ? `${entero(total)} cuentas en total`
      : `${entero(vistos)} de ${entero(total)} cuentas`
  })

  protected alta(fila: UsuarioGlobal): string {
    return fecha(fila.usuario.alta)
  }

  protected acceso(fila: UsuarioGlobal): string {
    return ultimoAcceso(fila.usuario.ultimaSesion)
  }
}
