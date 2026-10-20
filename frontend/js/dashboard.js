// REST snapshots + a single WebSocket. There is no synthetic sensor stream.
const MAX_CHART_POINTS = 20;
const series = {temperature: new Map(), humidity: new Map(), light: new Map()};
const lightStates = new Map();
let chart;
let latestTime = "";
let latestSensorId = 0;
let sensorRevision = 0;
let snapshotSequence = 0;
let socket;
let reconnectTimer;
let active = true;

document.addEventListener("DOMContentLoaded", async () => {
  initChart();
  document.querySelectorAll(".light-card").forEach((card, index) => {
    const state = {card, ready: false, revision: 0, pending: null};
    lightStates.set(index + 1, state);
    card.querySelector(".on-btn").addEventListener("click", () => sendLightCommand(index + 1, "ON"));
    card.querySelector(".off-btn").addEventListener("click", () => sendLightCommand(index + 1, "OFF"));
  });
  try {
    await App.ready;
    refreshSnapshot();
    connectSocket();
  } catch (error) { API.report(error); }
});

window.addEventListener("pagehide", () => {
  active = false;
  clearTimeout(reconnectTimer);
  if (socket) socket.close();
  lightStates.forEach((state) => { if (state.pending) clearTimeout(state.pending.timer); });
});
window.addEventListener("pageshow", (event) => {
  if (event.persisted) { active = true; refreshSnapshot(); connectSocket(); }
});

async function refreshSnapshot() {
  const sequence = ++snapshotSequence;
  const before = sensorRevision;
  const deviceRevisions = new Map([...lightStates].map(([id, state]) => [id, state.revision]));
  try {
    // Concurrent REST requests; each old response is checked before being applied.
    await Promise.all([
      API.get("/api/sensor-data/latest").then((data) => {
        if (active && sequence === snapshotSequence && before === sensorRevision) updateSensorCards(data);
      }),
      API.get("/api/sensor-data/chart", {limit: 20}).then((data) => {
        if (!active || sequence !== snapshotSequence) return;
        // Merge, rather than replace, points that arrived over WS during this request.
        Object.keys(series).forEach((key) => {
          const merged = new Map(data[key].map((point) => [point.id, point]));
          series[key].forEach((point, id) => merged.set(id, point));
          series[key] = trimSeries(merged);
        });
        renderChart();
      }),
      ...[...lightStates].map(([id, state]) => API.get(`/api/devices/${id}/status`).then((data) => {
        if (active && sequence === snapshotSequence && state.revision === deviceRevisions.get(id) && !state.pending) {
          state.ready = true;
          handleLightStatus(state.card, data.status, data.updatedAt);
        }
      })),
    ]);
  } catch (error) { API.report(error); }
}

function connectSocket() {
  if (!active || (socket && socket.readyState <= WebSocket.OPEN)) return;
  clearTimeout(reconnectTimer);
  socket = new WebSocket(`${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/ws`);
  socket.onopen = refreshSnapshot; // Close the REST/WS initialization gap on every new connection.
  socket.onmessage = (message) => {
    const event = JSON.parse(message.data);
    if (event.type === "sensor:update") {
      sensorRevision++;
      updateSensorCards(event.data);
      Object.keys(series).forEach((key) => {
        const point = {id: event.data.ids[key], time: event.data.time, value: event.data[key]};
        series[key].set(point.id, point);
        series[key] = trimSeries(series[key]);
      });
      renderChart();
    } else if (event.type === "device:status" || event.type === "device:timeout") {
      const state = lightStates.get(event.data.deviceId);
      if (!state) return;
      state.revision++;
      if (state.pending) clearTimeout(state.pending.timer);
      state.pending = null;
      state.ready = true;
      handleLightStatus(state.card, event.data.status, event.data.updatedAt);
    }
  };
  socket.onclose = () => {
    if (!active) return;
    // Verify the session even when only the socket fails; do not loop forever on an expired session.
    API.get("/api/auth/me").then(() => {
      if (active) reconnectTimer = setTimeout(connectSocket, 2000);
    }).catch((error) => {
      API.report(error);
      if (active && error.status !== 401) reconnectTimer = setTimeout(connectSocket, 2000);
    });
  };
}

function trimSeries(points) {
  return new Map([...points].sort(([, a], [, b]) => a.time.localeCompare(b.time) || a.id - b.id).slice(-MAX_CHART_POINTS));
}
function updateSensorCards(reading) {
  if (!reading.time || reading.time < latestTime ||
      (reading.time === latestTime && reading.ids.temperature < latestSensorId)) return;
  latestTime = reading.time;
  latestSensorId = reading.ids.temperature;
  document.getElementById("temp-value").textContent = reading.temperature == null ? "--" : reading.temperature.toFixed(1);
  document.getElementById("humidity-value").textContent = reading.humidity == null ? "--" : reading.humidity.toFixed(1);
  document.getElementById("light-value").textContent = reading.light == null ? "--" : reading.light.toFixed(0);
  ["temp-updated", "humidity-updated", "light-updated"].forEach((id) => {
    document.getElementById(id).textContent = `Updated ${shortTime(reading.time)}`;
  });
}
function renderChart() {
  // Every committed packet has one point per series; repeated timestamps remain distinct by DB ID.
  chart.data.labels = [...series.temperature.values()].map((point) => shortTime(point.time));
  Object.keys(series).forEach((key, index) => {
    chart.data.datasets[index].data = [...series[key].values()].map((point) => point.value);
  });
  chart.update("none");
}
function shortTime(value) { return value ? String(value).slice(11, 19) : "--"; }

async function sendLightCommand(id, action) {
  const state = lightStates.get(id);
  if (!state.ready || state.pending || state.card.dataset.status === action) return;
  const command = {previous: state.card.dataset.status, timer: null};
  state.pending = command;
  state.revision++; // Also invalidates any earlier REST status response.
  state.card.setAttribute("aria-busy", "true");
  setStatusPill(state.card, "LOADING");
  updateLightButtons(state.card);
  try {
    const response = await API.post(`/api/devices/${id}/control`, {action});
    // An immediate ACK may already have completed this command before POST resolves.
    if (state.pending !== command) return;
    // This is only a silent UI fallback when WS is broken. It never confirms success.
    // The server owns the exact 5-second deadline and normally sends device:timeout.
    command.timer = setTimeout(async () => {
      if (state.pending !== command) return;
      try {
        const confirmed = await API.get(`/api/devices/${id}/status`);
        if (state.pending !== command) return;
        state.pending = null;
        state.revision++;
        handleLightStatus(state.card, confirmed.status, confirmed.updatedAt);
      } catch (error) {
        if (state.pending === command) {
          state.pending = null;
          state.card.setAttribute("aria-busy", "false");
          setStatusPill(state.card, command.previous);
          updateLightButtons(state.card);
        }
        API.report(error);
      }
    }, response.timeoutSeconds * 1000);
  } catch (error) {
    if (state.pending === command) {
      state.pending = null;
      state.card.setAttribute("aria-busy", "false");
      setStatusPill(state.card, command.previous);
      updateLightButtons(state.card);
    }
    API.report(error);
  }
}
function handleLightStatus(card, status, updatedAt) {
  card.setAttribute("aria-busy", "false");
  card.dataset.status = status;
  setStatusPill(card, status);
  updateLightButtons(card);
  card.querySelector(".light-updated").textContent = `Last updated: ${shortTime(updatedAt)}`;
}

function initChart() {
  const context = document.getElementById("trend-chart").getContext("2d");

  chart = new Chart(context, {
    type: "line",
    data: {
      labels: [],
      datasets: [
        createDataset("Temperature", getCssVariable("--temp"), "yTemp"),
        createDataset("Humidity", getCssVariable("--humidity"), "yHumidity"),
        createDataset("Light", getCssVariable("--light"), "yLight"),
      ],
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: "index", intersect: false },
      plugins: {
        legend: { display: false },
        tooltip: {
          displayColors: true,
          callbacks: {
            label(context) {
              const value = context.parsed.y;

              if (context.datasetIndex === 0) {
                return `Temperature: ${value.toFixed(1)} \u00B0C`;
              }
              if (context.datasetIndex === 1) {
                return `Humidity: ${value.toFixed(1)} %`;
              }
              return `Light: ${Math.round(value)} lx`;
            },
          },
        },
      },
      scales: {
        x: {
          display: true,
          grid: {
            display: true,
            color: "rgba(148, 163, 184, 0.10)",
          },
          border: { color: "#cbd5e1" },
          ticks: {
            display: true,
            color: "#64748b",
            maxTicksLimit: 7,
            autoSkip: true,
            maxRotation: 0,
            minRotation: 0,
            padding: 6,
            font: { size: 11 },
          },
          title: {
            display: true,
            text: "Time",
            color: "#64748b",
            font: { size: 11, weight: "600" },
          },
        },
        yTemp: { type: "linear", display: false },
        yHumidity: { type: "linear", display: false },
        yLight: { type: "linear", display: false },
      },
    },
  });
}

function createDataset(label, color, yAxisID) {
  return {
    label,
    data: [],
    borderColor: color,
    backgroundColor: color,
    yAxisID,
    tension: 0.3,
    pointRadius: 2,
    pointHoverRadius: 5,
    pointBackgroundColor: color,
    pointBorderWidth: 0,
    borderWidth: 2.5,
    fill: false,
  };
}

function getCssVariable(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}


function setStatusPill(card, status) {
  const pill = card.querySelector(".status-pill");

  pill.classList.remove("on", "off", "loading");
  pill.classList.add(status.toLowerCase());
  pill.querySelector(".status-text").textContent = status;
}

function updateLightButtons(card) {
  const status = card.dataset.status;
  const isLoading = card.getAttribute("aria-busy") === "true";
  const onButton = card.querySelector(".on-btn");
  const offButton = card.querySelector(".off-btn");

  onButton.disabled = isLoading || status === "ON";
  offButton.disabled = isLoading || status === "OFF";
  onButton.classList.toggle("is-active", status === "ON");
  offButton.classList.toggle("is-active", status === "OFF");
  onButton.setAttribute("aria-pressed", String(status === "ON"));
  offButton.setAttribute("aria-pressed", String(status === "OFF"));
}
