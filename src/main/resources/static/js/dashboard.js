// Dashboard display logic backed by REST + WebSocket.

const MAX_CHART_POINTS = 20;
const DEVICE_MAP = {
  "light-1-card": 1,
  "light-2-card": 2,
};

let chart;
let dashboardSocket;
const commandPolls = new Map();
const DEVICE_STATUS_POLL_MS = 500;

document.addEventListener("DOMContentLoaded", async () => {
  try {
    await AppSession.ready;
    initChart();
    initLightControls();
    await Promise.allSettled([
      loadLatestSensorData(),
      loadInitialChartData(),
      loadDeviceStatus(1),
      loadDeviceStatus(2),
    ]);
    connectDashboardWebSocket();
  } catch (error) {
    console.error("Dashboard initialization failed", error);
  }
});

window.addEventListener("pagehide", () => {
  if (dashboardSocket) dashboardSocket.close();
  commandPolls.forEach((timer) => clearInterval(timer));
  commandPolls.clear();
});

// -----------------------------------------------------------------------------
// Sensor cards
// -----------------------------------------------------------------------------

async function loadLatestSensorData() {
  const reading = await AppApi.get("/api/sensor-data/latest");
  if (reading) updateSensorCards(reading);
}

function updateSensorCards(reading) {
  document.getElementById("temp-value").textContent = Number(reading.temperature).toFixed(1);
  document.getElementById("humidity-value").textContent = Number(reading.humidity).toFixed(1);
  document.getElementById("light-value").textContent = Math.round(Number(reading.light));

  const updatedText = `Updated ${shortTime(reading.time)}`;
  ["temp-updated", "humidity-updated", "light-updated"].forEach((id) => {
    document.getElementById(id).textContent = updatedText;
  });
}

// -----------------------------------------------------------------------------
// Chart
// -----------------------------------------------------------------------------

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
                return `Temperature: ${value.toFixed(1)} °C`;
              }
              if (context.datasetIndex === 1) {
                return `Humidity: ${value.toFixed(1)} %`;
              }
              return `Light: ${Math.round(value)} lux`;
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

async function loadInitialChartData() {
  const response = await AppApi.get(`/api/sensor-data/chart?limit=${MAX_CHART_POINTS}`);
  const temperature = response?.temperature || [];
  const humidity = response?.humidity || [];
  const light = response?.light || [];

  const timeKeys = new Set([
    ...temperature.map((point) => point.time),
    ...humidity.map((point) => point.time),
    ...light.map((point) => point.time),
  ]);
  const times = [...timeKeys].sort((a, b) => new Date(a) - new Date(b)).slice(-MAX_CHART_POINTS);

  const temperatureByTime = new Map(temperature.map((point) => [point.time, point.value]));
  const humidityByTime = new Map(humidity.map((point) => [point.time, point.value]));
  const lightByTime = new Map(light.map((point) => [point.time, point.value]));

  chart.data.labels = times.map(shortTime);
  chart.data.datasets[0].data = times.map((time) => temperatureByTime.get(time) ?? null);
  chart.data.datasets[1].data = times.map((time) => humidityByTime.get(time) ?? null);
  chart.data.datasets[2].data = times.map((time) => lightByTime.get(time) ?? null);
  chart.update("none");
}

function addReadingToChart(reading) {
  chart.data.labels.push(shortTime(reading.time));
  [reading.temperature, reading.humidity, reading.light].forEach((value, index) => {
    chart.data.datasets[index].data.push(Number(value));
  });

  if (chart.data.labels.length > MAX_CHART_POINTS) {
    chart.data.labels.shift();
    chart.data.datasets.forEach((dataset) => dataset.data.shift());
  }
  chart.update("none");
}

function shortTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  const pad = (number) => String(number).padStart(2, "0");
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

// -----------------------------------------------------------------------------
// Light controls - behavior follows the report.
// -----------------------------------------------------------------------------

function initLightControls() {
  document.querySelectorAll(".light-card").forEach((card) => {
    const deviceId = DEVICE_MAP[card.id];
    card.dataset.deviceId = String(deviceId);
    card.dataset.ready = "false";
    card.dataset.confirmedStatus = card.dataset.status;
    card.querySelector(".on-btn").addEventListener("click", () => sendLightCommand(card, "ON"));
    card.querySelector(".off-btn").addEventListener("click", () => sendLightCommand(card, "OFF"));
  });
}

async function loadDeviceStatus(deviceId) {
  const card = document.getElementById(`light-${deviceId}-card`);
  if (!card) return;

  const response = await AppApi.get(`/api/devices/${deviceId}/status`);
  applyConfirmedDeviceStatus(card, response.status, response.updatedAt);
}

function isCommandPending(card) {
  return card.dataset.pending === "true";
}

async function sendLightCommand(card, action) {
  const isBusy = isCommandPending(card);
  const currentStatus = card.dataset.confirmedStatus;
  if (card.dataset.ready !== "true" || isBusy || !currentStatus || currentStatus === action) return;

  card.dataset.pendingAction = action;
  card.dataset.pending = "true";
  setStatusPill(card, "LOADING");
  updateLightButtons(card);

  try {
    const deviceId = Number(card.dataset.deviceId);
    const response = await AppApi.post(`/api/devices/${deviceId}/control`, { action });

    // The hardware confirmation can arrive through WebSocket before the POST
    // response reaches the browser. If that already happened, do not put the
    // card back into a pending state.
    if (!isCommandPending(card)) return;

    card.dataset.pendingHistoryId = String(response.id);
    startPendingCommandWatch(card);
  } catch (error) {
    restoreConfirmedStatus(card);
    console.error("Device control failed", error);
  }
}

function startPendingCommandWatch(card) {
  const deviceId = Number(card.dataset.deviceId);
  clearCommandWatch(deviceId);

  // WebSocket is the authoritative realtime path. While the command is
  // pending, this lightweight reconciliation only covers a missed
  // successful device:update frame. A timeout is decided by the Backend
  // and arrives as device:timeout.
  const pollTimer = setInterval(() => {
    void reconcilePendingDeviceStatus(card);
  }, DEVICE_STATUS_POLL_MS);
  commandPolls.set(deviceId, pollTimer);
}

async function reconcilePendingDeviceStatus(card) {
  if (!isCommandPending(card)) return false;
  if (card.dataset.reconciling === "true") return false;

  const pendingAction = card.dataset.pendingAction;
  if (!pendingAction) return false;

  card.dataset.reconciling = "true";
  try {
    const deviceId = Number(card.dataset.deviceId);
    const response = await AppApi.get(`/api/devices/${deviceId}/status`);

    // The command may have completed or been replaced while the request was in flight.
    if (!isCommandPending(card)) return false;
    if (card.dataset.pendingAction !== pendingAction) return false;

    if (response?.status !== pendingAction) return false;

    applyConfirmedDeviceStatus(card, response.status, response.updatedAt);
    return true;
  } catch (error) {
    // Keep waiting for the normal WebSocket confirmation/timeout path.
    return false;
  } finally {
    delete card.dataset.reconciling;
  }
}

function clearCommandWatch(deviceId) {
  const poll = commandPolls.get(deviceId);
  if (poll) clearInterval(poll);
  commandPolls.delete(deviceId);
}

function applyConfirmedDeviceStatus(card, status, updatedAt) {
  const deviceId = Number(card.dataset.deviceId);
  clearCommandWatch(deviceId);
  delete card.dataset.pending;
  card.dataset.ready = "true";
  card.dataset.status = status;
  card.dataset.confirmedStatus = status;
  delete card.dataset.pendingHistoryId;
  delete card.dataset.pendingAction;
  setStatusPill(card, status);
  updateLightButtons(card);
  card.querySelector(".light-updated").textContent = updatedAt
    ? `Last updated: ${shortTime(updatedAt)}`
    : "Last updated: --";
}

function restoreConfirmedStatus(card) {
  const status = card.dataset.confirmedStatus;
  const deviceId = Number(card.dataset.deviceId);
  clearCommandWatch(deviceId);
  delete card.dataset.pending;
  delete card.dataset.pendingHistoryId;
  delete card.dataset.pendingAction;
  if (status) {
    card.dataset.status = status;
    setStatusPill(card, status);
  }
  updateLightButtons(card);
}

function setStatusPill(card, status) {
  const pill = card.querySelector(".status-pill");
  pill.classList.remove("on", "off", "loading");
  pill.classList.add(status.toLowerCase());
  pill.querySelector(".status-text").textContent = status;
}

function updateLightButtons(card) {
  const status = card.dataset.confirmedStatus;
  const isLoading = isCommandPending(card);
  const onButton = card.querySelector(".on-btn");
  const offButton = card.querySelector(".off-btn");

  onButton.disabled = isLoading || status === "ON";
  offButton.disabled = isLoading || status === "OFF";
  onButton.classList.toggle("is-active", status === "ON");
  offButton.classList.toggle("is-active", status === "OFF");
  onButton.setAttribute("aria-pressed", String(status === "ON"));
  offButton.setAttribute("aria-pressed", String(status === "OFF"));
}


function handleDeviceUpdate(data) {
  const card = document.getElementById(`light-${data.deviceId}-card`);
  if (!card) return;

  const isBusy = isCommandPending(card);
  if (isBusy) {
    const pendingAction = card.dataset.pendingAction;
    const pendingHistoryId = card.dataset.pendingHistoryId;

    if (pendingAction && data.status !== pendingAction) return;
    if (
      pendingHistoryId &&
      data.actionHistoryId != null &&
      String(data.actionHistoryId) !== pendingHistoryId
    ) {
      return;
    }
  }

  applyConfirmedDeviceStatus(card, data.status, data.updatedAt);
}

function handleDeviceTimeout(data) {
  const card = document.getElementById(`light-${data.deviceId}-card`);
  if (!card || !isCommandPending(card)) return;

  const pendingHistoryId = card.dataset.pendingHistoryId;
  if (
    pendingHistoryId &&
    data.actionHistoryId != null &&
    String(data.actionHistoryId) !== pendingHistoryId
  ) {
    return;
  }

  // Timeout means the Backend did not receive hardware confirmation.
  // Remove LOADING only: keep the last confirmed status, active button
  // and Last updated time exactly as they were before the request.
  restoreConfirmedStatus(card);
}

// -----------------------------------------------------------------------------
// Realtime WebSocket
// -----------------------------------------------------------------------------

function connectDashboardWebSocket() {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  dashboardSocket = new WebSocket(`${protocol}//${window.location.host}/ws`);

  dashboardSocket.addEventListener("message", (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch {
      return;
    }

    if (message.event === "sensor:update") {
      updateSensorCards(message.data);
      addReadingToChart(message.data);
      return;
    }

    if (message.event === "device:update") {
      handleDeviceUpdate(message.data);
      return;
    }

    if (message.event === "device:timeout") {
      handleDeviceTimeout(message.data);
    }
  });
}
