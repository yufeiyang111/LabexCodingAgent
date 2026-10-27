import assert from 'node:assert/strict'
import test from 'node:test'

import { isHashingAvailable, resolveCrypto, sha256Hex, toHex, trySha256Hex } from './contentHash.js'

const HELLO_SHA256 = '2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824'

test('hashes content with WebCrypto when available', async () => {
  assert.equal(isHashingAvailable(), true)
  assert.equal(await sha256Hex('hello'), HELLO_SHA256)
})

test('hashing is deterministic and empty-safe', async () => {
  assert.equal(await sha256Hex(''), await sha256Hex(''))
  assert.equal(await sha256Hex('a\nb'), await sha256Hex('a\nb'))
  assert.notEqual(await sha256Hex('a\nb'), await sha256Hex('ab'))
  assert.equal(await sha256Hex(null), await sha256Hex(''))
})

test('reports unavailable crypto instead of silently substituting a weak hash', async () => {
  assert.equal(resolveCrypto({}), null)
  assert.equal(isHashingAvailable({}), false)
  assert.equal(await trySha256Hex('hello', { crypto: {} }), null)
  await assert.rejects(() => sha256Hex('hello', { crypto: {} }), /WebCrypto unavailable/)
})

test('formats bytes as lower-case hex', () => {
  assert.equal(toHex(new Uint8Array([0, 15, 255])), '000fff')
})
