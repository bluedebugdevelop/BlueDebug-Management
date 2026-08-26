import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'

import { entero, fecha, ultimoAcceso } from '../nucleo/formato'
import type { CampoExtra, UsuarioApp } from '../nucleo/tipos'
import { Icono } from './icono'

/**
 * La tabla de cuentas.
 *
 * Las columnas fijas son las que tienen sentido en cualquier app —quién, cuándo
 * se dio de alta, cuándo entró por última vez, qué plan o rol tiene— y las demás
 * las declara cada conector en {@code camposExtra}. Por eso la misma tabla vale
 * para los suscriptores de VBStats y para los socios del club sin un solo
 * `if (app === ...)`.
 *
 * El buscador filtra en el navegador y no en el servidor a propósito: son listas
 * de cientos de filas, ya están descargadas, y buscar en local responde al
 * teclear en vez de a los 300 ms de una petición.
 */
@Component({
  selector: 'bd-tabla-usuarios',
  standalone: true,
  imports: [FormsModule, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="barra">
      <label class="buscador">
        <bd-icono nombre="buscar" [tamano]="16" />
        <input
          type="search"
          placeholder="Buscar por nombre o correo…"
          [ngModel]="busqueda()"
          (ngModelChange)="busqueda.set($event)"
          aria-label="Buscar usuarios"
        />
      </label>
      <span class="cuenta">{{ resumenCuenta() }}</span>
    </div>

    @if (filtrados().length === 0) {
      <p class="vacia">
        {{ usuarios().length === 0 ? 'No hay ninguna cuenta todavía' : 'Nadie coincide con esa búsqueda' }}
      </p>
    } @else {
      <div class="marco-tabla">
        <table>
          <thead>
            <tr>
              <th>Cuenta</th>
              @if (etiquetaPlan()) {
                <th>{{ etiquetaPlan() }}</th>
              }
              <th>Alta</th>
              <th>Último acceso</th>
              <th class="numero">Móviles</th>
              @for (campo of camposExtra(); track campo.clave) {
                <th>{{ campo.etiqueta }}</th>
              }
              @if (borrable()) {
                <th></th>
              }
            </tr>
          </thead>
          <tbody>
            @for (usuario of filtrados(); track usuario.id) {
              <tr [class.inactivo]="!usuario.activo">
                <td>
                  <div class="quien">
                    <strong>{{ usuario.nombre || 'Sin nombre' }}</strong>
                    <small>{{ usuario.email || '—' }}</small>
                  </div>
                </td>
                @if (etiquetaPlan()) {
                  <td>
                    <span class="pastilla" [class.acento]="destacaPlan(usuario)">{{ usuario.plan || '—' }}</span>
                    @if (!usuario.activo) {
                      <span class="pastilla mal">de baja</span>
                    }
                  </td>
                }
                <td>{{ alta(usuario) }}</td>
                <td [title]="usuario.ultimaSesion || 'nunca ha entrado'">{{ acceso(usuario) }}</td>
                <td class="numero">{{ usuario.dispositivos || '—' }}</td>
                @for (campo of camposExtra(); track campo.clave) {
                  <td>{{ extra(usuario, campo) }}</td>
                }
                @if (borrable()) {
                  <td class="acciones">
                    @if (confirmando() === usuario.id) {
                      <div class="confirmar">
                        <button type="button" class="mini peligro" (click)="borrar.emit(usuario)">
                          {{ textoBorrar() }}
                        </button>
                        <button type="button" class="mini" (click)="confirmando.set(null)">No</button>
                      </div>
                    } @else {
                      <button
                        type="button"
                        class="icono-boton"
                        [title]="textoBorrar()"
                        (click)="confirmando.set(usuario.id)"
                      >
                        <bd-icono nombre="papelera" [tamano]="16" />
                      </button>
                    }
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: [
    `
      .barra {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 1rem;
        margin-bottom: 0.9rem;
        flex-wrap: wrap;
      }

      .buscador {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        background: var(--fondo-2);
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        padding: 0 0.75rem;
        width: min(340px, 100%);
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

      .cuenta {
        font-size: 0.78rem;
        color: var(--texto-3);
        font-variant-numeric: tabular-nums;
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

      .acciones {
        text-align: right;
        white-space: nowrap;
      }

      .icono-boton {
        color: var(--texto-3);
        padding: 0.3rem;
        border-radius: 6px;
        transition: color var(--transicion), background var(--transicion);
      }

      .icono-boton:hover {
        color: #fca5a5;
        background: rgba(239, 68, 68, 0.12);
      }

      .confirmar {
        display: inline-flex;
        gap: 0.35rem;
      }

      .mini {
        font-size: 0.74rem;
        font-weight: 600;
        padding: 0.25rem 0.6rem;
        border-radius: 6px;
        border: 1px solid var(--borde-fuerte);
        color: var(--texto-2);
      }

      .mini.peligro {
        border-color: rgba(239, 68, 68, 0.5);
        color: #fca5a5;
      }

      .vacia {
        color: var(--texto-3);
        font-size: 0.87rem;
        padding: 2.5rem;
        text-align: center;
        border: 1px dashed var(--borde);
        border-radius: var(--radio);
      }
    `,
  ],
})
export class TablaUsuarios {
  readonly usuarios = input.required<UsuarioApp[]>()
  readonly camposExtra = input<CampoExtra[]>([])
  /** Cabecera de la columna de plan/rol; vacío la esconde. */
  readonly etiquetaPlan = input<string>('Plan')
  readonly borrable = input(false)
  /** Qué dice el botón: en unas apps es borrar y en otras dar de baja. */
  readonly textoBorrar = input('Borrar')

  readonly borrar = output<UsuarioApp>()

  protected readonly busqueda = signal('')
  protected readonly confirmando = signal<string | null>(null)

  protected readonly filtrados = computed(() => {
    const texto = this.busqueda().trim().toLowerCase()
    if (!texto) {
      return this.usuarios()
    }
    return this.usuarios().filter((u) =>
      `${u.nombre ?? ''} ${u.email ?? ''} ${u.plan ?? ''}`.toLowerCase().includes(texto),
    )
  })

  protected readonly resumenCuenta = computed(() => {
    const total = this.usuarios().length
    const vistos = this.filtrados().length
    return vistos === total
      ? `${entero(total)} cuentas`
      : `${entero(vistos)} de ${entero(total)}`
  })

  protected alta(usuario: UsuarioApp): string {
    return fecha(usuario.alta)
  }

  protected acceso(usuario: UsuarioApp): string {
    return ultimoAcceso(usuario.ultimaSesion)
  }

  protected destacaPlan(usuario: UsuarioApp): boolean {
    // Lo que se paga y lo que manda, resaltado; lo demás, en gris. Son los dos
    // casos que se buscan con la vista al recorrer la tabla.
    return ['pro', 'basic', 'admin', 'entrenador'].includes((usuario.plan ?? '').toLowerCase())
  }

  protected extra(usuario: UsuarioApp, campo: CampoExtra): string {
    const valor = usuario.extra?.[campo.clave]

    if (valor === null || valor === undefined || valor === '') return '—'
    if (Array.isArray(valor)) return valor.length ? valor.join(', ') : '—'
    if (typeof valor === 'boolean') return valor ? 'Sí' : 'No'
    if (typeof valor === 'number') return entero(valor)

    // Las fechas llegan como texto ISO; se reconocen por la forma y se acortan,
    // porque una marca de tiempo completa en una celda estrecha no se lee.
    const texto = String(valor)
    return /^\d{4}-\d{2}-\d{2}T/.test(texto) ? fecha(texto) : texto
  }
}
