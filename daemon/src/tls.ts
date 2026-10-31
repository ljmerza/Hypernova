// Certificates for the LAN listener (docs/PROTOCOL.md, "Local network (direct HTTPS)"): the
// daemon's own self-signed server certificate (the phone pins it), its device CA, and the device
// certificates it signs from a phone's CSR (mutual TLS). Everything lives in <support>/tls/, made
// once and kept. The system's openssl does the ASN.1 work (/usr/bin/openssl: LibreSSL on macOS,
// OpenSSL on Linux; never Homebrew's, which may not be there), Node checks what it made.
import { execFileSync, spawnSync } from 'node:child_process'
import { X509Certificate, createHash, createPublicKey, randomBytes } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { SUPPORT, log } from './util.ts'
import { identity } from './store.ts'
import { MACHINE } from './platform.ts'

export const TLS_DIR = join(SUPPORT, 'tls')
const OPENSSL = existsSync('/usr/bin/openssl') ? '/usr/bin/openssl' : 'openssl'
const TEN_YEARS = 3650
const TWO_YEARS = 730

export type ServerIdentity = {
  /** PEM, for the HTTPS server. */
  key: string
  cert: string
  ca: string
  caDer: Buffer
  /** Lowercase hex SHA-256 of the server certificate's DER: what the phone pins. */
  fingerprint: string
}
export class TlsError extends Error {
  code: 'bad_csr' | 'no_tls'
  constructor(code: 'bad_csr' | 'no_tls', message: string) {
    super(message)
    this.code = code
  }
}

/** The fingerprint the protocol uses everywhere: lowercase hex SHA-256 of a certificate's DER, no colons. */
export const certFingerprint = (der: Buffer): string => createHash('sha256').update(der).digest('hex')

function openssl(args: string[]): Buffer {
  return execFileSync(OPENSSL, args, { stdio: ['ignore', 'pipe', 'pipe'], timeout: 20_000, maxBuffer: 1 << 20 })
}

/** A fresh EC P-256 private key, readable by the daemon alone. */
function makeKey(path: string): void {
  openssl(['ecparam', '-genkey', '-name', 'prime256v1', '-noout', '-out', path])
  chmodSync(path, 0o600)
}

/** A self-signed certificate for [keyPath], ten years, with these X.509 v3 extensions. */
function selfSign(keyPath: string, certPath: string, cn: string, exts: string[]): void {
  const args = ['req', '-new', '-x509', '-key', keyPath, '-out', certPath, '-days', String(TEN_YEARS), '-sha256', '-subj', `/CN=${cn}`]
  for (const e of exts) args.push('-addext', e)
  openssl(args)
  chmodSync(certPath, 0o600)
}

let ident: ServerIdentity | null | undefined

/**
 * The daemon's server certificate and device CA, made on the first call if they're missing and
 * read back from <support>/tls/ after that. `null` when openssl can't make them: the daemon then
 * runs without the LAN listener, and says so in the log.
 */
export function serverIdentity(): ServerIdentity | null {
  if (ident !== undefined) return ident
  const key = join(TLS_DIR, 'server.key')
  const cert = join(TLS_DIR, 'server.crt')
  const caKey = join(TLS_DIR, 'ca.key')
  const ca = join(TLS_DIR, 'ca.crt')
  try {
    mkdirSync(TLS_DIR, { recursive: true, mode: 0o700 })
    chmodSync(TLS_DIR, 0o700)
    if (!existsSync(key) || !existsSync(cert)) {
      makeKey(key)
      selfSign(key, cert, `Hypernova ${identity.id}`, ['basicConstraints=critical,CA:FALSE', 'keyUsage=critical,digitalSignature', 'extendedKeyUsage=serverAuth'])
      log('tls: made the server certificate')
    }
    if (!existsSync(caKey) || !existsSync(ca)) {
      makeKey(caKey)
      selfSign(caKey, ca, `Hypernova device CA ${identity.id}`, ['basicConstraints=critical,CA:TRUE,pathlen:0', 'keyUsage=critical,keyCertSign,cRLSign'])
      log('tls: made the device CA')
    }
    const certPem = readFileSync(cert, 'utf8')
    const caPem = readFileSync(ca, 'utf8')
    ident = { key: readFileSync(key, 'utf8'), cert: certPem, ca: caPem, caDer: new X509Certificate(caPem).raw, fingerprint: certFingerprint(new X509Certificate(certPem).raw) }
  } catch (e) {
    log('tls: no certificates, so no LAN listener', e)
    ident = null
  }
  return ident
}

/** `expiresAt`: when the device certificate runs out (epoch ms), kept with the device. */
export type IssuedCert = { cert: Buffer; ca: Buffer; fingerprint: string; certFingerprint: string; expiresAt: number }

/**
 * A device certificate from a phone's CSR (base64 DER PKCS#10): CN must be the device's id, the key
 * EC P-256, the signature good. Two years, client auth only. The caller records `certFingerprint`
 * with the device; `fingerprint` is the server's, for the phone's pin.
 */
export function issueDeviceCert(deviceId: string, csrBase64: string): IssuedCert {
  const id = serverIdentity()
  if (!id) throw new TlsError('no_tls', `This ${MACHINE} has no certificates for the local network.`)
  const der = Buffer.from(String(csrBase64 ?? ''), 'base64')
  // A PKCS#10 request is one DER SEQUENCE; a phone's is a few hundred bytes.
  if (der.length < 64 || der.length > 8192 || der[0] !== 0x30) throw new TlsError('bad_csr', 'Send the certificate request as base64 DER.')
  // LibreSSL's `x509 -req` reads only PEM requests, so wrap the DER ourselves.
  const pem = `-----BEGIN CERTIFICATE REQUEST-----\n${der.toString('base64').match(/.{1,64}/g)!.join('\n')}\n-----END CERTIFICATE REQUEST-----\n`
  const dir = mkdtempSync(join(TLS_DIR, 'csr-'))
  try {
    const csr = join(dir, 'req.pem')
    writeFileSync(csr, pem, { mode: 0o600 })
    // LibreSSL exits 0 on "verify failure" and only says so: read what it says.
    const v = spawnSync(OPENSSL, ['req', '-verify', '-noout', '-in', csr], { encoding: 'utf8', timeout: 20_000 })
    if (v.status !== 0 || !/verify OK/.test(v.stdout + v.stderr)) throw new TlsError('bad_csr', 'That certificate request is not valid.')
    // "subject=/CN=x" (LibreSSL) or "subject=CN = x" (OpenSSL 3): one shape before comparing.
    const subject = openssl(['req', '-noout', '-subject', '-in', csr]).toString('utf8').trim().replace(/^subject=\s*/, '').replace(/^\//, '').replace(/\s*=\s*/g, '=')
    if (subject !== `CN=${deviceId}`) throw new TlsError('bad_csr', 'The request’s subject must be CN=<your device id>.')
    const pub = createPublicKey(openssl(['req', '-noout', '-pubkey', '-in', csr]).toString('utf8'))
    if (pub.asymmetricKeyType !== 'ec' || pub.asymmetricKeyDetails?.namedCurve !== 'prime256v1') throw new TlsError('bad_csr', 'The key must be EC P-256.')
    const ext = join(dir, 'ext.cnf')
    writeFileSync(ext, 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\nsubjectKeyIdentifier=hash\n', { mode: 0o600 })
    const out = join(dir, 'cert.der')
    openssl(['x509', '-req', '-in', csr, '-CA', join(TLS_DIR, 'ca.crt'), '-CAkey', join(TLS_DIR, 'ca.key'), '-set_serial', '0x' + randomBytes(16).toString('hex'), '-days', String(TWO_YEARS), '-sha256', '-extfile', ext, '-outform', 'DER', '-out', out])
    const cert = readFileSync(out)
    const x = new X509Certificate(cert)
    // What openssl wrote must be what we meant: our CA's signature, the device's name.
    if (!x.verify(createPublicKey(id.ca)) || x.subject !== `CN=${deviceId}`) throw new TlsError('bad_csr', 'The certificate could not be made.')
    return { cert, ca: id.caDer, fingerprint: id.fingerprint, certFingerprint: certFingerprint(cert), expiresAt: Date.parse(x.validTo) }
  } catch (e) {
    if (e instanceof TlsError) throw e
    // openssl refusing the request somewhere else (a signature it only checks when signing, say):
    // the request's fault as far as the phone is concerned, with the detail in the log.
    log('tls: device certificate request refused', e instanceof Error ? e.message : e)
    throw new TlsError('bad_csr', 'That certificate request could not be signed.')
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}
