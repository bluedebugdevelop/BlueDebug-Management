import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { firstValueFrom } from 'rxjs'

import { Api, type ErrorApi } from '../nucleo/api'
import type { AltaGasto, CatalogoContabilidad, Gasto } from '../nucleo/tipos'
import { Icono } from './icono'

/**
 * El formulario de un gasto, para darlo de alta o para corregirlo.
 *
 * Al revés que {@code bd-accion}, este formulario SÍ tiene los campos escritos:
 * no los declara ningún conector porque no hay conector detrás, son los gastos de
 * la propia empresa. Lo que sí viene del backend es todo lo que puede cambiar
 * —los socios, las categorías, las recurrencias y las apps a las que imputar—,
 * así que añadir una categoría sigue siendo una línea en el enum de Java.
 *
 * Dos decisiones de forma que ahorran errores al teclear, que es donde se
 * cometen:
 *
 *  - El pagador va en botones y no en un desplegable. Son tres, se ven los tres
 *    a la vez, y es el campo que más se mira después.
 *  - El concepto tiene una lista de los ya escritos. El coste fijo mensual se
 *    calcula agrupando por concepto, así que «Railway» y «Railway plan» serían
 *    dos servicios distintos y lo contaría dos veces.
 */
@Component({
  selector: 'bd-formulario-gasto',
  standalone: true,
  imports: [FormsModule, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="tarjeta formulario" (ngSubmit)="guardar()">
      <header>
        <h3>{{ gasto() ? 'Corregir gasto' : 'Nuevo gasto' }}</h3>
        <button type="button" class="cerrar" (click)="cancelado.emit()" aria-label="Cerrar">
          <bd-icono nombre="menos" [tamano]="18" />
        </button>
      </header>

      <div class="rejilla">
        <div class="campo ancho">
          <label class="etiqueta" for="concepto">Concepto</label>
          <input
            id="concepto"
            name="concepto"
            list="conceptos-usados"
            maxlength="200"
            placeholder="Railway — plan Hobby"
            [(ngModel)]="concepto"
          />
          <datalist id="conceptos-usados">
            @for (usado of conceptos(); track usado) {
              <option [value]="usado"></option>
            }
          </datalist>
        </div>

        <div class="campo">
          <label class="etiqueta" for="fecha">Fecha del pago</label>
          <input id="fecha" name="fecha" type="date" [(ngModel)]="fecha" />
        </div>

        <div class="campo">
          <label class="etiqueta" for="categoria">Categoría</label>
          <select id="categoria" name="categoria" [(ngModel)]="categoria">
            @for (opcion of catalogo().categorias; track opcion.valor) {
              <option [value]="opcion.valor">{{ opcion.etiqueta }}</option>
            }
          </select>
        </div>

        <div class="campo">
          <label class="etiqueta" for="importe">Importe (IVA incluido)</label>
          <input
            id="importe"
            name="importe"
            type="number"
            step="0.01"
            min="0"
            placeholder="0,00"
            [(ngModel)]="importe"
          />
        </div>

        <div class="campo">
          <label class="etiqueta" for="iva">De eso, IVA</label>
          <input id="iva" name="iva" type="number" step="0.01" min="0" placeholder="0,00" [(ngModel)]="iva" />
          <button type="button" class="calcular" (click)="calcularIva()" [disabled]="!importe()">
            Calcular el 21 %
          </button>
        </div>

        <div class="campo ancho">
          <span class="etiqueta">Lo pagó</span>
          <div class="socios">
            @for (socio of catalogo().socios; track socio) {
              <button
                type="button"
                class="socio"
                [class.puesto]="pagadoPor() === socio"
                (click)="pagadoPor.set(socio)"
              >
                {{ socio }}
              </button>
            }
          </div>
        </div>

        <div class="campo">
          <label class="etiqueta" for="app">Se imputa a</label>
          <select id="app" name="app" [(ngModel)]="app">
            <option value="">La empresa, en general</option>
            @for (opcion of catalogo().apps; track opcion.id) {
              <option [value]="opcion.id">{{ opcion.nombre }}</option>
            }
          </select>
        </div>

        <div class="campo">
          <label class="etiqueta" for="recurrencia">Cada cuánto</label>
          <select id="recurrencia" name="recurrencia" [(ngModel)]="recurrencia">
            @for (opcion of catalogo().recurrencias; track opcion.valor) {
              <option [value]="opcion.valor">{{ opcion.etiqueta }}</option>
            }
          </select>
          <p class="ayuda">Lo que se repite entra en el «coste fijo al mes».</p>
        </div>

        <div class="campo">
          <label class="etiqueta" for="proveedor">Proveedor</label>
          <input
            id="proveedor"
            name="proveedor"
            maxlength="120"
            placeholder="Railway, Apple, la gestoría…"
            [(ngModel)]="proveedor"
          />
        </div>

        <div class="campo ancho">
          <label class="etiqueta" for="nota">Nota</label>
          <textarea
            id="nota"
            name="nota"
            maxlength="500"
            placeholder="Opcional: número de factura, qué cubre, lo que haga falta recordar."
            [(ngModel)]="nota"
          ></textarea>
        </div>
      </div>

      @if (error(); as fallo) {
        <p class="fallo">{{ fallo }}</p>
      }

      <footer>
        <button type="button" class="boton secundario" (click)="cancelado.emit()">Cancelar</button>
        <button type="submit" class="boton" [disabled]="guardando()">
          {{ guardando() ? 'Guardando…' : gasto() ? 'Guardar los cambios' : 'Apuntar el gasto' }}
        </button>
      </footer>
    </form>
  `,
  styles: [
    `
      .formulario {
        padding: 1.35rem 1.5rem 1.5rem;
      }

      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        margin-bottom: 1.2rem;
      }

      h3 {
        font-size: 1.05rem;
        font-weight: 700;
        letter-spacing: -0.01em;
      }

      .cerrar {
        display: inline-flex;
        padding: 0.35rem;
        border-radius: var(--radio-chico);
        color: var(--texto-3);
      }

      .cerrar:hover {
        background: rgba(255, 255, 255, 0.06);
        color: var(--texto);
      }

      .rejilla {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
        gap: 0.9rem 1rem;
      }

      /* Los campos que se leen mal partidos ocupan la fila entera. */
      .campo.ancho {
        grid-column: 1 / -1;
      }

      textarea {
        min-height: 74px;
      }

      .calcular {
        font-size: 0.74rem;
        color: var(--acento-claro);
        margin-top: 0.35rem;
        padding: 0;
      }

      .calcular:hover:not(:disabled) {
        text-decoration: underline;
      }

      .calcular:disabled {
        color: var(--texto-3);
        cursor: not-allowed;
      }

      .socios {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
      }

      .socio {
        padding: 0.5rem 0.9rem;
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        font-size: 0.85rem;
        color: var(--texto-2);
        transition: border-color var(--transicion), background var(--transicion), color var(--transicion);
      }

      .socio:hover {
        border-color: var(--borde-fuerte);
        color: var(--texto);
      }

      .socio.puesto {
        background: var(--acento-suave);
        border-color: var(--acento);
        color: var(--acento-claro);
        font-weight: 600;
      }

      footer {
        display: flex;
        justify-content: flex-end;
        gap: 0.6rem;
        margin-top: 1.3rem;
      }

      .fallo {
        color: #fca5a5;
        border: 1px solid rgba(239, 68, 68, 0.35);
        border-radius: var(--radio-chico);
        background: rgba(239, 68, 68, 0.07);
        padding: 0.7rem 0.85rem;
        font-size: 0.85rem;
        margin-top: 1rem;
      }
    `,
  ],
})
export class FormularioGasto {
  private readonly api = inject(Api)

  readonly catalogo = input.required<CatalogoContabilidad>()

  /** El gasto que se está corrigiendo, o null si es un alta. */
  readonly gasto = input<Gasto | null>(null)

  /** Los conceptos ya escritos, para la lista de sugerencias. */
  readonly conceptos = input<string[]>([])

  readonly guardado = output<Gasto>()
  readonly cancelado = output<void>()

  protected readonly concepto = signal('')
  protected readonly fecha = signal(hoy())
  protected readonly categoria = signal('HOSTING')
  protected readonly importe = signal<number | null>(null)
  protected readonly iva = signal<number | null>(null)
  protected readonly pagadoPor = signal('')
  protected readonly app = signal('')
  protected readonly recurrencia = signal('UNICO')
  protected readonly proveedor = signal('')
  protected readonly nota = signal('')

  protected readonly guardando = signal(false)
  protected readonly error = signal<string | null>(null)

  ngOnInit(): void {
    const previo = this.gasto()

    if (previo) {
      this.concepto.set(previo.concepto)
      // La fecha llega ISO completa o no, según el motor; el input type=date solo
      // entiende los diez primeros caracteres.
      this.fecha.set(previo.fecha.slice(0, 10))
      this.categoria.set(previo.categoria)
      this.importe.set(previo.importe)
      this.iva.set(previo.iva || null)
      this.pagadoPor.set(previo.pagadoPor)
      this.app.set(previo.app ?? '')
      this.recurrencia.set(previo.recurrencia)
      this.proveedor.set(previo.proveedor ?? '')
      this.nota.set(previo.nota ?? '')
      return
    }

    // En un alta, el pagador más probable es quien está delante. No se puede
    // saber —la sesión va por correo y los socios son nombres— así que se deja
    // el primero puesto y que lo cambie quien no sea.
    this.pagadoPor.set(this.catalogo().socios[0] ?? '')
    this.categoria.set(this.catalogo().categorias[0]?.valor ?? 'OTROS')
  }

  /**
   * Saca el IVA de un importe que ya lo lleva dentro.
   *
   * 121 € con el 21 % son 21 € de IVA, no 25,41: el porcentaje se aplica sobre la
   * base, y lo que hay escrito en el formulario es el total. Es la cuenta que más
   * fácil se hace mal a mano, y por eso está el botón.
   */
  protected calcularIva(): void {
    const total = this.importe()
    if (!total) return
    this.iva.set(Math.round((total - total / 1.21) * 100) / 100)
  }

  protected async guardar(): Promise<void> {
    this.error.set(null)

    const alta: AltaGasto = {
      fecha: this.fecha(),
      concepto: this.concepto().trim(),
      categoria: this.categoria(),
      proveedor: vacioANulo(this.proveedor()),
      importe: this.importe() ?? 0,
      iva: this.iva() ?? 0,
      pagadoPor: this.pagadoPor(),
      app: vacioANulo(this.app()),
      recurrencia: this.recurrencia(),
      nota: vacioANulo(this.nota()),
    }

    this.guardando.set(true)
    try {
      const previo = this.gasto()
      const guardado = previo
        ? await firstValueFrom(this.api.editarGasto(previo.id, alta))
        : await firstValueFrom(this.api.crearGasto(alta))

      this.guardado.emit(guardado)
    } catch (fallo) {
      // El backend valida en castellano y con frases enteras, así que su mensaje
      // se enseña tal cual en vez de traducirlo otra vez aquí.
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.guardando.set(false)
    }
  }
}

/**
 * Hoy, en la hora de aquí.
 *
 * Con `toISOString()` saldría en UTC, y entre medianoche y las dos de la mañana
 * en España eso es AYER. Apuntar un gasto de madrugada con la fecha del día
 * anterior es justo el tipo de error que nadie revisa.
 */
function hoy(): string {
  const ahora = new Date()
  const mes = String(ahora.getMonth() + 1).padStart(2, '0')
  const dia = String(ahora.getDate()).padStart(2, '0')
  return `${ahora.getFullYear()}-${mes}-${dia}`
}

function vacioANulo(valor: string): string | null {
  const limpio = valor.trim()
  return limpio === '' ? null : limpio
}
