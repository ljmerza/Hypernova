// What the lens shows and what each gesture does. Five screens and a quiet one:
//   glance  one line per session that matters, the one that needs you on top
//   talk    hold the temple, speak, see captions; tap sends, double tap discards, swipe re-routes
//   needs   an approval, question, plan or terminal menu: tap allows, double tap denies
//   session a session at work: what runs now (its tool, its agents) over its output, which scrolls
//           like a teleprompter; tap pauses it, swipes scroll; when the job ends it is the result
//   result  a finished turn: its reply as one text that scrolls, over its facts; swipe scrolls, tap
//           reads it aloud
//   telescope  what the session builds, as a picture filling the lens (its page, the emulator, the
//           simulator, its terminal, a picture it made, its Mac app's window); tap zooms into a
//           quarter, swipes pan, double tap goes back; "watch" looks again every few seconds
//   picker  "+ New session" on the glance: where it starts (a project, or a new one in Nebula)
//   power   the battery card: the glasses and the Mac, and whether the Mac is kept awake
// Everywhere: hold talks (to the session in front of you; on the glance, to what needs you, else a
// new session in Nebula), double tap goes back, swipes move. A pop-up band over any screen says what
// needs you, what finished, a limit, the Mac's battery: tap opens it, a swipe puts it away.
// A session's screens follow it: one that starts working again shows it working, never its last
// reply; what it asks comes up; its work ending shows the result.
// A needs card is the one place double tap answers (deny), so it has a back that answers nothing:
// a "Back" row under a question's options, a swipe past a command or plan, and the menu's Back.
import { glanceRows, defaultFocus, approvalsOf, activeSessions, agentsAtWork, agentsLine, AWAY_LINE, ERROR_LINE, isBusy, rowState, stoppedAway, type GlanceMemory, type GlanceRow, type RowState } from '../core/glance.ts';
import { feedItems, nowLines } from '../core/live.ts';
import { activityLabel, activitySpoken, ago, approvalDetail, clockTime, LOW_BATTERY, powerSpoken, spokenName, wantsLine } from '../core/format.ts';
import { cardFacts, doneLine, firstSentence, lastTurn, spokenFacts, turnFacts, turnStopped, type TurnFacts } from '../core/result.ts';
import { headLines, plainText, speakable, summaryLines, textLines, wrapAll } from '../core/text.ts';
import { NEBULA_ROUTE, parseUtterance, routeTo, type Route, type VoiceWord } from '../core/voice.ts';
import { folderName } from '../core/nebula.ts';
import { blockedLine, blockedSpoken, limitEnds, limitKey, limitLabel, limitNow, limitSpoken, atClock, providerSentence, sunMode, type SunMode } from '../core/limits.ts';
import { awakeLine, awakeModeFor, awakeSpoken, awakeToast, powerRows } from '../core/power.ts';
import { autoDeny, bandWords, DEFAULT_POPS, DONE_MS, PopQueue, type Pop } from '../core/popups.ts';
import { codeAtoms } from '../core/transmission.ts';
import { approvalRisk, riskSpoken, verbMatches } from '../core/risk.ts';
import { changeRows, changesSpoken, counts, diffText, turnChanges, type DiffText, type FileChange } from '../core/changes.ts';
import { fitRows, hasMore, lastTop, MORE_W, scrollTo, settle, startScroll, swipe, windowLines, type Scroll } from '../core/scroll.ts';
import { debrief, type DebriefItem } from '../core/debrief.ts';
import { GUIDE_DONE, GUIDE_OFFER, GUIDE_STEPS, guideDots, guideGesture, guideHint, type GuideGesture, type GuideState } from '../core/lens-guide.ts';
import { BOX_BYTES, fitBytes, spread, type Parts } from '../core/font.ts';
import { briefOf } from './moving.ts';
import { noteBand, noteHead, noteIdOf, noteSpoken, noteText } from './notes.ts';
import { askBand, askHead } from './asks.ts';
import { askCountdown, askEndWords, askSpoken, readRunAsks, askTimedOut, matchAnswer, type AskCard, type AskEnd, type RunAsks } from '../core/ask.ts';
import { NOTES_KEPT } from '../core/notes.ts';
import { Transmission } from './transmit.ts';
import type { Band } from './overlay.ts';
import type { Agent, Approval, AutomationNote, GlassesBattery, HnEvent, MacAwake, Provider, Session, Shot, Snapshot, TermMenu } from '../core/types.ts';
import { leading, sessionName } from '../core/types.ts';
import { scopeLabel, sourceWord, WATCH_EVERY_MS, WATCH_FOR_MS, type ScopeAsk, type ScopeSource } from '../core/telescope.ts';
import { CARD_TEXT_W, LIVE_NOW_ROWS, LIVE_OUT_ROWS, LIVE_TEXT_W, TALK_ROWS, TALK_TEXT_W, cardRoom, factRows, type LensView, type OrreryView, type PlanetState } from './pages.ts';
import type { HubEvent, HubLike } from '../data/hub.ts';
import { ApiError, explain } from '../data/api.ts';
import { lensWords } from './words.ts';
import type { Heard, SpeechEngine } from '../data/speech.ts';
import { SpeechError } from '../data/speech.ts';
import type { Settings } from '../data/store.ts';
import type { VoiceOut } from '../data/voice.ts';
import { WaveBeats } from '../core/speech-out.ts';
import { lookOf, type Look } from './look.ts';
import { drawsView } from './drawn.ts';
import { approvalTypeset, replyTypeset } from './typeset.ts';
import { limitCorner, spendMeter } from '../core/meter.ts';
import { WORDS, type NewSessionChoice } from '../data/bridge.ts';
import { offlineWords } from '../data/link.ts';

export interface Services {
  hub(): HubLike | null;
  speech(): SpeechEngine | null;
  voice: VoiceOut;
  settings(): Settings;
  /** Change settings from the glasses ("mute", "unmute"); kept like the phone's. */
  updateSettings(patch: Partial<Settings>): void;
  /** The Mac this lens is paired with, or null (the lens then asks you to pair on the phone). */
  paired(): boolean;
  /** While Lens isn't connected, what the lens says instead (data/link.ts: open the app, approve on the phone…). */
  linkMessage?(): { title: string; sub: string; code?: string } | null;
  /** A tap while Lens isn't connected: ask the phone again (a denied or unanswered ask, access taken back). */
  retryLink?(): void;
  /** Open a screenshot (or the live page) full size on the phone. */
  showOnPhone(shot: Shot | null, liveUrl: string | null): void;
  /** A screenshot (or only a live page) is ready: the lens draws it, the phone keeps it in a card. */
  shotReady(shot: Shot | null, liveUrl: string | null): void;
  /** The glasses' battery, when the Even App has said (none in a browser). */
  glassesBattery?(): GlassesBattery | null;
}

/** `finishing`: let go, the last words still on their way (data/speech.ts); the captions settle meanwhile. */
type TalkPhase = 'listening' | 'finishing' | 'review' | 'sending' | 'done' | 'error';

type Screen =
  | { kind: 'glance' }
  | { kind: 'quiet' }
  | { kind: 'session'; id: string }
  | { kind: 'result'; id: string }
  /**
   * `back`: it came up on the session's own screen, which comes back once it's answered. `whole`: a
   * plan read whole (after its three lines), as one text that scrolls.
   */
  | { kind: 'needs'; id: string; key: string; cursor: number; qi: number; picks: number[]; answers: Record<string, string>; reason?: string; back?: boolean; whole?: boolean }
  /**
   * Talking. On review: `sendAt`, when it goes by itself (0: it waits for a tap); `paused`, a swipe
   * stopped that count (you're reading it, or choosing where it goes); `cut`, its last words never
   * came back; `top`, the first line in view of a draft longer than the box (unset: its end).
   */
  | { kind: 'talk'; phase: TalkPhase; draft: string; interim: string; route: Route | null; routes: Route[]; message: string; handsFree: boolean; replace: boolean; from: Screen; sendAt?: number; paused?: boolean; cut?: boolean; top?: number; said?: string[] }
  /**
   * Telescope: `ask` is what it was pointed at; `zoom` the quarter shown (null: all of it);
   * `watchUntil` when it stops looking again (0: not watching); `back` the screen double tap returns to.
   */
  | { kind: 'telescope'; id: string; status: 'loading' | 'ready' | 'missing' | 'error'; message: string; shot: Shot | null; live: string | null; ask: ScopeAsk; zoom: number | null; watchUntil: number; back: Screen }
  /** An automation's note for the glasses (core/notes.ts): its text as one that scrolls; tap reads it aloud. */
  | { kind: 'note'; noteId: string; back: Screen }
  /**
   * An automation run waits at an `ask` step (core/ask.ts): swipe picks among its answers, tap
   * answers, double tap sets it aside (the band keeps it until the default is taken). `closed`: how it
   * ended (someone else answered, the default was taken, or our answer went through), shown a moment.
   */
  | { kind: 'ask'; key: string; cursor: number; back: Screen; closed?: string[] }
  /** "+ New session": where it starts. Row 0 is a new project in Nebula, then the projects, newest first. */
  | { kind: 'picker'; cursor: number }
  /** The battery card; `back` is where double tap returns. */
  | { kind: 'power'; back: Screen }
  /**
   * The guide on the glasses (core/lens-guide.ts): each gesture tried and checked. `phase`: the
   * temple held on the hold step ('holding'), or listening for the spoken one (then its last words
   * on the way, 'finishing'); `heard`: its captions, then what was heard, shown once it's ticked.
   */
  | { kind: 'guide'; g: GuideState; phase: 'try' | 'holding' | 'listening' | 'finishing'; heard: string; back: Screen }
  /** "Debrief": what finished, needs you and runs, since `since`; a tap opens the one under the cursor. */
  | { kind: 'debrief'; since: number; items: DebriefItem[]; counts: string; cursor: number; back: Screen }
  /** "What changed?" (the files and their counts) and "show diff" (`diff`: the diff, one text that scrolls). */
  | { kind: 'diff'; id: string; changes: FileChange[]; diff: boolean; back: Screen };

/** Taps that land this soon after a card appears were meant for what was there before. */
const TAP_GUARD_MS = 700;
/** Swiping through the glance this recently means you're choosing a line: a new approval doesn't take the focus. */
const SWIPE_KEEP_MS = 6000;
const TOAST_MS = 1800;
/** The speaking wave steps at most this often: about three text updates a second over Bluetooth. */
const WAVE_MS = 340;
const NEW_RESULT_MS = 30 * 60_000;
/** A prompt sent from the lens makes its turn new to you for this long (sentHere). */
const SENT_HERE_MS = 30 * 60_000;
/** The live view's teleprompter: a line every so often, faster while more than a box is waiting. */
const SCROLL_MS = 1500, SCROLL_FAST_MS = 800;
/** A swipe scrolls by a box less a line, so the line you were on stays in view. */
const SCROLL_STEP = LIVE_OUT_ROWS - 1;
/** Without the socket (the hub polls), the session on screen is asked for its turn this often. */
const POLL_MS = 4000;
/** A failed load of a turn or its agents is tried again no sooner than this. */
const RETRY_MS = 5000;
/** An answer just sent: its card doesn't come up again while the Mac catches up. */
const ANSWERED_MS = 5000;
/** A guide step's line ("A tap opens, sends and allows.") stays this long, then the next step. */
const GUIDE_NEXT_MS = 1600;
/** What the spoken step heard stays longer: there's a sentence to read. */
const GUIDE_HEARD_MS = 4500;
/**
 * Let go of a prompt: it shows whole, then goes by itself after a count, unless a gesture comes
 * first (tap sends at once, double tap cancels, a swipe or a hold stops the count). A prompt of up
 * to two lines goes after SEND_IN_MS; a longer one takes time to read, so a second more for each
 * line after those, up to SEND_MAX_MS (a whole box of lines, and more, has time to be read through).
 */
const SEND_IN_MS = 2000, SEND_LINE_MS = 1000, SEND_MAX_MS = 8000, SEND_SHORT_LINES = 2;
/** How long a draft of `lines` lines (as the talk box wraps it) shows before it goes by itself. */
export const sendDelay = (lines: number) => Math.min(SEND_MAX_MS, SEND_IN_MS + Math.max(0, lines - SEND_SHORT_LINES) * SEND_LINE_MS);
/** The first "debrief" covers the last hour; each later one, what came since the one before. */
const DEBRIEF_FIRST_MS = 60 * 60_000;

export class LensController {
  onChange: () => void = () => {};
  private s: Services;
  private screen: Screen = { kind: 'glance' };
  private focusId: string | null = null;
  private mem: GlanceMemory = { workingSince: new Map(), unseen: new Map() };
  private blips = new Map<string, number>();
  private facts = new Map<string, TurnFacts>();
  /** The session's last activity as of its facts: a newer one means it moved on since. */
  private factsAt = new Map<string, number>();
  private toast: { text: string; until: number } | null = null;
  private guardUntil = 0;
  private swipedAt = 0;
  private timers = new Set<ReturnType<typeof setTimeout>>();
  private lastSnap: Snapshot | null = null;
  private listenGen = 0;
  /** When the last countdown was to send (each one is its own moment). */
  private lastSendAt = 0;
  private handsFreeTimer: ReturnType<typeof setTimeout> | null = null;
  /** The live view's teleprompter: the line at the top of the output box, and whether it follows. */
  private tele: { id: string; turn: string; top: number; end: number; auto: boolean } | null = null;
  private teleTimer: ReturnType<typeof setTimeout> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private agentsLoading = new Set<string>();
  /** When a session's turn or agents last failed to load (a retry waits RETRY_MS). */
  private failedAt = new Map<string, number>();
  private answeredAt = new Map<string, number>();
  private feedCache: { key: string; lines: string[]; starts: number[]; turn: string } | null = null;
  /** The telescope's next look while watching. */
  private watchTimer: ReturnType<typeof setTimeout> | null = null;
  /** Which telescope request is current: an answer for an older one is dropped. */
  private scopeGen = 0;
  /** Pop-ups waiting or on the lens (core/popups.ts), and when the band last changed (its tap guard). */
  private pops = new PopQueue();
  /** What automations told the glasses, the last few, to open again from a band. */
  private notes = new Map<string, AutomationNote>();
  /** Asks of automation runs, open until answered, taken as their default, or ended (core/ask.ts). */
  private asks = new Map<string, AskCard>();
  private lastAskName = new Map<string, string>();
  /** Which tick of the asks' countdowns is scheduled, and for when. */
  private askDue = 0;
  private bandKey = '';
  private bandAt = 0;
  private popTimer: ReturnType<typeof setTimeout> | null = null;
  /** Text that arrives like speech (Settings: "Text arrives"), and its next step's redraw. */
  private tx = new Transmission(() => this.now(), () => ({ on: this.s.settings().textArrives === 'speech', wpm: this.s.settings().pace ?? 170 }));
  private txTimer: ReturnType<typeof setTimeout> | null = null;
  private txDue = 0;
  /** The speaking wave in the header (core/speech-out.ts): its beats, and when it last moved. */
  private wave = new WaveBeats();
  private waveText = '';
  private waveAt = 0;
  /**
   * The live view's session, and what of its turn and agents was there when they first loaded for
   * it (shown at once; null: not loaded yet, so nothing is new).
   */
  private liveSeen: { id: string; events: Set<string> | null; agents: Set<string> | null } | null = null;
  /**
   * Prompts sent from the lens, by the session they went to (a new one's too), and when: the turn
   * each starts is new to you wherever you first see it, even if Claude's first words came before
   * you looked (a new session's, while the lens was back on the glance).
   */
  private sentHere = new Map<string, { at: number; text: string }>();
  /** The reply being read aloud (its card follows the voice when text arrives like speech). */
  private reading: string | null = null;
  /** The sun as last drawn, and when a limit last lifted (its flash). */
  private sunWas: SunMode | null = null;
  private sunFlashAt: number | undefined;
  /** The limit as last seen (undefined: not seen yet, so one already on when the lens opens doesn't pop up). */
  private limitWas: string | null | undefined = undefined;
  /** The Mac's battery has popped up for this discharge. */
  private batteryPopped = false;
  /** Each provider's trouble as last seen, by id (undefined: not seen yet, so trouble already on when the lens opens doesn't pop up). */
  private providerWas: Map<string, string> | undefined = undefined;
  /** When "debrief" was last said. */
  private debriefAt: number | null = null;
  /** Moving: cards read out once each (a risky command, a question), and the risky ones heard to the end. */
  private readOut = new Set<string>();
  private heard = new Set<string>();
  /** The diff on the lens, its lines worked out once. */
  private diffCache: { changes: FileChange[]; text: DiffText } | null = null;
  /**
   * A text longer than the lens on screen (a reply, a plan, a diff), one text that scrolls
   * (core/scroll.ts): `at` names it ("reply:<id>"), `text` is what it was worked out for (another
   * text starts at its top), `rows` the window's lines. Opening it afresh starts at its top; coming
   * back to it (after talking, a pop-up, Telescope) finds it where it was.
   */
  private reader: { at: string; text: unknown; s: Scroll; rows: number } | null = null;

  constructor(s: Services) { this.s = s; }

  // ------------------------------------------------------------------ state from the Mac

  private snap(): Snapshot | null { return this.s.hub()?.snapshot ?? null; }
  private session(id: string): Session | null { return this.snap()?.sessions.find(x => x.id === id) ?? null; }
  private now(): number { return Date.now(); }

  /** At work: Claude, agents it started, or a job whose last agent has just reported. */
  private busy(s: Session): boolean { return isBusy(s, this.s.hub()?.held); }

  /** The glance's memory, with the hub's word on jobs that go on after their turn. */
  private memory(): GlanceMemory { return { ...this.mem, held: this.s.hub()?.held }; }

  /** The hub changed: keep the memory (who works since when) and the screens in step. */
  hubEvent(e: HubEvent): void {
    const snap = this.snap();
    // An agent's news matters only to the session on screen.
    if (e.t === 'agent') { if (this.liveId() === e.sessionId) this.onChange(); return; }
    if (e.t === 'change' && snap) { this.track(snap); this.watchPops(snap); }
    if (e.t === 'finished') void this.finished(e.sessionId);
    if (e.t === 'approval') this.asked(e.approval);
    if (e.t === 'backpack_ending') this.backpackEnding(e.until);
    if (e.t === 'note') this.noteArrived(e.note);
    if (e.t === 'ask') this.askArrived(e.ask);
    if (e.t === 'run_ask') this.runAsk(e.run);
    if (e.t === 'sleep_soon') this.sleepSoon(e.inSeconds, e.reason, e.percent ?? null, e.stillAwake ? (e.otherApp || 'another app') : null);
    if (e.t === 'event' && (this.screen.kind === 'result' || this.screen.kind === 'session') && this.screen.id === e.sessionId) {
      const events = this.s.hub()?.events(e.sessionId);
      if (events && e.event.kind !== 'thinking') this.facts.set(e.sessionId, turnFacts(events));
    }
    this.fixScreen();
    if (e.t === 'change') { this.refreshResult(); this.offerGuide(); }
    this.onChange();
  }

  private track(snap: Snapshot): void {
    const now = this.now();
    for (const s of snap.sessions) {
      // Agents still at work after the turn: the job goes on, and so does its time.
      const busy = this.busy(s);
      if (busy && !this.mem.workingSince.has(s.id)) {
        const prev = this.lastSnap?.sessions.find(x => x.id === s.id);
        // A session already working when the lens first saw it: its last activity is the best guess.
        this.mem.workingSince.set(s.id, prev ? now : Math.min(now, s.lastActivityAt));
      }
      if (!busy && s.state !== 'needs_you') this.mem.workingSince.delete(s.id);
      if (busy) this.mem.unseen.delete(s.id);
    }
    for (const [id, u] of this.mem.unseen) if (now - u.at > NEW_RESULT_MS || !snap.sessions.some(s => s.id === id)) this.mem.unseen.delete(id);
    this.lastSnap = snap;
  }

  private async finished(id: string): Promise<void> {
    const now = this.now();
    this.mem.unseen.set(id, { at: now, facts: '' });
    this.blips.set(id, now);
    // What we knew was the turn before this one.
    this.facts.delete(id);
    this.factsAt.delete(id);
    // Eclipse: only what needs you gets through, so a finished turn makes no sound and takes nothing.
    if (!this.eclipse) this.s.voice.blip('done');
    // A finished turn takes the lens, unless you're in the middle of something else or asked for
    // quiet (only what needs you breaks through that). Its own screen shows it from the start.
    const sc = this.screen;
    const own = (sc.kind === 'session' || sc.kind === 'result') && sc.id === id;
    const took = own || (sc.kind === 'glance' && !this.eclipse);
    // A new reply starts at its top.
    if (took) { this.reader = null; this.go({ kind: 'result', id }); }
    await this.loadFacts(id);
    // Anywhere else, a band says so (once its facts are in, to tell done from stopped).
    if (!took) this.popFinished(id);
    // Moving: you can't read it, so it's said, in one sentence.
    this.speakResult(id);
  }

  private loading = new Set<string>();

  /** The session's last turn, for its facts (and the live view): false if it didn't load. */
  private async loadFacts(id: string): Promise<boolean> {
    const hub = this.s.hub();
    if (!hub || this.loading.has(id)) return true;
    this.loading.add(id);
    // The session's activity as the request goes out: anything later may not be in the answer, so
    // it counts as the session moving on (refreshResult).
    const at = this.session(id)?.lastActivityAt ?? 0;
    try {
      const events = await hub.loadEvents(id);
      const f = turnFacts(events);
      this.facts.set(id, f);
      this.factsAt.set(id, at);
      const u = this.mem.unseen.get(id);
      if (u) u.facts = doneLine(f);
      this.onChange();
      return true;
    } catch (err) {
      console.warn('[lens] events', explain(err));
      return false;
    } finally {
      this.loading.delete(id);
    }
  }

  /**
   * A result card's facts as of now. What was worked out when the card was last open may be a turn
   * old (a turn that ran while the lens looked elsewhere, or while the socket was down), and would
   * show an old reply as DONE. The hub keeps a loaded turn current from the socket and drops it on a
   * reconnect, so its copy is used when it has one; otherwise the turn is loaded. While the hub polls
   * (no socket), nothing keeps its copy current, so it's loaded then too.
   */
  private freshFacts(id: string): void {
    const hub = this.s.hub();
    const events = hub?.events(id);
    if (events?.length) this.facts.set(id, turnFacts(events));
    if (!events?.length || hub?.status === 'polling') void this.loadFacts(id);
  }

  private asked(a: Approval): void {
    this.s.voice.blip('needs');
    if (this.screen.kind !== 'glance' && this.screen.kind !== 'quiet') { this.popNeeds(a); return; }
    if (this.screen.kind === 'quiet') this.go({ kind: 'glance' });
    // The glance points at what needs you, unless you were just choosing a line yourself: then the
    // focus stays where you put it, so the tap you were about to make opens what you chose.
    if (this.now() - this.swipedAt < SWIPE_KEEP_MS || this.focusId === a.sessionId) return;
    this.focusId = a.sessionId;
    // A tap already on its way was meant for the line that had the focus a moment ago.
    this.guardUntil = this.now() + TAP_GUARD_MS;
  }

  /**
   * A card whose subject went away (answered on the Mac, session ended) steps back, and a session's
   * own screens follow it: working again, its last reply gives way to the live view (no DONE or
   * REPLY on a session at work); what it asks comes up, to read first; its job ending shows the
   * result.
   */
  private fixScreen(): void {
    let sc = this.screen;
    if (sc.kind === 'needs' && !this.needItem(sc.id, sc.key)) {
      // Raised on the session's own screen: what else it asks, else back to it. Otherwise the next
      // thing that needs you, anywhere.
      const own = sc.back ? this.freshNeed(sc.id) : null;
      const next: Screen | null = sc.back
        ? (own ? { kind: 'needs', id: sc.id, key: own.key, cursor: 0, qi: 0, picks: [], answers: {}, back: true } : { kind: 'session', id: sc.id })
        : this.nextNeeds(sc.id);
      this.screen = next ?? { kind: 'glance' };
      if (next?.kind === 'needs') this.guardUntil = this.now() + TAP_GUARD_MS;
      sc = this.screen;
    }
    if (sc.kind === 'ask' && !sc.closed && !this.asks.has(sc.key)) { this.screen = sc.back.kind === 'ask' ? { kind: 'glance' } : sc.back; return; }
    if (sc.kind !== 'session' && sc.kind !== 'result') return;
    const s = this.session(sc.id);
    if (!s) { this.screen = { kind: 'glance' }; return; }
    const item = this.freshNeed(sc.id);
    if (item) {
      this.screen = { kind: 'needs', id: sc.id, key: item.key, cursor: 0, qi: 0, picks: [], answers: {}, back: true };
      this.guardUntil = this.now() + TAP_GUARD_MS;
      return;
    }
    if (sc.kind === 'result' && this.busy(s)) { this.screen = { kind: 'session', id: sc.id }; return; }
    if (sc.kind === 'session' && !this.busy(s) && s.state !== 'needs_you') {
      // It stopped without a finish to say so (interrupted on the Mac): the result, quietly.
      this.screen = { kind: 'result', id: sc.id };
      this.mem.unseen.delete(sc.id);
      this.freshFacts(sc.id);
    }
  }

  /** What a session asks that wasn't answered from here a moment ago (the Mac may not have caught up). */
  private freshNeed(id: string): ReturnType<LensController['needItem']> {
    const now = this.now();
    for (const [k, at] of this.answeredAt) if (now - at > ANSWERED_MS) this.answeredAt.delete(k);
    const item = this.needItem(id);
    if (!item || !this.answeredAt.has(item.key)) return item;
    const snap = this.snap()!;
    const other = approvalsOf(snap, id).find(a => !this.answeredAt.has(a.id));
    return other ? { approval: other, menu: null, key: other.id } : null;
  }

  /**
   * The result card on screen, kept to its turn as it is now. On the socket the hub's copy of the
   * turn is current, and so are the facts worked out from its events. A turn that ran while the
   * socket was down never came as events, though: the reconnect drops the hub's copy, and while the
   * hub polls nothing updates it. Then a session that moved on since its facts (newer activity), or
   * whose facts never loaded, is loaded again: a DONE card never keeps the reply from before the
   * outage. Never while the Mac is away; a failed load is tried again a little later.
   */
  private refreshResult(): void {
    const sc = this.screen;
    const hub = this.s.hub();
    if (sc.kind !== 'result' || !hub || (hub.status !== 'live' && hub.status !== 'polling')) return;
    const id = sc.id, s = this.session(id);
    if (!s || this.busy(s) || this.loading.has(id)) return;
    if (hub.status === 'live' && hub.events(id)) { this.factsAt.set(id, s.lastActivityAt); return; }
    const at = this.factsAt.get(id);
    if (at !== undefined && this.facts.has(id) && s.lastActivityAt <= at) return;
    if (this.now() - (this.failedAt.get(`e:${id}`) ?? 0) <= RETRY_MS) return;
    void this.loadFacts(id).then(ok => {
      if (ok) return;
      this.failedAt.set(`e:${id}`, this.now());
      this.later(RETRY_MS + 50, () => this.refreshResult());
    });
  }

  // ------------------------------------------------------------------ helpers

  private rows(): GlanceRow[] {
    const snap = this.snap();
    return snap ? glanceRows(snap, this.s.settings(), this.memory(), this.now()) : [];
  }

  private focus(rows = this.rows()): string | null {
    if (this.focusId === NEW_ROW) return NEW_ROW;
    if (this.focusId && rows.some(r => r.sessionId === this.focusId)) return this.focusId;
    return defaultFocus(rows) ?? NEW_ROW;
  }

  /** The session the user is looking at: the card's, or the glance's focus. */
  contextId(sc: Screen = this.screen): string | null {
    if (sc.kind === 'talk') return sc.route?.kind === 'session' ? sc.route.sessionId : this.contextId(sc.from);
    if ('id' in sc) return sc.id;
    const f = this.focus();
    return f === NEW_ROW ? null : f;
  }

  private go(sc: Screen): void {
    if (sc.kind === 'needs') this.guardUntil = this.now() + TAP_GUARD_MS;
    // What you open is what its pop-ups were about.
    if (sc.kind === 'needs' || sc.kind === 'session' || sc.kind === 'result') { const id = sc.id; this.pops.drop(p => p.sessionId === id); }
    // A session's live view starts at its newest lines, following.
    if (sc.kind === 'session') this.tele = null;
    if (sc.kind === 'result') { this.mem.unseen.delete(sc.id); this.freshFacts(sc.id); }
    if ((this.screen.kind === 'result' && sc.kind !== 'result') || (this.screen.kind === 'note' && sc.kind !== 'note')) this.s.voice.stop();
    if (sc.kind !== 'telescope') this.stopWatching();
    this.screen = sc;
    // Going back to a card whose subject went away meanwhile (answered on the Mac while you talked).
    this.fixScreen();
    this.onChange();
  }

  private later(ms: number, fn: () => void): void {
    const t = setTimeout(() => { this.timers.delete(t); fn(); }, ms);
    this.timers.add(t);
  }

  private say(text: string, ms = TOAST_MS): void {
    this.toast = { text, until: this.now() + ms };
    this.later(ms + 20, () => this.onChange());
    this.onChange();
  }

  /** What needs answering in a session: its oldest approval, else the menu its terminal shows. */
  private needItem(id: string, key?: string): { approval: Approval | null; menu: TermMenu | null; key: string } | null {
    const snap = this.snap();
    if (!snap) return null;
    const list = approvalsOf(snap, id);
    if (key) {
      const a = list.find(x => x.id === key);
      if (a) return { approval: a, menu: null, key };
      const m = this.session(id)?.menu;
      return m && `menu:${m.id}` === key ? { approval: null, menu: m, key } : null;
    }
    if (list[0]) return { approval: list[0], menu: null, key: list[0].id };
    const m = this.session(id)?.menu;
    return m ? { approval: null, menu: m, key: `menu:${m.id}` } : null;
  }

  private nextNeeds(prefer: string): Screen | null {
    const snap = this.snap();
    if (!snap) return null;
    const ids = [prefer, ...activeSessions(snap, this.s.settings(), this.memory(), this.now()).map(s => s.id)];
    for (const id of ids) {
      const item = this.needItem(id);
      if (item) return { kind: 'needs', id, key: item.key, cursor: 0, qi: 0, picks: [], answers: {} };
    }
    return null;
  }

  private openFocused(): void {
    const id = this.focus();
    if (id === NEW_ROW) return this.go({ kind: 'picker', cursor: 0 });
    if (id) this.open(id);
  }

  /** Open a session's card: what it needs from you, else what it's doing, else its last reply (at its top). */
  open(id: string): void {
    this.focusId = id;
    this.reader = null;
    const s = this.session(id);
    if (!s) return;
    const item = this.needItem(id);
    if (item) return this.go({ kind: 'needs', id, key: item.key, cursor: 0, qi: 0, picks: [], answers: {} });
    if (this.busy(s)) return this.go({ kind: 'session', id });
    this.go({ kind: 'result', id });
  }

  // ------------------------------------------------------------------ gestures

  tap(): void {
    this.stopWatching();
    if (!this.s.paired()) { this.s.retryLink?.(); return; }
    // The guide takes every gesture, except over its offer, where a pop-up's band is still a band.
    if (this.screen.kind === 'guide' && (this.screen.g.step >= 0 || !this.bandTop())) return this.guideDo('tap');
    if (this.bandTap()) return;
    const sc = this.screen;
    // Text still arriving like speech: in the live view a tap shows the rest at once. On a reply's
    // card a tap reads it aloud at once, the words starting again with the voice (waiting for the
    // whole text first hid Read aloud, the owner found); with reading aloud off it shows the rest.
    // A reply's rest comes in under the window, which stays where it is, to read on from there.
    if ((sc.kind === 'session' || (sc.kind === 'result' && this.muted)) && !this.s.voice.speaking && this.tx.behind()) {
      this.tx.catchUp();
      this.unfollow(`reply:${sc.id}`);
      this.onChange();
      return;
    }
    switch (sc.kind) {
      case 'picker': return this.pick(sc.cursor);
      case 'power': return;
      case 'quiet': return this.go({ kind: 'glance' });
      case 'glance': if (this.now() >= this.guardUntil) this.openFocused(); return;
      case 'session': {
        // Reading aloud: a tap stops it. Otherwise it pauses the scroll, or picks it up where it is.
        if (this.s.voice.speaking) { this.s.voice.stop(); this.onChange(); return; }
        if (this.tele?.id === sc.id) { this.tele.auto = !this.tele.auto; this.onChange(); }
        return;
      }
      case 'result': {
        // Stopped while you were away: a tap carries it on (as Continue on the phone).
        const s = this.session(sc.id);
        if (s && stoppedAway(s) && !this.s.voice.speaking) return void this.carryOn(sc.id);
        if (this.muted && !this.s.voice.speaking) { this.say(MUTED, 2600); return; }
        return void this.readAloud(sc.id);
      }
      case 'needs':
        if (this.now() < this.guardUntil) return;
        if (this.onBackRow(sc)) return this.back();
        // Moving, a risky command is never allowed by a tap: by its verb, said once it has been heard.
        if (this.moving && this.riskOf(sc)) { this.say(`Say “approve ${this.riskOf(sc)!.verb}” to allow it`, 3000); this.readCard(sc, !this.heard.has(sc.key)); return; }
        return void this.approve(sc);
      case 'note':
        if (this.muted && !this.s.voice.speaking) { this.say(MUTED, 2600); return; }
        return void this.readNoteAloud(sc.noteId);
      case 'ask':
        if (this.now() < this.guardUntil) return;
        if (sc.closed) return this.leaveAsk(sc);
        return void this.answerAsk(sc, sc.cursor);
      case 'debrief': { const item = sc.items[sc.cursor]; if (item && this.session(item.sessionId)) this.open(item.sessionId); return; }
      case 'diff': return this.diffTap(sc);
      case 'telescope':
        // The whole picture: a tap zooms into its first quarter, cut from the full-size picture.
        if (sc.status === 'ready' && sc.shot && sc.zoom === null) { this.screen = { ...sc, zoom: 0 }; this.onChange(); return; }
        // Zoomed in (or no picture, only the live page): the picture full size on the phone.
        if (sc.shot || sc.live) { this.s.showOnPhone(sc.shot, sc.live); this.say('Open your phone to see it full size'); }
        return;
      case 'talk':
        if (sc.handsFree && sc.phase === 'listening') return void this.release();
        // Counting down or waiting: sent now. While the last words are still coming, nothing yet.
        if (sc.phase === 'review' || sc.phase === 'error') return void this.send(sc);
        return;
    }
  }

  doubleTap(): void {
    this.stopWatching();
    const sc = this.screen;
    switch (sc.kind) {
      case 'guide':
        // Skipping the spoken step while it listens: the microphone goes off, and what it heard is dropped.
        if (sc.phase === 'listening' || sc.phase === 'finishing') { this.s.speech()?.cancel(); this.listenGen++; }
        return this.guideDo('double');
      case 'debrief': case 'diff': case 'note': this.s.voice.stop(); return this.go(sc.back);
      case 'ask': this.s.voice.stop(); return this.leaveAsk(sc);
      case 'glance': return this.go({ kind: 'quiet' });
      case 'quiet': return this.go({ kind: 'glance' });
      case 'needs': if (this.now() >= this.guardUntil) void this.deny(sc, sc.reason); return;
      // Zoomed in: the whole picture again; else back where you were.
      case 'telescope':
        if (sc.zoom !== null) { this.screen = { ...sc, zoom: null }; this.onChange(); return; }
        return this.go(sc.back);
      case 'talk':
        // Cancelled, whenever: listening, its last words on the way, or counting down. Nothing is
        // sent and nothing is kept.
        if (sc.phase === 'listening' || sc.phase === 'finishing') { this.s.speech()?.cancel(); this.listenGen++; }
        return this.go(sc.from.kind === 'talk' ? { kind: 'glance' } : sc.from);
      case 'power': return this.go(sc.back);
      default:
        this.s.voice.stop();
        return this.go({ kind: 'glance' });
    }
  }

  /** Swipe toward you (forward on the temple): previous / up. */
  up(): void { this.move(-1); }
  /** Swipe away: next / down. */
  down(): void { this.move(1); }

  /** Back to the glance without answering or sending anything (the menu's Back, a needs card's
   * Back row, a swipe past a command or a plan). */
  back(): void {
    const sc = this.screen;
    if (sc.kind === 'glance' || sc.kind === 'talk') return;
    this.s.voice.stop();
    this.go(sc.kind === 'telescope' || sc.kind === 'power' || sc.kind === 'guide' || sc.kind === 'debrief' || sc.kind === 'diff' || sc.kind === 'note' ? sc.back : sc.kind === 'ask' ? this.leaveTo(sc) : { kind: 'glance' });
  }

  private move(d: 1 | -1): void {
    this.stopWatching();
    if (this.screen.kind === 'guide' && (this.screen.g.step >= 0 || !this.bandTop())) return this.guideDo('swipe');
    // A pop-up on the lens: a swipe puts it away (and nothing else moves).
    if (this.bandSwipe()) return;
    const sc = this.screen;
    switch (sc.kind) {
      case 'quiet': return this.go({ kind: 'glance' });
      case 'glance': {
        // The sessions, then "+ New session"; a swipe up past the first line shows the batteries.
        const ids = [...this.rows().map(r => r.sessionId), NEW_ROW];
        const i = Math.max(0, ids.indexOf(this.focus() ?? NEW_ROW));
        if (d < 0 && i === 0) return this.showPower();
        this.swipedAt = this.now();
        const j = Math.max(0, Math.min(ids.length - 1, i + d));
        if (j !== i) { this.focusId = ids[j]; this.onChange(); }
        return;
      }
      case 'picker': {
        const n = this.pickerOptions().length;
        const cursor = Math.max(0, Math.min(n - 1, sc.cursor + d));
        if (cursor !== sc.cursor) { this.screen = { ...sc, cursor }; this.onChange(); }
        return;
      }
      case 'power': return this.go(sc.back);
      case 'debrief': {
        const cursor = Math.max(0, Math.min(sc.items.length - 1, sc.cursor + d));
        if (cursor !== sc.cursor) { this.screen = { ...sc, cursor }; this.onChange(); }
        return;
      }
      case 'diff': {
        // From the list of files, on into the diff; back past the diff's start, the list again.
        if (!sc.diff) { if (d > 0 && this.diffOf(sc).lines.length) { this.reader = null; this.screen = { ...sc, diff: true }; this.onChange(); } return; }
        if (!this.scroll(`diff:${sc.id}`, d) && d < 0) { this.screen = { ...sc, diff: false }; this.onChange(); }
        return;
      }
      case 'session': {
        // Back through the turn stops the scroll; forward to its end follows again.
        const t = this.tele;
        if (!t || t.id !== sc.id) return;
        const top = Math.max(0, Math.min(t.end, t.top + d * SCROLL_STEP));
        if (d < 0) {
          if (top === t.top) return;
          t.auto = false;
        } else if (top >= t.end) t.auto = true;
        t.top = top;
        this.onChange();
        return;
      }
      case 'result': this.scroll(`reply:${sc.id}`, d); return;
      case 'note': this.scroll(`note:${sc.noteId}`, d); return;
      case 'ask': {
        const card = this.asks.get(sc.key);
        if (!card || sc.closed) return;
        const cursor = Math.max(0, Math.min(card.answers.length - 1, sc.cursor + d));
        if (cursor !== sc.cursor) { this.screen = { ...sc, cursor }; this.onChange(); }
        return;
      }
      case 'needs': {
        const item = this.needItem(sc.id, sc.key);
        if (!item) return;
        const opts = this.optionCount(item, sc.qi);
        // The options, then the Back row (cursor === opts).
        if (opts > 0) { this.screen = { ...sc, cursor: Math.max(0, Math.min(opts, sc.cursor + d)) }; this.onChange(); return; }
        // A command has nothing to swipe through, and a plan ends: a swipe past the end sets it
        // aside, unanswered (it stays first on the glance). Not in the card's first moment: that
        // swipe was meant for the card before it (the one just answered).
        const aside = () => { if (this.now() >= this.guardUntil) this.back(); };
        if (item.approval?.kind !== 'plan') return aside();
        // A plan's three lines lead on into the whole plan, one text that scrolls; back past its
        // start, the three lines again.
        if (!sc.whole) { if (d > 0) { this.reader = null; this.screen = { ...sc, whole: true }; this.onChange(); } return; }
        if (this.scroll(`plan:${sc.key}`, d)) return;
        if (d > 0) return aside();
        this.screen = { ...sc, whole: false };
        this.onChange();
        return;
      }
      case 'telescope':
        // Zoomed in: the next or previous quarter, round and round.
        if (sc.zoom !== null) { this.screen = { ...sc, zoom: (sc.zoom + d + 4) % 4 }; this.onChange(); }
        return;
      case 'talk': {
        if (sc.phase !== 'review' && sc.phase !== 'error') return;
        // Any swipe pauses the count until a tap sends it or a double tap cancels: you're reading
        // it, or choosing where it goes. A draft longer than the box scrolls (by a box less a line);
        // a shorter one goes elsewhere.
        const paused = sc.paused || !!sc.sendAt;
        const lines = this.talkLines(sc.draft);
        const rows = this.talkRows(lines);
        if (sc.phase === 'review' && lines.length > rows) {
          const { s } = swipe({ top: this.talkTop(sc, lines), lines: lines.length, follow: false }, d, rows);
          this.screen = { ...sc, top: s.top, sendAt: 0, paused };
          this.onChange();
          return;
        }
        if (!sc.routes.length) { if (sc.sendAt) { this.screen = { ...sc, sendAt: 0, paused }; this.onChange(); } return; }
        const i = sc.routes.findIndex(r => sameRoute(r, sc.route));
        const j = (i + d + sc.routes.length) % sc.routes.length;
        this.screen = { ...sc, route: sc.routes[j], phase: 'review', message: '', sendAt: 0, paused };
        this.onChange();
        return;
      }
      default: return;
    }
  }

  // ------------------------------------------------------------------ talk

  /**
   * The temple is held: start listening to whoever is in front of you. On the main screen that's
   * the project that needs you (an approval, question, plan or menu), else a new session in Nebula.
   * `preset`: where it goes, chosen first (the project picker).
   */
  async hold(handsFree = false, preset?: Route): Promise<void> {
    if (!this.s.paired()) return;
    this.stopWatching();
    const prev = this.screen;
    if (prev.kind === 'guide') return this.guideHold(prev);
    if (prev.kind === 'talk' && (prev.phase === 'listening' || prev.phase === 'finishing')) return;
    const speech = this.s.speech();
    this.s.voice.stop();
    const appending = prev.kind === 'talk' && (prev.phase === 'review' || prev.phase === 'error');
    const from = appending ? prev.from : prev;
    const route = appending ? prev.route : preset ?? (HOME.has(prev.kind) ? this.homeRoute() : this.routeFor(this.contextId(prev)));
    const draft = appending ? prev.draft : '';
    const sc: Screen = { kind: 'talk', phase: 'listening', draft, interim: '', route, routes: [], message: '', handsFree, replace: false, from };
    this.screen = sc;
    this.onChange();
    if (!speech) {
      this.screen = { ...sc, phase: 'error', message: 'Voice needs Soniox on your Mac. Connect it on your phone: Settings, Speech.' };
      this.onChange();
      return;
    }
    const gen = ++this.listenGen;
    let last = '';
    try {
      await speech.start((fin, interim) => {
        // After the release too: the last words settle in place while they come.
        if (gen !== this.listenGen || this.screen.kind !== 'talk' || (this.screen.phase !== 'listening' && this.screen.phase !== 'finishing')) return;
        this.screen = { ...this.screen, interim: [fin, interim].filter(Boolean).join(' ') };
        if (handsFree) this.armHandsFree(gen, fin + interim !== last);
        last = fin + interim;
        this.onChange();
      });
      if (handsFree) this.armHandsFree(gen, false, 8000);
    } catch (err) {
      if (gen !== this.listenGen || this.screen.kind !== 'talk') return;
      this.screen = { ...this.screen, phase: 'error', message: err instanceof SpeechError ? err.message : explain(err) };
      this.onChange();
    }
  }

  /** Hands-free listening (from the menu) ends after a pause in speech, or 8 s of silence. */
  private armHandsFree(gen: number, spoke: boolean, ms = 1800): void {
    if (this.handsFreeTimer && !spoke && ms !== 8000) return;
    if (this.handsFreeTimer) clearTimeout(this.handsFreeTimer);
    this.handsFreeTimer = setTimeout(() => { this.handsFreeTimer = null; if (gen === this.listenGen) void this.release(); }, ms);
  }

  /**
   * The temple is let go. The last words are still on their way (the microphone's tail, then
   * Soniox's), so the captions stay and settle; then what was said becomes a command, or a draft
   * shown whole that goes by itself after a count as long as it takes to read (sendDelay; with
   * "send it", at once). A draft whose last words never came back waits for a tap instead, and says so.
   */
  async release(): Promise<void> {
    const sc = this.screen;
    if (sc.kind === 'guide') return this.guideRelease(sc);
    if (sc.kind !== 'talk' || sc.phase !== 'listening') return;
    if (this.handsFreeTimer) { clearTimeout(this.handsFreeTimer); this.handsFreeTimer = null; }
    const gen = this.listenGen;
    const speech = this.s.speech();
    this.screen = { ...sc, phase: 'finishing' };
    this.onChange();
    let heard: Heard = { text: '', complete: true };
    const still = () => gen === this.listenGen && this.screen.kind === 'talk' && this.screen.phase === 'finishing';
    try { if (speech) heard = await speech.stop(); } catch (err) {
      if (still() && this.screen.kind === 'talk') { this.screen = { ...this.screen, phase: 'error', message: explain(err) }; this.onChange(); }
      return;
    }
    if (!still() || this.screen.kind !== 'talk') return;
    const cur = this.screen;
    const said = heard.text || cur.interim;
    if (!said.trim()) {
      if (cur.draft) { this.screen = { ...cur, phase: 'review', interim: '' }; this.onChange(); }
      else this.go(cur.from);
      return;
    }
    // An ask on the lens: its answers are spoken as the approvals' words are ("skip", "approve", "two").
    if (heard.complete && !cur.draft && cur.from.kind === 'ask' && !cur.from.closed) {
      const card = this.asks.get(cur.from.key);
      const i = card ? matchAnswer(said, card.answers) : -1;
      if (i >= 0) { this.screen = cur.from; this.onChange(); return void this.answerAsk(cur.from, i); }
    }
    const snap = this.snap();
    const u = parseUtterance(said, { sessions: snap?.sessions ?? [], projects: snap?.projects ?? [], focusId: this.contextId(cur.from), needs: cur.from.kind === 'needs' });
    // A voice word acts at once, but only when the words are known to be whole: an end that never
    // came back may have made "stop" into "stop the server and restart it".
    if (heard.complete) {
      if (u.kind === 'word' && !cur.draft) return void this.command(u.word, u.rest, cur.from, u.ask, u.n);
      if (u.kind === 'word' && u.word === 'send') return void this.send({ ...cur, interim: '' });
      if (u.kind === 'word' && u.word === 'discard') return this.go(cur.from);
    }
    const text = u.kind === 'prompt' ? u.text : said;
    const route = (u.kind === 'prompt' && u.route) || cur.route || this.defaultNewRoute();
    const draft = cur.draft ? `${cur.draft} ${text}` : text;
    const next: Extract<Screen, { kind: 'talk' }> = { ...cur, phase: 'review', draft, interim: '', route, routes: this.routesAround(route, HOME.has(cur.from.kind)), cut: !heard.complete, top: undefined, sendAt: 0, paused: false };
    if (heard.complete && u.kind === 'prompt' && u.sendNow) { this.screen = next; this.onChange(); return void await this.send(next); }
    this.screen = heard.complete ? this.countdown(next) : next;
    this.onChange();
  }

  /**
   * The draft goes by itself unless a gesture comes first, after a count as long as it takes to read
   * (sendDelay); the seconds left are redrawn as they change.
   */
  private countdown(sc: Extract<Screen, { kind: 'talk' }>): Extract<Screen, { kind: 'talk' }> {
    const ms = sendDelay(this.talkLines(sc.draft).length);
    // Each count its own moment, so a late timer never sends a newer draft.
    const at = (this.lastSendAt = Math.max(this.now() + ms, this.lastSendAt + 1));
    const counting = () => { const cur = this.screen; return cur.kind === 'talk' && cur.phase === 'review' && cur.sendAt === at ? cur : null; };
    for (let t = 1000; t < ms; t += 1000) this.later(t, () => { if (counting()) this.onChange(); });
    this.later(at - this.now(), () => { const cur = counting(); if (cur) void this.send(cur); });
    return { ...sc, sendAt: at, paused: false };
  }

  /** The draft as the talk box's lines, narrower by the ↓ that marks more under the window. */
  private talkLines(draft: string): string[] { return wrapAll(draft, TALK_TEXT_W - MORE_W); }

  /** How many of the draft's lines the talk box shows: all TALK_ROWS, unless they're too dense for one text box (font.ts BOX_BYTES). */
  private talkRows(lines: string[]): number { return fitRows(lines, TALK_ROWS, TALK_TEXT_W); }

  /** Where a draft of `lines` is scrolled: where a swipe left it, else its end (the newest words, under the captions just seen). */
  private talkTop(sc: Extract<Screen, { kind: 'talk' }>, lines: string[]): number {
    const end = lastTop(lines.length, this.talkRows(lines));
    return Math.max(0, Math.min(sc.top ?? end, end));
  }

  /** The talk box's window on the draft: ↓ marks more below, and, since it opens at its end, ↑ more above. */
  private talkWindow(sc: Extract<Screen, { kind: 'talk' }>, lines: string[]): string[] {
    const top = this.talkTop(sc, lines);
    const shown = windowLines(lines, top, this.talkRows(lines), TALK_TEXT_W);
    if (top > 0) shown[0] = spread(shown[0], '↑', TALK_TEXT_W - 3);
    return shown;
  }

  private routeFor(id: string | null): Route | null {
    const s = id ? this.session(id) : null;
    return s ? { kind: 'session', sessionId: s.id, project: s.projectName } : null;
  }

  /** No session to talk to: a new one, in a folder of its own in Nebula. */
  private defaultNewRoute(): Route | null {
    return NEBULA_ROUTE;
  }

  /**
   * Where a swipe can send the draft: from the main screen its two targets first (what needs you,
   * a new session in Nebula); then the glance's sessions, new sessions in their projects, and Nebula.
   */
  private routesAround(route: Route | null, home = false): Route[] {
    const out: Route[] = [];
    const add = (r: Route | null) => { if (r && !out.some(x => sameRoute(x, r))) out.push(r); };
    add(route);
    if (home) { add(this.needsRoute()); add(NEBULA_ROUTE); }
    for (const row of this.rows()) add(this.routeFor(row.sessionId));
    const snap = this.snap();
    const projectOf = (r: Route) => r.kind === 'session' ? this.session(r.sessionId)?.cwd ?? '' : r.kind === 'new' ? r.projectPath : '';
    for (const r of [...out]) {
      const path = projectOf(r);
      const name = r.project;
      if (path) add({ kind: 'new', projectPath: path, project: name });
    }
    for (const p of (snap?.projects ?? []).slice(0, 4)) add({ kind: 'new', projectPath: p.path, project: p.name });
    const list = out.slice(0, 8);
    return list.some(r => r.kind === 'nebula') ? list : [...list.slice(0, 7), NEBULA_ROUTE];
  }

  private async send(sc: Extract<Screen, { kind: 'talk' }>): Promise<void> {
    const hub = this.s.hub();
    const text = sc.draft.trim();
    if (!hub || !text) return;
    const route = sc.route ?? this.defaultNewRoute();
    if (!route) { this.screen = { ...sc, phase: 'error', message: 'No project to send it to.' }; this.onChange(); return; }
    this.screen = { ...sc, phase: 'sending', route, message: '' };
    this.onChange();
    try {
      let words: string[], now = route, target: string | null = null;
      if (route.kind === 'session') {
        const r = await hub.prompt(route.sessionId, text, sc.replace);
        // A terminal session still open on the Mac (not shared) is continued in a new session, a
        // fork: that's where the reply will come, so the lens follows it.
        const moved = r.session?.id && r.session.id !== route.sessionId ? r.session.id : null;
        words = [r.forked ? 'NEW SESSION' : r.queued ? 'QUEUED' : 'SENT', route.project];
        this.focusId = target = moved ?? route.sessionId;
        if (moved) now = { ...route, sessionId: moved };
      } else if (route.kind === 'nebula') {
        // A folder of its own, named from what was said; the Mac makes the name unique.
        if (!hub.newNebulaSession) throw new ApiError('missing', 'This Mac’s Hypernova can’t start sessions in Nebula yet. Update it.');
        const s = await hub.newNebulaSession(folderName(text), text, this.newSessionChoice());
        words = ['NEW PROJECT', s?.projectName ?? folderName(text)];
        if (s) this.focusId = target = s.id;
      } else {
        const s = await hub.newSession(route.projectPath, text, this.newSessionChoice());
        words = ['NEW SESSION', route.project];
        if (s) this.focusId = target = s.id;
      }
      if (target) this.sentFrom(target, text);
      if (this.screen.kind !== 'talk') return;
      this.screen = { ...this.screen, phase: 'done', message: '', said: words, route: now };
      this.onChange();
      // Said to the session on screen (its live view or its result): back to it, which shows it at
      // work once the prompt starts it (its result while it hasn't). Else the glance (or a new
      // session's live view).
      const from = sc.from;
      const own = (from.kind === 'session' || from.kind === 'result') && now.kind === 'session' && now.sessionId === from.id;
      // A new session or project (owner, 2026-10-16): its live view opens, not the glance, so you
      // watch it start; double tap goes back.
      const fresh = target && (now.kind === 'nebula' || now.kind === 'new') ? target : null;
      const after: Screen = own ? { kind: 'session', id: from.id } : fresh ? { kind: 'session', id: fresh } : { kind: 'glance' };
      this.later(1300, () => { if (this.screen.kind === 'talk' && this.screen.phase === 'done') this.go(after); });
    } catch (err) {
      if (this.screen.kind !== 'talk') return;
      let message = explain(err), replace = false;
      if (err instanceof ApiError && err.code === 'typing') { message = 'There’s unsent text in its terminal on the Mac. Tap to replace it and send.'; replace = true; }
      if (err instanceof ApiError && err.code === 'needs_answer') {
        // It asks first: its card comes up, and what was said goes with a deny, as "deny, …" does.
        if (route.kind === 'session' && this.askFirst(route.sessionId, text)) return;
        message = `${route.project} is waiting for your answer first.`;
      }
      this.screen = { ...this.screen, phase: 'error', message, replace };
      this.onChange();
    }
  }

  /**
   * The provider and model a new session from the glasses starts on: the phone's last choice (its
   * new-session sheet, through the app's settings), while this Mac still has that provider; else
   * none, and the Mac's defaults. Never a permission mode: Auto and Bypass are the phone's.
   */
  private newSessionChoice(): NewSessionChoice | null {
    const c = this.s.settings().newSession ?? null;
    if (!c) return null;
    const providers = this.snap()?.providers;
    return !providers?.length || providers.some(p => p.id === c.providerId) ? c : null;
  }

  // ------------------------------------------------------------------ voice words

  private async command(word: VoiceWord, rest: string, from: Screen, ask: ScopeAsk = {}, n?: number): Promise<void> {
    const ctx = this.contextId(from);
    this.screen = from;
    switch (word) {
      case 'status': {
        this.go({ kind: 'glance' });
        if (this.muted) { this.say(MUTED, 2600); return; }
        void this.s.voice.speak(this.statusText()).then(who => { if (who === 'locked') this.say(SOUND_LOCKED, 2800); });
        return;
      }
      case 'mute': case 'unmute': {
        const on = word === 'unmute';
        this.s.updateSettings({ readAloud: on });
        if (!on) this.s.voice.stop();
        this.say(on ? 'Read aloud on' : 'Read aloud off');
        return;
      }
      case 'approve': case 'always': case 'deny': {
        // Voice answers only what the lens is showing: the card that was in front of you when you
        // started talking, and only if it still asks the same thing.
        if (from.kind === 'needs') {
          if (!this.needItem(from.id, from.key)) { this.fixScreen(); this.say('Already answered'); return; }
          if (word === 'deny') await this.deny(from, rest || from.reason);
          // A risky command only by its own verb ("approve push"), and while moving once it's been heard.
          else if (this.riskCleared(from, word, rest)) await this.approve(from, word === 'always');
          return;
        }
        // Anywhere else the words bring up what needs you, to read first (tap guard and all). A
        // reason said with "deny" stays with that card, so a double tap sends it.
        const target = (ctx && this.needItem(ctx) ? ctx : null) ?? this.rows().find(r => r.state === 'needs')?.sessionId ?? null;
        if (!target || !this.needItem(target)) { this.say('Nothing needs you'); return; }
        this.open(target);
        // Only a permission's or a plan's deny carries a reason to Claude: a question's skips, and a
        // terminal menu's cancels with nothing to say.
        const card = this.screen.kind === 'needs' && this.screen.id === target ? this.screen : null;
        const kind = card ? this.needItem(target, card.key)?.approval?.kind : undefined;
        const reason = word === 'deny' && (kind === 'tool' || kind === 'plan') ? rest.trim() : '';
        if (reason && card) this.screen = { ...card, reason };
        const risk = card ? this.riskOf(card) : null;
        this.say(word === 'deny' ? (reason ? 'Read it, then double tap to deny' : 'Read it, then say “deny” or double tap')
          : risk ? (this.moving ? `Listen, then say “approve ${risk.verb}”` : `Read it, then say “approve ${risk.verb}” or tap`)
          : 'Read it, then say “approve” or tap', 3200);
        return;
      }
      case 'stop': {
        if (!ctx) { this.say('Nothing to stop'); return; }
        const s = this.session(ctx);
        try { await this.s.hub()?.interrupt(ctx); this.say(`Stopped ${s?.projectName ?? 'it'}`); } catch (err) { this.say(lensWords(err), 3000); }
        return;
      }
      case 'read': {
        if (!ctx) { this.say('No reply to read'); return; }
        if (this.muted) { this.say(MUTED, 2600); return; }
        void this.readAloud(ctx);
        return;
      }
      case 'send': this.say('Nothing to send'); return;
      case 'telescope': return void this.telescope(ctx, ask, rest);
      case 'watch': return this.watch(ctx);
      case 'discard': this.onChange(); return;
      case 'battery': return this.showPower();
      case 'awake': case 'sleep': return void this.stayAwake(word);
      case 'extend': return void this.extendBackpack();
      // On a bike or a walk (core/phrases.ts).
      case 'next': return this.move(1);
      case 'previous': return this.move(-1);
      case 'back': return this.back();
      case 'option': return void this.answerOption(from, n ?? 0, ctx);
      case 'plan': case 'goahead': return void this.modeByVoice(word, from, ctx);
      case 'continue': return void this.carryOn(ctx);
      case 'eclipse': case 'eclipseoff': {
        const on = word === 'eclipse';
        this.s.updateSettings({ eclipse: on });
        if (on) this.pops.drop(p => p.kind !== 'needs');
        this.say(on ? 'Eclipse: only what needs you gets through' : 'Eclipse over', 2600);
        return;
      }
      case 'moving': case 'still': {
        const on = word === 'moving';
        this.s.updateSettings({ moving: on });
        this.say(on ? 'Moving: two lines, one sentence' : 'Still: the whole lens', 2400);
        return;
      }
      case 'debrief': return this.showDebrief(from);
      case 'note': return void this.note(rest, ctx);
      case 'changes': case 'diff': return void this.showChanges(ctx, from, word === 'diff');
      case 'guide': return this.startGuide(false);
      case 'refused': this.say('Auto and Bypass only from the phone: it asks you to think twice', 3400); return;
    }
  }

  statusText(): string {
    const snap = this.snap();
    if (!snap) return 'Not connected to your Mac.';
    const rows = this.rows();
    const parts = rows.map(r => {
      const s = this.session(r.sessionId)!;
      const name = spokenName(r.project);
      switch (r.state) {
        case 'needs': return `${name} ${wantsLine(approvalsOf(snap, s.id)[0] ?? null, s)}.`;
        case 'working': {
          const n = s.agentsRunning ?? 0;
          if (s.state === 'idle') return agentsAtWork(s) ? `${name} has ${agentsLine(agentsAtWork(s))}.` : `${name} is reading its agents’ reports.`;
          return `${name} is ${activitySpoken(s.activity)}${n ? `, with ${agentsLine(n)}` : ''}.`;
        }
        case 'done': if (stoppedAway(s)) return `${name} ${AWAY_LINE}.`; return `${name} ${this.mem.unseen.get(s.id)?.facts || 'finished'}.`;
        case 'error': return `${name} ${ERROR_LINE}.`;
        default: return blockedSpoken(name, s, snap.account, this.now(), snap.providers) ?? (stoppedAway(s) ? `${name} ${AWAY_LINE}.` : `${name} is idle.`);
      }
    });
    // A battery about to run out is worth hearing; one that's fine isn't. Not the Mac's while it's
    // away: its last level would pass for the current one (as on the glance).
    const away = this.s.hub()?.status === 'offline';
    const power = powerSpoken(this.s.glassesBattery?.() ?? null, away ? null : snap.mac.battery ?? null);
    // Away, what follows is what the Mac last said: said so first.
    const since = away ? `Your Mac is out of reach since ${clockTime(this.awaySince() ?? this.now())}. As it last said:` : '';
    const pace = spendMeter(snap.usage, snap.account, this.now())?.limitInMs ?? null;
    return [since, parts.length ? parts.join(' ') : 'All quiet.', away ? '' : limitSpoken(snap.usage, snap.account, this.now(), pace), power, away ? '' : awakeSpoken(snap.mac.awake)].filter(Boolean).join(' ');
  }

  /** Reading aloud is off (Settings, "mute"): taps and "status" stay silent. */
  private get muted(): boolean { return this.s.settings().readAloud === false; }

  private async readAloud(id: string): Promise<void> {
    // Stopped: the window stays where the voice was, as the rest of the reply comes back under it.
    if (this.s.voice.speaking) { this.unfollow(`reply:${id}`); this.s.voice.stop(); this.onChange(); return; }
    let f = this.facts.get(id);
    if (!f) { await this.loadFacts(id); f = this.facts.get(id); }
    const reply = f?.reply || this.session(id)?.lastText || '';
    if (!reply) { this.say('No reply yet'); return; }
    const facts = f ? spokenFacts(f) : '';
    this.reading = id;
    // Text that arrives like speech starts again with the voice, and the window follows it.
    this.refollow(`reply:${id}`);
    this.onChange();
    const who = await this.s.voice.speak(`${speakable(reply)}\n${facts}`.trim(), { sessionId: id });
    if (this.reading === id) {
      // Read, or stopped: the reply shows whole again, the window where the voice left it.
      this.reading = null;
      this.tx.finish(`read:${id}`);
      this.tx.finish(`r:${id}`);
      this.tx.finish(this.replyKey(id));
      this.unfollow(`reply:${id}`);
    }
    if (who === 'locked') this.say(SOUND_LOCKED, 2800);
    else if (!who && this.s.settings().voice !== 'off') this.say('No voice here: set up read-aloud on your phone', 2800);
    this.onChange();
  }

  // ------------------------------------------------------------------ needs you

  private optionCount(item: NonNullable<ReturnType<LensController['needItem']>>, qi: number): number {
    if (item.menu) return item.menu.options.length;
    const q = item.approval?.kind === 'question' ? item.approval.questions?.[qi] : null;
    return q ? q.options.length + (q.multiSelect ? 1 : 0) : 0;
  }

  /** The cursor is on the Back row under a question's or menu's options. */
  private onBackRow(sc: Extract<Screen, { kind: 'needs' }>): boolean {
    const item = this.needItem(sc.id, sc.key);
    if (!item) return false;
    const opts = this.optionCount(item, sc.qi);
    return opts > 0 && sc.cursor >= opts;
  }

  /** Allow, once. [saidAlways]: "always" was spoken; the glasses can't check a fingerprint, so it's taken as Allow once and says so. */
  private async approve(sc: Extract<Screen, { kind: 'needs' }>, saidAlways = false): Promise<void> {
    const hub = this.s.hub();
    const item = this.needItem(sc.id, sc.key);
    if (!hub || !item) return;
    if (this.onBackRow(sc)) { this.say('Swipe to an option first'); return; }
    try {
      if (item.menu) {
        await hub.menu(sc.id, item.menu.id, { choice: sc.cursor });
        return this.answered('Picked', sc);
      }
      const a = item.approval!;
      if (a.kind === 'question' && a.questions?.length) {
        const q = a.questions[sc.qi];
        let answer: string;
        if (q.multiSelect) {
          if (sc.cursor < q.options.length) {
            const picks = sc.picks.includes(sc.cursor) ? sc.picks.filter(x => x !== sc.cursor) : [...sc.picks, sc.cursor];
            this.screen = { ...sc, picks };
            this.onChange();
            return;
          }
          answer = sc.picks.sort((x, y) => x - y).map(i => q.options[i].label).join(', ');
          if (!answer) { this.say('Pick at least one'); return; }
        } else answer = q.options[sc.cursor]?.label ?? '';
        const answers = { ...sc.answers, [q.question]: answer };
        if (sc.qi + 1 < a.questions.length) {
          this.screen = { ...sc, qi: sc.qi + 1, cursor: 0, picks: [], answers };
          this.guardUntil = this.now() + TAP_GUARD_MS;
          this.onChange();
          return;
        }
        await hub.answer(a.id, { decision: 'allow', answers });
        return this.answered('Answered', sc);
      }
      await hub.answer(a.id, { decision: 'allow' });
      this.answered(a.kind === 'plan' ? 'Plan approved' : saidAlways ? 'Allowed once. Always is on your phone.' : 'Allowed', sc);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'resolved') return this.answered('Already answered', sc);
      this.say(lensWords(err), 3000);
    }
  }

  private async deny(sc: Extract<Screen, { kind: 'needs' }>, message = ''): Promise<void> {
    const hub = this.s.hub();
    const item = this.needItem(sc.id, sc.key);
    if (!hub || !item) return;
    try {
      if (item.menu) {
        if (!item.menu.cancel) { this.say('This menu can’t be cancelled; pick an option'); return; }
        await hub.menu(sc.id, item.menu.id, { cancel: true });
        return this.answered('Cancelled', sc);
      }
      const a = item.approval!;
      await hub.answer(a.id, { decision: 'deny', ...(message ? { message } : {}) });
      this.answered(a.kind === 'plan' ? 'Keep planning' : a.kind === 'question' ? 'Skipped' : 'Denied', sc);
    } catch (err) {
      if (err instanceof ApiError && err.code === 'resolved') return this.answered('Already answered', sc);
      this.say(lensWords(err), 3000);
    }
  }

  /**
   * `card` was answered: move on from it, but only if the lens still shows it. The daemon tells
   * every client the approval is resolved before it answers our request, so by now the lens may
   * already have stepped to the next card (fixScreen), and that card must stay.
   */
  private answered(words: string, card: { id: string; key: string }): void {
    this.answeredAt.set(card.key, this.now());
    this.say(words);
    const sc = this.screen;
    if (sc.kind !== 'needs' || sc.key !== card.key) return;
    // Raised on the session's own screen: what else it asks, else back to it (working, or done).
    if (sc.back) {
      const own = this.freshNeed(card.id);
      this.screen = own ? { kind: 'needs', id: card.id, key: own.key, cursor: 0, qi: 0, picks: [], answers: {}, back: true } : { kind: 'session', id: card.id };
      if (own) this.guardUntil = this.now() + TAP_GUARD_MS;
      else this.fixScreen();
      this.onChange();
      return;
    }
    // The daemon may not have removed the approval yet; until then don't show it again.
    const next = this.nextNeedsExcept(card.id, card.key);
    this.screen = next ?? { kind: 'glance' };
    if (next) this.guardUntil = this.now() + TAP_GUARD_MS;
    this.onChange();
  }

  private nextNeedsExcept(prefer: string, key: string): Screen | null {
    const n = this.nextNeeds(prefer);
    if (n && n.kind === 'needs' && n.key === key) {
      const snap = this.snap()!;
      for (const s of activeSessions(snap, this.s.settings(), this.memory(), this.now())) {
        const list = approvalsOf(snap, s.id).filter(a => a.id !== key);
        if (list[0]) return { kind: 'needs', id: s.id, key: list[0].id, cursor: 0, qi: 0, picks: [], answers: {} };
      }
      return null;
    }
    return n;
  }

  // ------------------------------------------------------------------ telescope

  /**
   * Point the telescope at a session: what it builds, from the source named in `ask` or the one the
   * Mac picks. `said`: the address as spoken, for a page on the Mac ("localhost 3000"). `watch`:
   * keep looking every few seconds for a minute.
   */
  async telescope(id: string | null, ask: ScopeAsk = {}, said = '', watch = false): Promise<void> {
    if (!id) { this.say('Which session? Swipe to one first'); return; }
    const hub = this.s.hub();
    if (!hub) return;
    const url = ask.source === 'web' && said ? spokenUrl(said) : undefined;
    const want: ScopeAsk = url ? { ...ask, url } : ask;
    const cur = this.screen;
    // Double tap comes back to the session it was pointed from, else to the glance.
    const back: Screen = cur.kind === 'telescope' ? cur.back : cur.kind === 'session' || cur.kind === 'result' ? cur : { kind: 'glance' };
    const gen = ++this.scopeGen;
    this.go({ kind: 'telescope', id, status: 'loading', message: '', shot: null, live: null, ask: want, zoom: null, watchUntil: watch ? this.now() + WATCH_FOR_MS : 0, back });
    try {
      const shot = await hub.telescope(id, want);
      if (gen !== this.scopeGen || this.screen.kind !== 'telescope') return;
      this.screen = { ...this.screen, status: 'ready', shot };
      this.s.shotReady(shot, null);
      // Picking by itself, the Mac passed over something the owner can fix in a word (Screen
      // Recording, several phones): say how, over whatever it showed instead.
      if (shot.skipped) this.say(shot.skipped.message, 4000);
      else this.say(this.screen.watchUntil ? 'watching' : 'tap to zoom', 2400);
      this.watchLater();
    } catch (err) {
      if (gen !== this.scopeGen || this.screen.kind !== 'telescope') return;
      // A page the Mac can't picture (a Hypernova from before screenshots, or no Chrome there) can
      // still open live on the phone. Not when the Mac looked and found nothing running: the
      // address in the transcript would only be a page that has stopped.
      const missing = err instanceof ApiError && (err.problem === 'missing' || err.problem === 'update_mac');
      const noBrowser = err instanceof ApiError && err.code === 'no_browser';
      let live: string | null = null;
      if ((missing || noBrowser) && (!want.source || want.source === 'web')) {
        const port = (url ? Number(new URL(url).port) : 0) || devServerPort(hub.events(id) ?? await hub.loadEvents(id).catch(() => []));
        if (port) live = await hub.preview({ port }).catch(() => null);
      }
      if (gen !== this.scopeGen || this.screen.kind !== 'telescope') return;
      this.screen = {
        ...this.screen,
        status: missing ? 'missing' : 'error',
        live,
        watchUntil: 0,
        message: missing ? (live ? 'This Mac can’t take pictures yet.' : 'This Mac can’t take pictures yet, and no page is running.') : explain(err),
      };
      if (live) this.s.shotReady(null, live);
      this.onChange();
    }
  }

  /** The picture came, but this WebView couldn't read its pixels: say so instead of blank tiles. */
  shotFailed(url: string): void {
    const sc = this.screen;
    if (sc.kind !== 'telescope' || sc.shot?.url !== url) return;
    this.screen = { ...sc, status: 'error', watchUntil: 0, message: 'The picture didn’t load here. Tap to see it on your phone.' };
    this.onChange();
  }

  /**
   * "Watch": the telescope looks again every few seconds for a minute, at the same source, and any
   * gesture stops it. The Mac answers a picture that hasn't changed with the same link, so the
   * glasses get nothing new until it does, and then only the tiles that changed.
   */
  watch(id: string | null): void {
    const sc = this.screen;
    if (sc.kind !== 'telescope' || sc.id !== id || (sc.status !== 'ready' && sc.status !== 'loading')) { void this.telescope(id, {}, '', true); return; }
    this.screen = { ...sc, watchUntil: this.now() + WATCH_FOR_MS };
    this.say('watching', 1500);
    if (sc.status === 'ready') void this.lookAgain();
  }

  private stopWatching(): void {
    if (this.watchTimer) { clearTimeout(this.watchTimer); this.watchTimer = null; }
    const sc = this.screen;
    if (sc.kind === 'telescope' && sc.watchUntil) { this.screen = { ...sc, watchUntil: 0 }; this.onChange(); }
  }

  private watchLater(): void {
    if (this.watchTimer) { clearTimeout(this.watchTimer); this.watchTimer = null; }
    const sc = this.screen;
    if (sc.kind !== 'telescope' || sc.status !== 'ready' || !sc.watchUntil) return;
    if (this.now() >= sc.watchUntil) { this.screen = { ...sc, watchUntil: 0 }; this.onChange(); return; }
    this.watchTimer = setTimeout(() => { this.watchTimer = null; void this.lookAgain(); }, WATCH_EVERY_MS);
  }

  private async lookAgain(): Promise<void> {
    const sc = this.screen;
    const hub = this.s.hub();
    if (sc.kind !== 'telescope' || !sc.watchUntil || !sc.shot || !hub) return;
    if (this.now() >= sc.watchUntil) { this.screen = { ...sc, watchUntil: 0 }; this.onChange(); return; }
    // The source of the picture on screen: picking again could pick another meanwhile.
    const gen = this.scopeGen;
    // The same device too, and marked as a watching look: the Mac keeps only the newest frames.
    const again: ScopeAsk = { ...sc.ask, source: scopeSourceOf(sc.shot) ?? sc.ask.source, ...(sc.shot.page ? { page: sc.shot.page } : {}), ...(sc.shot.device ? { device: sc.shot.device } : {}), watch: true };
    try {
      const shot = await hub.telescope(sc.id, again);
      const cur = this.screen;
      if (gen !== this.scopeGen || cur.kind !== 'telescope' || !cur.watchUntil) return;
      // The same link: nothing to load or send. The label's age stays at the last change ("20s ago"
      // over a screen that hasn't moved), not the last look; "watching" says it's still looking.
      const same = !!cur.shot && shot.url === cur.shot.url;
      this.screen = { ...cur, shot: same ? { ...shot, at: cur.shot!.at } : shot };
      if (shot.url !== cur.shot?.url) this.s.shotReady(shot, null);
      this.onChange();
      this.watchLater();
    } catch (err) {
      const cur = this.screen;
      if (gen !== this.scopeGen || cur.kind !== 'telescope' || !cur.watchUntil) return;
      // The last picture stays; watching stops, and says why.
      this.screen = { ...cur, watchUntil: 0 };
      this.say(lensWords(err), 3000);
    }
  }

  /** Menu items on the glasses (if the OS shows our menu on a long press). */
  menu(id: number): void {
    this.stopWatching();
    switch (id) {
      case MENU.talk: void this.hold(true); break;
      case MENU.back: this.back(); break;
      case MENU.status: void this.command('status', '', this.screen); break;
      case MENU.read: void this.command('read', '', this.screen); break;
      case MENU.telescope: void this.telescope(this.contextId()); break;
      case MENU.watch: this.watch(this.contextId()); break;
      case MENU.quiet: this.go({ kind: this.screen.kind === 'quiet' ? 'glance' : 'quiet' }); break;
    }
  }

  // ------------------------------------------------------------------ text that scrolls

  /**
   * Where the text `at` names is scrolled, its lines as they are now (`lines`, in a window of
   * `rows`). `arriving`: it grows as words arrive like speech or a voice reads it, so the window
   * follows its newest line, until you scroll back. Another text (`text`) starts at its top.
   */
  private scrolled(at: string, text: unknown, lines: number, rows: number, arriving: boolean): Scroll {
    if (this.reader?.at !== at || this.reader.text !== text) this.reader = { at, text, s: startScroll(lines), rows };
    this.reader.rows = rows;
    this.reader.s = settle(this.reader.s, lines, rows, arriving);
    return this.reader.s;
  }

  /** A swipe on the text `at` names, if it's on the lens: false when it was already at that end. */
  private scroll(at: string, d: 1 | -1): boolean {
    const r = this.reader;
    if (r?.at !== at) return false;
    const { s, moved } = swipe(r.s, d, r.rows);
    r.s = s;
    if (moved) this.onChange();
    return moved;
  }

  /** The text `at` names stays where it is as it grows (the rest shown at once, a voice stopped). */
  private unfollow(at: string): void {
    if (this.reader?.at === at) this.reader.s = { ...this.reader.s, follow: false };
  }

  /** It follows its newest line again (a voice starts reading it). */
  private refollow(at: string): void {
    if (this.reader?.at === at) this.reader.s = { ...this.reader.s, follow: true };
  }

  // ------------------------------------------------------------------ the view

  /** Which planet each glance row gets, and the sun. */
  private orrery(rows: Array<{ id: string; state: PlanetState; focused: boolean }>): OrreryView {
    return {
      sun: this.sun(),
      planets: rows.map(r => ({ state: r.state, focused: r.focused, blipAt: this.blips.get(r.id) })),
    };
  }

  get isQuiet(): boolean { return this.screen.kind === 'quiet'; }
  get screenKind(): Screen['kind'] { return this.screen.kind; }
  get talking(): boolean { return this.screen.kind === 'talk' && this.screen.phase === 'listening'; }

  view(): LensView {
    this.tx.begin();
    let v = this.screenView();
    if (v.kind !== 'message' && v.kind !== 'blank') {
      const band = this.band();
      if (band) (v as { band?: Band | null }).band = band;
    }
    // Moving: two short lines; a card that must be heard (a risky command, a question) is read out.
    if (this.moving) {
      const sc = this.screen;
      // After this view is out: reading starts sound and may say something on the lens.
      if (sc.kind === 'needs') queueMicrotask(() => { if (this.screen === sc && this.needItem(sc.id, sc.key)) this.readCard(sc); });
      v = briefOf(v, this.toast && this.toast.until > this.now() ? this.toast.text : '', this.sayNext());
    }
    // A voice speaks (here, or the phone app for the glasses): "reading aloud" on the header's right,
    // steady in the glasses' own font (a wave rewritten three times a second would be as many
    // Bluetooth writes, and reads as punctuation); in the drawn look a small wave that moves with its
    // loudness, one step a redraw and three a second at most (the Bluetooth budget).
    const look = this.look();
    if (this.s.voice.speaking && v.kind !== 'blank' && v.kind !== 'message') {
      if (drawsView(v, look)) {
        const now = this.now();
        if (!this.waveText || now - this.waveAt >= WAVE_MS) {
          this.waveText = this.wave.next(this.s.voice.level?.() ?? 0);
          this.waveAt = now;
        }
        v.wave = this.waveText;
      } else v.wave = READING_WORD;
    } else if (this.waveText) {
      this.wave.reset();
      this.waveText = '';
    }
    this.arrivals();
    // The look in use (look.ts): what it draws had its lines laid out for it (typeset.ts).
    if (look !== 'native') v.look = look;
    return v;
  }

  /** Type on the lens: the hybrid drawn look, the glasses' built-in font, or (development) all drawn. */
  private look(): Look { return lookOf(this.s.settings()); }

  private screenView(): LensView {
    if (!this.s.paired()) {
      const m = this.s.linkMessage?.();
      return m ? { kind: 'message', title: m.title, sub: m.sub, ...(m.code ? { code: m.code } : {}) } : { kind: 'message', title: 'Pair on your phone', sub: 'Open Hypernova Lens in the Even App and scan the code on your Mac.' };
    }
    const hub = this.s.hub();
    const snap = this.snap();
    if (!snap) {
      const status = hub?.status;
      if (status === 'unpaired') return { kind: 'message', title: 'Pair again on your phone', sub: 'This phone was removed from the Mac.' };
      if (status === 'offline') return { kind: 'message', ...offlineWords(hub?.problem ?? null).lens };
      return { kind: 'message', title: 'Connecting to your Mac…', sub: hub?.macName ?? '' };
    }
    const toast = this.toast && this.toast.until > this.now() ? this.toast.text : '';
    const sc = this.screen;
    // A card whose subject is gone shows the glance until the next change steps back for real.
    if ((sc.kind === 'needs' && !this.needItem(sc.id, sc.key)) || ((sc.kind === 'session' || sc.kind === 'result') && !this.session(sc.id))) return this.glanceView(toast);
    // A session's screen shows what it is doing now, even in the moment before the screen catches
    // up. Waiting on an answer that isn't here yet is still the job going on.
    const cur = sc.kind === 'session' || sc.kind === 'result' ? this.session(sc.id)! : null;
    const live = !!cur && (this.busy(cur) || (sc.kind === 'session' && cur.state === 'needs_you'));
    switch (sc.kind) {
      case 'quiet': return { kind: 'blank' };
      case 'glance': return this.glanceView(toast);
      case 'session': return live ? this.liveView(sc.id, toast) : this.resultView({ kind: 'result', id: sc.id }, toast);
      case 'result': return live ? this.liveView(sc.id, toast) : this.resultView(sc, toast);
      case 'needs': return this.needsView(sc, toast);
      case 'talk': return this.talkView(sc);
      case 'telescope': return this.telescopeView(sc, toast);
      case 'picker': return this.pickerView(sc, toast);
      case 'power': return this.powerView(toast);
      case 'guide': return this.guideView(sc);
      case 'debrief': return this.debriefView(sc, toast);
      case 'diff': return this.diffView(sc, toast);
      case 'note': return this.noteView(sc, toast);
      case 'ask': return this.askView(sc, toast);
    }
  }

  /** While the Mac is away, when it went: what the lens shows is what the Mac said then, at rest. */
  private awaySince(): number | null {
    const hub = this.s.hub();
    return hub?.status === 'offline' ? hub.offlineSince ?? this.now() : null;
  }

  /** "offline since 14:32": the corner while the Mac is away (the rows are what it said then). */
  private offlineCorner(): string {
    const at = this.awaySince();
    return at !== null ? `offline since ${clockTime(at)}` : 'offline';
  }

  /** A mode the session isn't asking in by default, in the phone's words ("plan", "accept edits"); '' for the usual. */
  private modeWord(id: string): string {
    switch (this.session(id)?.permissionMode) {
      case 'plan': return 'plan';
      case 'acceptEdits': return 'accept edits';
      case 'auto': return 'auto';
      case 'bypassPermissions': return 'bypass';
      case 'dontAsk': return 'allowed only';
      default: return '';
    }
  }

  private glanceView(toast: string): LensView {
    const snap = this.snap()!;
    const away = this.awaySince();
    // The Mac away: its rows as it last said them, their times stopped there and their planets at rest.
    const rows = away !== null ? glanceRows(snap, this.s.settings(), this.memory(), away) : this.rows();
    const focus = this.focus(rows);
    const hub = this.s.hub();
    const corner = away !== null ? [this.offlineCorner()] : hub?.status === 'connecting' ? ['connecting…'] : limitCorner(snap.usage, snap.account, this.now());
    // Eclipse says so where the usage is: what doesn't need you won't pop up meanwhile.
    const right = [this.eclipse ? 'eclipse' : '', ...corner].filter(Boolean);
    const f = rows.find(r => r.sessionId === focus);
    // What a tap opens, then where a hold talks: the project that needs you, else a new session.
    const needs = this.needsRoute();
    const tap = focus === NEW_ROW ? 'tap pick a project' : f?.state === 'needs' ? 'tap answer' : f?.state === 'done' ? 'tap reply' : f?.state === 'working' ? 'tap watch' : f ? 'tap open' : '';
    const hold = needs ? `hold talk to ${needs.project}` : 'hold new session';
    const hint = toastOr(toast, tap, hold);
    return {
      kind: 'glance', left: 'hypernova/', clock: clockTime(this.now()), right,
      // The drawn look draws the spending as a meter where the corner's words would be (core/meter.ts).
      meter: hub?.status === 'offline' || hub?.status === 'connecting' ? null : spendMeter(snap.usage, snap.account, this.now()), eclipse: this.eclipse,
      rows: rows.map(r => ({ project: r.project, detail: r.detail, ...(r.detailShort ? { detailShort: r.detailShort } : {}), right: r.right, state: r.state, focused: r.sessionId === focus, tag: r.tag })),
      empty: `All quiet on ${snap.mac.name}.`,
      hint,
      orrery: this.orrery(rows.map(r => ({ id: r.sessionId, state: away !== null ? 'idle' : r.state, focused: r.sessionId === focus }))),
      // The batteries are a card of their own, on request ("battery", or a swipe up from the top).
      newRow: { label: 'New session', focused: focus === NEW_ROW },
    };
  }

  /** A card's header line is its seam: the session's planet sits where the sun sits on the glance. */
  private seam(id: string, state?: PlanetState): OrreryView {
    const snap = this.snap()!;
    const s = this.session(id);
    const st: PlanetState = state ?? (s ? rowState(s, snap, this.memory()) : 'idle');
    return { sun: null, planets: [], seam: { state: st, blipAt: this.blips.get(id) } };
  }

  /** The session on the lens is at work, shown live: its id, else null. */
  private liveId(): string | null {
    const sc = this.screen;
    if (sc.kind !== 'session' && sc.kind !== 'result') return null;
    const s = this.session(sc.id);
    return s && this.busy(s) ? sc.id : null;
  }

  /**
   * A session at work in two boxes: what runs now (its tool, its agents at work) and this turn's
   * output, which follows the newest lines a line at a time, like a teleprompter.
   */
  private liveView(id: string, toast: string): LensView {
    const s = this.session(id)!;
    const hub = this.s.hub();
    this.follow(id);
    const agents = hub?.agents(id) ?? null;
    // A first look: what's there already shows at once (feed); only what comes now arrives like speech.
    if (!this.tele || this.tele.id !== id) this.liveSeen = null;
    const { lines, starts, turn } = this.feed(hub?.events(id) ?? [], agents, id);
    const end = Math.max(0, lines.length - LIVE_OUT_ROWS);
    // A new turn (or the first look) starts at its newest lines: from the start of the newest
    // message when that's within a box of them, scrolling down to the end.
    if (!this.tele || this.tele.id !== id || this.tele.turn !== turn) {
      const first = [...starts].reverse().find(k => k <= end) ?? 0;
      this.tele = { id, turn, top: end - first < LIVE_OUT_ROWS ? first : end, end, auto: true };
    }
    const t = this.tele;
    t.end = end;
    t.top = Math.min(t.top, end);
    if (t.auto && t.top < end) this.scrollLater();
    const now = nowLines(s, agents, LIVE_NOW_ROWS);
    const since = this.mem.workingSince.get(id) ?? s.lastActivityAt;
    // Lines too dense for one text box (font.ts BOX_BYTES): following, the newest stay; read back, the first.
    const window = lines.slice(t.top, t.top + LIVE_OUT_ROWS);
    const output = window.length ? fitBytes(window.join('\n'), BOX_BYTES, t.auto).split('\n') : [];
    return {
      kind: 'live',
      head: [s.state === 'idle' && agentsAtWork(s) ? 'AGENTS' : 'WORKING', sessionName(s)],
      // While the Mac is away this is only what it last said: the corner says so, as the glance's does.
      right: hub?.status === 'offline' ? this.offlineCorner() : [s.queued ? `${s.queued} queued` : '', ago(this.now() - since), this.modeWord(id)].filter(Boolean),
      now: now.lines.length ? now.lines : ['› thinking'],
      more: now.more,
      output: output.length ? output : ['…'],
      hint: toast ? [toast] : this.s.voice.speaking ? READING_HINT : this.tx.behind() ? ['tap show all', 'swipe scroll', 'hold talk'] : t.auto ? ['tap pause', 'swipe scroll', 'hold talk'] : ['paused', 'tap resume', 'swipe scroll'],
      orrery: this.seam(id, this.busy(s) ? 'working' : undefined),
    };
  }

  /** A prompt went from the lens to session `id`: the turn it starts arrives like speech wherever it's first seen. */
  private sentFrom(id: string, text: string): void {
    const now = this.now();
    for (const [k, v] of this.sentHere) if (now - v.at > SENT_HERE_MS) this.sentHere.delete(k);
    this.sentHere.set(id, { at: now, text });
  }

  /** The last turn in `events` is one the lens started: it opens with the prompt sent from here. */
  private turnFromHere(id: string, events: HnEvent[]): boolean {
    const sent = this.sentHere.get(id);
    if (!sent || this.now() - sent.at > SENT_HERE_MS) return false;
    const first = lastTurn(events)[0];
    // Attachments and the like may come before it, so the prompt's start is looked for, not its whole.
    const fold = (t: string) => t.replace(/\s+/g, ' ').trim().toLowerCase();
    return first?.kind === 'user' && fold(first.text).includes(fold(sent.text).slice(0, 60));
  }

  /** The turn's output lines and where each item starts, wrapped once per change (the view is asked for often). */
  private feed(events: HnEvent[], agents: Agent[] | null, id: string): { lines: string[]; starts: number[]; turn: string } {
    let text = 0;
    for (const e of events) if (e.kind === 'assistant' || e.kind === 'user') text += e.text.length;
    const key = `${events.length}|${events[0]?.id}|${events.at(-1)?.id}|${text}|${(agents ?? []).map(a => `${a.id}${a.status}${a.result?.length ?? 0}`).join(',')}`;
    // What was there when the live view opened shows at once; what comes after arrives like speech.
    // A turn started from the lens is all new, though: its words arrive like speech even if they
    // came before you looked (only its agents' reports count, so none is there before it).
    if (this.liveSeen?.id !== id) this.liveSeen = { id, events: null, agents: null };
    const seen = this.liveSeen;
    const mine = this.turnFromHere(id, events);
    const turn = mine ? new Set(lastTurn(events).map(e => `e:${e.id}`)) : null;
    if (!seen.events && this.s.hub()?.events(id)) seen.events = new Set(events.map(e => `e:${e.id}`).filter(k => !turn?.has(k)));
    if (!seen.agents && agents) seen.agents = mine ? new Set() : new Set(agents.map(a => `a:${a.id}`));
    const fresh = (k: string) => { const set = k.startsWith('e:') ? seen.events : seen.agents; return !!set && !set.has(k); };
    const reveal = this.tx.on ? (k: string, plain: string, _at: number, md: string) => this.tx.shown(k, plain, fresh(k), codeAtoms(md, plain)) : undefined;
    // While words arrive, the lines change with time as well as with events: no cache then.
    if (this.feedCache?.key !== key || reveal) {
      const items = feedItems(events, agents, LIVE_TEXT_W, reveal);
      const starts: number[] = [];
      let n = 0;
      for (const it of items) { starts.push(n); n += it.length; }
      // A new first event is a new turn.
      this.feedCache = { key, lines: items.flat(), starts, turn: lastTurn(events)[0]?.id ?? '' };
    }
    return this.feedCache;
  }

  /** The teleprompter's next line, soon. */
  private scrollLater(): void {
    const t = this.tele;
    if (this.teleTimer || !t) return;
    this.teleTimer = setTimeout(() => {
      this.teleTimer = null;
      const cur = this.tele;
      if (!cur || !cur.auto || this.liveId() !== cur.id || cur.top >= cur.end) return;
      // Far behind (a long message landed at once): skip to a box before the end, then scroll.
      cur.top = cur.end - cur.top > 3 * LIVE_OUT_ROWS ? cur.end - LIVE_OUT_ROWS : cur.top + 1;
      this.onChange();
    }, t.end - t.top > LIVE_OUT_ROWS ? SCROLL_FAST_MS : SCROLL_MS);
  }

  /**
   * What the live view needs from the Mac: the turn and the agents, once (the socket keeps them up
   * to date), and only for the session on screen. Without the socket, asked again every few
   * seconds while it works. Never while the Mac is away, and a failure waits before trying again.
   */
  private follow(id: string): void {
    const hub = this.s.hub();
    if (!hub || (hub.status !== 'live' && hub.status !== 'polling')) return;
    const retry = (k: string) => this.now() - (this.failedAt.get(k) ?? 0) > RETRY_MS;
    if (!hub.events(id) && !this.loading.has(id) && retry(`e:${id}`)) void this.loadFacts(id).then(ok => { if (!ok) this.failedAt.set(`e:${id}`, this.now()); });
    if (!hub.agents(id) && retry(`a:${id}`)) void this.loadAgents(id);
    if (hub.status === 'polling' && !this.pollTimer) {
      this.pollTimer = setTimeout(() => {
        this.pollTimer = null;
        const h = this.s.hub();
        const sid = this.liveId();
        if (!h || h.status !== 'polling' || !sid) return;
        void Promise.all([h.loadEvents(sid), h.loadAgents(sid)]).then(() => this.onChange(), err => console.warn('[lens] live', explain(err)));
      }, POLL_MS);
    }
  }

  private async loadAgents(id: string): Promise<void> {
    const hub = this.s.hub();
    if (!hub || this.agentsLoading.has(id)) return;
    this.agentsLoading.add(id);
    try {
      await hub.loadAgents(id);
      this.onChange();
    } catch (err) {
      this.failedAt.set(`a:${id}`, this.now());
      console.warn('[lens] agents', explain(err));
    } finally {
      this.agentsLoading.delete(id);
    }
  }

  /**
   * A finished turn: its reply as one text that scrolls, never pages (core/scroll.ts), over its
   * facts. Text that arrives like speech comes into it from its start, the window following its
   * newest line; read aloud, it follows the voice.
   */
  private resultView(sc: Extract<Screen, { kind: 'result' }>, toast: string): LensView {
    const s = this.session(sc.id)!;
    const f = this.facts.get(sc.id);
    const reply = f?.reply || s.lastText || '';
    const stopped = !!f && (!f.ok || !!f.error);
    // A limit (or the account, or its provider) stopped it: what the phone says, "session limit until
    // 2:00"; or its question waited 10 minutes and was denied by itself: a tap carries it on.
    const blocked = blockedLine(s, this.snap()?.account, this.now(), this.snap()?.providers);
    const away = !blocked && stoppedAway(s);
    const paused = blocked ?? (away ? AWAY_LINE : null);
    const waits = s.blocked?.kind === 'limit' || s.blocked?.kind === 'model' || (s.blocked?.kind === 'provider' && !!s.blocked.resume);
    const head = [paused ? (away || waits ? 'PAUSED' : 'CAN’T REPLY') : stopped ? 'STOPPED' : 'DONE', sessionName(s)];
    // What stopped it comes first, then whatever Claude said.
    const md = stopped && f?.error ? `${f.error}\n\n${reply}` : reply;
    const plain = plainText(md) || (f ? 'Finished.' : '…');
    const atoms = codeAtoms(md, plain);
    const reading = this.reading === sc.id && this.s.voice.speaking;
    let shown = plain;
    if (reading && this.tx.on) {
      // Read aloud while text arrives like speech: as far as the voice has got (it starts again with it).
      const spoken = speakable(reply).length, all = spoken + 1 + (f ? spokenFacts(f).length : 0);
      const p = this.s.voice.progress?.() ?? 0;
      shown = this.tx.synced(`read:${sc.id}`, plain, Math.min(1, (p * all) / Math.max(1, spoken)), atoms);
    } else if (this.tx.on) {
      // New to the lens, the reply arrives from its start (a tap shows it all); one seen whole stays
      // whole. Its words are the live view's stream when the turn is here, so a reply watched there
      // carries on here rather than starting over; until the turn loads, its own, carried over then.
      const key = this.replyKey(sc.id);
      shown = this.tx.shown(key, plain, true, atoms, key === `r:${sc.id}` ? undefined : `r:${sc.id}`);
    }
    // A busy turn has many facts (files, tests, build, agents, time): two lines, so none is cut. The
    // window is as tall as the whole reply needs, up to what the card holds over them: a short reply
    // keeps its facts right under it, and its size never changes while words arrive or as it
    // scrolls, so a step or a swipe is a text update.
    const facts = [paused ?? '', ...(f ? cardFacts(f) : [])].filter(Boolean);
    const type = replyTypeset(this.look());
    const lines = type.readLines(shown);
    const whole = shown === plain ? lines : type.readLines(plain);
    // Fewer lines in view when they're too dense for one text box (font.ts BOX_BYTES).
    const rows = type.replyRows(whole, facts);
    const r = this.scrolled(`reply:${sc.id}`, plain, lines.length, rows, this.tx.on);
    const more = hasMore(r, rows);
    const hint = toast ? [toast] : this.s.voice.speaking ? READING_HINT
      : away ? ['tap continue', more ? 'swipe scroll' : '', 'double tap back'].filter(Boolean)
      : this.tx.behind() ? [this.muted ? 'tap show all' : 'tap read aloud', 'swipe scroll', 'double tap back']
      : more ? (this.muted ? ['swipe read', 'double tap back'] : ['swipe read', 'tap read aloud'])
      : this.muted ? ['double tap back', 'hold talk'] : ['tap read aloud', 'double tap back'];
    return {
      kind: 'card', head, right: this.modeWord(sc.id), lead: '', body: type.window(lines, r.top, rows), facts, factsRows: 2, hint,
      orrery: this.seam(sc.id, stopped ? 'error' : 'done'), role: 'reply', scroll: { top: r.top, rows, lines: lines.length },
    };
  }

  /**
   * The stream a session's reply arrives under (lens/transmit.ts): the live view's for its last
   * message ("e:<event id>") when the hub has the turn, else the card's own ("r:<session id>").
   */
  private replyKey(id: string): string {
    const turn = lastTurn(this.s.hub()?.events(id) ?? []);
    const last = [...turn].reverse().find(e => e.kind === 'assistant' && e.text.trim());
    return last ? `e:${last.id}` : `r:${id}`;
  }

  private needsView(sc: Extract<Screen, { kind: 'needs' }>, toast: string): LensView {
    const s = this.session(sc.id)!;
    const item = this.needItem(sc.id, sc.key)!;
    // How soon it's denied by itself (as the phone's band says it), and the mode when it isn't the usual;
    // a terminal's menu has no such clock: how long it has waited.
    const mode = this.modeWord(sc.id);
    const age = [item.approval ? autoDeny(item.approval.expiresAt, this.now()) : ago(this.now() - s.lastActivityAt), mode].filter(Boolean);
    if (item.menu) {
      const m = item.menu;
      return {
        kind: 'choice', head: ['MENU', s.projectName], right: age, prompt: [m.title, ...m.body].filter(Boolean).join(' '),
        options: [...m.options.map(o => ({ label: o.detail ? `${o.label} — ${o.detail}` : o.label, mark: '' as const })), BACK_ROW], cursor: sc.cursor,
        hint: toastOr(toast, 'swipe choose', 'tap pick', m.cancel && 'double tap cancel'), orrery: this.seam(sc.id, 'needs'),
      };
    }
    const a = item.approval!;
    if (a.kind === 'question' && a.questions?.length) {
      const q = a.questions[sc.qi];
      const opts: Array<{ label: Parts; mark: '' | '○' | '●' }> = q.options.map((o, i) => ({ label: o.label, mark: (q.multiSelect ? (sc.picks.includes(i) ? '●' : '○') : '') as '' | '○' | '●' }));
      if (q.multiSelect) opts.push({ label: 'Done', mark: '' });
      opts.push(BACK_ROW);
      return {
        kind: 'choice', head: ['QUESTION', s.projectName], right: a.questions.length > 1 ? [`${sc.qi + 1} of ${a.questions.length}`, ...age] : age,
        prompt: q.question, options: opts, cursor: sc.cursor,
        hint: toastOr(toast, 'swipe choose', q.multiSelect ? 'tap tick' : 'tap pick', 'double tap skip'),
        orrery: this.seam(sc.id, 'needs'),
      };
    }
    if (a.kind === 'plan') {
      const sum = summaryLines(a.plan ?? '', CARD_TEXT_W, 3);
      const steps = sum.steps ? `${sum.steps} steps` : '';
      const card = { kind: 'card' as const, head: ['PLAN', s.projectName], right: age, lead: '',
        hint: toast ? [toast] : sc.reason ? [`double tap keep planning: “${sc.reason}”`] : ['tap approve', 'double tap keep planning'], orrery: this.seam(sc.id, 'needs') };
      // Three lines first; a swipe reads it whole, one text that scrolls over a line saying how far
      // there is to go (its box the same size throughout: a swipe is a text update).
      if (!sc.whole) return { ...card, body: sum.lines, facts: [steps, 'swipe to read it all'].filter(Boolean) };
      const lines = textLines(plainText(a.plan ?? ''), READ_W);
      const rows = fitRows(lines, Math.min(cardRoom(false, 1), Math.max(1, lines.length)), CARD_TEXT_W);
      const r = this.scrolled(`plan:${sc.key}`, a.plan ?? '', lines.length, rows, false);
      const facts = hasMore(r, rows) ? [steps, 'swipe to read on'].filter(Boolean) : ['the end: swipe on to answer later'];
      return { ...card, body: windowLines(lines, r.top, rows, CARD_TEXT_W), facts };
    }
    const d = approvalDetail(a);
    // A command that can't be taken back says so under it (where Claude's reason goes when it gives
    // one), and how to allow it by voice.
    const risk = approvalRisk(a);
    const facts = a.reason ? [a.reason] : [];
    const warn = risk ? `careful: ${risk.what} can’t be undone` : undefined;
    const body = approvalTypeset(this.look()).command(d.detail, facts, warn);
    const hint = sc.reason ? [`double tap deny: “${sc.reason}”`] : risk ? ['tap allow', `say “approve ${risk.verb}”`, 'double tap deny'] : ['tap allow', 'double tap deny', 'swipe back'];
    return {
      // An agent that asks says so: "survey:parser wants to run". The reason, then what can't be undone on its own line.
      kind: 'card', head: ['NEEDS YOU', s.projectName], right: age, lead: a.agentLabel ? `${a.agentLabel} ${d.lead}` : d.lead, body, facts,
      ...(warn ? { warn, factsRows: facts.length + 1 } : {}),
      hint: toast ? [toast] : hint, orrery: this.seam(sc.id, 'needs'), role: 'approval',
    };
  }

  private talkView(sc: Extract<Screen, { kind: 'talk' }>): LensView {
    const to = this.routeWords(sc.route, false);
    const seam = (state: PlanetState): OrreryView => ({ sun: null, planets: [], seam: { state } });
    switch (sc.phase) {
      case 'listening': case 'finishing': {
        // A project named while speaking ("in hyperscribe, …") shows where it will go at once.
        const snap = this.snap();
        const heard = sc.interim ? parseUtterance(sc.interim, { sessions: snap?.sessions ?? [], projects: snap?.projects ?? [], focusId: this.contextId(sc.from), needs: sc.from.kind === 'needs' }) : null;
        const named = heard?.kind === 'prompt' && heard.route ? this.routeWords(heard.route, false) : to;
        const hint = sc.phase === 'finishing' ? 'getting the last words…' : sc.handsFree ? 'tap when done' : 'let go when done';
        return { kind: 'talk', head: ['LISTENING'], right: named, text: [sc.draft, sc.interim].filter(Boolean).join(' ') || '…', live: true, hint: [hint], orrery: seam('listening') };
      }
      case 'review': {
        // The whole draft, never cut: a long one scrolls, from its end (the words just said).
        const lines = this.talkLines(sc.draft);
        const long = lines.length > this.talkRows(lines);
        const sendIn = sc.sendAt ? Math.max(1, Math.ceil((sc.sendAt - this.now()) / 1000)) : 0;
        // Paused by a swipe: it waits, and says what sends it.
        const right = sendIn ? `sending in ${sendIn}` : sc.paused ? 'tap to send' : sc.cut ? 'last words missing?' : sc.routes.length > 1 && !long ? 'swipe ↑↓' : '';
        const hint = sendIn ? ['tap send now', 'double tap cancel', long ? 'swipe read' : ''].filter(Boolean)
          // Nothing is made on the Mac before it goes: Nebula's folder neither.
          : sc.route?.kind === 'nebula' ? ['tap make its folder & send', 'double tap discard']
          : sc.paused ? ['tap send', 'double tap cancel', long ? 'swipe read' : ''].filter(Boolean) : ['tap send', 'double tap discard', 'hold add'];
        return {
          kind: 'talk', head: this.routeWords(sc.route, true, sc.draft), right, text: sc.draft, live: false, hint, orrery: seam('listening'),
          lines: this.talkWindow(sc, lines), ...(sendIn ? { sendIn } : {}),
        };
      }
      // Going, and gone: the same lines stay where they were, never cut short (as if half of it went).
      case 'sending':
        return { kind: 'talk', head: ['SENDING'], right: to, text: sc.draft, live: false, hint: [], orrery: seam('working'), lines: this.talkWindow(sc, this.talkLines(sc.draft)) };
      case 'done':
        return { kind: 'talk', head: sc.said ?? [], right: [], text: sc.draft, live: false, hint: [], orrery: seam('done'), lines: this.talkWindow(sc, this.talkLines(sc.draft)) };
      case 'error':
        return { kind: 'talk', head: sc.draft ? ['NOT SENT', sc.route?.project ?? ''] : ['CAN’T LISTEN'], right: [], text: sc.draft ? `${sc.message}\n${sc.draft}` : sc.message, live: false,
          hint: sc.draft ? [sc.replace ? 'tap replace and send' : 'tap retry', 'double tap discard'] : ['double tap back'], orrery: seam('error') };
    }
  }

  /** "TELESCOPE   hypernova   emulator" over the picture, how old it is on the right; while there's none, why. */
  private telescopeView(sc: Extract<Screen, { kind: 'telescope' }>, toast: string): LensView {
    const s = this.session(sc.id);
    const label = scopeLabel(s?.projectName ?? '', sourceWord(sc.shot?.source ?? sc.ask.source), sc.shot ? sc.shot.at : null, this.now());
    const watching = sc.watchUntil > this.now();
    const picture = sc.status === 'ready' && !!sc.shot;
    return {
      kind: 'telescope', head: label.head,
      right: toast ? [toast] : [label.age, watching ? 'watching' : picture && sc.zoom !== null ? `${sc.zoom + 1} of 4` : ''].filter(Boolean),
      status: sc.status === 'loading' ? 'Pointing the telescope…' : sc.status === 'ready' ? '' : sc.message,
      hint: sc.status !== 'loading' && (sc.live || sc.shot) ? ['tap open on phone', 'double tap back'] : ['double tap back'],
      picture, loading: sc.status === 'loading',
      key: sc.shot?.url ?? '', zoom: picture ? sc.zoom : null, crisp: sc.shot?.source === 'terminal',
    };
  }

  // ------------------------------------------------------------------ new sessions from the glasses

  /** Where a hold on the main screen talks: the project that needs you, else a new session in Nebula. */
  private homeRoute(): Route {
    return this.needsRoute() ?? NEBULA_ROUTE;
  }

  /** The first session that needs you (an approval, question, plan or menu: not just a reply waiting). */
  private needsRoute(): Extract<Route, { kind: 'session' }> | null {
    const snap = this.snap();
    if (!snap) return null;
    const s = activeSessions(snap, this.s.settings(), this.memory(), this.now()).find(x => rowState(x, snap, this.memory()) === 'needs');
    return s ? { kind: 'session', sessionId: s.id, project: s.projectName } : null;
  }

  /**
   * Where the prompt goes, in the talk screen's words, in parts: ["TO", "0stems", "NEEDS YOU"],
   * ["NEW", "Nebula/landing-page"] (the folder named from what was said, whole: it's what the tap
   * makes) on review; ["to 0stems", "needs you"], ["new session"] while listening.
   */
  private routeWords(r: Route | null, review: boolean, draft = ''): string[] {
    if (!r) return review ? ['TO', '?'] : [];
    if (r.kind === 'nebula') return review ? ['NEW', `Nebula/${draft.trim() ? folderName(draft) : '…'}`] : ['new session'];
    if (r.kind === 'new') return review ? ['NEW SESSION', r.project] : [`new session in ${r.project}`];
    const needs = !!this.needItem(r.sessionId);
    return review ? ['TO', r.project, needs ? 'NEEDS YOU' : ''].filter(Boolean) : [`to ${r.project}`, needs ? 'needs you' : ''].filter(Boolean);
  }

  /**
   * A prompt for a session that asks something first (its terminal takes nothing until it's answered):
   * its card comes up to read, and what was said goes with a deny, as "deny, …" would send it.
   */
  private askFirst(id: string, text: string): boolean {
    const item = this.needItem(id);
    if (!item) return false;
    this.open(id);
    const card = this.screen.kind === 'needs' && this.screen.id === id ? this.screen : null;
    const kind = card ? this.needItem(id, card.key)?.approval?.kind : undefined;
    if (card && (kind === 'tool' || kind === 'plan')) {
      this.screen = { ...card, reason: text };
      this.say('It asks first: double tap denies with what you said', 3600);
    } else this.say('It asks first: answer it, then talk', 3000);
    return true;
  }

  /** The picker's rows: a new project in Nebula, then the projects, newest first (Nebula itself isn't one). */
  private pickerOptions(): Array<{ label: string[]; route: Route }> {
    const projects = [...(this.snap()?.projects ?? [])]
      .filter(p => !/\/Nebula\/?$/.test(p.path))
      .sort((a, b) => (b.lastActivityAt ?? 0) - (a.lastActivityAt ?? 0))
      .slice(0, 7);
    return [{ label: NEW_PROJECT_ROW, route: NEBULA_ROUTE }, ...projects.map(p => ({ label: [p.name], route: { kind: 'new' as const, projectPath: p.path, project: p.name } }))];
  }

  /** "+ New session" (or a picker) by tap. */
  showPicker(): void { this.go({ kind: 'picker', cursor: 0 }); }

  /** A project picked: talk, hands-free, to a new session there. Its folder (in Nebula) is made only when it's sent. */
  private pick(cursor: number): void {
    const o = this.pickerOptions()[cursor];
    if (o) void this.hold(true, o.route);
  }

  private pickerView(sc: Extract<Screen, { kind: 'picker' }>, toast: string): LensView {
    const opts = this.pickerOptions();
    return {
      kind: 'choice', head: ['NEW SESSION'], right: [], prompt: 'Where should it start?',
      options: opts.map(o => ({ label: o.label, mark: '' as const })), cursor: Math.min(sc.cursor, opts.length - 1),
      hint: toast ? [toast] : sc.cursor === 0 ? ['tap talk', 'a folder is made when you send', 'double tap back'] : ['swipe choose', 'tap talk', 'double tap back'],
      orrery: { sun: null, planets: [], seam: { state: 'idle' } },
    };
  }

  // ------------------------------------------------------------------ the battery card, Stay awake

  /** The battery card, from wherever you are; double tap comes back. */
  showPower(): void {
    const sc = this.screen;
    this.go({ kind: 'power', back: sc.kind === 'power' ? sc.back : sc.kind === 'talk' ? sc.from : sc });
  }

  private powerView(toast: string): LensView {
    const snap = this.snap()!;
    const away = this.s.hub()?.status === 'offline';
    // The Mac's level only while it's there to say it: an old one would pass for the current one.
    const rows = powerRows(this.s.glassesBattery?.() ?? null, away ? null : snap.mac.battery ?? null);
    const awake = away ? null : awakeLine(snap.mac.awake, this.now());
    return {
      kind: 'power', head: ['BATTERIES'], right: [clockTime(this.now())], rows, awake,
      empty: 'No battery to show: the glasses haven’t said yet',
      hint: toast ? [toast] : snap.mac.awake ? ['double tap back', 'say “stay awake” or “let it sleep”'] : ['double tap back'],
    };
  }

  /**
   * Backpack ends in about ten minutes: a pop-up offers "+2 h" (a tap on it, or "extend"). It is the
   * Mac's own warning kind, so Eclipse lets it through; it goes when Backpack's end moves on or passes.
   */
  private backpackEnding(until: number): void {
    const mins = Math.max(1, Math.round((until - this.now()) / 60_000));
    this.pop({ key: 'extend', kind: 'sleep', head: [`BACKPACK ENDS IN ${mins} MIN`], detail: 'Tap or say “extend” for 2 more hours', short: 'Backpack ends soon', ttl: null, until: until || undefined });
  }

  /** "Extend" (or a tap on its pop-up): Backpack's end 2 hours on. The Mac's other guards still apply. */
  private async extendBackpack(): Promise<void> {
    const hub = this.s.hub();
    this.pops.drop(p => p.key === 'extend');
    if (!hub?.extendAwake) { this.say('This Mac’s Hypernova can’t extend Backpack yet', 2800); return; }
    const cur = this.snap()?.mac.awake;
    if (cur && cur.mode !== 'backpack') { this.say('Backpack isn’t on', 2400); return; }
    try {
      await hub.extendAwake();
      this.say('Backpack: 2 more hours', 2600);
    } catch (err) { this.say(lensWords(err), 3000); }
  }

  /** "Stay awake" and "let it sleep": Stay awake's mode on the Mac. */
  private async stayAwake(word: 'awake' | 'sleep'): Promise<void> {
    const hub = this.s.hub();
    const cur = this.snap()?.mac.awake;
    if (!hub?.setAwake || cur === undefined) { this.say('This Mac’s Hypernova can’t keep it awake yet', 2800); return; }
    const mode: MacAwake['mode'] = awakeModeFor(word, cur);
    try {
      await hub.setAwake(mode);
      this.say(awakeToast(word, mode), 2600);
    } catch (err) { this.say(lensWords(err), 3000); }
  }

  // ------------------------------------------------------------------ limits on the sun

  /** The sun: the 5-hour window as the accretion disk, quiescent at the limit, the week's low state, an eclipse. */
  private sun(): OrreryView['sun'] {
    const snap = this.snap();
    const now = this.now();
    // The sun follows the agent that leads: Claude Code's usage, or Codex's on a Mac without Claude Code.
    const lead = leading(snap);
    const mode = sunMode(lead.usage, lead.account, now);
    // A limit lifting, seen as it happens (the reset passing on the clock, or the Mac saying so): the outburst.
    if ((this.sunWas === 'quiet' || this.sunWas === 'low') && mode === 'disk') this.sunFlashAt = now;
    this.sunWas = mode;
    const u = lead.usage?.fiveHour ?? (lead.codex ? lead.usage?.sevenDay : undefined);
    if (!u && mode === 'disk') return null;
    const flash = this.sunFlashAt !== undefined && now - this.sunFlashAt < 2000 ? this.sunFlashAt : undefined;
    return { pct: mode === 'quiet' ? 100 : u?.pct ?? 0, limit: mode === 'quiet', mode, flashAt: flash };
  }

  // ------------------------------------------------------------------ pop-ups

  /** Pop-ups on/off by kind (Settings); a kind added since they were saved takes its default. */
  private popOn(kind: Pop['kind']): boolean { return this.s.settings().popups?.[kind] ?? DEFAULT_POPS[kind]; }

  private pop(p: Omit<Pop, 'at'>): void {
    if (!this.popOn(p.kind)) return;
    // Eclipse: only what needs you gets through, and the Mac's two warnings (it running low, it
    // about to sleep): they matter whatever else is quiet (owner, 2026-10-16).
    if (this.eclipse && p.kind !== 'needs' && p.kind !== 'battery' && p.kind !== 'sleep') return;
    this.pops.add({ ...p, at: this.now() });
    // Moving: every pop-up chimes (what needs you and a finished turn already do).
    if (this.moving && (p.kind === 'limit' || p.kind === 'battery' || p.kind === 'sleep')) this.s.voice.blip('needs');
    this.onChange();
  }

  /** Something needs you while the lens shows something else (not that session's own screens). */
  private popNeeds(a: Approval): void {
    const sc = this.screen;
    if ((sc.kind === 'session' || sc.kind === 'result' || sc.kind === 'needs') && sc.id === a.sessionId) return;
    const s = this.session(a.sessionId);
    const name = s?.projectName ?? 'a session';
    this.pop({ key: `needs:${a.id}`, kind: 'needs', head: ['NEEDS YOU', name], detail: `${a.agentLabel ? `${a.agentLabel} ` : ''}${wantsLine(a, s ?? undefined)}`, short: `${name} needs you`, who: name, sessionId: a.sessionId, ttl: null, expiresAt: a.expiresAt });
  }

  /** A turn ended while the lens showed something else: done, stopped, or paused at a limit. */
  private popFinished(id: string): void {
    const s = this.session(id);
    if (!s || this.busy(s)) return;
    const sc = this.screen;
    if ((sc.kind === 'session' || sc.kind === 'result') && sc.id === id) return;
    const f = this.facts.get(id);
    // Its question waited 10 minutes and was denied by itself: paused, waiting for "continue".
    const paused = blockedLine(s, this.snap()?.account, this.now(), this.snap()?.providers) ?? (stoppedAway(s) ? AWAY_LINE : null);
    const stopped = !!paused || s.state === 'error' || (!!f && turnStopped(f));
    const name = s.projectName;
    const detail = paused ?? (stopped ? f?.error ?? ERROR_LINE : firstSentence(f?.reply || s.lastText || '') || (f ? doneLine(f) : 'finished'));
    this.pop({ key: `done:${id}`, kind: stopped ? 'stopped' : 'done', head: [paused ? 'PAUSED' : stopped ? 'STOPPED' : 'DONE', name], detail, short: `${name} ${paused ? 'paused' : stopped ? 'stopped' : 'finished'}`,
      sessionId: id, ttl: stopped ? 10_000 : DONE_MS });
  }

  /** The Mac will let itself sleep soon (Stay awake's guards). */
  private sleepSoon(inSeconds: number, reason: 'battery' | 'hot' | 'time', percent: number | null = null, keptBy: string | null = null): void {
    const mins = Math.max(1, Math.round(inSeconds / 60));
    // Another app (Amphetamine) has sleep turned off too: Hypernova letting go won't put the Mac to
    // sleep, so say who still keeps it awake rather than promise a sleep that won't come.
    if (keptBy) {
      const why = reason === 'battery' ? `Battery ${percent !== null ? `${Math.round(percent)}%` : 'low'}` : reason === 'hot' ? 'Running hot' : 'Hours awake';
      this.pop({ key: 'sleep', kind: 'sleep', head: [`HYPERNOVA LETS GO IN ${mins} MIN`], detail: `${why}: ${keptBy} still keeps the Mac awake`, short: `${keptBy} keeps the Mac awake`, ttl: null, until: this.now() + Math.max(5000, inSeconds * 1000) });
      return;
    }
    const detail = reason === 'battery' ? `Its battery is ${percent !== null ? `at ${Math.round(percent)}%` : 'low'}: plug it in to keep it awake`
      : reason === 'hot' ? 'It’s running hot: give it some air'
      : 'It has been kept awake for hours: say “stay awake” for more';
    this.pop({ key: 'sleep', kind: 'sleep', head: [`MAC SLEEPS IN ${mins} MIN`], detail, short: 'Mac sleeps soon', ttl: null, until: this.now() + Math.max(5000, inSeconds * 1000) });
  }

  /**
   * What changed on the Mac that pops up by itself: a limit hit or lifted (once per episode), the
   * Mac's battery at 20% (once per discharge). Pop-ups whose subject went away go too.
   */
  private watchPops(snap: Snapshot): void {
    const now = this.now();
    this.pops.drop(p => p.kind === 'needs' && !!p.sessionId && !this.needItem(p.sessionId));
    const key = limitKey(snap.usage, snap.account, now);
    if (this.limitWas !== undefined && key !== this.limitWas) {
      if (key) {
        const lim = limitNow(snap.usage, snap.account, now);
        const ends = limitEnds(snap.usage, snap.account, now);
        const auto = snap.account?.limit?.autoResumeAt;
        const label = lim === 'week' ? 'Weekly limit' : limitLabel(snap.account?.limit);
        const detail = auto && auto > now ? `Waiting sessions carry on by themselves ${atClock(auto, now)}` : ends ? `Claude is back ${atClock(ends, now)}` : 'Claude can’t reply until it resets';
        this.pop({ key: 'limit', kind: 'limit', head: [`${label.toUpperCase()} REACHED`], detail, short: 'limit reached', ttl: 10_000 });
      } else if (this.limitWas) {
        const waiting = snap.sessions.filter(x => x.blocked?.kind === 'limit').length;
        this.pop({ key: 'limit', kind: 'limit', head: ['LIMIT LIFTED'], detail: waiting ? `Claude can reply again. ${waiting} waiting session${waiting === 1 ? ' carries' : 's carry'} on.` : 'Claude can reply again', short: 'limit lifted', ttl: 10_000 });
      }
    }
    this.limitWas = key;
    this.watchProviders(snap);
    // Backpack's end moved on (extended here or on the Mac) or it ended: the offer is old.
    const aw = snap.mac.awake;
    if (aw) this.pops.drop(p => p.key === 'extend' && (aw.mode !== 'backpack' || (aw.until != null && p.until !== undefined && aw.until > p.until + 1000)));
    const b = this.s.hub()?.status === 'offline' ? null : snap.mac.battery ?? null;
    const low = !!b && b.percent <= LOW_BATTERY && !b.charging && !b.onAC;
    if (low && !this.batteryPopped) {
      this.batteryPopped = true;
      // The phone's own notice, in a sentence.
      const said = `Your Mac is at ${Math.round(b!.percent)}%. Plug it in or its sessions stop.`;
      this.pop({ key: 'battery', kind: 'battery', head: ['MAC BATTERY', `${Math.round(b!.percent)}%`], detail: said, line: said, short: `Mac at ${Math.round(b!.percent)}%`, ttl: null });
    }
    // Plugged in, or charged a little: the next time it runs low pops up again.
    if (b && (b.charging || b.onAC || b.percent > LOW_BATTERY + 5)) { this.batteryPopped = false; this.pops.drop(p => p.kind === 'battery'); }
  }

  /**
   * Another provider's trouble pops up once per episode (it running out of credits, its key refused,
   * rate limited, not answering), as the phone's notice does, in the phone's words; its credit coming
   * back too, while sessions wait on it. One already on when the lens first looks isn't news.
   */
  private watchProviders(snap: Snapshot): void {
    const list = (snap.providers ?? []).filter(p => !p.builtIn);
    const now = this.now();
    const was = this.providerWas;
    const next = new Map<string, string>();
    for (const p of list) {
      const key = p.status ? `${p.status.state}:${p.status.since ?? ''}` : '';
      next.set(p.id, key);
      if (!was || was.get(p.id) === key) continue;
      const paused = snap.sessions.filter(x => x.providerId === p.id && x.blocked?.kind === 'provider').length;
      if (p.status) {
        this.pop({ key: `provider:${p.id}`, kind: 'limit', head: providerHead(p), detail: providerDetail(p, paused, now), short: providerSentence(p, now), ttl: 10_000 });
      } else if (was.get(p.id)?.startsWith('no_credit') && paused) {
        this.pop({ key: `provider:${p.id}`, kind: 'limit', head: ['CREDIT AGAIN', p.name], detail: 'Paused sessions can carry on.', short: `${p.name} has credit again`, ttl: 10_000 });
      }
    }
    this.providerWas = next;
  }

  /** Hypernova on the phone stopped answering (the Mac's rows stay as they were): a band says it, until it's back. */
  private stoppedBand(): Band | null {
    const hub = this.s.hub();
    if (hub?.status !== 'offline' || hub.problem !== WORDS.no_app) return null;
    return { head: ['HYPERNOVA STOPPED'], right: '', detail: 'Open Hypernova on your phone', hint: [], line: 'Hypernova stopped: open it on your phone' };
  }

  /** The band on the lens now: nothing while you talk or are read to (they wait), or while it's quiet. */
  private band(): Band | null {
    const sc = this.screen;
    // The guide's steps hold them too (its offer doesn't: you may not take it up for a while).
    const held = sc.kind === 'talk' || sc.kind === 'quiet' || (sc.kind === 'guide' && sc.g.step >= 0) || this.s.voice.speaking;
    const list = this.pops.visible(this.now(), held);
    const w = bandWords(list, this.now()) ?? (held ? null : this.stoppedBand());
    const key = list[0]?.key ?? '';
    // A new band (or a new one leading it): taps for a moment were meant for what was there before.
    if (key !== this.bandKey) { this.bandKey = key; if (key) this.bandAt = this.now(); }
    return w;
  }

  /** The leading pop-up, if the band is on the lens. */
  private bandTop(): Pop | null {
    if (!this.bandKey) return null;
    return this.pops.all().find(p => p.key === this.bandKey) ?? null;
  }

  /** A tap with the band on the lens opens what it's about. */
  private bandTap(): boolean {
    const top = this.bandTop();
    if (!top) return false;
    if (this.now() - this.bandAt < TAP_GUARD_MS) return true;
    this.pops.drop(p => p.key === top.key);
    this.bandKey = '';
    switch (top.kind) {
      case 'done': case 'stopped': if (top.sessionId && this.session(top.sessionId)) { this.reader = null; this.go({ kind: 'result', id: top.sessionId }); } break;
      case 'limit': this.go({ kind: 'glance' }); break;
      case 'sleep': if (top.key === 'extend') { void this.extendBackpack(); break; } this.showPower(); break;
      case 'battery': this.showPower(); break;
      case 'needs': if (top.key.startsWith('ask:')) { this.openAsk(top.key.slice(4)); break; } if (top.sessionId) this.open(top.sessionId); break;
      case 'note': { const id = noteIdOf(top.key); if (id && this.notes.has(id)) { this.reader = null; this.go({ kind: 'note', noteId: id, back: { kind: 'glance' } }); } break; }
    }
    this.onChange();
    return true;
  }

  /** A swipe with the band on the lens puts it away: all of it, merged as it is. */
  private bandSwipe(): boolean {
    if (!this.bandTop()) return false;
    const shown = new Set(this.pops.all().filter(p => p.shownAt !== undefined).map(p => p.key));
    this.pops.drop(p => shown.has(p.key));
    this.bandKey = '';
    this.onChange();
    return true;
  }

  /** Redraws for what arrives by itself: the next words of text arriving like speech, a band fading. */
  private arrivals(): void {
    const now = this.now();
    // While a voice speaks, a few redraws a second: the wave moves, and a card read aloud keeps up with it.
    const next = this.tx.nextDue();
    const due = this.s.voice.speaking ? Math.min(now + WAVE_MS, next ?? Infinity) : next;
    if (due !== null && (!this.txTimer || due < this.txDue)) {
      if (this.txTimer) clearTimeout(this.txTimer);
      this.txDue = due;
      this.txTimer = setTimeout(() => { this.txTimer = null; this.onChange(); }, Math.max(30, due - now));
    }
    this.askTick();
    const fade = this.pops.nextFade();
    if (fade !== null && !this.popTimer) this.popTimer = setTimeout(() => { this.popTimer = null; this.onChange(); }, Math.max(30, fade - now + 20));
  }

  // ------------------------------------------------------------------ on a bike or a walk

  /** Moving ("moving" / "still"): two short lines, one sentence per result, risky commands by voice only. */
  private get moving(): boolean { return this.s.settings().moving === true; }
  /** Eclipse: only what needs you gets through. */
  private get eclipse(): boolean { return this.s.settings().eclipse === true; }

  /** The card's command, if it can't be taken back (core/risk.ts). */
  private riskOf(sc: Extract<Screen, { kind: 'needs' }>) { return approvalRisk(this.needItem(sc.id, sc.key)?.approval); }

  /**
   * May a voice approval go through? Any card that isn't risky, yes. A risky one only by its own verb
   * ("approve push"), never as "always", and while moving only once its reading was heard to
   * the end (else it's read now, and you say it again after).
   */
  private riskCleared(sc: Extract<Screen, { kind: 'needs' }>, word: VoiceWord, verb: string): boolean {
    const risk = this.riskOf(sc);
    if (!risk) return true;
    if (word === 'always') { this.say(`Risky: allow it once, with “approve ${risk.verb}”`, 3000); return false; }
    if (!verb) { this.say(`Say “approve ${risk.verb}” for this one`, 3000); this.readCard(sc); return false; }
    if (!verbMatches(risk, verb)) { this.say(`This one would ${risk.what}: say “approve ${risk.verb}”`, 3200); return false; }
    if (this.moving && !this.heard.has(sc.key)) {
      // Muted, it can't be heard: unmute, or stop and read it (still, a tap allows it).
      if (this.muted) { this.say('Hear it first: say “unmute”, or “still” and read it', 3200); return false; }
      this.readCard(sc, true);
      this.say('Listen first, then say it again', 3000);
      return false;
    }
    return true;
  }

  /**
   * Moving: a card you must hear before answering is read out, once: a risky command with the verb
   * that allows it, a question or a terminal menu with its numbered options. `again`: once more (you
   * asked to allow it before it was heard). A risky command counts as heard when its reading ends by
   * itself, not stopped by a hold or cut off by another.
   */
  private readCard(sc: Extract<Screen, { kind: 'needs' }>, again = false): void {
    if (!this.moving || (!again && this.readOut.has(sc.key))) return;
    const item = this.needItem(sc.id, sc.key);
    if (!item) return;
    const name = spokenName(this.session(sc.id)?.projectName ?? 'A session');
    const a = item.approval;
    const risk = approvalRisk(a);
    const q = a?.kind === 'question' ? a.questions?.[sc.qi] ?? null : null;
    const text = risk && a ? riskSpoken(name, risk, approvalDetail(a).detail)
      : q ? optionsSpoken(`${name} asks: ${q.question}`, q.options.map(o => o.label), q.multiSelect)
      : item.menu ? optionsSpoken(`${name} shows a menu${item.menu.title ? `: ${item.menu.title}` : ''}`, item.menu.options.map(o => o.label), false)
      : '';
    if (!text) return;
    this.readOut.add(sc.key);
    if (this.muted) { this.say('Read aloud is off: say “unmute” to hear it', 3000); return; }
    const key = sc.key;
    const voice = this.s.voice;
    const reading = voice.speakWhole ? voice.speakWhole(text, { sessionId: sc.id }) : voice.speak(text).then(who => ({ who, whole: !!who }));
    void reading.then(({ who, whole }) => {
      if (who === 'locked') this.say(SOUND_LOCKED, 2800);
      else if (whole && risk) this.heard.add(key);
      this.onChange();
    });
  }

  /** Moving: what to say next on the card in front of you, for its second line. */
  private sayNext(): string {
    const sc = this.screen;
    if (sc.kind === 'diff' && sc.diff) return 'say “still” to read it';
    if (sc.kind !== 'needs') return '';
    const item = this.needItem(sc.id, sc.key);
    if (!item) return '';
    const risk = approvalRisk(item.approval);
    if (risk) return `${risk.what}: say “approve ${risk.verb}”`;
    const a = item.approval;
    const n = item.menu ? item.menu.options.length : a?.kind === 'question' ? a.questions?.[sc.qi]?.options.length ?? 0 : 0;
    if (n) return `say ${sayRange(n)}`;
    return a?.kind === 'plan' ? 'say “go ahead” or “plan first”' : '';
  }

  /** Moving: a finished turn in one sentence, into the earbuds. */
  private speakResult(id: string): void {
    if (!this.moving || this.eclipse || this.muted) return;
    // Not over your own voice, nor over the guide.
    if (this.screen.kind === 'talk' || this.screen.kind === 'guide') return;
    const s = this.session(id);
    if (!s || this.busy(s)) return;
    const name = spokenName(s.projectName);
    const paused = blockedSpoken(name, s, this.snap()?.account, this.now(), this.snap()?.providers) ?? (stoppedAway(s) ? `${name} ${AWAY_LINE}.` : null);
    const f = this.facts.get(id);
    const stopped = s.state === 'error' || (!!f && (!f.ok || !!f.error));
    const said = stopped ? f?.error ?? '' : firstSentence(f?.reply || s.lastText || '', 160);
    void this.s.voice.speak(paused ?? `${name} ${stopped ? 'stopped' : 'is done'}${said ? `: ${said.replace(/[.!?…]+$/, '')}` : ''}.`);
  }

  /** "One", "two", "three": an option of the question or menu in front of you (else it comes up first). */
  private async answerOption(from: Screen, n: number, ctx: string | null): Promise<void> {
    const asks = (id: string) => { const it = this.needItem(id); return !!it && (!!it.menu || it.approval?.kind === 'question'); };
    if (from.kind !== 'needs') {
      const target = (ctx && asks(ctx) ? ctx : null) ?? this.rows().map(r => r.sessionId).find(asks) ?? null;
      if (!target) { this.say('Nothing asks for an option'); return; }
      this.open(target);
      this.say(this.moving ? 'Listen, then say the number' : 'Read it, then say the number', 3000);
      return;
    }
    const item = this.needItem(from.id, from.key);
    if (!item) { this.fixScreen(); this.say('Already answered'); return; }
    const q = item.approval?.kind === 'question' ? item.approval.questions?.[from.qi] ?? null : null;
    const count = item.menu ? item.menu.options.length : q ? q.options.length : 0;
    if (!count) { this.say('Say “approve” or “deny”'); return; }
    if (n < 1 || n > count) { this.say(count === 1 ? 'There is one option' : `There are ${count} options`); return; }
    if (q?.multiSelect) {
      // Several may be picked: each number ticks or unticks one; "approve" or a tap sends them.
      const i = n - 1;
      const picks = from.picks.includes(i) ? from.picks.filter(x => x !== i) : [...from.picks, i];
      this.screen = { ...from, picks, cursor: q.options.length };
      this.say(`${picks.includes(i) ? 'Ticked' : 'Unticked'} ${n}: say more, or “approve”`, 2600);
      return;
    }
    await this.approve({ ...from, cursor: n - 1 });
  }

  /**
   * "Plan first" / "go ahead": the permission mode of the session in front of you (plan, or the usual
   * asking). On a plan they answer it instead (go ahead approves, plan first keeps planning), and on a
   * command "go ahead" allows it, a risky one by its verb only. Auto and Bypass never by voice.
   */
  private async modeByVoice(word: 'plan' | 'goahead', from: Screen, ctx: string | null): Promise<void> {
    if (from.kind === 'needs') {
      const a = this.needItem(from.id, from.key)?.approval;
      if (a?.kind === 'plan') return word === 'goahead' ? this.approve(from) : this.deny(from);
      if (a?.kind === 'tool' && word === 'goahead') { if (this.riskCleared(from, 'approve', '')) await this.approve(from); return; }
    }
    if (!ctx) { this.say('Which session? Swipe to one first'); return; }
    const hub = this.s.hub();
    const name = this.session(ctx)?.projectName ?? 'it';
    if (!hub?.setMode) { this.say('This Mac’s Hypernova can’t change modes yet', 2800); return; }
    try {
      const r = await hub.setMode(ctx, word === 'plan' ? 'plan' : 'default');
      // In the phone's words for the mode: "ask first" is the usual one.
      this.say(`${name} ${word === 'plan' ? 'plans first' : 'asks first again'}${r.applies === 'next_turn' ? ', from its next turn' : ''}`, 2600);
    } catch (err) { this.say(lensWords(err), 3200); }
  }

  /** "Continue": the session in front of you carries on (else one that stopped while you were away). */
  private async carryOn(ctx: string | null): Promise<void> {
    const id = ctx ?? this.snap()?.sessions.find(s => s.stoppedForAway && s.state !== 'ended')?.id ?? null;
    if (!id) { this.say('Which session? Swipe to one first'); return; }
    const hub = this.s.hub();
    const name = this.session(id)?.projectName ?? 'it';
    if (!hub?.continueSession) { this.say('This Mac’s Hypernova can’t do that yet', 2800); return; }
    try {
      await hub.continueSession(id);
      this.say(`${name} carries on`);
    } catch (err) { this.say(err instanceof ApiError && err.code === 'needs_answer' ? `${name} is waiting for your answer first` : lensWords(err), 3000); }
  }

  /** "Debrief": what finished since the last one (the first, the last hour), what needs you, what runs. */
  private showDebrief(from: Screen): void {
    const snap = this.snap();
    if (!snap) return;
    const now = this.now();
    const since = this.debriefAt ?? now - DEBRIEF_FIRST_MS;
    const d = debrief({ snap, held: this.s.hub()?.held, facts: this.facts, since, now, brief: this.moving });
    this.debriefAt = now;
    const back: Screen = from.kind === 'debrief' ? from.back : from.kind === 'talk' ? { kind: 'glance' } : from;
    // The cursor starts on what needs you, so a tap answers it.
    this.go({ kind: 'debrief', since, items: d.items, counts: d.counts, cursor: Math.max(0, d.items.findIndex(i => i.kind === 'needs')), back });
    if (this.muted) return;
    void this.s.voice.speak(d.spoken).then(who => { if (who === 'locked') this.say(SOUND_LOCKED, 2800); });
  }

  private debriefView(sc: Extract<Screen, { kind: 'debrief' }>, toast: string): LensView {
    const when = clockTime(sc.since);
    return {
      kind: 'choice', head: ['DEBRIEF'], right: [`since ${when}`],
      prompt: sc.items.length ? sc.counts : `Nothing new since ${when}. All quiet.`,
      options: sc.items.map(i => ({ label: i.line, mark: '' as const })), cursor: sc.cursor,
      hint: toast ? [toast] : sc.items.length ? ['swipe choose', 'tap open', 'double tap back'] : ['double tap back'],
      orrery: { sun: null, planets: [], seam: { state: 'idle' } },
    };
  }

  /** "Note: …": a line in Nebula's notes.md, with the time and the project in front of you. Claude isn't started. */
  private async note(text: string, ctx: string | null): Promise<void> {
    if (!text) { this.say('Say “note,” then the idea, in one go', 3000); return; }
    const hub = this.s.hub();
    const old = 'This Mac’s Hypernova can’t keep notes yet. Update it';
    if (!hub?.note) { this.say(old, 3000); return; }
    const project = ctx ? this.session(ctx)?.projectName ?? null : null;
    try {
      await hub.note(text, project);
      this.say(`Noted: ${text}`, 3000);
      if (this.moving && !this.muted) void this.s.voice.speak('Noted.');
    } catch (err) { this.say(err instanceof ApiError && err.problem === 'missing' ? old : lensWords(err), 3000); }
  }

  /**
   * "What changed?": the files the last turn of the session in front of you changed, said in a
   * sentence or two and listed; "show diff" opens its diff. Without a session in front, the glance's
   * first line.
   */
  private async showChanges(ctx: string | null, from: Screen, diff: boolean): Promise<void> {
    const id = ctx ?? this.rows()[0]?.sessionId ?? null;
    const hub = this.s.hub();
    if (!id || !hub) { this.say('Which session? Swipe to one first'); return; }
    let events = hub.events(id);
    if (!events?.length) {
      try { events = await hub.loadEvents(id); } catch (err) { this.say(lensWords(err), 3000); return; }
    }
    const changes = turnChanges(events);
    const back: Screen = from.kind === 'diff' ? from.back : from.kind === 'talk' ? { kind: 'glance' } : from;
    this.reader = null;
    this.go({ kind: 'diff', id, changes, diff: diff && changes.length > 0, back });
    if (diff) { if (!changes.length) this.say('Its last turn changed no files', 2600); return; }
    if (this.muted) return;
    const name = spokenName(this.session(id)?.projectName ?? 'It');
    void this.s.voice.speak(changesSpoken(name, changes, turnFacts(events).reply)).then(who => { if (who === 'locked') this.say(SOUND_LOCKED, 2800); });
  }

  /** The diff as one text, worked out once for what's on the lens. */
  private diffOf(sc: Extract<Screen, { kind: 'diff' }>): DiffText {
    if (this.diffCache?.changes !== sc.changes) this.diffCache = { changes: sc.changes, text: diffText(sc.changes, READ_W) };
    return this.diffCache.text;
  }

  /** A tap: from the list of files, the diff; in the diff, the next file at the top (after the last, the list). */
  private diffTap(sc: Extract<Screen, { kind: 'diff' }>): void {
    const t = this.diffOf(sc);
    if (!t.lines.length) return;
    if (!sc.diff) { this.reader = null; this.screen = { ...sc, diff: true }; this.onChange(); return; }
    const r = this.reader?.at === `diff:${sc.id}` ? this.reader : null;
    const next = r ? t.starts[t.file[Math.min(r.s.top, t.file.length - 1)] + 1] : undefined;
    if (r && next !== undefined) r.s = scrollTo(r.s, next, r.rows);
    else this.screen = { ...sc, diff: false };
    this.onChange();
  }

  // ------------------------------------------------------------------ automations' asks

  /**
   * A run waits at an `ask` step (core/ask.ts). On the glance it takes the lens, as an approval
   * does; over anything else a band says it (Eclipse lets it through: it needs you). The question
   * is read out when reading aloud is on. Nothing here for a Mac without workflows: it sends none.
   */
  private askArrived(card: AskCard): void {
    if (this.asks.has(card.key) || card.until <= this.now()) return;
    this.asks.set(card.key, card);
    this.lastAskName.set(card.key, card.name);
    this.s.voice.blip('needs');
    const sc = this.screen;
    if (sc.kind === 'glance' || sc.kind === 'quiet') {
      this.reader = null;
      this.go({ kind: 'ask', key: card.key, cursor: 0, back: { kind: 'glance' } });
      this.guardUntil = this.now() + TAP_GUARD_MS;
    } else this.pop(askBand(card));
    if (sc.kind === 'talk' || sc.kind === 'guide') return;
    if (!this.muted && !this.s.voice.speaking) void this.s.voice.speak(askSpoken(card));
  }

  /** Bring a set-aside ask back (a tap on its band). */
  private openAsk(key: string): void {
    if (!this.asks.has(key)) return;
    this.reader = null;
    this.go({ kind: 'ask', key, cursor: 0, back: { kind: 'glance' } });
    this.guardUntil = this.now() + TAP_GUARD_MS;
  }

  /** Double tap on an ask: set aside, unanswered; its band keeps it until the default is taken. */
  private leaveAsk(sc: Extract<Screen, { kind: 'ask' }>): void {
    this.go(this.leaveTo(sc));
  }

  private leaveTo(sc: Extract<Screen, { kind: 'ask' }>): Screen {
    const card = this.asks.get(sc.key);
    if (card && !sc.closed) this.pop(askBand(card));
    return sc.back.kind === 'ask' ? { kind: 'glance' } : sc.back;
  }

  /** The ask is over: whoever answered, or the default taken. The card says so a moment, then closes. */
  private closeAsk(key: string, words: string[]): void {
    const card = this.asks.get(key);
    this.asks.delete(key);
    this.pops.drop(p => p.key === `ask:${key}`);
    const sc = this.screen;
    if (sc.kind === 'ask' && sc.key === key) {
      this.screen = { ...sc, closed: words };
      this.guardUntil = this.now() + TAP_GUARD_MS;
      this.later(ASK_CLOSED_MS, () => {
        const cur = this.screen;
        if (cur.kind === 'ask' && cur.key === key && cur.closed) { this.screen = cur.back.kind === 'ask' ? { kind: 'glance' } : cur.back; this.onChange(); }
      });
    } else if (card) this.pop({ key: `ask-end:${key}`, kind: 'note', head: askHead(card), detail: words.join(': '), short: card.name, ttl: 6000 });
    this.onChange();
  }

  /** `{t:"run"}`: an ask that is no longer open ended (someone answered, the default was taken, the run stopped). */
  private runAsk(run: RunAsks): void {
    for (const card of [...this.asks.values()]) {
      if (card.runId !== run.runId || run.open?.stepId === card.stepId) continue;
      const end: AskEnd | null = run.ended.find(x => x.stepId === card.stepId) ?? null;
      this.closeAsk(card.key, askEndWords(end, card.default));
    }
  }

  /** The countdown redraws, and the default taken at its time (unless the Mac said it first). */
  private askTick(): void {
    const now = this.now();
    let next = Infinity;
    for (const card of [...this.asks.values()]) {
      if (card.until <= now) { this.closeAsk(card.key, askTimedOut(card.default)); continue; }
      const sc = this.screen;
      const left = card.until - now;
      const shown = sc.kind === 'ask' && sc.key === card.key && !sc.closed;
      next = Math.min(next, card.until, shown ? now + (left < 60_000 ? 1000 : left % 60_000 || 60_000) + 20 : Infinity);
    }
    if (!Number.isFinite(next) || (this.askDue > now && this.askDue <= next)) return;
    this.askDue = next;
    this.later(Math.max(30, next - now), () => { this.askDue = 0; this.onChange(); });
  }

  /** Answer with answer `i`: tap, or the words said. A 409 `not_asking` means someone was first. */
  private async answerAsk(sc: Extract<Screen, { kind: 'ask' }>, i: number): Promise<void> {
    const card = this.asks.get(sc.key);
    const hub = this.s.hub();
    if (!card || sc.closed) return;
    const answer = card.answers[i];
    if (!hub?.answerAsk) { this.say('This Mac’s Hypernova can’t take that yet. Update it', 3000); return; }
    try {
      await hub.answerAsk(card.runId, card.stepId, answer);
      if (!this.asks.has(card.key)) return;
      this.asks.delete(card.key);
      this.pops.drop(p => p.key === `ask:${card.key}`);
      this.say(`Answered ${answer}`);
      const cur = this.screen;
      if (cur.kind === 'ask' && cur.key === card.key) this.screen = cur.back.kind === 'ask' ? { kind: 'glance' } : cur.back;
      this.onChange();
    } catch (err) {
      if (err instanceof ApiError && err.code === 'not_asking') {
        const run = readRunAsks(err.extra.run);
        const end = run?.ended.find(x => x.stepId === card.stepId) ?? null;
        return this.closeAsk(card.key, askEndWords(end, card.default));
      }
      this.say(lensWords(err), 3000);
    }
  }

  /** The ask card: the automation, its question, the answers, and the default with its clock in one line. */
  private askView(sc: Extract<Screen, { kind: 'ask' }>, toast: string): LensView {
    const seam: OrreryView = { sun: null, planets: [], seam: { state: 'needs' } };
    if (sc.closed) {
      const name = this.lastAskName.get(sc.key) ?? 'Automation';
      return { kind: 'card', head: ['ASK', name], right: '', lead: '', body: sc.closed, facts: [], hint: toastOr(toast, 'tap or double tap to close'), orrery: { sun: null, planets: [], seam: { state: 'done' } } };
    }
    const card = this.asks.get(sc.key);
    if (!card) return this.glanceView(toast);
    return {
      kind: 'choice', head: askHead(card), right: askCountdown(card.default, card.until, this.now()),
      prompt: card.question, options: card.answers.map(a => ({ label: a === card.default ? `${a} (default)` : a, mark: '' as const })), cursor: sc.cursor,
      hint: toastOr(toast, 'swipe choose', 'tap answer', 'double tap later'), orrery: seam,
    };
  }

  // ------------------------------------------------------------------ automations' notes

  /**
   * An automation has news for the glasses (core/notes.ts). On the glance it takes the lens, as a
   * finished turn does; over anything else a band says it. With reading aloud on it is read at
   * once. Eclipse, or the pop-up switch off on the phone, keeps it all quiet.
   */
  private noteArrived(n: AutomationNote): void {
    if (this.notes.has(n.id)) return;
    this.notes.set(n.id, n);
    while (this.notes.size > NOTES_KEPT) this.notes.delete(this.notes.keys().next().value!);
    if (this.eclipse || !this.popOn('note')) return;
    const sc = this.screen;
    if (sc.kind === 'glance') { this.reader = null; this.go({ kind: 'note', noteId: n.id, back: sc }); }
    else this.pop(noteBand(n));
    if (sc.kind === 'talk' || sc.kind === 'guide' || sc.kind === 'quiet') return;
    this.s.voice.blip('done');
    if (!this.muted && !this.s.voice.speaking) void this.s.voice.speak(noteSpoken(n));
  }

  /** Tap on a note: read it aloud, or stop the voice. */
  private async readNoteAloud(id: string): Promise<void> {
    const n = this.notes.get(id);
    if (!n) return;
    if (this.s.voice.speaking) { this.s.voice.stop(); this.onChange(); return; }
    const who = await this.s.voice.speak(noteSpoken(n));
    if (who === 'locked') this.say(SOUND_LOCKED, 2800);
    else if (!who && this.s.settings().voice !== 'off') this.say('No voice here: set up read-aloud on your phone', 2800);
    this.onChange();
  }

  private noteView(sc: Extract<Screen, { kind: 'note' }>, toast: string): LensView {
    const n = this.notes.get(sc.noteId);
    if (!n) return this.glanceView(toast);
    const plain = noteText(n);
    const lines = textLines(plain, READ_W);
    const rows = fitRows(lines, Math.min(cardRoom(false, false), Math.max(1, lines.length)), CARD_TEXT_W);
    const r = this.scrolled(`note:${n.id}`, plain, lines.length, rows, false);
    const reading = this.s.voice.speaking;
    const hint = toast ? [toast] : reading ? READING_HINT
      : hasMore(r, rows) ? (this.muted ? ['swipe read', 'double tap back'] : ['swipe read', 'tap read aloud'])
      : this.muted ? ['double tap back', 'hold talk'] : ['tap read aloud', 'double tap back'];
    return {
      kind: 'card', head: noteHead(n), right: n.at ? ago(Math.max(0, this.now() - n.at)) : '', lead: '',
      body: windowLines(lines, r.top, rows, CARD_TEXT_W), facts: [], hint,
      orrery: { sun: null, planets: [], seam: { state: 'done' } },
    };
  }

  private diffView(sc: Extract<Screen, { kind: 'diff' }>, toast: string): LensView {
    const name = this.session(sc.id)?.projectName ?? '';
    const t = this.diffOf(sc);
    const seam = this.seam(sc.id);
    if (!sc.diff || !t.lines.length) {
      const rows = changeRows(sc.changes);
      const room = cardRoom(false, true);
      const shown = rows.length > room ? rows.slice(0, room - 1) : rows;
      // A few px short of the line: spaces are 5 px, so the counts can overshoot by half of one.
      const body = rows.length ? shown.map(r => (r.counts ? spread(r.name, r.counts, CARD_TEXT_W - 4) : r.name)) : ['Its last turn changed no files.'];
      if (rows.length > shown.length) body.push(`+${rows.length - shown.length} more`);
      const total = { added: sc.changes.reduce((n, f) => n + f.added, 0), removed: sc.changes.reduce((n, f) => n + f.removed, 0) };
      return {
        kind: 'card', head: ['CHANGED', name], right: `${sc.changes.length} file${sc.changes.length === 1 ? '' : 's'}`, lead: '', body,
        facts: counts(total) ? [`${counts(total)} in all`] : [], hint: toast ? [toast] : t.lines.length ? ['tap show diff', 'double tap back'] : ['double tap back'], orrery: seam,
      };
    }
    // One text that scrolls through every file; the header names the file at the top. Blank lines
    // after the last file let it come to the top too (a tap brings the next file there).
    const rows = fitRows(t.lines, cardRoom(false, false), CARD_TEXT_W);
    const last = t.starts.at(-1) ?? 0;
    const lines = last + rows > t.lines.length ? [...t.lines, ...Array<string>(last + rows - t.lines.length).fill('')] : t.lines;
    const r = this.scrolled(`diff:${sc.id}`, sc.changes, lines.length, rows, false);
    const f = sc.changes[t.file[Math.min(r.top, t.file.length - 1)]];
    return {
      kind: 'card', head: ['DIFF', f.name], right: counts(f), lead: '', body: windowLines(lines, r.top, rows, CARD_TEXT_W, t.lines.length), facts: [],
      hint: toast ? [toast] : sc.changes.length > 1 ? ['swipe scroll', 'tap next file', 'double tap back'] : ['swipe scroll', 'double tap back'], orrery: seam,
    };
  }

  // ------------------------------------------------------------------ the guide on the glasses

  /** The first time the glasses have the Mac's sessions (and nothing needs you), the guide is offered, once. */
  private offerGuide(): void {
    if (this.s.settings().guideOffered !== false || this.screen.kind !== 'glance' || !this.snap()) return;
    if (this.rows().some(r => r.state === 'needs')) return;
    this.s.updateSettings({ guideOffered: true });
    this.screen = { kind: 'guide', g: { step: -1, checked: false, note: '' }, phase: 'try', heard: '', back: { kind: 'glance' } };
  }

  /**
   * The guide: "guide", the phone's button, or (`offer`) its one offer. Double tap comes back here.
   * `step`: start further in (simulator scenes).
   */
  startGuide(offer = false, step = 0): void {
    const sc = this.screen;
    const back: Screen = sc.kind === 'guide' ? sc.back : sc.kind === 'talk' ? { kind: 'glance' } : sc;
    this.go({ kind: 'guide', g: { step: offer ? -1 : step, checked: false, note: '' }, phase: 'try', heard: '', back });
  }

  /** A gesture (or the word) in the guide: checked, tried again, or the guide left (core/lens-guide.ts). */
  private guideDo(g: GuideGesture, heard = ''): void {
    const sc = this.screen;
    if (sc.kind !== 'guide') return;
    const out = guideGesture(sc.g, g);
    switch (out.kind) {
      case 'ignore': if (sc.phase !== 'try') { this.screen = { ...sc, phase: 'try' }; this.onChange(); } return;
      case 'leave': this.s.voice.stop(); return this.go(sc.back);
      case 'again': this.screen = { ...sc, phase: 'try', heard: '', g: out.state }; break;
      case 'next': this.screen = { ...sc, phase: 'try', heard: '', g: out.state }; break;
      case 'checked': {
        // The spoken step keeps what it heard on the lens.
        this.screen = { ...sc, phase: 'try', heard: g === 'speak' ? heard : '', g: out.state };
        const step = out.state.step;
        // What the gesture is for stays a moment, then the next step (any gesture gets there sooner).
        this.later(g === 'speak' ? GUIDE_HEARD_MS : GUIDE_NEXT_MS, () => {
          const cur = this.screen;
          if (cur.kind === 'guide' && cur.g.step === step && cur.g.checked) { this.screen = { ...cur, g: { step: step + 1, checked: false, note: '' } }; this.onChange(); }
        });
        break;
      }
    }
    this.onChange();
  }

  /** A hold in the guide: on the spoken step it listens (captions and all); otherwise it waits for the release. */
  private async guideHold(sc: Extract<Screen, { kind: 'guide' }>): Promise<void> {
    const step = GUIDE_STEPS[sc.g.step];
    if (!step || sc.g.checked) return;
    if (!step.voice) { this.screen = { ...sc, phase: 'holding' }; this.onChange(); return; }
    const speech = this.s.speech();
    if (!speech) { this.screen = { ...sc, g: { ...sc.g, note: 'Voice isn’t set up yet (on the phone: Settings, Speech). Double tap skips.' } }; this.onChange(); return; }
    const gen = ++this.listenGen;
    this.screen = { ...sc, phase: 'listening', heard: '' };
    this.onChange();
    try {
      await speech.start((fin, interim) => {
        const cur = this.screen;
        if (gen !== this.listenGen || cur.kind !== 'guide' || (cur.phase !== 'listening' && cur.phase !== 'finishing')) return;
        this.screen = { ...cur, heard: [fin, interim].filter(Boolean).join(' ') };
        this.onChange();
      });
    } catch (err) {
      const cur = this.screen;
      if (gen !== this.listenGen || cur.kind !== 'guide') return;
      this.screen = { ...cur, phase: 'try', g: { ...cur.g, note: err instanceof SpeechError ? err.message : explain(err) } };
      this.onChange();
    }
  }

  /**
   * The spoken step let go: its last words come in as for a prompt, then what was heard shows and the
   * step is ticked. Practice only: nothing here ever goes to the Mac.
   */
  private async guideRelease(sc: Extract<Screen, { kind: 'guide' }>): Promise<void> {
    if (sc.phase === 'holding') return this.guideDo('hold');
    if (sc.phase !== 'listening') return;
    const gen = this.listenGen;
    this.screen = { ...sc, phase: 'finishing' };
    this.onChange();
    let said = '';
    try { said = (await this.s.speech()?.stop())?.text ?? ''; } catch (err) {
      const cur = this.screen;
      if (gen === this.listenGen && cur.kind === 'guide' && cur.phase === 'finishing') { this.screen = { ...cur, phase: 'try', g: { ...cur.g, note: explain(err) } }; this.onChange(); }
      return;
    }
    const cur = this.screen;
    if (gen !== this.listenGen || cur.kind !== 'guide' || cur.phase !== 'finishing') return;
    said = (said || cur.heard).trim();
    if (!said) { this.screen = { ...cur, phase: 'try', heard: '', g: { ...cur.g, note: 'Nothing was heard. Hold, say a few words, let go.' } }; this.onChange(); return; }
    this.guideDo('speak', said);
  }

  private guideView(sc: Extract<Screen, { kind: 'guide' }>): LensView {
    const n = GUIDE_STEPS.length;
    const orrery: OrreryView = { sun: null, planets: [], seam: { state: sc.phase === 'listening' || sc.phase === 'finishing' ? 'listening' : sc.g.checked ? 'done' : 'idle' } };
    const card = (head: Parts, right: string, lines: string[], hint: Parts): LensView =>
      ({ kind: 'card', head, right, lead: '', body: lines.flatMap(l => (l ? wrapAll(l, CARD_TEXT_W) : [''])), facts: [], hint, orrery });
    if (sc.g.step < 0) return card([GUIDE_OFFER.head], 'a minute', GUIDE_OFFER.lines, GUIDE_OFFER.hint);
    if (sc.g.step >= n) return card(GUIDE_DONE.head, guideDots(sc.g), GUIDE_DONE.lines, GUIDE_DONE.hint);
    const step = GUIDE_STEPS[sc.g.step];
    const head = [`GUIDE ${sc.g.step + 1} of ${n}`, step.title], ask = `${sc.g.checked ? '● ' : ''}${step.ask}`;
    // The spoken step, ticked: what was heard (its first three lines), then what a prompt does next.
    if (sc.g.checked && step.voice && sc.heard) {
      const heard = headLines(`“${sc.heard}”`, CARD_TEXT_W, 3), note = wrapAll(sc.g.note, CARD_TEXT_W), first = wrapAll(ask, CARD_TEXT_W);
      const gap = first.length + heard.length + note.length < cardRoom(false, 0) ? [''] : [];
      return { kind: 'card', head, right: guideDots(sc.g), lead: '', body: [...first, ...gap, ...heard, ...note], facts: [], hint: guideHint(sc.g, !!this.s.speech()), orrery };
    }
    const now = sc.phase === 'holding' ? 'Now let go.'
      : sc.phase === 'listening' || sc.phase === 'finishing' ? (sc.heard ? `“${sc.heard}”` : sc.phase === 'finishing' ? 'Getting the last words…' : 'Listening… let go when you’ve said it.')
      : sc.g.note;
    return card(head, guideDots(sc.g), [ask, '', now], guideHint(sc.g, !!this.s.speech()));
  }

  // ------------------------------------------------------------------ for the phone

  /** Is anything on the lens moving (the planet column needs frames)? */
  get shot(): Shot | null { return this.screen.kind === 'telescope' ? this.screen.shot : null; }

  /** Jump straight to a screen (demo scenes and simulator shots). */
  scene(name: string): void {
    const rows = this.rows();
    const id = (state: RowState) => rows.find(r => r.state === state)?.sessionId ?? rows[0]?.sessionId ?? null;
    switch (name) {
      case 'needs': { const x = id('needs'); if (x) { this.focusId = x; this.openFocused(); } break; }
      case 'result': { const x = id('idle'); this.reader = null; if (x) this.go({ kind: 'result', id: x }); break; }
      case 'session': { const x = id('working'); if (x) this.go({ kind: 'session', id: x }); break; }
      case 'quiet': this.go({ kind: 'quiet' }); break;
      case 'power': this.showPower(); break;
      case 'picker': this.showPicker(); break;
      default: this.go({ kind: 'glance' });
    }
  }

  /** Simulator scenes: the telescope on a sample project, at a source if named, zoomed into a quarter, watching. */
  async telescopeScene(project: string, source: string | null, zoom: number | null, watch = false): Promise<void> {
    const id = this.snap()?.sessions.find(s => s.projectName === project)?.id ?? null;
    await this.telescope(id, source ? { source: source as ScopeSource } : {}, '', watch);
    const sc = this.screen;
    if (zoom !== null && sc.kind === 'telescope' && sc.status === 'ready') { this.screen = { ...sc, zoom: Math.max(0, Math.min(3, zoom)) }; this.onChange(); }
  }

  dispose(): void {
    for (const t of this.timers) clearTimeout(t);
    this.timers.clear();
    if (this.teleTimer) clearTimeout(this.teleTimer);
    if (this.pollTimer) clearTimeout(this.pollTimer);
    if (this.watchTimer) clearTimeout(this.watchTimer);
    if (this.popTimer) clearTimeout(this.popTimer);
    if (this.txTimer) clearTimeout(this.txTimer);
    this.teleTimer = this.pollTimer = this.watchTimer = this.popTimer = this.txTimer = null;
  }
}

export const MENU = { talk: 1, status: 2, read: 3, telescope: 4, quiet: 5, exit: 6, back: 7, watch: 8 } as const;
export const MENU_ITEMS: Array<[number, string]> = [
  [MENU.talk, 'Talk (hands-free)'], [MENU.back, 'Back (answer later)'], [MENU.status, 'Status'], [MENU.read, 'Read the reply'], [MENU.telescope, 'Telescope'],
  [MENU.watch, 'Watch (telescope)'], [MENU.quiet, 'Quiet on/off'], [MENU.exit, 'Exit'],
];

/** The source to look at again while watching: the one the picture came from. */
function scopeSourceOf(shot: Shot): ScopeSource | undefined {
  if (shot.source === 'url' || shot.source === 'demo') return 'web';
  return shot.source === 'screen' ? undefined : shot.source;
}

/** Under a question's or a terminal menu's options: leaves the card without answering it. */
const BACK_ROW = { label: ['Back', 'answer later'], mark: '' as const };
/** A text that scrolls (a reply, a plan, a diff) is wrapped this wide: room for the mark that says more follows. */
const READ_W = CARD_TEXT_W - MORE_W;
const SOUND_LOCKED = 'Touch your phone once to allow sound';
const MUTED = 'Read aloud is off: say “unmute”';
/** A voice reading, on the header's right in the glasses' own font (the drawn look draws a wave). */
const READING_WORD = 'reading aloud';
/** While a voice reads: a tap stops it, a swipe still scrolls. */
const READING_HINT = ['tap stop reading', 'swipe scroll', 'double tap back'];

/** A hint: the toast in its place while there is one, else the gestures given (those that are). */
/** How long a card that ended says so before it closes. */
const ASK_CLOSED_MS = 3500;

const toastOr = (toast: string, ...parts: Array<string | false | null | undefined>): string[] => (toast ? [toast] : parts.filter((p): p is string => !!p));

const NUMBER_WORDS = ['one', 'two', 'three', 'four', 'five', 'six'];

/** "one or two", "one, two or three", "one to five": the numbers to say for `n` options. */
function sayRange(n: number): string {
  if (n <= 1) return '“one”';
  if (n === 2) return '“one” or “two”';
  if (n === 3) return '“one”, “two” or “three”';
  return `“one” to “${NUMBER_WORDS[Math.min(n, 6) - 1]}”`;
}

/** A question or menu read out with its numbered options: "hypernova asks: …? One: …. Two: …. Say one or two." */
function optionsSpoken(lead: string, labels: string[], multi: boolean): string {
  const head = /[?.!]$/.test(lead.trim()) ? lead.trim() : `${lead.trim()}.`;
  const opts = labels.slice(0, 6).map((l, i) => `${NUMBER_WORDS[i][0].toUpperCase()}${NUMBER_WORDS[i].slice(1)}: ${l.replace(/[.!?]+$/, '')}.`).join(' ');
  return `${head} ${opts} Say ${multi ? 'each number you want, then approve' : sayRange(labels.length).replace(/[“”]/g, '')}.`;
}

function sameRoute(a: Route | null, b: Route | null): boolean {
  if (!a || !b) return a === b;
  if (a.kind === 'nebula' || b.kind === 'nebula') return a.kind === b.kind;
  return a.kind === 'session' && b.kind === 'session' ? a.sessionId === b.sessionId : a.kind === 'new' && b.kind === 'new' && a.projectPath === b.projectPath;
}

/** A provider's trouble as a band's head: what it is, then which provider. */
function providerHead(p: Provider): string[] {
  const what = p.status?.state === 'no_credit' ? (p.status.budget ? 'BUDGET REACHED' : 'OUT OF CREDITS')
    : p.status?.state === 'bad_key' ? 'KEY REFUSED' : p.status?.state === 'limited' ? 'RATE LIMITED' : p.status?.state === 'down' ? 'NOT ANSWERING' : 'CAN’T REPLY';
  return [what, p.name];
}

/** Under it, the phone's notice: what happens to its sessions, and what fixes it. */
function providerDetail(p: Provider, paused: number, now: number): string {
  const st = p.status;
  switch (st?.state) {
    case 'no_credit': return `${paused ? `${paused} session${paused === 1 ? '' : 's'} paused. ` : ''}Add credits or continue on another provider.`;
    case 'bad_key': return 'Fix the key on your phone: Settings → Providers.';
    case 'limited': return st.resetsAt && st.resetsAt > now ? `Continues by itself ${atClock(st.resetsAt, now)}.` : 'It didn’t say for how long.';
    case 'down': return 'Its sessions carry on when it answers again.';
    default: return providerSentence(p, now);
  }
}

/** The glance's focus on "+ New session" rather than a session. */
const NEW_ROW = '+new';
/** Screens where a hold talks to what needs you, else starts a new session: the main screen and its cards. */
const HOME = new Set<string>(['glance', 'quiet', 'power', 'picker', 'debrief']);
/** "+ New project" in the picker: a folder of its own in Nebula, made only when the prompt is sent. */
const NEW_PROJECT_ROW = ['+ New project', 'its own folder in Nebula'];

/** "localhost 3000", "localhost:3000/settings", "port 5173", "3000" as said → a URL; with no port,
 * nothing (the Mac then finds the page the session started). */
export function spokenUrl(said: string): string | undefined {
  const host = /localhost|127\.0\.0\.1|\[::1\]/i.exec(said)?.[0].toLowerCase() ?? 'localhost';
  const m = /\b(\d{2,5})\b(\/\S*)?/.exec(said.replace(/localhost|127\.0\.0\.1|\[::1\]/gi, ' '));
  return m ? `http://${host}:${m[1]}${m[2] ?? '/'}` : undefined;
}

const SERVER_CMD = /\b(npm|pnpm|yarn|bun)\s+(run\s+)?(dev|start|serve|preview)\b|\bvite\b|\bnext\s+(dev|start)\b|\bastro\s+dev\b|\bwebpack(-dev-server|\s+serve)\b|http\.server|\bserve\b|\brails\s+s(erver)?\b|\bflask\s+run\b|\buvicorn\b|\bgunicorn\b|\bphp\s+-S\b|\bhugo\s+server\b|\bjekyll\s+serve\b/;
/** The daemon's own ports: a transcript that mentions them is never "the page Claude built". */
const DAEMON_PORTS = new Set([47321, 47322]);

const localPort = (text: string): number | null => {
  const hits = [...text.matchAll(/https?:\/\/(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]):(\d{2,5})/g)].map(m => Number(m[1])).filter(p => !DAEMON_PORTS.has(p));
  return hits.length ? hits[hits.length - 1] : null;
};

/**
 * The port of the page a session started: a server command it ran ("npm run dev", "vite",
 * "python3 -m http.server") and the address that command printed ("Local: http://localhost:5173/").
 * Claude Code usually starts a dev server in the background, and then the command's own result is
 * only "Command running in background with ID: b8f…. Output is being written to: /…/b8f….output":
 * the address shows up later, when Claude reads that output (Read on the file, `tail` of it, or
 * BashOutput with the ID). Addresses Claude merely mentions or fetches don't count. The daemon
 * does better (it reads the file and sees which ports listen, DAEMON.md 3); this is for a Mac that
 * can't take the picture, so the live page can open on the phone instead.
 */
export function devServerPort(events: HnEvent[]): number | null {
  for (let i = events.length - 1; i >= 0; i--) {
    const e = events[i];
    if (e.kind !== 'tool' || e.name !== 'Bash' || !SERVER_CMD.test(String((e.input as { command?: unknown })?.command ?? ''))) continue;
    const out = String(e.output ?? '');
    const now = localPort(out);
    if (now) return now;
    const bgId = /running in background with ID:\s*([\w-]+)/i.exec(out)?.[1];
    const file = /written to:\s*(\/\S+?)\.?(?=\s|$)/i.exec(out)?.[1];
    if (!bgId && !file) continue;
    for (let j = events.length - 1; j > i; j--) {
      const r = events[j];
      if (r.kind !== 'tool' || !r.output) continue;
      const input = JSON.stringify(r.input ?? {});
      const reads = (file && input.includes(file)) || (bgId && /Output$/.test(r.name) && input.includes(`"${bgId}"`));
      const port = reads ? localPort(String(r.output)) : null;
      if (port) return port;
    }
  }
  return null;
}

void routeTo;
