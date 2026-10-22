// `node integration.ts install|uninstall`: the integration (hooks, the status line tap, the `claude`
// shell function) put in or taken out by the daemon's own code while the daemon isn't running, such
// as during a container's build or before the service's first start. `hypernova setup` runs this
// file from the installed daemon, in a process of its own, because the daemon's code needs the
// packages installed beside it (the command itself may run from a checkout or a tarball without
// them). It prints the integration's status as JSON on its last line.
import { installIntegration, uninstallIntegration } from '../hooks.ts'
import { writeHnBin } from '../terms.ts'

const on = process.argv[2] !== 'uninstall'
try {
  // The `claude` function calls <support>/bin/hn: written now, so it works before the daemon's
  // first start (the daemon writes it again each time it starts).
  if (on) writeHnBin()
  const status = on ? installIntegration() : uninstallIntegration()
  process.stdout.write('\n' + JSON.stringify(status) + '\n')
  process.exit(0)
} catch (e: any) {
  process.stderr.write(String(e?.message ?? e) + '\n')
  process.exit(1)
}
