// Merge committed readings by database ID, never by a client-generated timestamp.
window.DashboardState = class DashboardState {
  constructor() {
    this.series = { temperature: [], humidity: [], light: [] };
    this.devices = { 1: { ready: false, busy: false, revision: 0 }, 2: { ready: false, busy: false, revision: 0 } };
  }
  mergeSeries(name, points) {
    const unique = new Map(this.series[name].map(point => [point.id, point]));
    for (const point of points) if (point.id != null) unique.set(point.id, point);
    this.series[name] = [...unique.values()].sort((a, b) => a.id - b.id).slice(-20);
  }
  mergeChart(snapshot) {
    for (const name of Object.keys(this.series)) this.mergeSeries(name, snapshot[name]);
  }
  mergeReading(reading) {
    for (const name of Object.keys(this.series)) {
      if (reading.ids[name] != null) this.mergeSeries(name, [{ id: reading.ids[name], value: reading[name], time: reading.time }]);
    }
  }
  applySnapshot(device, expectedRevision) {
    const state = this.devices[device.deviceId];
    if (state.revision !== expectedRevision) return;
    Object.assign(state, { status: device.status, updatedAt: device.updatedAt, ready: true });
  }
  applyResult(device) {
    const state = this.devices[device.deviceId];
    Object.assign(state, { status: device.status, updatedAt: device.updatedAt, ready: true, busy: false,
      revision: state.revision + 1 });
  }
  begin(deviceId, action) {
    const state = this.devices[deviceId];
    if (!state.ready || state.busy || state.status === action) return null;
    state.busy = true;
    state.revision += 1;
    return state.revision;
  }
};
