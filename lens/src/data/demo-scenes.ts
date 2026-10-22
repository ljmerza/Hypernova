// Simulator scenes for new sessions, pop-ups, the battery card, limits and Transmission, with sample
// data (`?demo=1&scene=<name>`), so each screen can be shot (test/sim-shots.py) without a Mac.
// Each scene plays the gestures and the Mac's news that lead to its screen.
import type { DemoHub } from './demo.ts';
import type { DemoSpeech } from './speech.ts';
import type { LensController } from '../lens/controller.ts';
import type { Settings } from './store.ts';
import { playRoadScene } from './demo-road.ts';
import { WORDS } from './bridge.ts';

export interface SceneDeps {
  lens: LensController;
  hub: DemoHub | null;
  speech: DemoSpeech;
  update(patch: Partial<Settings>): void | Promise<void>;
}

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));
const MIN = 60_000, HOUR = 60 * MIN;

/** A morning brief, as an automation's note would read. */
const NOTE_BRIEF = 'Three pull requests need a look, and the build on main is green. Nothing is blocked. Hyperscribe has two new commits since yesterday.';
const NOTE_LONG = 'Five pull requests are open. 0stems #41 changes the stem export and has failing checks; Hyperscribe #12 is ready to merge; Hypernova #88, #89 and #90 are drafts by you. The oldest waits since Monday. Nothing needs you before lunch, but #12 would be quick to merge now that its checks are green.';

/** Say `words` with a held temple, as the glasses would. */
/** A prompt longer than the lens' talk box (it scrolls). */
const LONG_PROMPT = 'make the export button remember the last folder it used, then run every test in the project and tell me which ones were slow and why; if any failed, fix them one at a time and explain each fix in a sentence before you move on to the next one. When that is done, update the changelog with a short line for each fix, bump the version, and build the release so I can try it on the glasses tonight';

async function talk(d: SceneDeps, words: string): Promise<void> {
  d.speech.next(words);
  await d.lens.hold();
  await sleep(300);
  await d.lens.release();
}

/** Play scene `name`; false if it isn't one of these. */
export async function playScene(name: string, d: SceneDeps): Promise<boolean> {
  const { lens, hub } = d;
  const now = Date.now();
  // The answer to 0stems' push, so nothing needs you.
  const settle = async () => { await hub?.answer('ap-push', { decision: 'deny' }).catch(() => {}); await sleep(50); };
  switch (name) {
    case 'home': lens.scene('glance'); return true;
    case 'home-new': for (let i = 0; i < 4; i++) lens.down(); return true;
    case 'talk-needs': await talk(d, 'use a feature branch instead, then open a pull request'); return true;
    case 'talk-nebula': await talk(d, 'build a landing page for my bakery with a menu and opening hours'); lens.down(); return true;
    case 'listen-needs': d.speech.next('use a feature branch instead, then open a pull request'); await lens.hold(); return true;
    case 'listen-nebula': await settle(); d.speech.next('build a landing page for my bakery'); await lens.hold(); return true;
    case 'nebula-sent': await settle(); await talk(d, 'build a landing page for my bakery'); lens.tap(); return true;
    // Let go: the last words on their way (here they never come, so it stays), then the prompt shown
    // whole and counting down; a long one from its end, and scrolled back; and one whose end never came.
    case 'talk-finishing': d.speech.next('in hyperscribe, make the export button remember the last folder it used', { tailMs: 600_000 }); await lens.hold(); await sleep(1500); void lens.release(); return true;
    case 'talk-countdown': await talk(d, 'in hyperscribe, make the export button remember the last folder it used'); return true;
    case 'talk-long': await talk(d, `in hyperscribe, ${LONG_PROMPT}`); return true;
    case 'talk-long-read': await talk(d, `in hyperscribe, ${LONG_PROMPT}`); lens.up(); return true;
    case 'talk-cut': d.speech.next('in hyperscribe, rename the export to use the date and', { cut: true }); await lens.hold(); await sleep(1700); await lens.release(); return true;
    case 'picker': lens.showPicker(); return true;
    case 'picker-project': lens.showPicker(); lens.down(); lens.down(); return true;
    case 'pop-needs': {
      lens.scene('session');
      await sleep(400);
      hub?.approve('s-hyperscribe', 'rm -rf build/ && ./gradlew clean');
      return true;
    }
    case 'pop-done': {
      await d.update({ popups: { needs: true, done: true, stopped: true, limit: true, battery: true, sleep: true, note: true } });
      lens.scene('needs');
      await sleep(800);
      hub?.set({ session: ['s-site', { state: 'working', lastActivityAt: Date.now() }] });
      await sleep(100);
      hub?.set({ session: ['s-site', { state: 'idle', lastText: 'The blog layout uses the new grid now; every post page passes the checks.', lastActivityAt: Date.now() }] });
      hub?.finish('s-site');
      return true;
    }
    case 'pop-merged': {
      await d.update({ popups: { needs: true, done: true, stopped: true, limit: true, battery: true, sleep: true, note: true } });
      lens.scene('result');
      await sleep(300);
      hub?.approve('s-site', 'git push --force origin main');
      hub?.set({ battery: { percent: 14, charging: false, onAC: false, minutesLeft: 31 } });
      return true;
    }
    // Automations' notes (core/notes.ts): on the glance it takes the lens; over a session's screen it is a band.
    case 'note': lens.scene('glance'); hub?.automationNote(NOTE_BRIEF); return true;
    case 'note-long': lens.scene('glance'); hub?.automationNote(NOTE_LONG, 'Pull requests'); return true;
    case 'note-band': await d.update({ readAloud: false }); lens.scene('session'); await sleep(400); hub?.automationNote(NOTE_BRIEF); return true;
    // An automation's ask (core/ask.ts): a card with the answers; over a session's screen it is a band.
    case 'ask': lens.scene('glance'); hub?.automationAsk(); return true;
    case 'ask-three': lens.scene('glance'); hub?.automationAsk('Which branch should the nightly build use for the release notes?', 'Nightly build', ['main', 'release', 'Skip'], 4); return true;
    case 'ask-band': await d.update({ readAloud: false }); lens.scene('session'); await sleep(400); hub?.automationAsk(); return true;
    case 'pop-battery': hub?.set({ battery: { percent: 14, charging: false, onAC: false, minutesLeft: 31 } }); return true;
    case 'pop-sleep': lens.scene('result'); await sleep(300); hub?.sleepSoon('battery'); return true;
    case 'pop-limit': case 'pop-lifted': {
      await d.update({ popups: { needs: true, done: false, stopped: false, limit: true, battery: true, sleep: true, note: true } });
      // The Mac as it was before the limit: a limit already on when the lens looks isn't news.
      hub?.set({});
      if (name === 'pop-lifted') await settle();
      else lens.scene('session');
      await sleep(300);
      hub?.set({ usage: { fiveHour: { pct: 100, resetsAt: now + HOUR + 2 * MIN } }, account: { state: 'limited', since: now, limit: { window: 'five_hour', label: 'session limit', resetsAt: now + HOUR + 2 * MIN, autoResumeAt: now + HOUR + 2 * MIN, usingCredits: false } } });
      if (name === 'pop-lifted') {
        // The reset: the disk's outburst, lit from the rim in, and the band says so.
        await sleep(1500);
        hub?.set({ usage: { fiveHour: { pct: 0, resetsAt: Date.now() + 5 * HOUR } }, account: { state: 'ok', since: null, limit: null } });
      }
      return true;
    }
    case 'pop-telescope': {
      await lens.telescopeScene('hyperscribe', 'web', null);
      await sleep(500);
      hub?.approve('s-site', 'npm publish');
      return true;
    }
    case 'power': lens.showPower(); return true;
    case 'power-mini': hub?.set({ battery: null }); lens.showPower(); return true;
    case 'power-backpack': {
      hub?.set({ battery: { percent: 58, charging: false, onAC: false, minutesLeft: 190 } });
      await talk(d, 'stay awake');
      await sleep(100);
      lens.showPower();
      return true;
    }
    case 'limit-hole': {
      await settle();
      hub?.set({
        usage: { fiveHour: { pct: 100, resetsAt: now + HOUR + 2 * MIN } },
        account: { state: 'limited', since: now - 20 * MIN, limit: { window: 'five_hour', label: 'session limit', resetsAt: now + HOUR + 2 * MIN, autoResumeAt: now + HOUR + 2 * MIN, usingCredits: false } },
        session: ['s-hyperscribe', { blocked: { kind: 'limit', text: 'You’ve hit your session limit', resetsAt: now + HOUR + 2 * MIN, autoResumeAt: now + HOUR + 2 * MIN }, queued: 1 }],
      });
      lens.scene('glance');
      return true;
    }
    case 'limit-week': {
      hub?.set({ usage: { sevenDay: { pct: 100, resetsAt: now + 2 * 24 * HOUR + 5 * HOUR } }, account: { state: 'limited', since: now - HOUR, limit: { window: 'seven_day', label: 'weekly limit', resetsAt: now + 2 * 24 * HOUR + 5 * HOUR, autoResumeAt: null, usingCredits: false } } });
      return true;
    }
    case 'eclipse': hub?.set({ usage: { source: 'stale' }, account: { state: 'logged_out', since: now - 5 * MIN, limit: null } }); return true;
    case 'paused-card': {
      hub?.set({ session: ['s-hyperscribe', { blocked: { kind: 'limit', text: 'You’ve hit your session limit · resets 4pm', resetsAt: now + 2 * HOUR, autoResumeAt: null } }],
        account: { state: 'limited', since: now - 20 * MIN, limit: { window: 'five_hour', label: 'session limit', resetsAt: now + 2 * HOUR, autoResumeAt: null, usingCredits: false } },
        usage: { fiveHour: { pct: 100, resetsAt: now + 2 * HOUR } } });
      lens.scene('result');
      return true;
    }
    case 'queued': hub?.set({ session: ['s-hypernova', { queued: 2 }] }); return true;
    // The states the glasses missed (WP-Lens): the Mac away (its rows at rest, since when) and the
    // app on the phone stopped; a session stopped while you were away, its card; another provider's
    // trouble on a row and as a band; a mode that isn't the usual; a reply read aloud.
    case 'offline': case 'app-stopped':
      // Gone a moment after the sample Mac's last news (its rows' times stop there).
      hub?.set({ status: { status: 'offline', since: now + 5000, problem: name === 'app-stopped' ? WORDS.no_app : 'Hypernova on your phone can’t reach your Mac right now.' } });
      lens.scene('glance');
      return true;
    case 'away': case 'away-card':
      await settle();
      hub?.set({ session: ['s-site', { stoppedForAway: true, lastActivityAt: now - 25 * MIN }] });
      if (name === 'away-card') lens.open('s-site'); else lens.scene('glance');
      return true;
    case 'provider': case 'provider-row': {
      await d.update({ popups: { needs: true, done: false, stopped: false, limit: true, battery: true, sleep: true, note: true } });
      await settle();
      const or = (status: null | { state: string; since: number; resetsAt: null }) => [{ id: 'anthropic', name: 'Claude', builtIn: true, status: null }, { id: 'openrouter', name: 'OpenRouter', builtIn: false, status }];
      hub?.set({ providers: or(null), session: ['s-site', { providerId: 'openrouter' }] });
      await sleep(100);
      hub?.set({ providers: or({ state: 'no_credit', since: now, resetsAt: null }), session: ['s-site', { lastActivityAt: now - MIN, blocked: { kind: 'provider', text: 'API Error: 402 Insufficient credits', resetsAt: null, autoResumeAt: null } }] });
      lens.scene('glance');
      if (name === 'provider-row') { await sleep(100); lens.down(); }
      return true;
    }
    case 'mode': hub?.set({ session: ['s-hypernova', { permissionMode: 'plan' }] }); lens.scene('session'); return true;
    case 'needs-mode': hub?.set({ session: ['s-0stems', { permissionMode: 'acceptEdits' }] }); lens.scene('needs'); return true;
    case 'reading': lens.scene('result'); await sleep(300); lens.tap(); return true;
    case 'transmit-result': await d.update({ textArrives: 'speech', pace: 150 }); lens.scene('result'); return true;
    // The looks compared (lens/look.ts; add &look=hypernova): the glance, an approval, a reply whole
    // and arriving like speech, in English or Russian (look-…-ru).
    case 'look-home': case 'look-home-ru': hub?.localise(name.endsWith('-ru') ? 'ru' : 'en'); lens.scene('glance'); return true;
    case 'look-needs': case 'look-needs-ru': hub?.localise(name.endsWith('-ru') ? 'ru' : 'en'); lens.scene('needs'); return true;
    case 'look-reply': case 'look-reply-ru': hub?.localise(name.endsWith('-ru') ? 'ru' : 'en'); lens.scene('result'); return true;
    // The glance's spending meter: nothing working (no pace), working at a pace that ends before the
    // limit, and burning fast enough to reach it before the reset (Usage.rate, PROTOCOL.md).
    case 'look-idle': case 'look-idle-ru': case 'look-pace': case 'look-pace-ru': case 'look-burn': case 'look-burn-ru': {
      hub?.localise(name.endsWith('-ru') ? 'ru' : 'en');
      if (name.startsWith('look-idle')) {
        hub?.set({ session: ['s-hypernova', { state: 'idle', activity: null, agentsRunning: 0, lastActivityAt: now - 9 * MIN }], usage: { rate: null } });
      } else if (name.startsWith('look-pace')) {
        hub?.set({ usage: { fiveHour: { pct: 42, resetsAt: now + 2 * HOUR + 12 * MIN }, rate: { fiveHour: 0.12, sevenDay: 0.01, fiveHourEtaMs: null, sevenDayEtaMs: null, working: true } } });
      } else {
        hub?.set({ usage: { fiveHour: { pct: 62, resetsAt: now + 2 * HOUR + MIN }, rate: { fiveHour: 0.8, sevenDay: 0.05, fiveHourEtaMs: 47 * MIN, sevenDayEtaMs: null, working: true } } });
      }
      lens.scene('glance');
      return true;
    }
    case 'look-stream': case 'look-stream-ru':
      hub?.localise(name.endsWith('-ru') ? 'ru' : 'en');
      await d.update({ textArrives: 'speech', pace: 170 });
      lens.scene('result');
      return true;
    case 'transmit-live': await d.update({ textArrives: 'speech', pace: 150 }); lens.scene('session'); return true;
  }
  // On a bike or a walk: the guide, debrief, notes, the diff, moving mode, risky commands.
  return playRoadScene(name, d);
}
