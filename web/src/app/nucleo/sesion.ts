import { Injectable, computed, inject, signal } from '@angular/core'
import { Router } from '@angular/router'
import { firstValueFrom } from 'rxjs'

import { Api } from './api'
import type { Administrador, AppEnMenu } from './tipos'

/**
 * Quién ha entrado y qué apps hay, disponible desde cualquier pantalla.
 *
 * El estado va en signals: son la forma que tiene Angular moderno de tener un
 * dato reactivo sin RxJS de por medio, y aquí encajan porque esto es exactamente
 * eso, tres valores que cambian poco y que la plantilla lee.
 *
 * `cargada` distingue tres estados que hay que separar: todavía no se sabe si hay
 * sesión (arranque), se sabe que no la hay (a la pantalla de acceso) y se sabe que
 * sí. Sin esa distinción, el panel enseñaría el login medio segundo cada vez que
 * se recarga la página, aunque la sesión sea perfectamente válida.
 */
@Injectable({ providedIn: 'root' })
export class Sesion {
  private readonly api = inject(Api)
  private readonly router = inject(Router)

  readonly admin = signal<Administrador | null>(null)
  readonly apps = signal<AppEnMenu[]>([])
  readonly periodos = signal<number[]>([7, 30, 90, 365])
  readonly cargada = signal(false)

  readonly autenticado = computed(() => this.admin() !== null)

  /** Se llama una sola vez, al arrancar la aplicación. */
  async comprobar(): Promise<boolean> {
    try {
      const respuesta = await firstValueFrom(this.api.sesion())
      if (!respuesta.autenticado || !respuesta.admin) {
        this.admin.set(null)
        return false
      }
      this.admin.set(respuesta.admin)
      await this.cargarApps()
      return true
    } catch {
      // Un fallo aquí (servidor caído) no es «no autenticado», pero para el front
      // el efecto es el mismo: no se puede entrar. La pantalla de acceso enseñará
      // el error de verdad cuando se intente.
      this.admin.set(null)
      return false
    } finally {
      this.cargada.set(true)
    }
  }

  async entrar(credencialGoogle: string): Promise<void> {
    const admin = await firstValueFrom(this.api.entrar(credencialGoogle))
    this.admin.set(admin)
    await this.cargarApps()
  }

  async salir(): Promise<void> {
    try {
      await firstValueFrom(this.api.salir())
    } finally {
      // Aunque la llamada falle, aquí dentro ya no hay sesión: dejar al usuario
      // «dentro» de un panel cuya cookie puede estar muerta es peor que echarlo.
      this.admin.set(null)
      this.apps.set([])
      await this.router.navigate(['/acceso'])
    }
  }

  /**
   * Vuelve a pedir el estado de las apps.
   *
   * Con `revisar`, el servidor sondea en vivo en vez de devolver la foto que toma
   * en segundo plano. Solo lo pide el botón de Ajustes: en las demás pantallas la
   * foto vale y evita una conexión a cada base de datos por cada carga.
   */
  async cargarApps(revisar = false): Promise<void> {
    try {
      const arranque = await firstValueFrom(this.api.arranque(revisar))
      this.apps.set(arranque.apps)
      this.periodos.set(arranque.periodos)
      if (arranque.admin) {
        this.admin.set(arranque.admin)
      }
    } catch {
      this.apps.set([])
    }
  }

  app(id: string): AppEnMenu | undefined {
    return this.apps().find((a) => a.app.id === id)
  }
}
