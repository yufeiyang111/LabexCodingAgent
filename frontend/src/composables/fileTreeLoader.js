export async function retryRequest(request, { attempts = 2, delayMs = 200 } = {}) {
  const totalAttempts = Math.max(1, Number.isInteger(attempts) ? attempts : 2)
  let lastError

  for (let attempt = 0; attempt < totalAttempts; attempt += 1) {
    try {
      return await request()
    } catch (error) {
      lastError = error
      if (attempt < totalAttempts - 1 && delayMs > 0) {
        await new Promise(resolve => setTimeout(resolve, delayMs))
      }
    }
  }

  throw lastError
}

export const loadFileTreeWithRetry = retryRequest
