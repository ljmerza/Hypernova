// Types for cosmos.js (the Hyper cosmos web sky, a global script).
export {};

export interface CosmosItem { id: string; label: string; state: 'working' | 'needs' | 'idle'; minutes: number }
export interface CosmosGroup { name: string; items: CosmosItem[] }
export interface CosmosData {
  usage?: number; resetAt?: number | null; limitedAt?: number | null;
  week?: number; weekLimitedAt?: number | null; weekResetAt?: number | null;
  groups?: CosmosGroup[];
  /** Lens change: false hides the sun's readout (no usage to show). */
  readout?: boolean;
}
export interface CosmosSky {
  setData(d: CosmosData): void;
  zoom(name: string | null): void;
  destroy(): void;
  onHit?: (hit: { type: 'sun' } | { type: 'group'; name: string } | { type: 'item'; group: string; id: string }) => void;
}

declare global {
  interface Window {
    HyperCosmos?: {
      mount(o: { canvas: HTMLCanvasElement; grain?: HTMLCanvasElement; death?: boolean; band?: () => { top: number; bottom: number } }): CosmosSky;
    };
  }
}
