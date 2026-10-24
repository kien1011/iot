// Live Dashboard: REST snapshots plus one same-origin WebSocket.
const state = new DashboardState();
let chart;
let socket;
let reconnectTimer;
let stopped = false;
let snapshotGeneration = 0;

document.addEventListener("DOMContentLoaded", async () => {
  initChart();
  document.querySelectorAll(".light-card").forEach((card, index) => {
    updateLightButtons(card);
    card.querySelector(".on-btn").addEventListener("click", () => sendLightCommand(index + 1, "ON"));
    card.querySelector(".off-btn").addEventListener("click", () => sendLightCommand(index + 1, "OFF"));
  });
  try {
    await window.appReady;
    loadSnapshot();
    connectSocket();
  } catch (error) { console.error("Dashboard initialization failed", error); }
});
window.addEventListener("pagehide", () => {
  stopped = true;
  clearTimeout(reconnectTimer);
  if (socket) socket.close();
});
window.addEventListener("pageshow", event => {
  if (event.persisted) { stopped = false; loadSnapshot(); connectSocket(); }
});

async function loadSnapshot() {
  const generation = ++snapshotGeneration;
  const revisions = { 1: state.devices[1].revision, 2: state.devices[2].revision };
  try {
    const [latest, points, first, second] = await Promise.all([
      Api.request("/api/sensor-data/latest"), Api.request("/api/sensor-data/chart"),
      Api.request("/api/devices/1/status"), Api.request("/api/devices/2/status"),
    ]);
    if (stopped || generation !== snapshotGeneration) return;
    state.mergeChart(points);
    state.mergeReading(latest);
    state.applySnapshot(first, revisions[1]);
    state.applySnapshot(second, revisions[2]);
    renderSensors();
    renderLights();
  } catch (error) { console.error("Dashboard snapshot failed", error); }
}

function connectSocket() {
  if (stopped || (socket && socket.readyState < 2)) return;
  const token = Api.getToken();
  if (!token) { window.location.replace("/html/login.html"); return; }
  const current = new WebSocket(`${location.protocol === "https:" ? "wss:" : "ws:"}//${location.host}/ws`, ["bearer", token]);
  socket = current;
  current.onopen = () => { clearTimeout(reconnectTimer); loadSnapshot(); };
  current.onmessage = event => {
    if (stopped || socket !== current) return;
    const message = JSON.parse(event.data);
    if (message.type === "sensor:update") {
      state.mergeReading(message.data);
      renderSensors();
    } else if (message.type === "device:status" || message.type === "device:timeout") {
      state.applyResult(message.data);
      renderLights();
    }
  };
  current.onclose = async () => {
    if (stopped || socket !== current) return;
    try {
      await Api.request("/api/auth/me");
      if (!stopped) reconnectTimer = setTimeout(connectSocket, 2000);
    } catch (error) {
      if (!stopped && error.status !== 401) reconnectTimer = setTimeout(connectSocket, 2000);
    }
  };
}

function renderSensors() {
  const fields = { temperature: ["temp-value", "temp-updated"], humidity: ["humidity-value", "humidity-updated"], light: ["light-value", "light-updated"] };
  for (const [name, ids] of Object.entries(fields)) {
    const point = state.series[name].at(-1);
    if (!point) continue;
    document.getElementById(ids[0]).textContent = name === "light" ? point.value.toFixed(0) : point.value.toFixed(1);
    document.getElementById(ids[1]).textContent = `Updated ${Api.shortTime(point.time)}`;
  }
  const names = Object.keys(state.series);
  chart.data.labels = state.series.temperature.map(point => Api.shortTime(point.time));
  names.forEach((name, index) => { chart.data.datasets[index].data = state.series[name].map(point => point.value); });
  chart.update("none");
}

function renderLights() {
  for (const id of [1, 2]) {
    const device = state.devices[id];
    const card = document.getElementById(`light-${id}-card`);
    card.dataset.ready = String(device.ready);
    card.dataset.status = device.status || "OFF";
    card.setAttribute("aria-busy", String(device.busy));
    setStatusPill(card, device.busy ? "LOADING" : card.dataset.status);
    card.querySelector(".light-updated").textContent = `Last updated: ${Api.shortTime(device.updatedAt)}`;
    updateLightButtons(card);
  }
}

async function sendLightCommand(deviceId, action) {
  const revision = state.begin(deviceId, action);
  if (revision === null) return;
  renderLights();
  try {
    await Api.request(`/api/devices/${deviceId}/control`, { method: "POST", body: JSON.stringify({ action }) });
    // Acceptance is not confirmation: a fast WebSocket result already rendered must stay intact.
  } catch (error) {
    console.error("Device control request failed", error);
    if (error.status === 401) return;
    if (state.devices[deviceId].revision !== revision) return;
    // Read the last confirmed database state after an HTTP failure.
    try {
      const confirmed = await Api.request(`/api/devices/${deviceId}/status`);
      if (state.devices[deviceId].revision === revision) { state.applyResult(confirmed); renderLights(); }
    } catch (snapshotError) { console.error("Device snapshot failed", snapshotError); }
  }
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
        // Keep the existing independent sensor scales; only make their Y axes readable.
        // One grid avoids clutter, while colored tick labels identify each unit.
        yTemp: createSensorAxis("left", "--temp", "°C", true),
        yHumidity: createSensorAxis("right", "--humidity", "%", false),
        yLight: createSensorAxis("right", "--light", "lux", false),
      },
    },
  });
}

// Distinct units need distinct Y scales: °C (left), % and lux (right).
function createSensorAxis(position, colorVariable, unit, showGrid) {
  const color = getCssVariable(colorVariable);
  return {
    type: "linear",
    display: true,
    position,
    grid: {
      drawOnChartArea: showGrid,
      color: "rgba(148, 163, 184, 0.10)",
    },
    border: { display: false },
    ticks: {
      color,
      maxTicksLimit: 5,
      padding: 6,
      font: { size: 10, weight: "500" },
      callback(value) {
        // Chart.js tick values may contain floating-point artifacts (e.g. 27.400000000006).
        const rounded = Number(Number(value).toFixed(unit === "lux" ? 0 : 2));
        return `${rounded} ${unit}`;
      },
    },
  };
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

  onButton.disabled = card.dataset.ready !== "true" || isLoading || status === "ON";
  offButton.disabled = card.dataset.ready !== "true" || isLoading || status === "OFF";
  onButton.classList.toggle("is-active", status === "ON");
  offButton.classList.toggle("is-active", status === "OFF");
  onButton.setAttribute("aria-pressed", String(status === "ON"));
  offButton.setAttribute("aria-pressed", String(status === "OFF"));
}
