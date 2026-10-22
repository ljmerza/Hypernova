// Even G2 host integration: the page, the temple gestures, the long-press menu, the microphone,
// lifecycle and storage. Page handling follows Hypertasks (src/lens/g2.ts there).
import {
  waitForEvenAppBridge, CreateStartUpPageContainer, RebuildPageContainer, ImageContainerProperty, ImageRawDataUpdate,
  TextContainerProperty, TextContainerUpgrade, MenuContainerProperty, MenuItemProperty, OsEventTypeList, AudioInputSource,
  type EvenAppBridge,
} from '@evenrealities/even_hub_sdk';
import type { ImageSlot, LensPage, NativeBox } from './pages.ts';
import type { LensSink } from './sender.ts';
import type { MicSource } from '../data/speech.ts';
import type { GlassesBattery } from '../core/types.ts';
import { glassesBatteryOf } from '../core/format.ts';

export interface G2Handlers {
  onTap(): void;
  onDoubleTap(): void;
  onUp(): void;
  onDown(): void;
  onHold(): void;
  onRelease(): void;
  onMenu(id: number): void;
  /** The glasses' page may have been disturbed (app resumed, a menu or dialogue closed): resend. */
  onResync(): void;
  onExit(): void;
  /** Glasses came or went. Lens runs on the phone without them (pairing, settings, the mirror);
   * pages are only written while they're connected. */
  onGlasses(connected: boolean): void;
  /** The glasses' battery level or charging changed (G2.battery). */
  onBattery?(): void;
}

const within = <T>(p: Promise<T>, ms: number, fallback: T) =>
  Promise.race([p.catch(() => fallback), new Promise<T>(r => setTimeout(() => r(fallback), ms))]);

/**
 * Are we inside the Even App (or its simulator)? It injects `flutter_inappwebview` into the page,
 * and its bridge answers a call that needs no glasses: reading this app's storage. Glasses are a
 * separate question (G2.glasses): opening Lens to pair before they connect is a normal first run,
 * and pairing must still go to the Even App's storage, not the WebView's. A phone that is slow to
 * set the bridge up gets a long wait and a second try; a plain browser (development) has no host
 * at all and is told apart within a second and a half.
 */
export async function connectG2(): Promise<EvenAppBridge | null> {
  const host = () => !!(window as unknown as { flutter_inappwebview?: unknown }).flutter_inappwebview;
  for (let waited = 0; !host(); waited += 100) {
    if (waited >= 1500) return null;
    await new Promise(r => setTimeout(r, 100));
  }
  try {
    const b = await within(waitForEvenAppBridge(), 10_000, null);
    if (!b) return null;
    for (let attempt = 0; attempt < 2; attempt++) {
      // Any answer, even a refusal (a key never stored, on a first run), is the Even App answering:
      // only silence means there's no bridge.
      if (await within(b.getLocalStorage('lens.settings').then(() => true, () => true), 4000, false)) return b;
    }
    return null;
  } catch {
    return null;
  }
}

type Status = { connectType?: string; batteryLevel?: number; isCharging?: boolean } | null | undefined;
/** A device the Even App knows about is taken as connected unless it says otherwise. */
const connectedOf = (s: Status) => !['disconnected', 'connectionFailed', 'connecting'].includes(String(s?.connectType ?? 'connected'));
/**
 * A push that doesn't say how the glasses are connected: the SDK writes 'none' for a connection the
 * Even App left out (a push only about being worn, say), and 0 for a level it left out. It changes
 * neither the connection nor the battery.
 */
const saysNothing = (s: Status) => s?.connectType === 'none';

const EMPTY: LensPage = { boxes: [], images: [] };

function container(Kind: typeof CreateStartUpPageContainer | typeof RebuildPageContainer, p: LensPage, menu: Array<[number, string]>) {
  // One blank full-screen text box only captures taps and swipes; it never shows a glyph, so no
  // firmware scrollbar can appear.
  const input = new TextContainerProperty({
    containerID: 1, containerName: 'input', xPosition: 0, yPosition: 0, width: 576, height: 288,
    borderWidth: 0, paddingLength: 0, content: '', isEventCapture: 1,
  });
  const texts = p.boxes.map(b => new TextContainerProperty({
    containerID: b.id, containerName: b.name, xPosition: b.x, yPosition: b.y, width: b.w, height: b.h,
    borderWidth: b.border, borderColor: b.borderColor, borderRadius: b.radius, paddingLength: b.pad,
    content: b.content, isEventCapture: 0, ...(b.textColor === undefined ? {} : { textColor: b.textColor }),
  }));
  const images = p.images.map(i => new ImageContainerProperty({ containerID: i.id, containerName: i.name, xPosition: i.x, yPosition: i.y, width: i.w, height: i.h }));
  return new Kind({
    containerTotalNum: 1 + texts.length + images.length, textObject: [input, ...texts], imageObject: images,
    menuObject: new MenuContainerProperty({ menuItems: menu.map(([itemID, itemName]) => new MenuItemProperty({ itemID, itemName })) }),
  } as never);
}

type Envelope = { eventType?: OsEventTypeList } | undefined;
const typeOf = (e: Envelope) => (e ? e.eventType ?? OsEventTypeList.CLICK_EVENT : null);

export class G2 implements LensSink, MicSource {
  readonly bridge: EvenAppBridge;
  /** Glasses connected: null until the Even App has said. */
  glasses: boolean | null = null;
  /** Their battery, as the Even App last reported it while they were connected; null when it hasn't. */
  battery: GlassesBattery | null = null;
  private h: G2Handlers;
  private menu: Array<[number, string]>;
  private exitId: number;
  /** The page the glasses should have; sent again whole after a dialogue. */
  private page: LensPage = EMPTY;
  private started = false;
  /** The start-up page, created the first time glasses are there to show it. */
  private ready: Promise<boolean> | null = null;
  private exiting = false;
  private exitTimer: ReturnType<typeof setTimeout> | null = null;
  private last: Record<string, number> = {};
  private pcm: ((pcm: Uint8Array) => void) | null = null;
  private micOn = false;
  /** Each start of the microphone, and the one whose stop is on its way. */
  private micGen = 0;
  private stopping = -1;
  private holding = false;

  constructor(bridge: EvenAppBridge, h: G2Handlers, menu: Array<[number, string]>, exitId: number) {
    this.bridge = bridge;
    this.h = h;
    this.menu = menu;
    this.exitId = exitId;
    bridge.onEvenHubEvent(ev => this.dispatch(ev));
    bridge.onDeviceStatusChanged?.(s => { if (saysNothing(s)) return; this.setGlasses(connectedOf(s)); this.setBattery(s); });
    // No glasses yet: getDeviceInfo answers null. A status change follows when they connect.
    void within(bridge.getDeviceInfo().then(d => { if (d) this.setBattery(d.status); return d ? connectedOf(d.status) : false; }), 4000, true)
      .then(on => { if (this.glasses === null) this.setGlasses(on); });
  }

  /**
   * A status update: its battery, if it says one. The Even App sends statuses for other reasons too
   * (worn, in the case), and the SDK fills a level it wasn't sent with 0, so a status without a real
   * level keeps the last one (glassesBatteryOf), only a new level or charging state is passed on,
   * and glasses that went away take their level with them (it would only get older).
   */
  private setBattery(s: Status): void {
    const b = connectedOf(s) ? glassesBatteryOf(s) ?? this.battery : null;
    if (b?.percent === this.battery?.percent && b?.charging === this.battery?.charging) return;
    this.battery = b;
    this.h.onBattery?.();
  }

  get name(): string { return this.glasses === false ? 'Even G2 · not connected' : 'Even G2'; }

  private setGlasses(on: boolean): void {
    if (this.glasses === on) return;
    // Glasses that went away may have lost the page: the next write starts it afresh (startPage
    // falls back to a rebuild if the Even App still has it).
    if (this.glasses && !on) { this.ready = null; this.started = false; }
    this.glasses = on;
    this.h.onGlasses(on);
  }

  private ensurePage(): Promise<boolean> { return (this.ready ??= this.startPage()); }

  private async startPage(): Promise<boolean> {
    if (this.started) return (await this.bridge.rebuildPageContainer(container(RebuildPageContainer, this.page, this.menu))) === true;
    this.started = true;                                       // one-shot: latch before awaiting
    const r = await this.bridge.createStartUpPageContainer(container(CreateStartUpPageContainer, this.page, this.menu));
    if (r === 0) return true;
    return (await this.bridge.rebuildPageContainer(container(RebuildPageContainer, this.page, this.menu))) === true;
  }

  // ------------------------------------------------------------------ LensSink

  async rebuild(p: LensPage): Promise<boolean> {
    this.page = p;
    await this.ensurePage();
    if (this.exiting || this.page !== p) return false;
    return (await this.bridge.rebuildPageContainer(container(RebuildPageContainer, p, this.menu))) === true;
  }

  async upgrade(b: NativeBox): Promise<boolean> {
    await this.ensurePage();
    if (this.exiting || !this.page.boxes.some(x => x.id === b.id)) return false;
    const ok = (await this.bridge.textContainerUpgrade(new TextContainerUpgrade({
      containerID: b.id, containerName: b.name, content: b.content, ...(b.textColor === undefined ? {} : { textColor: b.textColor }),
    }))) === true;
    if (ok) this.page = { ...this.page, boxes: this.page.boxes.map(x => (x.id === b.id ? { ...x, content: b.content, textColor: b.textColor } : x)) };
    return ok;
  }

  async image(slot: ImageSlot, png: Uint8Array): Promise<boolean> {
    if (!(await this.ensurePage()) || this.exiting || !this.page.images.some(i => i.id === slot.id)) return false;
    const r = await this.bridge.updateImageRawData(new ImageRawDataUpdate({ containerID: slot.id, containerName: slot.name, imageData: png }));
    if (String(r) !== 'success') console.warn('[lens] image write', slot.name, String(r), png.byteLength);
    return String(r) === 'success';
  }

  // ------------------------------------------------------------------ microphone

  async start(): Promise<boolean> {
    await this.ensurePage();
    const ok = await this.bridge.audioControl(true, AudioInputSource.Glasses).catch(() => false);
    this.micGen++;
    this.micOn = ok !== false;
    return this.micOn;
  }

  async stop(): Promise<void> {
    const gen = this.micGen;
    if (!this.micOn || this.stopping === gen) return;
    // Frames that arrive while the glasses are being told are still what was said before: they
    // count until the Even App answers.
    this.stopping = gen;
    await this.bridge.audioControl(false, AudioInputSource.Glasses).catch(() => false);
    // Unless a new hold switched it on again meanwhile.
    if (gen === this.micGen) this.micOn = false;
  }

  onPcm(fn: ((pcm: Uint8Array) => void) | null): void { this.pcm = fn; }

  // ------------------------------------------------------------------ input

  /** The firmware sends some events twice, ~50-100 ms apart. */
  private dup(kind: string, windowMs: number): boolean {
    const now = Date.now();
    if (this.last[kind] && now - this.last[kind] < windowMs) return true;
    this.last[kind] = now;
    return false;
  }

  private dispatch(ev: any): void {
    const pcm = ev.audioEvent?.audioPcm as Uint8Array | undefined;
    if (pcm) { if (this.micOn) this.pcm?.(pcm); return; }
    if (ev.menuItemClickEvent) {
      const id = ev.menuItemClickEvent.itemID;
      // A long press that opened the menu isn't a held temple any more.
      if (this.holding) { this.holding = false; this.h.onRelease(); }
      if (id === this.exitId) this.exit(); else this.h.onMenu(id);
      return;
    }
    const sys = typeOf(ev.sysEvent), list = typeOf(ev.listEvent), text = typeOf(ev.textEvent);
    const any = (t: OsEventTypeList) => sys === t || list === t || text === t;
    if (any(OsEventTypeList.LONG_PRESS_EVENT)) { if (!this.dup('hold', 400) && !this.holding) { this.holding = true; this.h.onHold(); } return; }
    if (any(OsEventTypeList.LONG_PRESS_RELEASE_EVENT)) { if (this.holding) { this.holding = false; this.h.onRelease(); } return; }
    if (any(OsEventTypeList.DOUBLE_CLICK_EVENT)) { if (!this.dup('double', 600)) this.h.onDoubleTap(); return; }
    if (any(OsEventTypeList.SCROLL_TOP_EVENT)) { if (!this.dup('up', 160)) this.h.onUp(); return; }
    if (any(OsEventTypeList.SCROLL_BOTTOM_EVENT)) { if (!this.dup('down', 160)) this.h.onDown(); return; }
    if (any(OsEventTypeList.CLICK_EVENT)) { if (!this.dup('tap', 450)) this.h.onTap(); return; }
    if (sys == null || this.dup(`sys${sys}`, 600)) return;
    switch (sys) {
      case OsEventTypeList.FOREGROUND_ENTER_EVENT:
      case OsEventTypeList.FOREGROUND_EXIT_EVENT:
        // The simulator sends these around its menu as well as for the app itself, so neither
        // pauses the lens: both just mean "the page may need sending again".
        if (this.exiting) { if (sys === OsEventTypeList.FOREGROUND_EXIT_EVENT) void this.recoverFromExit(); return; }
        if (this.holding) { this.holding = false; this.h.onRelease(); }
        this.h.onResync();
        break;
      case OsEventTypeList.ABNORMAL_EXIT_EVENT:
        this.h.onResync();
        break;
      case OsEventTypeList.SYSTEM_EXIT_EVENT:
        this.exiting = false;
        this.started = false;
        void this.stop();
        this.h.onExit();
        break;
    }
  }

  /** Store rule: exit is one gesture from the root, so it lives in the long-press menu. */
  exit(): void {
    this.exiting = true;
    void this.stop();
    this.exitTimer = setTimeout(() => void this.recoverFromExit(), 4000);  // the simulator sends no events after shutdown
    void this.bridge.shutDownPageContainer(1);
  }

  private async recoverFromExit(): Promise<void> {
    if (!this.exiting) return;
    this.exiting = false;
    if (this.exitTimer) clearTimeout(this.exitTimer);
    let ok = (await this.bridge.rebuildPageContainer(container(RebuildPageContainer, this.page, this.menu))) === true;
    if (!ok) ok = (await this.bridge.createStartUpPageContainer(container(CreateStartUpPageContainer, this.page, this.menu))) === 0;
    this.ready = Promise.resolve(ok);
    this.h.onResync();
  }

  // ------------------------------------------------------------------ app services

  getStorage(key: string): Promise<string> { return this.bridge.getLocalStorage(key); }
  setStorage(key: string, value: string): Promise<boolean> { return this.bridge.setLocalStorage(key, value); }
  captureImage() { return this.bridge.captureImageFromCamera(); }
  async deviceName(): Promise<string> {
    try {
      const d = await this.bridge.getDeviceInfo() as { model?: string; name?: string } | null;
      return d?.name || d?.model || 'Even G2';
    } catch { return 'Even G2'; }
  }
}
