import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { firstValueFrom } from 'rxjs'

import { FormularioGasto } from '../componentes/formulario-gasto'
import { GraficaBarras } from '../componentes/grafica-barras'
import { GraficaDonut } from '../componentes/grafica-donut'
import { Icono } from '../componentes/icono'
import { TarjetaMetrica } from '../componentes/metrica'
import { Api, type ErrorApi } from '../nucleo/api'
import { dinero, fecha } from '../nucleo/formato'
import type { CatalogoContabilidad, Gasto, ResumenContabilidad } from '../nucleo/tipos'

/**
 * Los gastos de BlueDebug: en qué se va el dinero y quién lo ha puesto.
 *
 * Es la primera pantalla del panel que no mira a ninguna app. No hay conector
 * detrás porque no hay nada que consultar fuera: el dato lo escribe quien paga.
 * Por eso vive suelta en el menú, en su propio epígrafe, y no bajo «Aplicaciones».
 *
 * La pantalla contesta tres preguntas, en este orden, que es el orden en el que
 * se preguntan de verdad:
 *
 *   1. ¿Cuánto llevamos gastado y cuánto cuesta esto al mes?
 *   2. ¿Quién ha puesto de más y a quién hay que pasarle dinero?
 *   3. ¿En qué se ha ido exactamente?
 *
 * La segunda es la que justifica que se apunte quién pagó cada cosa. Sin ella,
 * esto sería una lista de recibos.
 */
@Component({
  selector: 'bd-pagina-contabilidad',
  standalone: true,
  imports: [FormsModule, Icono, TarjetaMetrica, GraficaBarras, GraficaDonut, FormularioGasto],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cabecera">
      <div>
        <h1>Contabilidad</h1>
        <p>Lo que cuesta tener BlueDebug en pie, y quién lo ha pagado.</p>
      </div>

      <div class="controles">
        @if (catalogo(); as cat) {
          @if (cat.configurado) {
            <div class="periodos">
              @for (anio of cat.anios; track anio) {
                <button type="button" [class.activo]="anio === anioMirado()" (click)="cambiarAnio(anio)">
                  {{ anio }}
                </button>
              }
              <button type="button" [class.activo]="anioMirado() === 0" (click)="cambiarAnio(0)">
                Todo
              </button>
            </div>
            <button type="button" class="boton" (click)="abrirAlta()">
              <bd-icono nombre="mas" [tamano]="16" /> Apuntar gasto
            </button>
          }
        }
      </div>
    </header>

    @if (error(); as fallo) {
      <p class="fallo">{{ fallo }}</p>
    }

    @if (catalogo(); as cat) {
      @if (!cat.configurado) {
        <!-- Sin base de datos la sección no se esconde: se explica. Esconderla
             haría que pareciera que el panel no tiene contabilidad, cuando lo
             que pasa es que le falta una variable. -->
        <article class="tarjeta sin-configurar">
          <span class="icono"><bd-icono nombre="aviso" [tamano]="22" /></span>
          <div>
            <h2>La contabilidad todavía no tiene dónde guardar nada</h2>
            <p>{{ cat.motivo }}</p>
            <p class="pista">
              Es la única base de datos propia del panel. Se añade una en Railway y se pega su
              url en <code>BLUEDEBUG_CONTABILIDAD_URL</code>; vale Postgres o MySQL y la tabla
              se crea sola. Está explicado en <code>.env.example</code>.
            </p>
          </div>
        </article>
      } @else {
        @if (formularioAbierto()) {
          <bd-formulario-gasto
            [catalogo]="cat"
            [gasto]="editando()"
            [conceptos]="conceptosUsados()"
            (guardado)="trasGuardar()"
            (cancelado)="cerrarFormulario()"
          />
        }

        @if (datos(); as resumen) {
          <section class="metricas">
            @for (metrica of resumen.metricas; track metrica.clave) {
              <bd-metrica [metrica]="metrica" />
            }
          </section>

          <section class="graficas">
            <bd-grafica-barras titulo="Gasto mes a mes" [serie]="resumen.porMes" />
            <bd-grafica-donut [reparto]="resumen.porCategoria" formato="dinero" />
          </section>

          <section class="graficas">
            <article class="tarjeta liquidacion">
              <h2 class="titulo-seccion">Quién ha puesto qué</h2>

              @if (resumen.liquidacion.total === 0) {
                <p class="vacia-caja">Sin gastos apuntados en este periodo.</p>
              } @else {
                <p class="por-cabeza">
                  A partes iguales le tocaba
                  <strong>{{ euros(resumen.liquidacion.porCabeza) }}</strong> a cada uno.
                </p>

                <ul class="saldos">
                  @for (saldo of resumen.liquidacion.saldos; track saldo.socio) {
                    <li>
                      <span class="nombre">{{ saldo.socio }}</span>
                      <span class="puesto">puso {{ euros(saldo.pagado) }}</span>
                      <span
                        class="saldo"
                        [class.favor]="saldo.saldo > 0"
                        [class.contra]="saldo.saldo < 0"
                      >
                        {{ saldo.saldo > 0 ? '+' : '' }}{{ euros(saldo.saldo) }}
                      </span>
                    </li>
                  }
                </ul>

                @if (resumen.liquidacion.ajustes.length > 0) {
                  <div class="ajustes">
                    <p class="titulo-ajustes">Para dejarlo a cero</p>
                    @for (ajuste of resumen.liquidacion.ajustes; track ajuste.de + ajuste.a) {
                      <p class="ajuste">
                        <strong>{{ ajuste.de }}</strong>
                        <bd-icono nombre="volver" [tamano]="14" class="flecha" />
                        <strong>{{ ajuste.a }}</strong>
                        <span class="cuanto">{{ euros(ajuste.importe) }}</span>
                      </p>
                    }
                  </div>
                } @else {
                  <p class="cuadrado">
                    <bd-icono nombre="bien" [tamano]="15" /> Las cuentas están cuadradas.
                  </p>
                }
              }
            </article>

            <bd-grafica-donut [reparto]="resumen.porApp" formato="dinero" />
          </section>

          <section>
            <div class="cabecera-tabla">
              <h2 class="titulo-seccion">
                Apuntes
                <span class="cuantos">{{ gastosFiltrados().length }} de {{ resumen.gastos.length }}</span>
              </h2>
              <div class="buscador">
                <bd-icono nombre="buscar" [tamano]="16" />
                <input
                  type="search"
                  placeholder="Buscar por concepto, proveedor o quién lo pagó"
                  [ngModel]="busqueda()"
                  (ngModelChange)="busqueda.set($event)"
                  name="busqueda"
                />
              </div>
            </div>

            @if (gastosFiltrados().length === 0) {
              <p class="vacia">
                @if (resumen.gastos.length === 0) {
                  Nada apuntado todavía en este periodo. El primer gasto se apunta con el botón de
                  arriba.
                } @else {
                  Ningún apunte encaja con lo que has escrito.
                }
              </p>
            } @else {
              <div class="marco-tabla">
                <table>
                  <thead>
                    <tr>
                      <th>Fecha</th>
                      <th>Concepto</th>
                      <th>Categoría</th>
                      <th>Se imputa a</th>
                      <th>Lo pagó</th>
                      <th class="numero">Importe</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (gasto of gastosFiltrados(); track gasto.id) {
                      <tr>
                        <td class="dia">{{ dia(gasto.fecha) }}</td>
                        <td>
                          <span class="concepto">{{ gasto.concepto }}</span>
                          @if (gasto.proveedor) {
                            <span class="proveedor">{{ gasto.proveedor }}</span>
                          }
                          @if (gasto.nota) {
                            <span class="nota" [title]="gasto.nota">{{ gasto.nota }}</span>
                          }
                        </td>
                        <td>
                          <span class="pastilla categoria" [style.color]="colorCategoria(gasto.categoria)">
                            {{ nombreCategoria(gasto.categoria) }}
                          </span>
                        </td>
                        <td>{{ nombreApp(gasto.app) }}</td>
                        <td>{{ gasto.pagadoPor }}</td>
                        <td class="numero importe">
                          {{ euros(gasto.importe) }}
                          @if (gasto.recurrencia !== 'UNICO') {
                            <small>{{ gasto.recurrencia === 'MENSUAL' ? '/mes' : '/año' }}</small>
                          }
                        </td>
                        <td class="acciones">
                          <button type="button" (click)="abrirEdicion(gasto)" title="Corregir">
                            <bd-icono nombre="editar" [tamano]="15" />
                          </button>
                          <button
                            type="button"
                            class="borrar"
                            (click)="pedirBorrado(gasto)"
                            [title]="'Borrar ' + gasto.concepto"
                          >
                            <bd-icono nombre="papelera" [tamano]="15" />
                          </button>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>
        } @else if (cargando()) {
          <p class="vacia">Cargando…</p>
        }
      }
    }

    <!-- El borrado se confirma aparte y diciendo qué se va a borrar: es la única
         acción de esta pantalla que no se puede deshacer. -->
    @if (porBorrar(); as gasto) {
      <div class="velo" (click)="porBorrar.set(null)">
        <article class="tarjeta confirmacion" (click)="$event.stopPropagation()">
          <h3>¿Borrar este gasto?</h3>
          <p>
            <strong>{{ gasto.concepto }}</strong> · {{ euros(gasto.importe) }} · lo pagó
            {{ gasto.pagadoPor }} el {{ dia(gasto.fecha) }}.
          </p>
          <p class="aviso-borrado">
            Desaparece de los totales y del reparto entre socios. No hay deshacer.
          </p>
          <footer>
            <button type="button" class="boton secundario" (click)="porBorrar.set(null)">
              Dejarlo estar
            </button>
            <button type="button" class="boton peligro" (click)="borrar(gasto)" [disabled]="borrando()">
              {{ borrando() ? 'Borrando…' : 'Sí, borrarlo' }}
            </button>
          </footer>
        </article>
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

      .cabecera > div > p {
        color: var(--texto-3);
        font-size: 0.87rem;
        margin-top: 0.2rem;
      }

      .controles {
        display: flex;
        align-items: center;
        gap: 0.7rem;
        flex-wrap: wrap;
      }

      .periodos {
        display: flex;
        background: var(--fondo-2);
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        padding: 0.2rem;
        gap: 0.15rem;
      }

      .periodos button {
        padding: 0.35rem 0.7rem;
        border-radius: 6px;
        font-size: 0.8rem;
        color: var(--texto-3);
        transition: background var(--transicion), color var(--transicion);
      }

      .periodos button:hover {
        color: var(--texto);
      }

      .periodos button.activo {
        background: var(--acento-suave);
        color: var(--acento-claro);
        font-weight: 600;
      }

      bd-formulario-gasto {
        display: block;
        margin-bottom: 1.5rem;
      }

      .metricas {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
        gap: 1rem;
        margin-bottom: 1.5rem;
      }

      .graficas {
        display: grid;
        grid-template-columns: 1.4fr 1fr;
        gap: 1rem;
        margin-bottom: 1.5rem;
      }

      @media (max-width: 1000px) {
        .graficas {
          grid-template-columns: 1fr;
        }
      }

      /* --- liquidación --- */

      .liquidacion {
        display: flex;
        flex-direction: column;
      }

      .por-cabeza {
        font-size: 0.84rem;
        color: var(--texto-3);
        margin-bottom: 0.9rem;
      }

      .por-cabeza strong {
        color: var(--texto);
        font-variant-numeric: tabular-nums;
      }

      .saldos {
        list-style: none;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }

      .saldos li {
        display: grid;
        grid-template-columns: 1fr auto auto;
        align-items: baseline;
        gap: 0.75rem;
        padding-bottom: 0.5rem;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
      }

      .saldos .nombre {
        font-size: 0.88rem;
        font-weight: 600;
      }

      .saldos .puesto {
        font-size: 0.78rem;
        color: var(--texto-3);
        font-variant-numeric: tabular-nums;
      }

      .saldos .saldo {
        font-size: 0.88rem;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
        min-width: 88px;
        text-align: right;
        color: var(--texto-3);
      }

      .saldos .saldo.favor {
        color: #86efac;
      }

      .saldos .saldo.contra {
        color: #fca5a5;
      }

      .ajustes {
        margin-top: 1rem;
        padding-top: 0.9rem;
        border-top: 1px solid var(--borde);
      }

      .titulo-ajustes {
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.07em;
        color: var(--texto-3);
        font-weight: 700;
        margin-bottom: 0.6rem;
      }

      .ajuste {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        font-size: 0.86rem;
        padding: 0.3rem 0;
      }

      /* La flecha del icono «volver» apunta a la izquierda; se gira para que el
         dinero se lea yendo de quien debe a quien cobra. */
      .flecha {
        transform: rotate(180deg);
        color: var(--texto-3);
      }

      .ajuste .cuanto {
        margin-left: auto;
        font-weight: 700;
        font-variant-numeric: tabular-nums;
        color: var(--acento-claro);
      }

      .cuadrado {
        display: flex;
        align-items: center;
        gap: 0.45rem;
        margin-top: 1rem;
        padding-top: 0.9rem;
        border-top: 1px solid var(--borde);
        font-size: 0.84rem;
        color: #86efac;
      }

      .vacia-caja {
        color: var(--texto-3);
        font-size: 0.85rem;
        padding: 2rem 0;
        text-align: center;
      }

      /* --- tabla --- */

      .cabecera-tabla {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 0.9rem;
      }

      .titulo-seccion {
        margin-bottom: 0;
      }

      .cuantos {
        font-size: 0.75rem;
        font-weight: 500;
        color: var(--texto-3);
        margin-left: 0.5rem;
      }

      .buscador {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        color: var(--texto-3);
        min-width: 280px;
        flex: 0 1 340px;
      }

      .buscador input {
        padding: 0.45rem 0.7rem;
        font-size: 0.85rem;
      }

      .dia {
        white-space: nowrap;
        color: var(--texto-3);
        font-size: 0.82rem;
      }

      .concepto {
        display: block;
        color: var(--texto);
        font-weight: 500;
      }

      .proveedor,
      .nota {
        display: block;
        font-size: 0.76rem;
        color: var(--texto-3);
      }

      .nota {
        max-width: 320px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        opacity: 0.75;
      }

      /* El color lo pone la categoría, en línea; el fondo se queda neutro para que
         once colores distintos no conviertan la tabla en un semáforo. */
      .pastilla.categoria {
        background: rgba(255, 255, 255, 0.06);
      }

      .importe {
        font-weight: 600;
        color: var(--texto);
        white-space: nowrap;
      }

      .importe small {
        color: var(--texto-3);
        font-weight: 400;
        margin-left: 0.15rem;
      }

      .acciones {
        display: flex;
        gap: 0.2rem;
        justify-content: flex-end;
      }

      .acciones button {
        padding: 0.35rem;
        border-radius: var(--radio-chico);
        color: var(--texto-3);
        transition: background var(--transicion), color var(--transicion);
      }

      .acciones button:hover {
        background: rgba(255, 255, 255, 0.07);
        color: var(--texto);
      }

      .acciones .borrar:hover {
        background: rgba(239, 68, 68, 0.12);
        color: #fca5a5;
      }

      /* --- estados --- */

      .sin-configurar {
        display: flex;
        gap: 1rem;
        align-items: flex-start;
        border-color: rgba(245, 158, 11, 0.35);
        background: rgba(245, 158, 11, 0.05);
      }

      .sin-configurar .icono {
        color: var(--aviso);
        flex-shrink: 0;
        display: inline-flex;
        padding-top: 0.1rem;
      }

      .sin-configurar h2 {
        font-size: 1rem;
        font-weight: 700;
        margin-bottom: 0.35rem;
      }

      .sin-configurar p {
        font-size: 0.86rem;
        color: var(--texto-2);
      }

      .sin-configurar .pista {
        margin-top: 0.6rem;
        font-size: 0.8rem;
        color: var(--texto-3);
        line-height: 1.6;
        max-width: 620px;
      }

      code {
        font-family: var(--mono);
        font-size: 0.92em;
        background: rgba(255, 255, 255, 0.07);
        padding: 0.1rem 0.35rem;
        border-radius: 5px;
      }

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

      /* --- confirmación de borrado --- */

      .velo {
        position: fixed;
        inset: 0;
        background: rgba(0, 0, 0, 0.6);
        display: grid;
        place-items: center;
        padding: 1.25rem;
        z-index: 30;
      }

      .confirmacion {
        max-width: 440px;
        box-shadow: var(--sombra);
      }

      .confirmacion h3 {
        font-size: 1.05rem;
        font-weight: 700;
        margin-bottom: 0.6rem;
      }

      .confirmacion p {
        font-size: 0.87rem;
        color: var(--texto-2);
      }

      .aviso-borrado {
        margin-top: 0.5rem;
        font-size: 0.8rem !important;
        color: var(--texto-3) !important;
      }

      .confirmacion footer {
        display: flex;
        justify-content: flex-end;
        gap: 0.6rem;
        margin-top: 1.25rem;
      }
    `,
  ],
})
export class PaginaContabilidad {
  private readonly api = inject(Api)

  protected readonly catalogo = signal<CatalogoContabilidad | null>(null)
  protected readonly datos = signal<ResumenContabilidad | null>(null)
  protected readonly cargando = signal(false)
  protected readonly error = signal<string | null>(null)

  /** 0 significa «todos los años». */
  protected readonly anioMirado = signal(new Date().getFullYear())

  protected readonly busqueda = signal('')

  protected readonly formularioAbierto = signal(false)
  protected readonly editando = signal<Gasto | null>(null)
  protected readonly porBorrar = signal<Gasto | null>(null)
  protected readonly borrando = signal(false)

  /** Cómo se llama cada categoría y de qué color va, sacado del catálogo. */
  private readonly categorias = computed(
    () => new Map((this.catalogo()?.categorias ?? []).map((c) => [c.valor, c])),
  )

  private readonly apps = computed(
    () => new Map((this.catalogo()?.apps ?? []).map((a) => [a.id, a.nombre])),
  )

  /** Los conceptos ya escritos, para sugerirlos al apuntar el siguiente. */
  protected readonly conceptosUsados = computed(() =>
    [...new Set((this.datos()?.gastos ?? []).map((g) => g.concepto))].sort((a, b) =>
      a.localeCompare(b, 'es'),
    ),
  )

  /**
   * El filtro es en el cliente y no en el servidor a propósito: son los gastos de
   * un año, unas decenas de filas que ya están descargadas. Ir al servidor a cada
   * tecla sería más código y una espera donde ahora no hay ninguna.
   */
  protected readonly gastosFiltrados = computed(() => {
    const gastos = this.datos()?.gastos ?? []
    const texto = this.busqueda().trim().toLowerCase()
    if (!texto) return gastos

    return gastos.filter((gasto) =>
      [
        gasto.concepto,
        gasto.proveedor ?? '',
        gasto.pagadoPor,
        gasto.nota ?? '',
        this.nombreCategoria(gasto.categoria),
        this.nombreApp(gasto.app),
      ]
        .join(' ')
        .toLowerCase()
        .includes(texto),
    )
  })

  ngOnInit(): void {
    void this.cargar()
  }

  private async cargar(): Promise<void> {
    this.cargando.set(true)
    this.error.set(null)
    try {
      const catalogo = await firstValueFrom(this.api.catalogoContabilidad())
      this.catalogo.set(catalogo)

      if (!catalogo.configurado) {
        return
      }

      // Si el año que se estaba mirando no tiene apuntes, se cae al más reciente
      // que sí los tenga. Entrar en enero y ver la pantalla vacía por defecto
      // sería técnicamente correcto y bastante inútil.
      if (!catalogo.anios.includes(this.anioMirado()) && catalogo.anios.length > 0) {
        this.anioMirado.set(catalogo.anios[0])
      }

      await this.cargarResumen()
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  private async cargarResumen(): Promise<void> {
    this.datos.set(await firstValueFrom(this.api.contabilidad(this.anioMirado())))
  }

  protected async cambiarAnio(anio: number): Promise<void> {
    if (anio === this.anioMirado()) return
    this.anioMirado.set(anio)
    this.cargando.set(true)
    this.error.set(null)
    try {
      await this.cargarResumen()
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.cargando.set(false)
    }
  }

  // ------------------------------------------------------------------ formulario

  protected abrirAlta(): void {
    this.editando.set(null)
    this.formularioAbierto.set(true)
  }

  protected abrirEdicion(gasto: Gasto): void {
    this.editando.set(gasto)
    this.formularioAbierto.set(true)
  }

  protected cerrarFormulario(): void {
    this.formularioAbierto.set(false)
    this.editando.set(null)
  }

  /**
   * Tras guardar se recarga todo, incluido el catálogo.
   *
   * Recargar el resumen entero en vez de meter la fila a mano en la lista no es
   * pereza: el alta cambia los totales, el reparto entre socios, el coste fijo y
   * cuatro gráficas. Recalcularlo aquí sería reimplementar el servicio en
   * TypeScript para ahorrar una petición que tarda lo que un parpadeo. Y va el
   * catálogo también porque un gasto con fecha de otro año estrena año en el
   * selector.
   */
  protected async trasGuardar(): Promise<void> {
    this.cerrarFormulario()
    await this.cargar()
  }

  // --------------------------------------------------------------------- borrado

  protected pedirBorrado(gasto: Gasto): void {
    this.porBorrar.set(gasto)
  }

  protected async borrar(gasto: Gasto): Promise<void> {
    this.borrando.set(true)
    try {
      await firstValueFrom(this.api.borrarGasto(gasto.id))
      this.porBorrar.set(null)
      await this.cargar()
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
      this.porBorrar.set(null)
    } finally {
      this.borrando.set(false)
    }
  }

  // ----------------------------------------------------------------------- texto

  protected nombreCategoria(valor: string): string {
    return this.categorias().get(valor)?.etiqueta ?? valor
  }

  protected colorCategoria(valor: string): string {
    return this.categorias().get(valor)?.color ?? 'var(--texto-2)'
  }

  /** Un gasto sin app es de la empresa, no de nadie en concreto. */
  protected nombreApp(id: string | null): string {
    if (!id) return 'Empresa'
    // Un id que ya no tiene conector se enseña tal cual, para que se vea que hay
    // un apunte que recolocar en vez de un hueco en blanco.
    return this.apps().get(id) ?? id
  }

  protected euros(valor: number): string {
    return dinero(valor)
  }

  protected dia(iso: string): string {
    return fecha(iso)
  }
}
