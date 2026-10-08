"use strict";
// Deal UI JS runtime. Host supplies module loading and effect scheduling only.
globalThis.createDealUiSession = function(entry, rt, restoredState, scheduleEffect) {
  const call = (name, ...args) => {
    const fn = entry[name];
    if (!fn || fn.$kind !== 'function') throw Error('Missing checked UI export: ' + name);
    return fn.$f(...args);
  };
  let state = call('bridgeInitialState');
  if (restoredState !== null) state = rt.checkType(entry.bridgeInitialState.$sig.substring(4), Object.assign(state, restoredState));
  let store = call('initialStore');
  let current = call('initial', state, store);
  store = current.store;
  let status = call('bridgeSessionStatus');
  function visibleSlot(node, slot) {
    return node.props.some(prop => prop.kind === 'action' && prop.actionSlot === slot) || node.children.some(child => visibleSlot(child, slot));
  }
  function enqueue(action, completion = false) {
    const queued = call(completion ? 'complete' : 'enqueue', store, action);
    store = queued.store;
    if (queued.accepted && queued.startDrain) drain();
  }
  function drain() {
    try {
      while (!store.lifecycle.disposed) {
        const next = call('dequeue', store);
        store = next.store;
        if (!next.present) break;
        try {
          const candidate = call('nextState', state, next.action);
          const transition = call('transitionFromCandidate', candidate, current.tree, store, next.action);
          state = candidate; current = transition; store = transition.store;
          status = call('bridgeSessionCommitted', status);
          if (transition.effect.effectId >= 0) {
            const snapshot = state, action = next.action;
            status = call('bridgeSessionStarted', status);
            scheduleEffect(
              () => call('bridgeEffect' + transition.effect.effectId, snapshot, action),
              result => enqueue(result, true),
              error => { if (!store.lifecycle.disposed) status = call('bridgeSessionFailed', status, rt.reifyError(error).message || String(error), true); },
              () => { status = call('bridgeSessionExited', status); }
            );
          }
        } catch (error) { store = call('reject', store); status = call('bridgeSessionFailed', status, String(error), false); }
      }
    } finally { store = call('finish', store); }
  }
  return Object.freeze({
    snapshot: () => JSON.stringify({tree: current.tree, version: status.version, fault: status.fault}),
    state: () => { if (!call('bridgeReplacementReady', status)) throw Error('Wait for current work before replacing the experience'); return JSON.stringify(state); },
    dispatch: (slot, payload = null) => {
      if (!Number.isInteger(slot) || slot < 0 || !visibleSlot(current.tree, slot)) throw Error('Invalid or stale action slot');
      // Payload type comes from the checked action signature, never from the caller.
      const declared = call('payloadType_' + slot);
      let value = payload;
      if (declared === 'int') { if (!/^-?\d+$/.test(String(payload))) throw Error('Expected an integer payload'); value = parseInt(payload, 10); }
      else if (declared === 'boolean') { if (payload !== 'true' && payload !== 'false') throw Error('Expected a boolean payload'); value = payload === 'true'; }
      else if (declared === 'string') { if (typeof payload !== 'string') throw Error('Expected a string payload'); }
      else if (payload !== null) throw Error('This action takes no payload');
      enqueue(payload === null && declared === 'none' ? call('action_' + slot) : call('action_' + slot, value));
      return '';
    },
    dispose: () => { store = call('dispose', store); return ''; }
  });
};
