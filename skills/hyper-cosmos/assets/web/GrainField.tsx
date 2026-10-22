"use client";
import { useEffect, useRef } from "react";

/**
 * GrainField — live film-grain overlay. A small canvas regenerates a
 * grayscale noise pattern at ~24 fps and is upscaled to viewport size
 * via CSS. The result is a subtle, moving film texture instead of a
 * static tiled SVG.
 *
 * Respects prefers-reduced-motion: a single frame is drawn and the
 * loop is not started.
 */

const W = 960;
const H = 540;
const FPS = 24;

export function GrainField() {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    canvas.width = W;
    canvas.height = H;

    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    const renderFrame = () => {
      const imageData = ctx.createImageData(W, H);
      const data = imageData.data;
      for (let i = 0; i < data.length; i += 4) {
        const v = Math.random() * 255;
        data[i] = v;
        data[i + 1] = v;
        data[i + 2] = v;
        data[i + 3] = Math.random() * 90;
      }
      ctx.putImageData(imageData, 0, 0);
    };

    if (reduced) {
      renderFrame();
      return;
    }

    let raf = 0;
    let lastT = 0;
    const interval = 1000 / FPS;

    const tick = (t: number) => {
      if (t - lastT >= interval) {
        lastT = t;
        renderFrame();
      }
      raf = requestAnimationFrame(tick);
    };

    raf = requestAnimationFrame(tick);

    return () => cancelAnimationFrame(raf);
  }, []);

  return (
    <canvas
      ref={ref}
      aria-hidden
      className="fixed inset-0 pointer-events-none z-[60] w-full h-full"
      style={{
        mixBlendMode: "overlay",
        opacity: 0.32,
      }}
    />
  );
}
