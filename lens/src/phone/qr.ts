// Reads a pairing QR from a photo taken through the Even App bridge. The web view has no
// getUserMedia, so captureImage() (one still frame, base64) and jsQR are the only route.
import jsQR from 'jsqr';

export interface CapturedImage { base64: string; mimeType?: string }

/** jsQR costs time per pixel and phone photos are 12 MP. */
const MAX_SIDE = 1200;

export async function decodeQr(image: CapturedImage): Promise<string | null> {
  const src = image.base64.startsWith('data:') ? image.base64 : `data:${image.mimeType || 'image/jpeg'};base64,${image.base64}`;
  const img = await loadImage(src);
  const scale = Math.min(1, MAX_SIDE / Math.max(img.width, img.height));
  const w = Math.max(1, Math.round(img.width * scale));
  const h = Math.max(1, Math.round(img.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(img, 0, 0, w, h);
  // The Mac's code may be light on dark; jsQR assumes dark on light, so it tries both.
  return jsQR(ctx.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'attemptBoth' })?.data ?? null;
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Could not read the photo.'));
    img.src = src;
  });
}
