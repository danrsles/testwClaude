// Signs session tokens for the load test's made-up users, as Clerk would for
// real ones, with a local key that only LoadTestApplication trusts.
//
//   node loadtest/make-tokens.mjs [users] [hours]
//
// The first run makes the key pair in loadtest/keys/. Every run writes
// loadtest/tokens.json: one token per user, load_user_1 to load_user_<users>
// (1000), valid for <hours> (2). Both are git-ignored. Anyone with the private
// key can sign in as anyone wherever the public key is trusted, which is only
// ever the load test.
//
// Node's crypto only, so there is nothing to install.

import { generateKeyPairSync, createPrivateKey, sign } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// The same as LoadTestSecurityConfiguration.ISSUER.
const ISSUER = 'http://loadtest.invalid'

const here = dirname(fileURLToPath(import.meta.url))
const keys = join(here, 'keys')
const privatePath = join(keys, 'private.pem')
const publicPath = join(keys, 'public.pem')

const users = Number(process.argv[2] ?? 1000)
const hours = Number(process.argv[3] ?? 2)

if (!existsSync(privatePath)) {
  mkdirSync(keys, { recursive: true })
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  writeFileSync(privatePath, privateKey.export({ type: 'pkcs8', format: 'pem' }))
  writeFileSync(publicPath, publicKey.export({ type: 'spki', format: 'pem' }))
  console.log(`Made a key pair in ${keys}`)
}
const privateKey = createPrivateKey(readFileSync(privatePath))

const base64url = (value) => Buffer.from(value).toString('base64url')
const header = base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))
const now = Math.floor(Date.now() / 1000)

const tokens = []
for (let n = 1; n <= users; n++) {
  // username matches seed.sql, so the API finds nothing to sync and never writes the user.
  const claims = { sub: `load_user_${n}`, username: `load_${n}`, iss: ISSUER, iat: now, exp: now + hours * 3600 }
  const unsigned = `${header}.${base64url(JSON.stringify(claims))}`
  // RS256 is RSASSA-PKCS1-v1_5 with SHA-256, Node's default for an RSA key.
  tokens.push(`${unsigned}.${sign('sha256', Buffer.from(unsigned), privateKey).toString('base64url')}`)
}

writeFileSync(join(here, 'tokens.json'), JSON.stringify(tokens))
console.log(`Wrote ${users} tokens to loadtest/tokens.json, valid for ${hours} h`)
