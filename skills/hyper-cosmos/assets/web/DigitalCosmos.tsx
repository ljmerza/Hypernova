"use client";
import { useEffect, useRef } from "react";

/**
 * DigitalCosmos — calm scattered star field with soft nebula clouds.
 *
 * Three layers:
 *   1. Nebula clouds (background atmosphere) — 4 large radial gradients
 *      in subtle green-family hue variations, very low alpha, slow drift.
 *      This is what gives the bg its Hubble-photo feel instead of black
 *      void with isolated dots.
 *   2. Stars (mid/foreground) — ~95 across the viewport in 3 tiers:
 *      anchor (bright with 3-layer glow halo), mid, and background dots.
 *      Clustering around 6 centers, slow rotating wind, breathing +
 *      twinkle.
 *   3. Anchor labels — always show their hex label on the brightest stars;
 *      ~15% of mid stars also carry a label.
 *
 * Respects prefers-reduced-motion.
 */

type Particle = {
  x: number;
  y: number;
  size: number;
  baseAlpha: number;
  vx: number;
  vy: number;
  phase: number;
  phaseSpeed: number;
  twinklePhase: number;
  twinkleSpeed: number;
  isAnchor: boolean;
  number: string;
  showNumber: boolean;
};

type Nebula = {
  x: number;
  y: number;
  radius: number;
  baseAlpha: number;
  r: number;
  g: number;
  b: number;
  vx: number;
  vy: number;
  phase: number;
  phaseSpeed: number;
};

type Cluster = {
  x: number;
  y: number;
  radius: number;
};

const HEX_CHARS = "0123456789abcdef";
const NUM_PARTICLES = 95;
const NUM_CLUSTERS = 6;
const NUM_NEBULAE = 4;
const ANCHOR_RATIO = 0.07;
const MID_RATIO = 0.28;
const FONT_SIZE = 9;
const NUMBER_GAP_PX = 7;

// Subtle hue variations of the accent green — keeps the green family
// while giving the nebulae enough differentiation to feel like distinct
// clouds rather than identical overlays.
const NEBULA_COLORS: Array<[number, number, number]> = [
  [127, 255, 160],
  [150, 255, 200],
  [180, 255, 140],
  [105, 255, 185],
];

function randomHex2(): string {
  return HEX_CHARS[Math.floor(Math.random() * 16)]! +
    HEX_CHARS[Math.floor(Math.random() * 16)]!;
}

function inRange(min: number, max: number): number {
  return min + Math.random() * (max - min);
}

export function DigitalCosmos() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const coarse = window.matchMedia("(pointer: coarse)").matches;
    const dpr = Math.min(window.devicePixelRatio || 1, coarse ? 1.5 : 2);

    let w = 0;
    let h = 0;
    let raf = 0;
    let lastT = 0;
    let particles: Particle[] = [];
    let nebulae: Nebula[] = [];
    let clusters: Cluster[] = [];

    const buildParticles = () => {
      particles = [];
      nebulae = [];
      clusters = [];

      const minSide = Math.min(w, h);

      for (let i = 0; i < NUM_NEBULAE; i++) {
        const color = NEBULA_COLORS[i % NEBULA_COLORS.length]!;
        nebulae.push({
          x: Math.random() * w,
          y: Math.random() * h,
          radius: minSide * inRange(0.30, 0.55),
          baseAlpha: inRange(0.07, 0.14),
          r: color[0],
          g: color[1],
          b: color[2],
          vx: inRange(-0.25, 0.25),
          vy: inRange(-0.25, 0.25),
          phase: Math.random() * Math.PI * 2,
          phaseSpeed: inRange(0.08, 0.18),
        });
      }

      for (let i = 0; i < NUM_CLUSTERS; i++) {
        clusters.push({
          x: Math.random() * w,
          y: Math.random() * h,
          radius: minSide * inRange(0.12, 0.22),
        });
      }

      for (let i = 0; i < NUM_PARTICLES; i++) {
        const r = Math.random();
        const isAnchor = r < ANCHOR_RATIO;
        const isMid = !isAnchor && r < ANCHOR_RATIO + MID_RATIO;

        let x: number;
        let y: number;
        if (Math.random() < 0.55 && clusters.length > 0) {
          const c = clusters[Math.floor(Math.random() * clusters.length)]!;
          const angle = Math.random() * Math.PI * 2;
          const dist = Math.pow(Math.random(), 0.6) * c.radius;
          x = ((c.x + Math.cos(angle) * dist) % w + w) % w;
          y = ((c.y + Math.sin(angle) * dist) % h + h) % h;
        } else {
          x = Math.random() * w;
          y = Math.random() * h;
        }

        let size: number;
        let baseAlpha: number;
        if (isAnchor) {
          size = inRange(2.4, 3.6);
          baseAlpha = inRange(0.6, 0.9);
        } else if (isMid) {
          size = inRange(1.1, 2.0);
          baseAlpha = inRange(0.28, 0.5);
        } else {
          size = inRange(0.5, 1.0);
          baseAlpha = inRange(0.10, 0.22);
        }

        const speed = isAnchor ? inRange(0.08, 0.18) : inRange(0.15, 0.55);
        const angle = Math.random() * Math.PI * 2;

        particles.push({
          x,
          y,
          size,
          baseAlpha,
          vx: Math.cos(angle) * speed,
          vy: Math.sin(angle) * speed,
          phase: Math.random() * Math.PI * 2,
          phaseSpeed: inRange(0.2, 0.45),
          twinklePhase: Math.random() * Math.PI * 2,
          twinkleSpeed: inRange(0.8, 2.2),
          isAnchor,
          number: randomHex2(),
          showNumber: isAnchor || Math.random() < 0.15,
        });
      }
    };

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      w = canvas.width = Math.max(1, Math.floor(rect.width * dpr));
      h = canvas.height = Math.max(1, Math.floor(rect.height * dpr));
      buildParticles();
    };
    resize();

    const onResize = () => resize();
    window.addEventListener("resize", onResize);

    const draw = (dt: number, t: number) => {
      ctx.clearRect(0, 0, w, h);
      ctx.font = `${FONT_SIZE * dpr}px ui-monospace, "SF Mono", "JetBrains Mono", "Menlo", monospace`;
      ctx.textAlign = "left";
      ctx.textBaseline = "middle";

      const windAngle = t * 0.04;
      const windForce = 0.4;
      const windX = Math.cos(windAngle) * windForce;
      const windY = Math.sin(windAngle) * windForce;

      // Layer 1 — nebula clouds (background atmosphere)
      for (const n of nebulae) {
        n.x += (n.vx + windX * 0.5) * dt * dpr;
        n.y += (n.vy + windY * 0.5) * dt * dpr;

        const margin = n.radius;
        if (n.x < -margin) n.x = w + margin;
        if (n.x > w + margin) n.x = -margin;
        if (n.y < -margin) n.y = h + margin;
        if (n.y > h + margin) n.y = -margin;

        const breath = 0.6 + 0.4 * Math.sin(t * n.phaseSpeed + n.phase);
        const alpha = n.baseAlpha * breath;
        const r = n.radius * dpr;

        const gradient = ctx.createRadialGradient(n.x, n.y, 0, n.x, n.y, r);
        gradient.addColorStop(0, `rgba(${n.r}, ${n.g}, ${n.b}, ${alpha})`);
        gradient.addColorStop(0.3, `rgba(${n.r}, ${n.g}, ${n.b}, ${alpha * 0.55})`);
        gradient.addColorStop(0.6, `rgba(${n.r}, ${n.g}, ${n.b}, ${alpha * 0.2})`);
        gradient.addColorStop(1, `rgba(${n.r}, ${n.g}, ${n.b}, 0)`);

        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
        ctx.fill();
      }

      // Layer 2 — stars (foreground)
      for (const p of particles) {
        p.x += (p.vx + windX) * dt * dpr;
        p.y += (p.vy + windY) * dt * dpr;

        if (p.x < 0) p.x += w;
        if (p.x > w) p.x -= w;
        if (p.y < 0) p.y += h;
        if (p.y > h) p.y -= h;

        const breath = 0.7 + 0.3 * Math.sin(t * p.phaseSpeed + p.phase);
        const twinkle = 0.85 + 0.15 * Math.sin(t * p.twinkleSpeed + p.twinklePhase);
        const alpha = p.baseAlpha * breath * twinkle;

        if (p.isAnchor) {
          ctx.fillStyle = `rgba(127, 255, 160, ${alpha * 0.05})`;
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size * 4.5 * dpr, 0, Math.PI * 2);
          ctx.fill();

          ctx.fillStyle = `rgba(127, 255, 160, ${alpha * 0.14})`;
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size * 2.5 * dpr, 0, Math.PI * 2);
          ctx.fill();

          ctx.fillStyle = `rgba(127, 255, 160, ${alpha * 0.45})`;
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.size * 1.4 * dpr, 0, Math.PI * 2);
          ctx.fill();
        }

        ctx.fillStyle = `rgba(127, 255, 160, ${alpha})`;
        ctx.beginPath();
        ctx.arc(p.x, p.y, p.size * dpr, 0, Math.PI * 2);
        ctx.fill();

        if (p.showNumber) {
          const numAlpha = alpha * 0.55;
          ctx.fillStyle = `rgba(127, 255, 160, ${numAlpha})`;
          ctx.fillText(
            p.number,
            p.x + (p.size + NUMBER_GAP_PX) * dpr,
            p.y,
          );
        }
      }
    };

    if (reduced) {
      draw(0, 0);
      return () => window.removeEventListener("resize", onResize);
    }

    const tick = (timestamp: number) => {
      const t = timestamp / 1000;
      const dt = lastT === 0 ? 0 : Math.min(0.05, t - lastT);
      lastT = t;
      draw(dt, t);
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);

    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  return (
    <div
      aria-hidden
      className="pointer-events-none absolute inset-0 overflow-hidden"
    >
      <canvas ref={canvasRef} className="absolute inset-0 h-full w-full" />
    </div>
  );
}