import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { firstValueFrom } from 'rxjs'

import { Api, type ErrorApi } from '../nucleo/api'
import { entero } from '../nucleo/formato'
import type { AccionAdmin, CampoAccion, ResultadoAccion } from '../nucleo/tipos'
import { Icono } from './icono'

/**
 * El formulario de una acción de administración, construido a partir de lo que
 * declara el backend.
 *
 * Aquí no hay ni un campo escrito a mano: los tipos, las etiquetas, los límites
 * de caracteres y las opciones de los desplegables vienen del conector. Es lo que
 * permite que añadir «mandar un correo a los que caducan este mes» sea escribir
 * una acción en Java y recargar.
 *
 * Las acciones marcadas como peligrosas piden una confirmación aparte antes de
 * ejecutarse, porque este formulario le suena el móvil a cientos de personas de
 * golpe y no hay botón de deshacer.
 */
@Component({
  selector: 'bd-accion',
  standalone: true,
  imports: [FormsModule, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="tarjeta accion" (ngSubmit)="enviar()">
      <header>
        <span class="icono" [style.color]="color()">
          <bd-icono [nombre]="accion().icono" [tamano]="18" />
        </span>
        <div>
          <h3>{{ accion().nombre }}</h3>
          <p>{{ accion().descripcion }}</p>
        </div>
      </header>

      @for (campo of accion().campos; track campo.clave) {
        <div class="campo">
          <label class="etiqueta" [attr.for]="campo.clave">{{ campo.etiqueta }}</label>

          @switch (campo.tipo) {
            @case ('AREA') {
              <textarea
                [id]="campo.clave"
                [maxlength]="campo.maximo || null"
                [ngModel]="valor(campo.clave)"
                (ngModelChange)="cambiar(campo.clave, $event)"
                [name]="campo.clave"
              ></textarea>
            }
            @case ('SELECCION') {
              <select
                [id]="campo.clave"
                [ngModel]="valor(campo.clave)"
                (ngModelChange)="cambiar(campo.clave, $event)"
                [name]="campo.clave"
              >
                @for (opcion of campo.opciones; track opcion.valor) {
                  <option [value]="opcion.valor">
                    {{ opcion.etiqueta }}{{ opcion.detalle ? ' — ' + opcion.detalle : '' }}
                  </option>
                }
              </select>
            }
            @case ('NUMERO') {
              <input
                type="number"
                [id]="campo.clave"
                [ngModel]="valor(campo.clave)"
                (ngModelChange)="cambiar(campo.clave, $event)"
                [name]="campo.clave"
              />
            }
            @default {
              <input
                type="text"
                [id]="campo.clave"
                [maxlength]="campo.maximo || null"
                [ngModel]="valor(campo.clave)"
                (ngModelChange)="cambiar(campo.clave, $event)"
                [name]="campo.clave"
              />
            }
          }

          <div class="pie-campo">
            @if (campo.ayuda) {
              <span class="ayuda">{{ campo.ayuda }}</span>
            }
            @if (campo.maximo > 0) {
              <span class="cuenta" [class.cerca]="cercaDelTope(campo)">
                {{ largo(campo.clave) }}/{{ campo.maximo }}
              </span>
            }
          </div>
        </div>
      }

      @if (error()) {
        <p class="mensaje mal">{{ error() }}</p>
      }

      @if (resultado(); as r) {
        <div class="mensaje" [class.bien]="r.correcto" [class.mal]="!r.correcto">
          <strong>{{ r.mensaje }}</strong>
          @if (detalles(r).length) {
            <ul>
              @for (detalle of detalles(r); track detalle.clave) {
                <li>{{ detalle.etiqueta }}: <b>{{ detalle.valor }}</b></li>
              }
            </ul>
          }
        </div>
      }

      @if (confirmando()) {
        <div class="confirmar">
          <p>Esto no se puede deshacer. ¿Seguro?</p>
          <div class="botones">
            <button type="button" class="boton secundario" (click)="confirmando.set(false)">Cancelar</button>
            <button type="button" class="boton peligro" (click)="ejecutar()">Sí, {{ accion().textoBoton.toLowerCase() }}</button>
          </div>
        </div>
      } @else {
        <button type="submit" class="boton" [disabled]="enviando() || !completo()">
          @if (enviando()) {
            Enviando…
          } @else {
            {{ accion().textoBoton }}
          }
        </button>
      }
    </form>
  `,
  styles: [
    `
      .accion {
        display: flex;
        flex-direction: column;
        gap: 0.9rem;
        max-width: 560px;
      }

      header {
        display: flex;
        gap: 0.8rem;
        align-items: flex-start;
      }

      .icono {
        display: grid;
        place-items: center;
        width: 34px;
        height: 34px;
        border-radius: 10px;
        background: rgba(255, 255, 255, 0.06);
        flex-shrink: 0;
      }

      h3 {
        font-size: 0.95rem;
        font-weight: 700;
      }

      header p {
        font-size: 0.8rem;
        color: var(--texto-3);
        margin-top: 0.15rem;
      }

      .campo {
        display: flex;
        flex-direction: column;
      }

      .pie-campo {
        display: flex;
        justify-content: space-between;
        gap: 1rem;
        align-items: baseline;
      }

      .cuenta {
        font-size: 0.72rem;
        color: var(--texto-3);
        font-variant-numeric: tabular-nums;
        margin-top: 0.35rem;
        margin-left: auto;
      }

      .cuenta.cerca {
        color: var(--aviso);
      }

      .mensaje {
        font-size: 0.83rem;
        padding: 0.7rem 0.85rem;
        border-radius: var(--radio-chico);
        border: 1px solid var(--borde);
      }

      .mensaje.bien {
        border-color: rgba(34, 197, 94, 0.4);
        background: rgba(34, 197, 94, 0.08);
        color: #bbf7d0;
      }

      .mensaje.mal {
        border-color: rgba(239, 68, 68, 0.4);
        background: rgba(239, 68, 68, 0.08);
        color: #fecaca;
      }

      .mensaje ul {
        list-style: none;
        margin-top: 0.4rem;
        display: flex;
        gap: 0.9rem;
        flex-wrap: wrap;
        font-size: 0.78rem;
        opacity: 0.9;
      }

      .confirmar {
        border: 1px solid rgba(239, 68, 68, 0.4);
        background: rgba(239, 68, 68, 0.06);
        border-radius: var(--radio-chico);
        padding: 0.85rem;
      }

      .confirmar p {
        font-size: 0.85rem;
        margin-bottom: 0.7rem;
      }

      .botones {
        display: flex;
        gap: 0.6rem;
      }
    `,
  ],
})
export class FormularioAccion {
  private readonly api = inject(Api)

  readonly appId = input.required<string>()
  readonly accion = input.required<AccionAdmin>()
  readonly color = input('#2196f3')

  /** Avisa al padre para que recargue las cifras, que acaban de cambiar. */
  readonly hecho = output<ResultadoAccion>()

  protected readonly enviando = signal(false)
  protected readonly confirmando = signal(false)
  protected readonly error = signal<string | null>(null)
  protected readonly resultado = signal<ResultadoAccion | null>(null)

  private readonly valores = signal<Record<string, string>>({})

  /** No se deja enviar hasta que estén los campos obligatorios. */
  protected readonly completo = computed(() =>
    this.accion().campos.every((campo) => !campo.obligatorio || (this.valor(campo.clave) ?? '') !== ''),
  )

  protected valor(clave: string): string {
    const guardado = this.valores()[clave]
    if (guardado !== undefined) {
      return guardado
    }
    // Los desplegables arrancan en su primera opción: un select vacío obligaría a
    // elegir algo que casi siempre es lo que ya estaba puesto.
    const campo = this.accion().campos.find((c) => c.clave === clave)
    return campo?.tipo === 'SELECCION' ? (campo.opciones[0]?.valor ?? '') : ''
  }

  protected cambiar(clave: string, valor: string): void {
    this.valores.update((actuales) => ({ ...actuales, [clave]: valor }))
    // Un resultado viejo debajo de un formulario que ya se está reescribiendo
    // confunde: se borra en cuanto se toca algo.
    this.resultado.set(null)
    this.error.set(null)
  }

  protected largo(clave: string): number {
    return this.valor(clave).length
  }

  protected cercaDelTope(campo: CampoAccion): boolean {
    return campo.maximo > 0 && this.largo(campo.clave) > campo.maximo * 0.9
  }

  protected enviar(): void {
    if (this.accion().peligrosa) {
      this.confirmando.set(true)
      return
    }
    void this.ejecutar()
  }

  protected async ejecutar(): Promise<void> {
    this.confirmando.set(false)
    this.enviando.set(true)
    this.error.set(null)
    this.resultado.set(null)

    // Se mandan TODOS los campos declarados, incluidos los que el usuario no ha
    // tocado: si no, un desplegable que se deja en su valor por defecto llegaría
    // vacío al backend y la acción fallaría por un campo que en pantalla se veía
    // relleno.
    const parametros: Record<string, string> = {}
    for (const campo of this.accion().campos) {
      parametros[campo.clave] = this.valor(campo.clave)
    }

    try {
      const resultado = await firstValueFrom(
        this.api.ejecutar(this.appId(), this.accion().id, parametros),
      )
      this.resultado.set(resultado)
      this.hecho.emit(resultado)

      if (resultado.correcto) {
        this.valores.set({})
      }
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    } finally {
      this.enviando.set(false)
    }
  }

  /**
   * Las cifras que devuelve la acción, con nombres que se leen.
   *
   * Se enseñan también los ceros, al revés de como estaba antes. «Confirmados: 0»
   * es justo el dato que hay que ver cuando un aviso no llega; esconderlo por ser
   * cero dejaba en pantalla solo las cifras buenas y hacía parecer que todo había
   * ido bien.
   */
  protected detalles(resultado: ResultadoAccion): { clave: string; etiqueta: string; valor: string }[] {
    return Object.entries(resultado.detalles ?? {})
      .filter(([, valor]) => typeof valor === 'number')
      .map(([clave, valor]) => ({
        clave,
        etiqueta: ETIQUETAS[clave] ?? clave,
        valor: entero(valor as number),
      }))
  }
}

const ETIQUETAS: Record<string, string> = {
  dispositivos: 'Dispositivos',
  // La distinción es la que importa: Expo acepta primero y entrega después, y
  // solo lo segundo significa que a alguien le ha sonado el móvil.
  confirmados: 'Entregados de verdad',
  sinConfirmar: 'Sin confirmar todavía',
  fallidos: 'Fallidos',
  tokensLimpiados: 'Tokens muertos borrados',
  // Los de VBStats, que van por FCM.
  entregados: 'Entregados',
}
