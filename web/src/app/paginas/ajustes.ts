import { ChangeDetectionStrategy, Component, inject } from '@angular/core'
import { RouterLink } from '@angular/router'

import { Icono } from '../componentes/icono'
import { Sesion } from '../nucleo/sesion'

/**
 * Qué está conectado y qué falta por conectar.
 *
 * Es la pantalla a la que se llega desde el aviso de una app apagada, y su
 * trabajo es contestar «¿y ahora qué pongo?». Por eso enseña el nombre exacto de
 * cada variable de entorno junto al motivo que devuelve el conector.
 *
 * Lo que NO hace, y es deliberado: enseñar el valor de ninguna credencial, ni
 * siquiera parcialmente. Un panel que pinta los primeros caracteres de una clave
 * «para comprobar que es la buena» es un panel que filtra claves en cuanto
 * alguien mira la pantalla por encima del hombro o comparte una captura.
 */
@Component({
  selector: 'bd-pagina-ajustes',
  standalone: true,
  imports: [RouterLink, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="cabecera">
      <div>
        <h1>Ajustes</h1>
        <p>Qué aplicaciones están conectadas y qué le falta a cada una.</p>
      </div>
      <button type="button" class="boton secundario" (click)="revisar()">
        <bd-icono nombre="recargar" [tamano]="16" /> Volver a comprobar
      </button>
    </header>

    <section class="bloque">
      <h2 class="titulo-seccion">Acceso</h2>
      <div class="tarjeta ficha">
        <div class="fila">
          <span class="clave">Sesión iniciada</span>
          <span class="valor">{{ sesion.admin()?.email }}</span>
        </div>
        <div class="fila">
          <span class="clave">Cómo se entra</span>
          <span class="valor">Cuenta de Google contra una lista blanca del servidor</span>
        </div>
        <p class="nota">
          Quién puede entrar se define en la variable <code>BLUEDEBUG_PERMITIDOS</code>, separando
          los correos por comas. La lista se comprueba en cada petición, así que quitar un correo y
          reiniciar cierra esa sesión al momento, sin esperar a que caduque.
        </p>
      </div>
    </section>

    <section class="bloque">
      <h2 class="titulo-seccion">Aplicaciones</h2>
      <div class="apps">
        @for (item of sesion.apps(); track item.app.id) {
          <article class="tarjeta ficha">
            <header>
              <span class="icono" [style.color]="item.app.color" [style.background]="fondo(item.app.color)">
                <bd-icono [nombre]="item.app.icono" [tamano]="20" />
              </span>
              <div>
                <h3>{{ item.app.nombre }}</h3>
                <p>{{ item.app.descripcion }}</p>
              </div>
              <span class="pastilla" [class.bien]="item.estado.disponible" [class.aviso]="!item.estado.disponible">
                {{ item.estado.disponible ? 'Conectada' : 'Sin conectar' }}
              </span>
            </header>

            @if (!item.estado.disponible) {
              <p class="motivo">{{ item.estado.motivo }}</p>
            }

            <ul class="variables">
              @for (variable of variablesDe(item.app.id); track variable.nombre) {
                <li>
                  <code>{{ variable.nombre }}</code>
                  <span>{{ variable.para }}</span>
                </li>
              }
            </ul>

            <div class="pie">
              <span class="pastilla">{{ item.app.plataformas.join(' · ') }}</span>
              @for (capacidad of item.app.capacidades; track capacidad) {
                <span class="pastilla">{{ nombreCapacidad(capacidad) }}</span>
              }
            </div>

            <a [routerLink]="['/apps', item.app.id]" class="enlace">Abrir {{ item.app.nombre }} →</a>
          </article>
        } @empty {
          <p class="vacia">No hay ningún conector registrado en el servidor.</p>
        }
      </div>
    </section>

    <section class="bloque">
      <h2 class="titulo-seccion">Añadir una aplicación</h2>
      <div class="tarjeta ficha">
        <p class="explicacion">
          El panel no tiene ninguna lista de aplicaciones: se construye con lo que encuentra. Para
          meter una nueva basta con crear una clase en <code>conectores/</code> que implemente
          <code>ConectorApp</code> y marcarla como componente de Spring. Aparecerá sola en el menú,
          con sus pestañas, su tabla de usuarios y sus botones de acción, sin tocar una línea de
          Angular.
        </p>
        <p class="explicacion">
          Lo que decide qué pestañas se ven son las <em>capacidades</em> que declare en su
          descriptor. Una app que solo sepa listar usuarios enseñará esa pestaña y ninguna más.
        </p>
      </div>
    </section>
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

      .cabecera > div > p {
        color: var(--texto-3);
        font-size: 0.87rem;
        margin-top: 0.2rem;
      }

      .bloque {
        margin-bottom: 2rem;
      }

      .apps {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
        gap: 0.9rem;
      }

      .ficha header {
        display: flex;
        align-items: flex-start;
        gap: 0.8rem;
        margin-bottom: 0.9rem;
      }

      .icono {
        display: grid;
        place-items: center;
        width: 38px;
        height: 38px;
        border-radius: 11px;
        flex-shrink: 0;
      }

      h3 {
        font-size: 0.95rem;
        font-weight: 700;
      }

      .ficha header p {
        font-size: 0.78rem;
        color: var(--texto-3);
        margin-top: 0.1rem;
      }

      .ficha header .pastilla {
        margin-left: auto;
      }

      .fila {
        display: flex;
        justify-content: space-between;
        gap: 1rem;
        padding: 0.5rem 0;
        border-bottom: 1px solid rgba(255, 255, 255, 0.05);
        font-size: 0.85rem;
        flex-wrap: wrap;
      }

      .clave {
        color: var(--texto-3);
      }

      .valor {
        font-weight: 600;
      }

      .motivo {
        font-size: 0.8rem;
        color: var(--aviso);
        border-left: 2px solid var(--aviso);
        padding-left: 0.7rem;
        margin-bottom: 0.9rem;
        line-height: 1.5;
      }

      .variables {
        list-style: none;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
        margin-bottom: 0.9rem;
      }

      .variables li {
        display: flex;
        gap: 0.65rem;
        align-items: baseline;
        font-size: 0.78rem;
        flex-wrap: wrap;
      }

      .variables span {
        color: var(--texto-3);
      }

      code {
        font-family: var(--mono);
        font-size: 0.75rem;
        background: rgba(0, 0, 0, 0.35);
        border: 1px solid var(--borde);
        padding: 0.1rem 0.4rem;
        border-radius: 5px;
        white-space: nowrap;
      }

      .pie {
        display: flex;
        gap: 0.35rem;
        flex-wrap: wrap;
        padding-top: 0.85rem;
        border-top: 1px solid var(--borde);
      }

      .enlace {
        display: inline-block;
        margin-top: 0.9rem;
        font-size: 0.82rem;
        font-weight: 600;
        color: var(--acento-claro);
      }

      .nota,
      .explicacion {
        font-size: 0.82rem;
        color: var(--texto-3);
        line-height: 1.6;
        margin-top: 0.9rem;
        max-width: 720px;
      }

      .explicacion + .explicacion {
        margin-top: 0.7rem;
      }

      .vacia {
        color: var(--texto-3);
        font-size: 0.88rem;
        padding: 2rem;
        text-align: center;
      }
    `,
  ],
})
export class PaginaAjustes {
  protected readonly sesion = inject(Sesion)

  protected revisar(): void {
    void this.sesion.cargarApps()
  }

  protected fondo(color: string): string {
    return `${color}1f`
  }

  protected nombreCapacidad(capacidad: string): string {
    return CAPACIDADES[capacidad] ?? capacidad
  }

  /**
   * Qué variables de entorno necesita cada conector.
   *
   * Sí, esto es una lista escrita a mano en el front, que es justo lo que el
   * resto del panel evita. Se hace así a propósito: la alternativa es que el
   * backend publique por la API los nombres de sus variables de configuración, y
   * eso es información que ayuda a quien quiera atacarlo y que no aporta nada a
   * quien ya sabe dónde mirar. Para una app nueva, se añade aquí una entrada; y
   * si no se añade, la pantalla sigue funcionando sin esa lista.
   */
  protected variablesDe(appId: string): { nombre: string; para: string }[] {
    return VARIABLES[appId] ?? []
  }
}

const CAPACIDADES: Record<string, string> = {
  USUARIOS: 'Usuarios',
  METRICAS: 'Métricas',
  INGRESOS: 'Ingresos',
  ACCIONES: 'Acciones',
  BORRAR_USUARIOS: 'Bajas',
}

const VARIABLES: Record<string, { nombre: string; para: string }[]> = {
  vbstats: [
    { nombre: 'BLUEDEBUG_VBSTATS_URL', para: 'su base de datos MySQL' },
    { nombre: 'BLUEDEBUG_VBSTATS_STRIPE', para: 'los ingresos (clave restringida de lectura)' },
    { nombre: 'BLUEDEBUG_VBSTATS_FIREBASE', para: 'las notificaciones push' },
  ],
  cvo: [{ nombre: 'BLUEDEBUG_CVO_FIREBASE', para: 'Firestore y los avisos del club' }],
}
