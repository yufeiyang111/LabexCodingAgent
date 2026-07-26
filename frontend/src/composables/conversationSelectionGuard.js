export function createConversationSelectionGuard() {
  let generation = 0

  return {
    capture() {
      return generation
    },
    invalidate() {
      generation += 1
      return generation
    },
    isCurrent(capturedGeneration) {
      return capturedGeneration === generation
    }
  }
}