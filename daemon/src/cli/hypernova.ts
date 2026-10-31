// `hypernova`: Hypernova's command on a Linux server or PC (docs/LINUX.md), where there is no menu
// bar app: setup, pair, status, doctor, logs, lan, codex, auto-update, uninstall, and run (the daemon in the
// foreground). The installed launcher (~/.local/bin/hypernova) runs this file with Hypernova's
// own Node.js; from a checkout, `node daemon/src/cli/hypernova.ts setup` installs from there.
import { cliContext } from './context.ts'
import { HELP, autoUpdate, automation, codex, doctor, integrate, lan, logs, pair, parseArgs, setup, status, uninstall } from './commands.ts'

const { cmd, flags, rest } = parseArgs(process.argv.slice(2))
const cli = cliContext()

async function main(): Promise<number> {
  switch (cmd) {
    case 'setup':
    case 'install':
      return setup(cli, flags)
    case 'integrate':
      return integrate(cli)
    case 'pair':
      return pair(cli, flags)
    case 'status':
      return status(cli, flags)
    case 'doctor':
      return doctor(cli, flags)
    case 'logs':
    case 'log':
      return logs(cli, flags)
    case 'lan':
      return lan(cli, rest)
    case 'codex':
      return codex(cli, rest)
    case 'auto-update':
    case 'autoupdate':
      return autoUpdate(cli, rest)
    case 'uninstall':
      return uninstall(cli, flags)
    case 'automation':
    case 'automations':
      return automation(cli, flags, rest)
    case 'run':
      // The daemon itself, in this process: what the service runs, for a container's command or
      // for watching it work. Its log lines come to the terminal too.
      process.env.HYPERNOVA_LOG_STDOUT ??= '1'
      await import('../main.ts')
      return new Promise<number>(() => {})
    case 'version':
    case '--version':
    case '-v': {
      const { VERSION } = await import('../util.ts')
      cli.sys.say(`hypernova ${VERSION}`)
      return 0
    }
    case 'help':
    case '--help':
    case '-h':
      cli.sys.say(HELP)
      return 0
    default:
      cli.sys.say(`hypernova: no command “${cmd}”.\n`)
      cli.sys.say(HELP)
      return 2
  }
}

main().then(
  (code) => process.exit(code),
  (e) => {
    process.stderr.write(`hypernova: ${e?.message ?? e}\n`)
    process.exit(1)
  },
)
