import { ChangeDetectionStrategy, Component, ElementRef, inject, signal, viewChild } from '@angular/core'
import { ActivatedRoute, Router } from '@angular/router'
import { firstValueFrom } from 'rxjs'

import { Api, type ErrorApi } from '../nucleo/api'
import { Sesion } from '../nucleo/sesion'

/**
 * La puerta.
 *
 * Solo hay una forma de entrar: el botón de Google. No hay usuario y contraseña,
 * ni recuperación, ni registro, y eso es una decisión, no una falta. Las
 * contraseñas de un panel de administración son la parte más frágil de todo el
 * montaje —se reutilizan, se filtran, se apuntan— y quitarlas de en medio deja la
 * autenticación en manos de una cuenta de Google con su segundo factor.
 *
 * Quién puede entrar lo decide el servidor con su lista blanca, y esa lista NO
 * viaja hasta aquí: publicarla en la pantalla de acceso sería regalar los correos
 * de los administradores a cualquiera que abra la página. Por eso el aviso de
 * «esta cuenta no tiene acceso» solo puede llegar después de intentarlo.
 *
 * El script de Google se carga a mano, en cuanto se sabe el client id, en vez de
 * ponerlo en el index.html. Así, si el servidor no tiene la configuración de
 * Google, la pantalla lo dice claramente en vez de quedarse con un hueco donde
 * debería estar el botón.
 */
@Component({
  selector: 'bd-acceso',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main class="pantalla">
      <section class="caja">
        <img src="assets/logo.svg" alt="" class="logo" />
        <h1>BlueDebug <span>Management</span></h1>
        <p class="claim">El panel de administración de las aplicaciones de la casa.</p>

        @if (cargando()) {
          <p class="estado">Preparando el acceso…</p>
        } @else if (!configurado()) {
          <div class="fallo">
            <strong>El servidor no tiene configurado el acceso con Google.</strong>
            <p>
              Falta la variable <code>BLUEDEBUG_GOOGLE_CLIENT_ID</code>. En el README está de
              dónde se saca ese identificador.
            </p>
          </div>
        } @else {
          <div #boton class="boton-google"></div>
        }

        @if (error()) {
          <p class="fallo simple">{{ error() }}</p>
        }

        <p class="pie">Acceso restringido a las cuentas autorizadas.</p>
      </section>
    </main>
  `,
  styles: [
    `
      .pantalla {
        min-height: 100vh;
        display: grid;
        place-items: center;
        padding: 2rem 1.25rem;
        /* Un par de manchas de color muy tenues; el fondo plano en una pantalla
           tan vacía se ve apagado. */
        background:
          radial-gradient(60% 60% at 20% 10%, rgba(33, 150, 243, 0.14), transparent 70%),
          radial-gradient(50% 50% at 85% 85%, rgba(14, 165, 233, 0.1), transparent 70%),
          var(--fondo);
      }

      .caja {
        width: min(420px, 100%);
        background: var(--superficie);
        border: 1px solid var(--borde);
        border-radius: 20px;
        padding: 2.5rem 2rem;
        text-align: center;
        box-shadow: var(--sombra);
      }

      .logo {
        width: 76px;
        height: 76px;
        margin: 0 auto 1rem;
        display: block;
      }

      h1 {
        font-size: 1.4rem;
        font-weight: 800;
        letter-spacing: -0.02em;
      }

      h1 span {
        color: var(--acento);
      }

      .claim {
        color: var(--texto-3);
        font-size: 0.87rem;
        margin: 0.4rem 0 1.75rem;
      }

      .boton-google {
        display: flex;
        justify-content: center;
        min-height: 44px;
      }

      .estado {
        color: var(--texto-3);
        font-size: 0.85rem;
        padding: 0.8rem 0;
      }

      .fallo {
        text-align: left;
        font-size: 0.82rem;
        border: 1px solid rgba(239, 68, 68, 0.4);
        background: rgba(239, 68, 68, 0.08);
        color: #fecaca;
        border-radius: var(--radio-chico);
        padding: 0.8rem 0.9rem;
      }

      .fallo p {
        margin-top: 0.35rem;
        opacity: 0.9;
      }

      .fallo.simple {
        margin-top: 1rem;
        text-align: center;
      }

      code {
        font-family: var(--mono);
        font-size: 0.78rem;
        background: rgba(0, 0, 0, 0.3);
        padding: 0.1rem 0.3rem;
        border-radius: 4px;
      }

      .pie {
        margin-top: 1.75rem;
        font-size: 0.74rem;
        color: var(--texto-3);
      }
    `,
  ],
})
export class PaginaAcceso {
  private readonly api = inject(Api)
  private readonly sesion = inject(Sesion)
  private readonly router = inject(Router)
  private readonly ruta = inject(ActivatedRoute)

  private readonly boton = viewChild<ElementRef<HTMLElement>>('boton')

  protected readonly cargando = signal(true)
  protected readonly configurado = signal(false)
  protected readonly error = signal<string | null>(null)

  async ngOnInit(): Promise<void> {
    try {
      const config = await firstValueFrom(this.api.configuracionAcceso())
      this.configurado.set(config.configurado)

      if (config.configurado) {
        await cargarScriptDeGoogle()
        // El botón lo pinta Angular en el mismo ciclo, así que hay que esperar a
        // que el elemento exista antes de que Google escriba dentro.
        this.cargando.set(false)
        queueMicrotask(() => this.pintarBoton(config.googleClientId))
        return
      }
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    }
    this.cargando.set(false)
  }

  private pintarBoton(clientId: string): void {
    const destino = this.boton()?.nativeElement
    if (!destino) {
      return
    }

    google.accounts.id.initialize({
      client_id: clientId,
      callback: (respuesta) => void this.entrar(respuesta.credential),
      // Sin esto, Google intenta iniciar sesión solo con la cuenta ya abierta en
      // el navegador. En un panel de administración es mejor que el paso sea
      // siempre explícito.
      auto_select: false,
      cancel_on_tap_outside: true,
    })

    google.accounts.id.renderButton(destino, {
      type: 'standard',
      theme: 'filled_black',
      size: 'large',
      shape: 'pill',
      text: 'continue_with',
      locale: 'es',
      width: 280,
    })
  }

  private async entrar(credencial: string): Promise<void> {
    this.error.set(null)
    try {
      await this.sesion.entrar(credencial)
      const volverA = this.ruta.snapshot.queryParamMap.get('volverA')
      await this.router.navigateByUrl(volverA || '/panel')
    } catch (fallo) {
      this.error.set((fallo as ErrorApi).mensaje)
    }
  }
}

/**
 * Carga el script de Google una sola vez.
 *
 * La promesa se guarda en una variable de módulo: si se entra y se sale de la
 * pantalla de acceso varias veces, se reutiliza la misma carga en vez de meter
 * otra etiqueta `<script>` en la cabecera cada vez.
 */
let cargaEnMarcha: Promise<void> | null = null

function cargarScriptDeGoogle(): Promise<void> {
  if (cargaEnMarcha) {
    return cargaEnMarcha
  }

  cargaEnMarcha = new Promise<void>((cumplir, fallar) => {
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.defer = true
    script.onload = () => cumplir()
    script.onerror = () => {
      cargaEnMarcha = null
      fallar(new Error('No se pudo cargar el acceso de Google'))
    }
    document.head.appendChild(script)
  })

  return cargaEnMarcha
}
