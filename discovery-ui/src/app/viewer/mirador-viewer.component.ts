import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { TranslatePipe } from '@ngx-translate/core';

import { IiifSummary } from '../core/api/discovery-api';
import { ConfigService } from '../core/config/config.service';
import { LocaleService } from '../core/i18n/locale.service';
import { miradorConfig } from './mirador-config';
import type { MiradorInstance } from './mirador-loader';

let nextId = 0;

/**
 * Presents a resource's IIIF export in Mirador: images (deep zoom), audio and video, and PDFs
 * (through the PDF plugin). The server renders the resource's preview image and a link to its
 * IIIF Manifest; Mirador replaces them in the browser, loaded on demand.
 */
@Component({
  selector: 'disc-mirador-viewer',
  imports: [MatIconModule, MatProgressSpinnerModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { ngSkipHydration: 'true' },
  template: `
    <div
      class="disc-viewer"
      role="region"
      [attr.aria-label]="'viewer.label' | translate: { title: title() }"
      [style.height]="height()"
    >
      <div #host class="disc-viewer-host" [id]="id" [hidden]="state() !== 'ready'"></div>
      @if (state() !== 'ready') {
        <div class="disc-viewer-fallback">
          @if (thumbnail()) {
            <img [src]="thumbnail()" [alt]="'viewer.preview' | translate: { title: title() }" />
          }
          @if (state() === 'loading') {
            <mat-progress-spinner mode="indeterminate" diameter="40" [attr.aria-label]="'viewer.loading' | translate" />
          }
          @if (state() === 'failed') {
            <p role="alert">{{ 'viewer.failed' | translate }}</p>
          }
        </div>
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .disc-viewer {
      position: relative;
      min-height: 320px;
      overflow: hidden;
      border-radius: var(--mat-sys-corner-medium);
      border: 1px solid var(--mat-sys-outline-variant);
      background: var(--mat-sys-surface-container);
    }
    .disc-viewer-host {
      position: absolute;
      inset: 0;
    }
    .disc-viewer-fallback {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: 16px;
      height: 100%;
      padding: 16px;
      box-sizing: border-box;
    }
    .disc-viewer-fallback img {
      max-width: 100%;
      max-height: 80%;
      object-fit: contain;
    }
  `,
})
export class MiradorViewerComponent {
  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  /** The IIIF export. */
  readonly iiif = input.required<IiifSummary>();

  /** The resource's title (labels). */
  readonly title = input.required<string>();

  /** A preview image shown until Mirador runs. */
  readonly thumbnail = input<string | undefined>(undefined);

  protected readonly id = `disc-mirador-${nextId++}`;

  protected readonly state = signal<'loading' | 'ready' | 'failed'>('loading');

  protected readonly height = computed(() => this.config.config().viewer.height);

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');

  private instance?: MiradorInstance;

  private observer?: MutationObserver;

  constructor() {
    const destroyRef = inject(DestroyRef);
    afterNextRender(() => {
      void this.start(destroyRef);
    });
    destroyRef.onDestroy(() => {
      this.observer?.disconnect();
      this.instance?.unmount();
      this.instance = undefined;
    });
  }

  private async start(destroyRef: DestroyRef): Promise<void> {
    const settings = this.config.config().viewer;
    const element = this.host().nativeElement;
    const view = element.ownerDocument.defaultView;
    const styles = view?.getComputedStyle(element.ownerDocument.documentElement);
    const dark = styles?.colorScheme === 'dark' || (styles?.colorScheme?.includes('dark') && view?.matchMedia('(prefers-color-scheme: dark)').matches);
    try {
      const { startMirador } = await import('./mirador-loader');
      if (destroyRef.destroyed) {
        return;
      }
      this.state.set('ready');
      this.demoteLandmarks(element);
      this.instance = startMirador(
        miradorConfig({
          id: this.id,
          manifest: this.iiif().id,
          collection: this.iiif().type === 'Collection',
          settings,
          locale: this.locales.locale(),
          dark: !!dark,
          primary: this.config.config().theme.colors['primary'],
        }),
        settings.pdf ? { document: this.locales.t('viewer.document', { title: this.title() }), open: this.locales.t('viewer.openPdf') } : null,
      );
    } catch (error) {
      console.error('Mirador cannot be started', error);
      this.state.set('failed');
    }
  }

  /**
   * Mirador renders its workspace as a `main` element, which would make a second main landmark
   * inside the page's own: it is exposed as a region of the viewer instead.
   */
  private demoteLandmarks(element: HTMLElement): void {
    const demote = () =>
      element.querySelectorAll('main:not([role])').forEach((main) => main.setAttribute('role', 'region'));
    this.observer = new MutationObserver(demote);
    this.observer.observe(element, { childList: true, subtree: true });
  }
}
