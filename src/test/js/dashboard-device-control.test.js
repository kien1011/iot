const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function classList(initial = []) {
  const values = new Set(initial);
  return {
    add(...names) { names.forEach((name) => values.add(name)); },
    remove(...names) { names.forEach((name) => values.delete(name)); },
    toggle(name, force) {
      if (force === undefined) {
        if (values.has(name)) values.delete(name); else values.add(name);
        return values.has(name);
      }
      if (force) values.add(name); else values.delete(name);
      return force;
    },
    contains(name) { return values.has(name); },
  };
}

function makeCard() {
  const onButton = { disabled: false, classList: classList(), setAttribute() {} };
  const offButton = { disabled: true, classList: classList(['is-active']), setAttribute() {} };
  const statusText = { textContent: 'OFF' };
  const pill = {
    classList: classList(['off']),
    querySelector(selector) {
      if (selector === '.status-text') return statusText;
      throw new Error(`Unexpected pill selector ${selector}`);
    },
  };
  const updated = { textContent: 'Last updated: --' };
  const attributes = new Map([['aria-busy', 'false']]);

  return {
    dataset: {
      deviceId: '1',
      ready: 'true',
      status: 'OFF',
      confirmedStatus: 'OFF',
      pendingAction: 'ON',
      pendingHistoryId: '15',
      pending: 'true',
    },
    getAttribute(name) { return attributes.get(name) ?? null; },
    setAttribute(name, value) { attributes.set(name, String(value)); },
    querySelector(selector) {
      if (selector === '.on-btn') return onButton;
      if (selector === '.off-btn') return offButton;
      if (selector === '.status-pill') return pill;
      if (selector === '.light-updated') return updated;
      throw new Error(`Unexpected card selector ${selector}`);
    },
    parts: { onButton, offButton, statusText, updated, pill },
  };
}

async function main() {
  const source = fs.readFileSync('src/main/resources/static/js/dashboard.js', 'utf8');
  const api = {
    async get(path) {
      assert.equal(path, '/api/devices/1/status');
      return {
        deviceId: 1,
        status: 'ON',
        updatedAt: '2026-10-07T08:15:30',
      };
    },
    async post() { throw new Error('post not expected'); },
  };

  const context = {
    console,
    AppApi: api,
    AppSession: { ready: Promise.resolve() },
    Chart: function Chart() {},
    getComputedStyle() { return { getPropertyValue() { return ''; } }; },
    WebSocket: function WebSocket() {},
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    document: {
      addEventListener() {},
      querySelectorAll() { return []; },
      getElementById() { return null; },
      documentElement: {},
    },
    window: {
      addEventListener() {},
      location: { protocol: 'http:', host: 'localhost:8080' },
    },
  };
  vm.createContext(context);
  vm.runInContext(source, context, { filename: 'dashboard.js' });

  assert.equal(
    typeof context.reconcilePendingDeviceStatus,
    'function',
    'dashboard must be able to reconcile a pending command from confirmed backend status before timeout'
  );

  const card = makeCard();
  const confirmed = await context.reconcilePendingDeviceStatus(card);

  assert.equal(confirmed, true);
  assert.equal(card.dataset.pending, undefined);
  assert.equal(card.dataset.status, 'ON');
  assert.equal(card.dataset.confirmedStatus, 'ON');
  assert.equal(card.parts.statusText.textContent, 'ON');
  assert.equal(card.parts.onButton.classList.contains('is-active'), true);
  assert.equal(card.parts.offButton.classList.contains('is-active'), false);
  assert.equal(card.parts.onButton.disabled, true);
  assert.equal(card.parts.offButton.disabled, false);
  assert.equal(card.parts.updated.textContent, 'Last updated: 08:15:30');

  const wsCard = makeCard();
  context.document.getElementById = (id) => id === 'light-1-card' ? wsCard : null;
  context.handleDeviceUpdate({
    actionHistoryId: 15,
    deviceId: 1,
    status: 'ON',
    updatedAt: '2026-10-07T08:16:40',
  });
  assert.equal(wsCard.dataset.pending, undefined);
  assert.equal(wsCard.dataset.status, 'ON');
  assert.equal(wsCard.parts.statusText.textContent, 'ON');
  assert.equal(wsCard.parts.onButton.classList.contains('is-active'), true);
  assert.equal(wsCard.parts.offButton.classList.contains('is-active'), false);
  assert.equal(wsCard.parts.updated.textContent, 'Last updated: 08:16:40');

  // While waiting for hardware confirmation, only the status pill becomes LOADING.
  // The previously confirmed button remains active and Last updated stays unchanged.
  const loadingCard = makeCard();
  loadingCard.parts.updated.textContent = 'Last updated: 08:10:00';
  context.setStatusPill(loadingCard, 'LOADING');
  context.updateLightButtons(loadingCard);
  assert.equal(loadingCard.parts.statusText.textContent, 'LOADING');
  assert.equal(loadingCard.parts.onButton.classList.contains('is-active'), false);
  assert.equal(loadingCard.parts.offButton.classList.contains('is-active'), true);
  assert.equal(loadingCard.parts.onButton.disabled, true);
  assert.equal(loadingCard.parts.offButton.disabled, true);
  assert.equal(loadingCard.getAttribute('aria-busy'), 'false');
  assert.equal(loadingCard.parts.updated.textContent, 'Last updated: 08:10:00');

  // A backend timeout removes LOADING only: confirmed state, active button and time stay unchanged.
  assert.equal(
    typeof context.handleDeviceTimeout,
    'function',
    'dashboard must handle backend device:timeout events'
  );
  const timeoutCard = makeCard();
  timeoutCard.parts.updated.textContent = 'Last updated: 08:10:00';
  context.document.getElementById = (id) => id === 'light-1-card' ? timeoutCard : null;
  context.handleDeviceTimeout({ actionHistoryId: 15, deviceId: 1 });
  assert.equal(timeoutCard.dataset.pending, undefined);
  assert.equal(timeoutCard.dataset.status, 'OFF');
  assert.equal(timeoutCard.dataset.confirmedStatus, 'OFF');
  assert.equal(timeoutCard.parts.statusText.textContent, 'OFF');
  assert.equal(timeoutCard.parts.onButton.classList.contains('is-active'), false);
  assert.equal(timeoutCard.parts.offButton.classList.contains('is-active'), true);
  assert.equal(timeoutCard.parts.onButton.disabled, false);
  assert.equal(timeoutCard.parts.offButton.disabled, true);
  assert.equal(timeoutCard.parts.updated.textContent, 'Last updated: 08:10:00');

  console.log('dashboard device control reconciliation: PASS');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
