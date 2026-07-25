export function loadWorkspaceResources(loaders) {
  return Promise.allSettled(loaders.map(loader => loader()))
}
