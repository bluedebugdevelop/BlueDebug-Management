import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core'
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router'

import { Icono } from '../componentes/icono'
import { Sesion } from '../nucleo/sesion'

/**
 * El armazón: barra lateral fija y el contenido a la derecha.
 *
 * La sección «Aplicaciones» del menú SE CONSTRUYE SOLA con lo que devuelve el
 * backend. No hay ninguna lista de apps escrita en el front, y por eso el día que
 * se añada un conector nuevo aparece aquí sin tocar nada. Junto a cada una va un
 * punto de color: verde si responde, ámbar si le faltan credenciales, y así se ve
 * de un vistazo qué está en pie sin entrar a mirarlo.
 */
@Component({
  selector: 'bd-marco',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Icono],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="marco" [class.abierto]="menuAbierto()">
      <aside class="lateral">
        <a routerLink="/panel" class="marca" (click)="menuAbierto.set(false)">
          <img src="assets/logo.svg" alt="" />
          <span>
            BlueDebug
            <small>Management</small>
          </span>
        </a>

        <nav>
          <p class="grupo">General</p>
          <a routerLink="/panel" routerLinkActive="activo" (click)="menuAbierto.set(false)">
            <bd-icono nombre="panel" [tamano]="18" /> Panel
          </a>
          <a routerLink="/usuarios" routerLinkActive="activo" (click)="menuAbierto.set(false)">
            <bd-icono nombre="usuarios" [tamano]="18" /> Usuarios
          </a>
          <a routerLink="/actividad" routerLinkActive="activo" (click)="menuAbierto.set(false)">
            <bd-icono nombre="actividad" [tamano]="18" /> Actividad
          </a>

          <p class="grupo">Empresa</p>
          <a routerLink="/contabilidad" routerLinkActive="activo" (click)="menuAbierto.set(false)">
            <bd-icono nombre="recibo" [tamano]="18" /> Contabilidad
          </a>

          <p class="grupo">Aplicaciones</p>
          @for (item of sesion.apps(); track item.app.id) {
            <a
              [routerLink]="['/apps', item.app.id]"
              routerLinkActive="activo"
              (click)="menuAbierto.set(false)"
              [title]="item.estado.motivo || item.app.descripcion"
            >
              <span class="icono-app" [style.color]="item.app.color">
                <bd-icono [nombre]="item.app.icono" [tamano]="18" />
              </span>
              {{ item.app.nombre }}
              <i class="punto" [class.bien]="item.estado.disponible"></i>
            </a>
          } @empty {
            <p class="ninguna">Ningún conector configurado</p>
          }

          <p class="grupo">Sistema</p>
          <a routerLink="/ajustes" routerLinkActive="activo" (click)="menuAbierto.set(false)">
            <bd-icono nombre="ajustes" [tamano]="18" /> Ajustes
          </a>
        </nav>

        <footer>
          @if (sesion.admin(); as admin) {
            <div class="quien">
              @if (admin.foto) {
                <img [src]="admin.foto" alt="" referrerpolicy="no-referrer" />
              }
              <div class="datos">
                <strong>{{ admin.nombre || 'Administrador' }}</strong>
                <small>{{ admin.email }}</small>
              </div>
            </div>
          }
          <button type="button" class="salir" (click)="salir()">
            <bd-icono nombre="salir" [tamano]="16" /> Salir
          </button>
        </footer>
      </aside>

      <div class="velo" (click)="menuAbierto.set(false)"></div>

      <div class="contenido">
        <button type="button" class="hamburguesa" (click)="menuAbierto.set(!menuAbierto())" aria-label="Menú">
          <bd-icono nombre="panel" [tamano]="20" />
        </button>
        <router-outlet />
      </div>
    </div>
  `,
  styles: [
    `
      .marco {
        display: grid;
        grid-template-columns: 258px 1fr;
        min-height: 100vh;
      }

      .lateral {
        background: var(--fondo-2);
        border-right: 1px solid var(--borde);
        display: flex;
        flex-direction: column;
        padding: 1.25rem 0.85rem;
        position: sticky;
        top: 0;
        height: 100vh;
        overflow-y: auto;
      }

      .marca {
        display: flex;
        align-items: center;
        gap: 0.65rem;
        padding: 0 0.5rem 1.25rem;
      }

      .marca img {
        width: 34px;
        height: 34px;
      }

      .marca span {
        display: flex;
        flex-direction: column;
        font-weight: 800;
        font-size: 0.95rem;
        letter-spacing: -0.02em;
        line-height: 1.15;
      }

      .marca small {
        font-size: 0.72rem;
        font-weight: 600;
        color: var(--acento);
        letter-spacing: 0.02em;
      }

      nav {
        display: flex;
        flex-direction: column;
        gap: 0.12rem;
        flex: 1;
      }

      .grupo {
        font-size: 0.68rem;
        text-transform: uppercase;
        letter-spacing: 0.09em;
        color: var(--texto-3);
        font-weight: 700;
        padding: 1rem 0.75rem 0.4rem;
      }

      nav a {
        display: flex;
        align-items: center;
        gap: 0.65rem;
        padding: 0.55rem 0.75rem;
        border-radius: var(--radio-chico);
        color: var(--texto-2);
        font-size: 0.88rem;
        font-weight: 500;
        transition: background var(--transicion), color var(--transicion);
      }

      nav a:hover {
        background: rgba(255, 255, 255, 0.05);
        color: var(--texto);
      }

      nav a.activo {
        background: var(--acento-suave);
        color: var(--acento-claro);
        font-weight: 600;
      }

      .icono-app {
        display: inline-flex;
      }

      .punto {
        width: 7px;
        height: 7px;
        border-radius: 99px;
        background: var(--aviso);
        margin-left: auto;
        flex-shrink: 0;
      }

      .punto.bien {
        background: var(--bien);
      }

      .ninguna {
        font-size: 0.78rem;
        color: var(--texto-3);
        padding: 0.4rem 0.75rem;
      }

      footer {
        border-top: 1px solid var(--borde);
        padding-top: 0.85rem;
        margin-top: 0.85rem;
      }

      .quien {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        padding: 0 0.5rem 0.7rem;
      }

      .quien img {
        width: 30px;
        height: 30px;
        border-radius: 99px;
      }

      .datos {
        display: flex;
        flex-direction: column;
        min-width: 0;
      }

      .datos strong {
        font-size: 0.82rem;
      }

      .datos small {
        font-size: 0.72rem;
        color: var(--texto-3);
        overflow: hidden;
        text-overflow: ellipsis;
      }

      .salir {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        width: 100%;
        padding: 0.5rem 0.75rem;
        border-radius: var(--radio-chico);
        font-size: 0.82rem;
        color: var(--texto-3);
        transition: background var(--transicion), color var(--transicion);
      }

      .salir:hover {
        background: rgba(239, 68, 68, 0.1);
        color: #fca5a5;
      }

      .contenido {
        padding: 2rem 2.25rem 3rem;
        max-width: 1400px;
        width: 100%;
        /* Sin esto, el contenido puede ensanchar la página entera.
           Una columna de rejilla mide por defecto lo que mida su contenido
           MÍNIMO (min-width: auto), y ahí una tabla ancha no cuenta como algo
           que se pueda encoger: la columna crece hasta caber la tabla y quien
           hace scroll horizontal es la ventana, sacando la barra lateral de la
           pantalla. Con min-width: 0 la columna se queda en su sitio y el
           scroll lo hace la tabla dentro de su caja, que es lo que .marco-tabla
           ya prepara. */
        min-width: 0;
      }

      .hamburguesa,
      .velo {
        display: none;
      }

      @media (max-width: 900px) {
        .marco {
          grid-template-columns: 1fr;
        }

        .lateral {
          position: fixed;
          inset: 0 auto 0 0;
          width: 258px;
          z-index: 20;
          transform: translateX(-100%);
          transition: transform var(--transicion);
        }

        .marco.abierto .lateral {
          transform: none;
        }

        .marco.abierto .velo {
          display: block;
          position: fixed;
          inset: 0;
          background: rgba(0, 0, 0, 0.6);
          z-index: 10;
        }

        .hamburguesa {
          display: inline-flex;
          margin-bottom: 1rem;
          padding: 0.5rem;
          border: 1px solid var(--borde);
          border-radius: var(--radio-chico);
          color: var(--texto-2);
        }

        .contenido {
          padding: 1.25rem 1.1rem 2.5rem;
        }
      }
    `,
  ],
})
export class Marco {
  protected readonly sesion = inject(Sesion)

  protected readonly menuAbierto = signal(false)

  protected salir(): void {
    void this.sesion.salir()
  }
}
