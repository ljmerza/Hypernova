// Tiny DOM helpers (after Hypertasks' src/ui/dom.ts). No framework: the Even App WebView should
// load fast, and parts are patched rather than rebuilt so a live update never steals a text field.

type Child = Node | string | null | undefined | false;
type Attrs = Record<string, unknown>;

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Array<Child | Child[]>): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') el.className = String(v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v as EventListener);
    else if (k === 'value' && 'value' in el) (el as HTMLInputElement).value = String(v);
    else if (k === 'checked' || k === 'disabled') (el as unknown as Record<string, unknown>)[k] = Boolean(v);
    else el.setAttribute(k, String(v));
  }
  for (const c of children.flat()) if (c !== null && c !== undefined && c !== false) el.append(c);
  return el;
}

export function replace(container: Element, ...nodes: Array<Child | Child[]>): void {
  container.replaceChildren(...nodes.flat().filter((n): n is Node | string => n !== null && n !== undefined && n !== false));
}

/** A seam: [planet] LABEL ───── trail. */
export function seam(label: string, trail = '', planet: '' | 'live' | 'alert' = ''): HTMLElement {
  return h('div', { class: 'hn-seam' },
    h('span', { class: `hn-planet ${planet}`.trim() }, h('i')),
    h('span', { class: 'hn-label', style: planet === 'alert' ? { color: 'var(--hn-amber)' } : planet === 'live' ? { color: 'var(--hn-accent)' } : {} }, label),
    trail ? h('span', { class: 'hn-trail' }, trail) : null);
}

export function toast(message: string, ms = 2600): void {
  let el = document.querySelector<HTMLElement>('.toast');
  if (!el) { el = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite' }); document.body.append(el); }
  el.textContent = message;
  el.classList.add('show');
  clearTimeout((el as unknown as { _t?: number })._t);
  (el as unknown as { _t?: number })._t = window.setTimeout(() => el!.classList.remove('show'), ms);
}
