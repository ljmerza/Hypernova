#!/usr/bin/env python3
"""Drive the Even Hub simulator through Lens screens and save the glasses framebuffer after each step,
composited on black and scaled 2x. The simulator must be running with --automation-port (default 9917).

  python3 test/sim-shots.py <outDir> [step ...]
  steps: shot:<name> | up | down | click | double_click | long_press | long_press_release | context_menu | wait:<s>

Example (sample data, the dev server on 5177):
  npx evenhub-simulator 'http://127.0.0.1:5177/?demo=1' --automation-port 9917 &
  python3 test/sim-shots.py shots/sim shot:glance click wait:1 shot:needs
"""
import io, json, os, sys, time, urllib.request
from PIL import Image

PORT = os.environ.get('SIM_PORT', '9917')
API = f'http://127.0.0.1:{PORT}/api'
out = sys.argv[1] if len(sys.argv) > 1 else 'shots/sim'
steps = sys.argv[2:] or ['shot:glance']
os.makedirs(out, exist_ok=True)


def get(path):
    with urllib.request.urlopen(API + path, timeout=10) as r:
        return r.read()


def post(path, body):
    req = urllib.request.Request(API + path, data=json.dumps(body).encode(), headers={'Content-Type': 'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.read()


last_id = 0


def console():
    global last_id
    data = json.loads(get(f'/console?since_id={last_id}'))
    entries = data if isinstance(data, list) else data.get('entries', [])
    for e in entries:
        last_id = max(last_id, e.get('id', 0))
        msg = e.get('message', '')
        if 'IPC custom protocol' in msg or 'ipc://localhost' in msg or "Couldn't find callback id" in msg:
            continue
        if e.get('level') in ('error', 'warn') or msg.startswith('[uncaught]') or msg.startswith('[unhandledrejection]') or msg.startswith('[lens]'):
            print('  console:', e.get('level'), msg[:300])


for step in steps:
    if step.startswith('shot:'):
        img = Image.open(io.BytesIO(get('/screenshot/glasses'))).convert('RGBA')
        bg = Image.new('RGBA', img.size, (0, 0, 0, 255))
        bg.alpha_composite(img)
        path = f'{out}/{step[5:]}.png'
        bg.convert('RGB').resize((img.width * 2, img.height * 2), Image.NEAREST).save(path)
        print(path)
    elif step.startswith('wait:'):
        time.sleep(float(step[5:]))
    else:
        post('/input', {'action': step})
        print('input', step)
        time.sleep(0.3)
    console()
