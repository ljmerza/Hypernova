// A failure in the few words a lens line holds (the phone shows the long version).
import { ApiError } from '../data/api.ts';

export function lensWords(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.problem) {
      case 'update_mac': return 'Update Hypernova on the Mac first';
      case 'unreachable': return 'Can’t reach the Mac';
      case 'timeout': return 'The Mac didn’t answer';
      case 'unauthorized': return 'Pair again on your phone';
      case 'missing': return 'The Mac can’t do that yet';
      default: return err.message;
    }
  }
  return err instanceof Error ? err.message : String(err);
}
