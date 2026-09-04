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
            @case ('MULTISELECCION') {
              <div class="marcables">
                @for (opcion of campo.opciones; track opcion.valor) {
                  <label class="marcable" [class.puesta]="marcada(campo.clave, opcion.valor)">
                    <input
                      type="checkbox"
                      [checked]="marcada(campo.clave, opcion.valor)"
                      (change)="alternar(campo.clave, opcion.valor)"
                      [name]="campo.clave + '-' + opcion.valor"
                    />
                    <span>
                      <b>{{ opcion.etiqueta }}</b>
                      @if (opcion.detalle) {
                        <em>{{ opcion.detalle }}</em>
                      }
                    </span>
                  </label>
                }
              </div>
            }
            @case ('INTERRUPTOR') {
              <label class="marcable suelto" [class.puesta]="encendido(campo.clave)">
                <input
                  type="checkbox"
                  [id]="campo.clave"
                  [checked]="encendido(campo.clave)"
                  (change)="conmutar(campo.clave)"
                  [name]="campo.clave"
                />
                <span><b>{{ encendido(campo.clave) ? 'Sí' : 'No' }}</b></span>
              </label>
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
          @if (cifras(r).length) {
            <ul>
              @for (detalle of cifras(r); track detalle.clave) {
                <li>{{ detalle.etiqueta }}: <b>{{ detalle.valor }}</b></li>
              }
            </ul>
          }
          @if (textos(r).length) {
            <!--
              Los datos que hay que ENTREGARLE a alguien: el correo y la contraseña
              recién generada de un alta. Van en un bloque aparte, monoespaciados y
              con un botón de copiar, porque de una contraseña de Firebase no se
              puede volver atrás: esta pantalla es la única vez que se ve.
            -->
            <dl class="entregable">
              @for (detalle of textos(r); track detalle.clave) {
                <div>
                  <dt>{{ detalle.etiqueta }}</dt>
                  <dd>{{ detalle.valor }}</dd>
                </div>
              }
            </dl>
            <button type="button" class="boton secundario copiar" (click)="copiar(r)">
              {{ copiado() ? 'Copiado' : 'Copiar para enviar' }}
            </button>
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

      .marcables {
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        margin-top: 0.35rem;
      }

      .marcable {
        display: flex;
        gap: 0.6rem;
        align-items: flex-start;
        padding: 0.55rem 0.7rem;
        border: 1px solid var(--borde);
        border-radius: var(--radio-chico);
        cursor: pointer;
        transition: border-color var(--transicion), background var(--transicion);
      }

      .marcable:hover {
        border-color: var(--acento);
      }

      .marcable.puesta {
        border-color: var(--acento);
        background: var(--acento-suave);
      }

      .marcable.suelto {
        align-self: flex-start;
        min-width: 120px;
      }

      .marcable input {
        margin-top: 0.15rem;
        width: auto;
        accent-color: var(--acento);
      }

      .marcable b {
        font-size: 0.85rem;
        font-weight: 600;
        display: block;
      }

      .marcable em {
        font-size: 0.75rem;
        font-style: normal;
        color: var(--texto-3);
        display: block;
        margin-top: 0.1rem;
      }

      .entregable {
        margin-top: 0.6rem;
        display: flex;
        flex-wrap: wrap;
        gap: 0.35rem 1.5rem;
      }

      .entregable dt {
        font-size: 0.72rem;
        text-transform: uppercase;
        letter-spacing: 0.06em;
        opacity: 0.75;
      }

      .entregable dd {
        font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        font-size: 0.95rem;
        font-weight: 600;
        user-select: all;
      }

      .copiar {
        margin-top: 0.7rem;
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
  protected readonly copiado = signal(false)

  /**
   * Lo que hay escrito en cada campo.
   *
   * El tipo es unión porque los campos ya no son todos texto: una multiselección
   * guarda una lista y un interruptor un booleano, y los dos viajan así hasta el
   * backend. Aplanarlos a cadena obligaría a volver a partirlos allí, que es
   * justo la ida y vuelta que falla con el primer valor que lleve una coma.
   */
  private readonly valores = signal<Record<string, string | string[] | boolean>>({})

  /** No se deja enviar hasta que estén los campos obligatorios. */
  protected readonly completo = computed(() =>
    this.accion().campos.every((campo) => {
      if (!campo.obligatorio) return true
      if (campo.tipo === 'MULTISELECCION') return this.marcadas(campo.clave).length > 0
      if (campo.tipo === 'INTERRUPTOR') return true
      return this.valor(campo.clave) !== ''
    }),
  )

  protected valor(clave: string): string {
    const guardado = this.valores()[clave]
    if (typeof guardado === 'string') {
      return guardado
    }
    if (guardado !== undefined) {
      return String(guardado)
    }
    // Los desplegables arrancan en su primera opción: un select vacío obligaría a
    // elegir algo que casi siempre es lo que ya estaba puesto.
    const campo = this.accion().campos.find((c) => c.clave === clave)
    return campo?.tipo === 'SELECCION' ? (campo.opciones[0]?.valor ?? '') : ''
  }

  protected marcadas(clave: string): string[] {
    const guardado = this.valores()[clave]
    return Array.isArray(guardado) ? guardado : []
  }

  protected marcada(clave: string, valor: string): boolean {
    return this.marcadas(clave).includes(valor)
  }

  protected alternar(clave: string, valor: string): void {
    const puestas = this.marcadas(clave)
    this.cambiar(clave, puestas.includes(valor)
      ? puestas.filter((v) => v !== valor)
      : [...puestas, valor])
  }

  protected encendido(clave: string): boolean {
    return this.valores()[clave] === true
  }

  protected conmutar(clave: string): void {
    this.cambiar(clave, !this.encendido(clave))
  }

  protected cambiar(clave: string, valor: string | string[] | boolean): void {
    this.valores.update((actuales) => ({ ...actuales, [clave]: valor }))
    // Un resultado viejo debajo de un formulario que ya se está reescribiendo
    // confunde: se borra en cuanto se toca algo.
    this.resultado.set(null)
    this.error.set(null)
    this.copiado.set(false)
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
    const parametros: Record<string, unknown> = {}
    for (const campo of this.accion().campos) {
      parametros[campo.clave] =
        campo.tipo === 'MULTISELECCION'
          ? this.marcadas(campo.clave)
          : campo.tipo === 'INTERRUPTOR'
            ? this.encendido(campo.clave)
            : this.valor(campo.clave)
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
  protected cifras(resultado: ResultadoAccion): Detalle[] {
    return Object.entries(resultado.detalles ?? {})
      .filter(([, valor]) => typeof valor === 'number')
      .map(([clave, valor]) => ({
        clave,
        etiqueta: ETIQUETAS[clave] ?? clave,
        valor: entero(valor as number),
      }))
  }

  /**
   * Los detalles que son texto: lo que hay que copiar y darle a alguien.
   *
   * Las acciones que crean cuentas devuelven aquí el correo y la contraseña. No
   * pasan por ETIQUETAS porque quien los escribe ya los nombra para leerlos
   * («Correo», «Contraseña»): un mapa de traducción aquí sería un sitio más que
   * actualizar cada vez que un conector devuelve un dato nuevo.
   */
  protected textos(resultado: ResultadoAccion): Detalle[] {
    return Object.entries(resultado.detalles ?? {})
      .filter(([, valor]) => typeof valor === 'string' && valor !== '')
      .map(([clave, valor]) => ({ clave, etiqueta: clave, valor: valor as string }))
  }

  protected copiar(resultado: ResultadoAccion): void {
    const texto = this.textos(resultado)
      .map((d) => `${d.etiqueta}: ${d.valor}`)
      .join('\n')

    void navigator.clipboard
      .writeText(texto)
      .then(() => this.copiado.set(true))
      .catch(() => this.error.set('El navegador no ha dejado copiar; selecciónalo a mano.'))
  }
}

interface Detalle {
  clave: string
  etiqueta: string
  valor: string
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
  // Novedades de versión de VBStats.
  novedades: 'Novedades escritas',
  idiomas: 'Idiomas con texto propio',
}
