// Boot: find the glasses, find the Hypernova app on this phone (else Lens's own pairing), connect to
// the Mac, then keep three things in step: the Mac's sessions (the hub), the lens (controller →
// output → glasses) and the phone screen (a status card through the app, the older full page else).
import { connectG2, G2 } from './lens/g2.ts';
import { LensController, MENU, MENU_ITEMS } from './lens/controller.ts';
import { LensOutput, clockTicks } from './lens/output.ts';
import { LensSender, PreviewSink } from './lens/sender.ts';
import { LiveHub, pair, type HubLike } from './data/hub.ts';
import { Bridge, BRIDGE_URL, lensPatch, sharedPatch, type BridgeSettings, type BridgeStatus } from './data/bridge.ts';
import { PhoneLink, linkMessage } from './data/link.ts';
import type { Pairing } from './data/store.ts';
import { DemoHub, DEMO_SPEECH } from './data/demo.ts';
import { playScene } from './data/demo-scenes.ts';
import { Store } from './data/store.ts';
import { DemoSpeech, type MicSource, type SpeechEngine } from './data/speech.ts';
import { AutoSpeech, MacSpeech } from './data/stt.ts';
import { VoiceOut } from './data/voice.ts';
import type { PairLink } from './core/pairing.ts';
import { PhoneApp } from './phone/app.ts';
import { StatusPage } from './phone/status.ts';
import { toast } from './phone/dom.ts';
import type { Shot } from './core/types.ts';

/**
 * This build may pair with a Mac directly, the older way: in development, or a private build made
 * with LENS_HOSTS (scripts/whitelist.mjs). The public build reaches only the app on this phone.
 */
const DIRECT = !!import.meta.env.DEV || import.meta.env.VITE_LENS_DIRECT === '1';
const APP_VERSION = (import.meta.env.VITE_APP_VERSION as string | undefined) ?? 'dev';

/** The phone screen: the status card, or the older full page (Lens's own pairing, sample data). */
interface PhoneView {
  update(): void;
  lensChanged(): void;
  settingsChanged(): void;
  setShot(shot: Shot | null, live: string | null): void;
  showShot(shot: Shot | null, live: string | null): void;
  dispose(): void;
}

async function boot(): Promise<void> {
  const params = new URLSearchParams(location.search);
  const store = new Store();
  let hub: HubLike | null = null;
  let demo = false;
  let speech: SpeechEngine | null = null;
  let unsubscribe: (() => void) | null = null;
  const demoSpeech = new DemoSpeech(DEMO_SPEECH);

  const voice = new VoiceOut(() => hub, () => store.settings);
  // The phone is created below; the controller only needs it through these callbacks.
  let phone: PhoneView | null = null;
  let output: LensOutput | null = null;

  // The Hypernova app on this phone (data/bridge.ts) and how Lens reaches the Mac (data/link.ts).
  const bridgeApi = new Bridge(import.meta.env.DEV && params.get('bridge') ? params.get('bridge')! : BRIDGE_URL);
  let bridgeStatus: BridgeStatus | null = null;
  let statusAt = 0;
  /** "Practise the gestures" on the phone: the ask Lens has seen; a later one plays the guide. */
  let guideAskSeen: number | null = null;
  const onBridgeSettings = (s: BridgeSettings, first: boolean) => {
    void store.applyRemote(lensPatch(s));
    if (first || guideAskSeen === null) guideAskSeen = s.guideAsk;
    else if (s.guideAsk > guideAskSeen) { guideAskSeen = s.guideAsk; lens.startGuide(); }
  };
  const link = new PhoneLink({
    bridge: bridgeApi,
    token: () => store.bridgeToken,
    setToken: t => store.setBridgeToken(t),
    hasPairing: () => DIRECT && !!store.pairing,
    name: 'Hypernova Lens',
    version: APP_VERSION,
    onSettings: onBridgeSettings,
  });
  // Development: `?link=asking|confirm|unconfirmed|old_lens|old_app|expired|revoked|no_app` shows that
  // connection state on the lens and the phone card (simulator shots); the app isn't asked.
  const devLink = import.meta.env.DEV ? params.get('link') : null;
  if (devLink) {
    const code = '482913', until = Date.now() + 600_000;
    link.state = devLink === 'asking' || devLink === 'confirm' ? { kind: devLink, code, until }
      : devLink === 'old_app' ? { kind: 'old_app', app: '1.3.0', protocol: 2 }
      : { kind: devLink as 'unconfirmed' | 'old_lens' | 'expired' | 'revoked' | 'no_app' };
  }
  /**
   * Through the app, the calls go to it as if it were the Mac, each signed (data/bridge.ts): the
   * token stays here. The same for as long as the signer is.
   */
  let viaApp: Pairing | null = null;
  const activePairing = (): Pairing | null => {
    const st = link.state, signer = link.signer;
    if (st.kind === 'bridge' && signer) {
      if (viaApp?.signer !== signer) viaApp = { baseUrl: bridgeApi.base, token: '', macName: bridgeStatus?.mac ?? 'your Mac', macId: null, deviceId: null, signer };
      return viaApp;
    }
    return st.kind === 'direct' ? store.pairing : null;
  };
  /** A change the glasses made ("mute", "moving", "eclipse") goes to the app too, which keeps them now. */
  const updateSettings = (patch: Parameters<Store['update']>[0]) => {
    void store.update(patch);
    const signer = link.signer, shared = sharedPatch(patch);
    if (signer && shared) void bridgeApi.putSettings(signer, shared).catch(err => console.warn('[lens] settings to the app', err));
  };

  const lens = new LensController({
    hub: () => hub,
    speech: () => speech,
    voice,
    settings: () => store.settings,
    updateSettings,
    paired: () => !!activePairing() || demo,
    linkMessage: () => linkMessage(link.state, Date.now(), DIRECT),
    retryLink: () => link.retry(),
    showOnPhone: (shot, live) => phone?.showShot(shot, live),
    shotReady: (shot, live) => {
      if (shot) void output?.setShot(shot.url).catch(err => { console.warn('[lens] shot', err); lens.shotFailed(shot.url); });
      phone?.setShot(shot, live);
    },
    glassesBattery: () => g2?.battery ?? null,
  });

  // The page must reach the glasses before anything else (it is also what lets them show the lens).
  const bridge = await connectG2();
  let g2: G2 | null = null;
  let exited = false;
  const sender = new LensSender(new PreviewSink());
  if (bridge) {
    g2 = new G2(bridge, {
      onTap: () => lens.tap(),
      onDoubleTap: () => lens.doubleTap(),
      onUp: () => lens.up(),
      onDown: () => lens.down(),
      onHold: () => void lens.hold(),
      onRelease: () => void lens.release(),
      onMenu: id => lens.menu(id),
      onResync: () => { sender.reset(); (hub as LiveHub | null)?.wake?.(); },
      onExit: () => { exited = true; sender.pause(); (hub as LiveHub | null)?.stop?.(); },
      // Pages go out only while glasses are there; when they come (back), the whole page goes again.
      onGlasses: on => { if (on && !exited) sender.resume(); else if (!on) sender.pause(); phone?.lensChanged(); },
      // A new level for the glance's battery line: its text alone goes out.
      onBattery: () => lens.onChange(),
    }, MENU_ITEMS, MENU.exit);
    sender.setSink(g2);
  } else sender.resume();
  const out = output = new LensOutput(sender, () => lens.view());

  // Inside the Even App, pairing and settings always live in its storage for this app, glasses or not.
  await store.load(g2 ? { get: k => g2!.getStorage(k), set: (k, v) => g2!.setStorage(k, v) } : null);
  // A simulator scene shows its own screen: the guide's one offer would stand in front of it.
  if (params.has('scene')) store.settings = { ...store.settings, guideOffered: true };

  const mic: MicSource | null = g2;
  // Soniox through the Mac while the Mac says it's ready (data/stt.ts); else no engine, and a hold
  // says what the Mac needs. One engine for the life of the page: a hold keeps the one it started with.
  const language = () => store.settings.language;
  const onMac = mic ? new MacSpeech(mic, activePairing, language) : null;
  const auto = new AutoSpeech(() => (onMac && !demo && activePairing() && hub?.snapshot?.stt?.ready ? onMac : null));
  const chooseSpeech = () => {
    if (demo || params.get('speech') === 'demo') speech = demoSpeech;
    else if (mic) speech = auto;
    else speech = null;
  };

  const attach = (h: HubLike) => {
    unsubscribe?.();
    hub = h;
    unsubscribe = h.subscribe(e => {
      // Through the app: the glasses' settings it keeps, and its word that Lens's access is gone.
      if (e.t === 'bridge_settings') onBridgeSettings(e.settings, false);
      if (e.t === 'revoked') void link.revoked();
      if (e.t === 'old_lens') link.oldLens();
      lens.hubEvent(e);
      phone?.update();
    });
    chooseSpeech();
    out.invalidate();
    phone?.update();
  };

  /** The route the hub uses now; a new one starts a new hub, anything else only redraws. */
  let routeKey = '';
  const connect = (force = false) => {
    const st = link.state;
    const key = demo ? 'demo' : st.kind === 'bridge' ? `bridge:${st.token}` : st.kind === 'direct' && store.pairing ? `direct:${store.pairing.token}` : 'none';
    if (!force && key === routeKey) { out.invalidate(); phone?.update(); return; }
    routeKey = key;
    (hub as LiveHub | null)?.stop?.();
    if (demo) { attach(new DemoHub()); return; }
    const p = activePairing();
    if (!p) { hub = null; unsubscribe?.(); unsubscribe = null; chooseSpeech(); out.invalidate(); phone?.update(); return; }
    const live = new LiveHub(p, st.kind === 'bridge' ? 'bridge' : 'mac');
    attach(live);
    live.start();
  };

  // The phone screen: the status card while Lens goes (or would go) through the app; the full page
  // with Lens's own pairing, with sample data, or when the owner chose to pair directly.
  const root = document.getElementById('app')!;
  let pairingDirectly = false;
  let pageKind: 'status' | 'full' | null = null;
  const choosePage = () => {
    const want = demo || pairingDirectly || link.state.kind === 'direct' ? 'full' : 'status';
    if (want === pageKind) return;
    phone?.dispose();
    pageKind = want;
    phone = want === 'full' ? fullPage() : new StatusPage(root, {
      link: () => link.state,
      hub: () => hub,
      bridgeStatus: () => bridgeStatus,
      refreshStatus: () => {
        const signer = link.signer;
        if (!signer || Date.now() - statusAt < 5000) return;
        statusAt = Date.now();
        void bridgeApi.status(signer).then(s => {
          const changed = JSON.stringify(s) !== JSON.stringify(bridgeStatus);
          bridgeStatus = s;
          if (viaApp && s.mac) viaApp.macName = s.mac;
          if (changed) phone?.update();
        }).catch(() => { /* the card says what the hub knows */ });
      },
      askAgain: () => link.retry(),
      disconnect: async () => { await link.leave(); },
      // Only with the app's access (a signed call); without it Lens says to open Hypernova by hand.
      openApp: async () => {
        const signer = link.signer;
        if (link.state.kind !== 'bridge' || !signer) return null;
        try { return await bridgeApi.open(signer, 'glasses'); } catch { return null; }
      },
      startDemo: () => startDemo(),
      direct: DIRECT,
      pairDirect: () => { pairingDirectly = true; choosePage(); },
    });
  };

  const startDemo = () => { demo = true; choosePage(); connect(); phone?.update(); };

  link.onChange = () => { choosePage(); connect(); };

  const fullPage = () => new PhoneApp(root, {
    store, lens, output: out, sender, voice,
    hub: () => hub,
    demo: () => demo,
    pairWith: async (pl: PairLink) => {
      const deviceName = g2 ? `${await g2.deviceName()} · Lens` : 'Hypernova Lens (browser)';
      const p = await pair(pl, deviceName);
      await store.setPairing(p);
      demo = false;
      link.direct();
    },
    unpair: async () => {
      if (demo) { demo = false; choosePage(); connect(); phone?.update(); return; }
      const h = hub as LiveHub | null;
      await h?.unpair?.();
      h?.stop?.();
      await store.setPairing(null);
      pairingDirectly = false;
      toast('Unpaired');
      // Back to the app on this phone, if it's there.
      void link.start();
    },
    startDemo,
    capture: g2 ? () => g2!.captureImage() : null,
    speechKind: () => (speech instanceof AutoSpeech ? speech.source() : speech?.kind ?? 'none'),
    macApi: () => (hub instanceof LiveHub ? hub.api : null),
  });

  lens.onChange = () => { out.invalidate(); phone?.lensChanged(); };
  sender.onShown = () => phone?.lensChanged();
  store.subscribe(() => { chooseSpeech(); out.invalidate(); phone?.settingsChanged(); });
  voice.onChange = () => { out.invalidate(); };

  if (params.has('demo')) demo = true;
  choosePage();
  connect(true);

  // Development: ?pair=<pairing link> pairs on load (the simulator has no camera and no keyboard).
  if (import.meta.env.DEV && params.get('pair') && !store.pairing) {
    const { parsePairLink } = await import('./core/pairing.ts');
    const pl = parsePairLink(params.get('pair'));
    if (pl) {
      try { await store.setPairing(await pair(pl, 'Simulator · Lens')); } catch (err) { console.warn('[lens] pairing failed:', (err as Error).message); }
    }
  }
  // Sample data needs no Mac; otherwise find the app on this phone (else Lens's own pairing).
  if (import.meta.env.DEV && params.get('pair') && store.pairing) link.direct();
  else if (!demo && !devLink) void link.start();
  // The status card's words (a countdown, the app's route) stay current while it's on screen.
  setInterval(() => { if (!document.hidden && pageKind === 'status') phone?.update(); }, 5000);

  // A clock for the time of day, "2m" and "resets 2h": every 20 s, on the minute. Not news: it never
  // sets the planets moving again (LensOutput), and neither does the phone's screen coming back on.
  clockTicks(() => out.invalidate({ news: false }));
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) return;
    (hub as LiveHub | null)?.wake?.();
    // Back on screen with no app found yet: look for it now rather than in a few seconds.
    if (link.state.kind === 'no_app') void link.start();
    out.invalidate({ news: false });
  });

  // Keyboard stand-ins for the temple while developing in a browser: arrows swipe, Enter taps,
  // Backspace double-taps, and holding Space talks.
  let spaceDown = false;
  window.addEventListener('keydown', e => {
    const tag = (e.target as HTMLElement)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    if (e.key === 'ArrowUp') lens.up();
    else if (e.key === 'ArrowDown') lens.down();
    else if (e.key === 'Enter') lens.tap();
    else if (e.key === 'Backspace') lens.doubleTap();
    else if (e.key === ' ' && !spaceDown) { spaceDown = true; void lens.hold(); }
    else return;
    e.preventDefault();
  });
  window.addEventListener('keyup', e => { if (e.key === ' ' && spaceDown) { spaceDown = false; void lens.release(); } });

  // Simulator scenes: ?demo=1&scene=needs|result|session|question|plan|talk|telescope|quiet; with
  // &play=<s>, the working session's turn as it is that many seconds in.
  const scene = params.get('scene');
  if (scene) {
    setTimeout(() => {
      const d = hub instanceof DemoHub ? hub : null;
      if (d && Number(params.get('play'))) d.fastForward(Number(params.get('play')));
      if (scene === 'question' || scene === 'plan') { d?.ask(scene); lens.open('s-hypernova'); }
      else if (scene === 'talk') { if (params.get('say')) demoSpeech.next(params.get('say')!); void lens.hold(); setTimeout(() => { if (params.has('release')) void lens.release(); }, Number(params.get('release') || 0) * 1000 || 99_999_999); }
      // Telescope (&project=, &source=, &zoom=0-3, &watch): hyperscribe's page unless named.
      else if (scene === 'show' || scene === 'telescope') void lens.telescopeScene(params.get('project') ?? 'hyperscribe', params.get('source'), params.has('zoom') ? Number(params.get('zoom')) : null, params.has('watch'));
      // New sessions, pop-ups, the battery card, limits, Transmission (data/demo-scenes.ts).
      else void playScene(scene, { lens, hub: d, speech: demoSpeech, update: patch => store.update(patch) }).then(done => { if (!done) lens.scene(scene); });
    }, Number(params.get('after') ?? 600));
  }

  if (!bridge && !params.has('quiet')) console.info('[lens] no Even App bridge: the lens runs in preview mode');
  (window as unknown as { lens?: unknown }).lens = { store, lens, sender, output: out, hub: () => hub, voice, demoSpeech, g2, link };
}

void boot();
