const OPEN_STATE = 1

export class CdpClient {
  #socket
  #nextId = 1
  #pending = new Map()
  #listeners = new Map()
  #timeoutMs

  constructor(socket, options = {}) {
    if (!socket || typeof socket.send !== 'function') {
      throw new TypeError('A WebSocket-compatible transport is required')
    }
    this.#socket = socket
    this.#timeoutMs = Number.isFinite(options.timeoutMs) ? options.timeoutMs : 10_000
    socket.addEventListener('message', event => this.#handleMessage(event))
    socket.addEventListener('close', () => this.#rejectAll(new Error('CDP WebSocket closed')))
    socket.addEventListener('error', () => this.#rejectAll(new Error('CDP WebSocket failed')))
  }

  static async connect(webSocketUrl, options = {}) {
    const WebSocketImpl = options.WebSocketImpl ?? globalThis.WebSocket
    if (typeof WebSocketImpl !== 'function') {
      throw new Error('This Node runtime does not provide WebSocket')
    }
    const socket = new WebSocketImpl(webSocketUrl)
    await waitForOpen(socket, options.connectTimeoutMs ?? 10_000)
    return new CdpClient(socket, options)
  }

  send(method, params = {}) {
    if (this.#socket.readyState !== OPEN_STATE && this.#socket.readyState !== this.#socket.OPEN) {
      return Promise.reject(new Error(`CDP WebSocket is not open (state=${this.#socket.readyState})`))
    }

    const id = this.#nextId++
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id)
        reject(new Error(`CDP request timed out: ${method}`))
      }, this.#timeoutMs)
      this.#pending.set(id, { method, resolve, reject, timer })
      try {
        this.#socket.send(JSON.stringify({ id, method, params }))
      } catch (error) {
        clearTimeout(timer)
        this.#pending.delete(id)
        reject(error)
      }
    })
  }

  async evaluate(expression, options = {}) {
    const response = await this.send('Runtime.evaluate', {
      expression,
      awaitPromise: options.awaitPromise ?? true,
      returnByValue: options.returnByValue ?? true,
      userGesture: options.userGesture ?? true
    })
    if (response.exceptionDetails) {
      const detail = response.exceptionDetails.exception?.description
        ?? response.exceptionDetails.text
        ?? 'Unknown browser evaluation error'
      throw new Error(detail)
    }
    return response.result?.value
  }

  on(method, listener) {
    const listeners = this.#listeners.get(method) ?? new Set()
    listeners.add(listener)
    this.#listeners.set(method, listeners)
    return () => {
      listeners.delete(listener)
      if (listeners.size === 0) this.#listeners.delete(method)
    }
  }

  close() {
    this.#rejectAll(new Error('CDP client closed'))
    if (typeof this.#socket.close === 'function') this.#socket.close()
  }

  #handleMessage(event) {
    let message
    try {
      message = JSON.parse(String(event.data))
    } catch {
      return
    }

    if (message.id != null) {
      const pending = this.#pending.get(message.id)
      if (!pending) return
      clearTimeout(pending.timer)
      this.#pending.delete(message.id)
      if (message.error) {
        pending.reject(new Error(`CDP ${pending.method} failed: ${message.error.message ?? 'unknown error'}`))
      } else {
        pending.resolve(message.result ?? {})
      }
      return
    }

    if (message.method) {
      for (const listener of this.#listeners.get(message.method) ?? []) {
        listener(message.params ?? {})
      }
    }
  }

  #rejectAll(error) {
    for (const pending of this.#pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.#pending.clear()
  }
}

function waitForOpen(socket, timeoutMs) {
  if (socket.readyState === OPEN_STATE || socket.readyState === socket.OPEN) return Promise.resolve()
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      cleanup()
      reject(new Error('Timed out connecting to CDP WebSocket'))
    }, timeoutMs)
    const cleanup = () => {
      clearTimeout(timer)
      socket.removeEventListener?.('open', onOpen)
      socket.removeEventListener?.('error', onError)
      socket.removeEventListener?.('close', onClose)
    }
    const onOpen = () => {
      cleanup()
      resolve()
    }
    const onError = () => {
      cleanup()
      reject(new Error('Failed to connect to CDP WebSocket'))
    }
    const onClose = () => {
      cleanup()
      reject(new Error('CDP WebSocket closed before opening'))
    }
    socket.addEventListener('open', onOpen)
    socket.addEventListener('error', onError)
    socket.addEventListener('close', onClose)
  })
}
