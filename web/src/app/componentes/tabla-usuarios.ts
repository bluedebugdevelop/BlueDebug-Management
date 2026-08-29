import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'

import { entero, fecha, ultimoAcceso } from '../nucleo/formato'
import type { CampoExtra, EdicionRol, UsuarioApp } from '../nucleo/tipos'
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
    @if (edicionRol()?.aviso; as aviso) {
      <p class="aviso-rol">{{ aviso }}</p>
    }

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
                  <td class="celda-rol">
                    @if (editando() === usuario.id) {
                      <div class="editor">
                        @if (edicionRol()!.multiple) {
                          @for (opcion of edicionRol()!.opciones; track opcion.valor) {
                            <label class="opcion" [title]="opcion.detalle || ''">
                              <input
                                type="checkbox"
                                [checked]="seleccion().includes(opcion.valor)"
                                (change)="alternar(opcion.valor)"
                              />
                              {{ opcion.etiqueta }}
                            </label>
                          }
                        } @else {
                          <select
                            [ngModel]="seleccion()[0]"
                            (ngModelChange)="seleccion.set([$event])"
                            [name]="'rol-' + usuario.id"
                            aria-label="Elegir"
                          >
                            @for (opcion of edicionRol()!.opciones; track opcion.valor) {
                              <option [value]="opcion.valor">{{ opcion.etiqueta }}</option>
                            }
                          </select>
                        }

                        <div class="botones-editor">
                          <button
                            type="button"
                            class="mini guardar"
                            [disabled]="seleccion().length === 0 || guardando()"
                            (click)="guardar(usuario)"
                          >
                            {{ guardando() ? 'Guardando…' : 'Guardar' }}
                          </button>
                          <button type="button" class="mini" (click)="editando.set(null)">Cancelar</button>
                        </div>
                      </div>
                    } @else {
                      <span class="valores">
                        @for (rol of rolesDe(usuario); track rol) {
                          <span class="pastilla" [class.acento]="destaca(rol)">{{ nombreDe(rol) }}</span>
                        } @empty {
                          <span class="pastilla">—</span>
                        }
                        @if (!usuario.activo) {
                          <span class="pastilla mal">de baja</span>
                        }
                        @if (edicionRol()) {
                          <button
                            type="button"
                            class="icono-boton"
                            [title]="'Cambiar ' + edicionRol()!.etiqueta.toLowerCase()"
                            (click)="editar(usuario)"
                          >
                            <bd-icono nombre="ajustes" [tamano]="14" />
                          </button>
                        }
                      </span>
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

      /* --- editor de roles --- */

      .aviso-rol {
        font-size: 0.79rem;
        line-height: 1.5;
        color: var(--aviso);
        border-left: 2px solid var(--aviso);
        background: rgba(245, 158, 11, 0.06);
        border-radius: 0 8px 8px 0;
        padding: 0.6rem 0.85rem;
        margin-bottom: 0.9rem;
        max-width: 70ch;
      }

      .celda-rol {
        min-width: 190px;
      }

      .valores {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        flex-wrap: wrap;
      }

      /* El lápiz solo aparece al pasar por la fila: si estuviera siempre visible,
         una tabla de cien cuentas sería cien botones compitiendo por la vista. */
      .valores .icono-boton {
        opacity: 0;
        transition: opacity var(--transicion);
      }

      tr:hover .valores .icono-boton,
      .valores .icono-boton:focus-visible {
        opacity: 1;
      }

      .icono-boton:focus-visible {
        outline: 2px solid var(--acento);
        outline-offset: 2px;
      }

      .editor {
        display: flex;
        flex-direction: column;
        gap: 0.45rem;
        padding: 0.5rem;
        border: 1px solid var(--borde-fuerte);
        border-radius: var(--radio-chico);
        background: var(--fondo-2);
        min-width: 210px;
      }

      .opcion {
        display: flex;
        align-items: center;
        gap: 0.45rem;
        font-size: 0.82rem;
        color: var(--texto-2);
        cursor: pointer;
      }

      .opcion input {
        width: auto;
        margin: 0;
        accent-color: var(--acento);
      }

      .editor select {
        padding: 0.35rem 0.5rem;
        font-size: 0.83rem;
      }

      .botones-editor {
        display: flex;
        gap: 0.35rem;
        margin-top: 0.15rem;
      }

      .mini.guardar {
        border-color: var(--acento);
        color: var(--acento-claro);
      }

      .mini:disabled {
        opacity: 0.5;
        cursor: not-allowed;
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

  /** Cómo se editan los roles aquí. Null esconde el editor por completo. */
  readonly edicionRol = input<EdicionRol | null>(null)

  readonly borrar = output<UsuarioApp>()
  readonly cambiarRol = output<{ usuario: UsuarioApp; roles: string[] }>()

  /** Lo pone el padre mientras la llamada está en vuelo. */
  readonly guardando = input(false)

  protected readonly busqueda = signal('')
  protected readonly confirmando = signal<string | null>(null)
  protected readonly editando = signal<string | null>(null)
  protected readonly seleccion = signal<string[]>([])

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

  /**
   * Los roles que tiene ahora esa cuenta.
   *
   * Cuando la app admite varios, vienen en `extra.roles` como lista de verdad.
   * Cuando admite uno solo —el plan de VBStats—, `plan` ya es ese valor. Así el
   * mismo componente sirve para las dos formas sin saber de qué app se trata.
   */
  protected rolesDe(usuario: UsuarioApp): string[] {
    const extra = usuario.extra?.['roles']
    if (Array.isArray(extra)) {
      return extra.map(String)
    }
    return usuario.plan ? [usuario.plan] : []
  }

  protected nombreDe(valor: string): string {
    const opcion = this.edicionRol()?.opciones.find((o) => o.valor === valor)
    return opcion?.etiqueta ?? valor
  }

  protected destaca(rol: string): boolean {
    // Lo que se paga y lo que manda, resaltado; lo demás, en gris. Son los dos
    // casos que se buscan con la vista al recorrer la tabla.
    return ['pro', 'basic', 'admin', 'entrenador'].includes(rol.toLowerCase())
  }

  protected editar(usuario: UsuarioApp): void {
    this.seleccion.set(this.rolesDe(usuario))
    this.editando.set(usuario.id)
    // Abrir el editor y dejar abierta una confirmación de borrado de otra fila
    // sería pedir un clic en el sitio equivocado.
    this.confirmando.set(null)
  }

  protected alternar(valor: string): void {
    this.seleccion.update((actuales) =>
      actuales.includes(valor) ? actuales.filter((v) => v !== valor) : [...actuales, valor],
    )
  }

  protected guardar(usuario: UsuarioApp): void {
    this.cambiarRol.emit({ usuario, roles: this.seleccion() })
    this.editando.set(null)
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
