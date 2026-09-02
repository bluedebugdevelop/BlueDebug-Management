import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core'
import { DomSanitizer } from '@angular/platform-browser'

/**
 * El juego de iconos del panel.
 *
 * Van a mano y no como dependencia por dos motivos: son quince, y una librería
 * de iconos completa pesa más que todo el resto del front junto. Todos comparten
 * caja de 24, trazo de 1.8 y extremos redondeados, que es lo que hace que se vean
 * de la misma familia aunque los haya dibujado gente distinta.
 *
 * Para añadir uno: una entrada más en TRAZOS. El nombre es el que usan los
 * conectores del backend en su descriptor, así que una app nueva puede pedir su
 * icono sin tocar nada más.
 */
@Component({
  selector: 'bd-icono',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg
      [attr.width]="tamano()"
      [attr.height]="tamano()"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      [innerHTML]="dibujo()"
    ></svg>
  `,
  styles: [':host { display: inline-flex; line-height: 0; }'],
})
export class Icono {
  private readonly saneador = inject(DomSanitizer)

  readonly nombre = input.required<string>()
  readonly tamano = input(20)

  /*
    El `bypass` está justificado y merece la explicación, porque es de las cosas
    que hay que mirar con lupa cuando aparecen en una revisión.

    El saneador de Angular no conoce los elementos de SVG: si se le da un
    `<path>` por innerHTML, lo borra y el icono sale vacío. Y lo que se inyecta
    aquí no viene de ninguna parte de fuera: es una entrada de TRAZOS, un mapa
    constante escrito debajo en este mismo fichero. Un nombre que no exista cae
    en 'punto', así que ni siquiera hay forma de pedir algo que no esté en la
    lista. No hay dato de usuario ni de la API en ese HTML.
  */
  protected readonly dibujo = computed(() =>
    this.saneador.bypassSecurityTrustHtml(TRAZOS[this.nombre()] ?? TRAZOS['punto']),
  )
}

/** Los trazos, ya como cadena de SVG. Constantes: ver el comentario de arriba. */
const TRAZOS: Record<string, string> = {
  // Navegación
  panel: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  usuarios: '<path d="M16 20v-1.5a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4V20"/><circle cx="9" cy="7" r="3.5"/><path d="M22 20v-1.5a4 4 0 0 0-3-3.87"/><path d="M16 3.6a4 4 0 0 1 0 7.75"/>',
  actividad: '<path d="M3 12h4l3 8 4-16 3 8h4"/>',
  ajustes: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 0 0 .32 1.77l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-1.77-.32 1.6 1.6 0 0 0-1 1.47V21a2 2 0 1 1-4 0v-.1A1.6 1.6 0 0 0 9 19.4a1.6 1.6 0 0 0-1.77.32l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.6 1.6 0 0 0 4.6 15a1.6 1.6 0 0 0-1.47-1H3a2 2 0 1 1 0-4h.1A1.6 1.6 0 0 0 4.6 9a1.6 1.6 0 0 0-.32-1.77l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.6 1.6 0 0 0 9 4.6h.08A1.6 1.6 0 0 0 10 3.13V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.47 1.6 1.6 0 0 0 1.77-.32l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06A1.6 1.6 0 0 0 19.4 9v.08a1.6 1.6 0 0 0 1.47 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.47 1z"/>',
  salir: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',

  // Apps
  grafica: '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><rect x="7" y="12" width="3" height="5" rx="1"/><rect x="12" y="8" width="3" height="9" rx="1"/><rect x="17" y="4" width="3" height="13" rx="1"/>',
  escudo: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/>',
  movil: '<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/>',
  web: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 0 18 15 15 0 0 1 0-18z"/>',
  nevera: '<rect x="5" y="2.5" width="14" height="19" rx="2.5"/><path d="M5 10h14"/><path d="M8.5 5.5v2"/><path d="M8.5 12.5v2.5"/>',

  // Acciones y estados
  campana: '<path d="M18 8a6 6 0 1 0-12 0c0 7-3 8-3 8h18s-3-1-3-8"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
  aviso: '<path d="M12 9v4"/><path d="M12 17h.01"/><circle cx="12" cy="12" r="9"/>',
  bien: '<circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 4.5-5"/>',
  buscar: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  papelera: '<path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M10 11v6"/><path d="M14 11v6"/>',
  euro: '<path d="M17 5a8 8 0 1 0 0 14"/><path d="M3 10h9"/><path d="M3 14h9"/>',
  volver: '<path d="M19 12H5"/><path d="m12 19-7-7 7-7"/>',
  recargar: '<path d="M21 12a9 9 0 1 1-3-6.7"/><path d="M21 4v5h-5"/>',
  punto: '<circle cx="12" cy="12" r="3"/>',
}
