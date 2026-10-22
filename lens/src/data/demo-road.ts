// Simulator scenes for the glasses on a bike or a walk (`?demo=1&scene=<name>`): the guide, debrief,
// notes, what changed and its diff, moving mode, risky commands and eclipse, each played from the
// gestures and words that lead to it, so test/sim-shots.py can shoot it without a Mac.
import type { SceneDeps } from './demo-scenes.ts';

const sleep = (ms: number) => new Promise(r => setTimeout(r, ms));

async function talk(d: SceneDeps, words: string): Promise<void> {
  d.speech.next(words);
  await d.lens.hold();
  await sleep(300);
  await d.lens.release();
}

/** Play scene `name`; false if it isn't one of these. */
export async function playRoadScene(name: string, d: SceneDeps): Promise<boolean> {
  const { lens, hub } = d;
  const moving = () => d.update({ moving: true });
  const settle = async () => { await hub?.answer('ap-push', { decision: 'deny' }).catch(() => {}); await sleep(50); };
  switch (name) {
    case 'guide-offer': lens.startGuide(true); return true;
    case 'guide-tap': lens.startGuide(); return true;
    case 'guide-checked': lens.startGuide(); lens.tap(); return true;
    case 'guide-wrong': lens.startGuide(); lens.down(); return true;
    case 'guide-hold': lens.startGuide(false, 3); await lens.hold(); return true;
    case 'guide-voice': lens.startGuide(false, 4); return true;
    case 'guide-listen': lens.startGuide(false, 4); d.speech.next('fix the export and push it'); await lens.hold(); return true;
    case 'guide-heard': lens.startGuide(false, 4); await talk(d, 'fix the export and push it to main'); return true;
    case 'guide-done': lens.startGuide(false, 5); return true;
    case 'debrief': await talk(d, 'debrief'); return true;
    case 'changes': lens.open('s-hyperscribe'); await sleep(100); await talk(d, 'what changed?'); return true;
    case 'diff': lens.open('s-hyperscribe'); await sleep(100); await talk(d, 'show diff'); return true;
    case 'diff-new': lens.open('s-hyperscribe'); await sleep(100); await talk(d, 'show diff'); lens.tap(); lens.tap(); return true;
    case 'note': await talk(d, 'Note: comets should fade faster at night'); return true;
    case 'eclipse-on': await settle(); await talk(d, 'eclipse'); return true;
    case 'risky': hub?.approve('s-site', 'git push --force origin main'); await sleep(50); lens.open('s-site'); return true;
    case 'moving-glance': await moving(); lens.scene('glance'); return true;
    case 'moving-live': await moving(); lens.scene('session'); return true;
    case 'moving-pop': await moving(); lens.scene('session'); await sleep(300); hub?.approve('s-site', 'git push --force origin main'); return true;
    case 'moving-risky': await moving(); hub?.approve('s-site', 'git push --force origin main'); await sleep(50); lens.open('s-site'); return true;
    case 'moving-question': await moving(); hub?.ask('question'); await sleep(50); lens.open('s-hypernova'); return true;
  }
  return false;
}
