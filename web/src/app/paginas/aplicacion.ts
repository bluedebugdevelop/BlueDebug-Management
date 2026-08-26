import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core'
import { RouterLink } from '@angular/router'
import { firstValueFrom } from 'rxjs'

import { FormularioAccion } from '../componentes/formulario-accion'
import { GraficaDonut } from '../componentes/grafica-donut'
import { GraficaLineas } from '../componentes/grafica-lineas'
import { Icono } from '../componentes/icono'
import { TarjetaMetrica } from '../componentes/metrica'
import { TablaGenerica } from '../componentes/tabla-generica'
import { TablaUsuarios } from '../componentes/tabla-usuarios'
import { Api, type ErrorApi } from '../nucleo/api'
import { dinero, entero, fechaHora } from '../nucleo/formato'
import { Sesion } from '../nucleo/sesion'
import type { AccionAdmin, Ingresos, ResumenApp, Tabla, UsuarioApp } from '../nucleo/tipos'

type Pestana = 'resumen' | 'usuarios' | 'ingresos' | 'acciones' | 'detalle'

/**
 * La pantalla de UNA aplicación, sea cual sea.
 *
 * No hay una versión para VBStats y otra para el club: hay esta, y se amolda a lo
 * que el conector diga que sabe hacer. Las pestañas que se ven salen de las
 * capacidades del descriptor, las columnas de la tabla de usuarios de sus
 * `camposExtra`, los formularios de sus acciones y las tablas del final de lo que
 * declare en `tablas()`. Toda app futura entra por aquí.
 */
@Component({
  selector: 'bd-pagina-aplicacion',
  standalone: true,
  imports: [
    RouterLink,
    Icono,
    TarjetaMetrica,
    GraficaLineas,
    GraficaDonut,
    TablaUsuarios,
    TablaGenerica,
    FormularioAccion,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (resumen(); as datos) {
      <header class="cabecera">
        <div class="identidad">
          <span class="icono" [style.color]="datos.app.color" [style.background]="fondo(datos.app.color)">
            <bd-icono [nombre]="datos.app.icono" [tamano]="24" />
          </span>
          <div>
            <h1>{{ datos.app.nombre }}</h1>
            <p>{{ datos.app.descripcion }}</p>
          </div>
        </div>

        <div class="controles">
          <div class="periodos">
            @for (dias of sesion.periodos(); track dias) {
              <button type="button" [class.activo]="dias === periodo()" (click)="cambiarPeriodo(dias)">
                {{ dias === 365 ? '1 año' : dias + ' días' }}
              </button>
            }
          </div>
          <button type="button" class="boton secundario" (click)="recargar()" [disabled]="cargando()">
            <bd-icono nombre="recargar" [tamano]="16" />
            {{ cargando() ? 'Cargando…' : 'Actualizar' }}
          </button>
        </div>
      </header>

      @if (!datos.estado.disponible) {
        <section class="apagada">
          <bd-icono nombre="aviso" [tamano]="22" />
          <div>
            <strong>Esta aplicación no está dando datos ahora mismo.</strong>
            <p>{{ datos.estado.motivo }}</p>
            <a routerLink="/ajustes" class="enlace">Ver qué credenciales hacen falta →</a>
          </div>
        </section>
      } @else {
        <nav class="pestanas">
          @for (p of pestanas(); track p.clave) {
            <button type="button" [class.activa]="pestana() === p.clave" (click)="pestana.set(p.clave)">
              {{ p.nombre }}
            </button>
          }
        </nav>

        @switch (pestana()) {
          @case ('resumen') {
            <section class="metricas">
              @for (metrica of datos.metricas; track metrica.clave) {
                <bd-metrica [metrica]="metrica" />
              }
            </section>

            @for (serie of datos.series; track serie.clave) {
              <div class="grafica-suelta">
                <bd-grafica-lineas [titulo]="serie.etiqueta" [series]="[serie]" />
              </div>
            }

            <section class="donuts">
              @for (reparto of datos.repartos; track reparto.clave) {
                <bd-grafica-donut [reparto]="reparto" />
              }
            </section>
          }

          @case ('usuarios') {
            @if (usuarios() === null) {
              <p class="cargando">Cargando cuentas…</p>
            } @else {
              <bd-tabla-usuarios
                [usuarios]="usuarios()!"
                [camposExtra]="datos.app.camposExtra"
                [borrable]="puedeBorrar()"
                [textoBorrar]="textoBorrar()"
                (borrar)="borrar($event)"
              />
              @if (avisoBorrado(); as aviso) {
                <p class="aviso-accion" [class.mal]="!aviso.correcto">{{ aviso.mensaje }}</p>
              }
            }
          }

          @case ('ingresos') {
            @if (ingresos(); as dinero) {
              <section class="metricas">
                <article class="tarjeta"><p class="etiqueta">Facturado</p><p class="valor">{{ euros(dinero.facturado) }}</p></article>
                <article class="tarjeta"><p class="etiqueta">Recurrente estimado</p><p class="valor">{{ euros(dinero.recurrente) }}</p></article>
                <article class="tarjeta"><p class="etiqueta">Suscripciones activas</p><p class="valor">{{ numero(dinero.suscriptores) }}</p></article>
                <article class="tarjeta"><p class="etiqueta">Devuelto</p><p class="valor">{{ euros(dinero.devuelto) }}</p></article>
              </section>

              <div class="grafica-suelta">
                <bd-grafica-lineas titulo="Facturación diaria" [series]="[dinero.porDia]" />
              </div>

              @if (dinero.porPlan.trozos.length) {
                <div class="donuts">
                  <bd-grafica-donut [reparto]="dinero.porPlan" />
                </div>
              }

              <h3 class="titulo-seccion">Últimos movimientos</h3>
              @if (dinero.movimientos.length === 0) {
                <p class="vacia">No hay cobros en este periodo</p>
              } @else {
                <div class="marco-tabla">
                  <table>
                    <thead>
                      <tr>
                        <th>Fecha</th>
                        <th>Cuenta</th>
                        <th>Estado</th>
                        <th>Origen</th>
                        <th class="numero">Importe</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (movimiento of dinero.movimientos; track movimiento.id) {
                        <tr>
                          <td>{{ momento(movimiento.fecha) }}</td>
                          <td>{{ movimiento.email || '—' }}</td>
                          <td>
                            <span class="pastilla" [class.bien]="movimiento.estado === 'pagado'" [class.mal]="movimiento.estado === 'fallido'">
                              {{ movimiento.estado }}
                            </span>
                          </td>
                          <td>{{ movimiento.origen }}</td>
                          <td class="numero">{{ euros(movimiento.importe) }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            } @else {
              <p class="cargando">Leyendo la pasarela de pago…</p>
            }
          }

          @case ('acciones') {
            <div class="acciones">
              @for (accion of acciones(); track accion.id) {
                <bd-accion
                  [appId]="id()"
                  [accion]="accion"
                  [color]="datos.app.color"
                  (hecho)="recargar()"
                />
              } @empty {
                <p class="vacia">Esta aplicación no tiene acciones configuradas</p>
              }
            </div>
          }

          @case ('detalle') {
            <div class="tablas">
              @for (tabla of tablas(); track tabla.clave) {
                <bd-tabla [tabla]="tabla" />
              }
            </div>
          }
        }
      }
    } @else if (error()) {
      <p class="fallo">{{ error() }}</p>
    } @else {
      <p class="cargando">Cargando…</p>
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

      .identidad {
        display: flex;
        gap: 0.85rem;
        align-items: center;
      }

      .icono {
        display: grid;
        place-items: center;
        width: 46px;
        height: 46px;
        border-radius: 13px;
        flex-shrink: 0;
      }

      h1 {
        font-size: 1.5rem;
        font-weight: 800;
        letter-spacing: -0.03em;
      }

      .identidad p {
        color: var(--texto-3);
        font-size: 0.85rem;
        margin-top: 0.1rem;
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
      }

      .periodos button.activo {
        background: var(--acento-suave);
        color: var(--acento-claro);
      }

      .pestanas {
        display: flex;
        gap: 0.25rem;
        border-bottom: 1px solid var(--borde);
        margin-bottom: 1.5rem;
        overflow-x: auto;
      }

      .pestanas button {
        padding: 0.6rem 0.95rem;
        font-size: 0.87rem;
        font-weight: 600;
        color: var(--texto-3);
        border-bottom: 2px solid transparent;
        margin-bottom: -1px;
        white-space: nowrap;
        transition: color var(--transicion), border-color var(--transicion);
      }

      .pestanas button:hover {
        color: var(--texto);
      }

      .pestanas button.activa {
        color: var(--acento-claro);
        border-bottom-color: var(--acento);
      }

      .metricas {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(185px, 1fr));
        gap: 0.9rem;
        margin-bottom: 1.25rem;
      }

      .metricas .etiqueta {
        font-size: 0.78rem;
        color: var(--texto-3);
        margin: 0;
      }

      .metricas .valor {
        font-size: 1.75rem;
        font-weight: 700;
        letter-spacing: -0.03em;
        font-variant-numeric: tabular-nums;
        margin-top: 0.2rem;
      }

      .grafica-suelta {
        margin-bottom: 0.9rem;
      }

      .donuts {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(310px, 1fr));
        gap: 0.9rem;
        margin-bottom: 1.25rem;
      }

      .acciones {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
        gap: 1rem;
        align-items: start;
      }

      .tablas {
        display: flex;
        flex-direction: column;
        gap: 1.75rem;
      }

      .apagada {
        display: flex;
        gap: 0.9rem;
        align-items: flex-start;
        border: 1px solid rgba(245, 158, 11, 0.35);
        background: rgba(245, 158, 11, 0.07);
        color: #fcd34d;
        border-radius: var(--radio);
        padding: 1.1rem 1.25rem;
      }

      .apagada p {
        margin-top: 0.3rem;
        font-size: 0.85rem;
        opacity: 0.9;
        line-height: 1.5;
      }

      .enlace {
        display: inline-block;
        margin-top: 0.6rem;
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--acento-claro);
      }

      .aviso-accion {
        margin-top: 0.9rem;
        font-size: 0.83rem;
        padding: 0.7rem 0.85rem;
        border-radius: var(--radio-chico);
        border: 1px solid rgba(34, 197, 94, 0.4);
        background: rgba(34, 197, 94, 0.08);
        color: #bbf7d0;
      }

      .aviso-accion.mal {
        border-color: rgba(239, 68, 68, 0.4);
        background: rgba(239, 68, 68, 0.08);
        color: #fecaca;
      }

      .cargando,
      .vacia {
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
        font-size: 0.88rem;
      }
    `,
  ],
})
export class PaginaAplicacion {
  private readonly api = inject(Api)
  protected readonly sesion = inject(Sesion)

  /** Llega de la url gracias a `withComponentInputBinding`. */
  readonly id = input.required<string>()

  protected readonly resumen = signal<ResumenApp | null>(null)
  protected readonly usuarios = signal<UsuarioApp[] | null>(null)
  protected readonly ingresos = signal<Ingresos | null>(null)
  protected readonly acciones = signal<AccionAdmin[]>([])
  protected readonly tablas = signal<Tabla[]>([])

  protected readonly cargando = signal(false)
  protected readonly error = signal<string | null>(null)
  protected readonly pestana = signal<Pestana>('resumen')
  protected readonly periodo = signal(30)
  protected readonly avisoBorrado = signal<{ correcto: boolean; mensaje: string } | null>(null)

  constructor() {
    // Al cambiar de app en el menú, Angular reutiliza este mismo componente y solo
    // cambia el input. Sin este efecto, se quedarían en pantalla los datos de la
    // app anterior —que es de los errores más desconcertantes que puede tener un
    // panel: cifras de otra aplicación bajo el nombre de esta.
    effect(() => {
      const id = this.id()
      this.pestana.set('resumen')
      this.usuarios.set(null)
      this.ingresos.set(null)
      this.avisoBorrado.set(null)
      void this.cargarTodo(id)
    })
  }

  protected readonly pestanas = computed(() => {
    const app = this.resumen()?.app
    if (!app) return []

    const lista: { clave: Pestana; nombre: string }[] = [{ clave: 'resumen', nombre: 'Resumen' }]

    if (app.capacidades.includes('USUARIOS')) lista.push({ clave: 'usuarios', nombre: 'Usuarios' })
    if (app.capacidades.includes('INGRESOS')) lista.push({ clave: 'ingresos', nombre: 'Ingresos' })
    if (app.capacidades.includes('ACCIONES')) lista.push({ clave: 'acciones', nombre: 'Acciones' })
    if (this.tablas().length) lista.push({ clave: 'detalle', nombre: 'Detalle' })

    return lista
  })

  protected readonly puedeBorrar = computed(
    () => this.resumen()?.app.capacidades.includes('BORRAR_USUARIOS') ?? false,
  )

  /**
   * Lo que dice el botón de la tabla.
   *
   * En VBStats borra de verdad; en el club, da de baja y se puede deshacer. Que
   * el botón diga exactamente lo que va a pasar es lo que evita el clic de más.
   */
  protected readonly textoBorrar = computed(() =>
    this.resumen()?.app.id === 'cvo' ? 'Dar de baja' : 'Borrar',
  )

  private async cargarTodo(id: string): Promise<void> {
    this.cargando.set(true)
    this.error.set(null)

    try {
      const resumen = await firstValueFrom(this.api.resumen(id, this.periodo()))
      this.resumen.set(resumen)

      if (!resumen.estado.disponible) {
        this.acciones.set([])
        this.tablas.set([])
        return
      }

      // Lo secundario se pide en paralelo y sin que un fallo tumbe al resto: que
      // no haya historial no puede impedir ver las gráficas.
      const [acciones, tablas] = await Promise.all([
        this.pedir(() => firstValueFrom(this.api.acciones(id)), [] as AccionAdmin[]),
        this.pedir(() => firstValueFrom(this.api.tablas(id)), [] as Tabla[]),
      ])
      this.acciones.set(acciones)
      this.tablas.set(tablas)

      if (resumen.app.capacidades.includes('USUARIOS')) {
        this.usuarios.set(await this.pedir(() => firstValueFrom(this.api.usuarios(id)), []))
      }
      if (resumen.app.capacidades.includes('INGRESOS')) {
        this.ingresos.set(await this.pedir(() => firstValueFrom(this.api.ingresos(id, this.periodo())), null))
      }
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  private async pedir<T>(peticion: () => Promise<T>, siFalla: T): Promise<T> {
    try {
      return await peticion()
    } catch {
      return siFalla
    }
  }

  protected recargar(): void {
    void this.cargarTodo(this.id())
  }

  protected cambiarPeriodo(dias: number): void {
    this.periodo.set(dias)
    this.recargar()
  }

  protected async borrar(usuario: UsuarioApp): Promise<void> {
    this.avisoBorrado.set(null)
    try {
      const resultado = await firstValueFrom(this.api.borrarUsuario(this.id(), usuario.id))
      this.avisoBorrado.set({ correcto: resultado.correcto, mensaje: resultado.mensaje })
      this.recargar()
    } catch (fallo) {
      this.avisoBorrado.set({ correcto: false, mensaje: (fallo as ErrorApi).mensaje })
    }
  }

  protected numero(valor: number): string {
    return entero(valor)
  }

  protected euros(valor: number): string {
    return dinero(valor)
  }

  protected momento(iso: string): string {
    return fechaHora(iso)
  }

  protected fondo(color: string): string {
    return `${color}1f`
  }
}
