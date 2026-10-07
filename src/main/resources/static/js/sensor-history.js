// Sensor Data: frontend only presents controls/results; backend validates, filters and paginates.

const DEFAULT_PAGE_SIZE = 10;
const PAGE_WINDOW_SIZE = 5;

const state = {
  page: 0,
  pageSize: DEFAULT_PAGE_SIZE,
  filterBy: "",
  query: "",
};

document.addEventListener("DOMContentLoaded", async () => {
  try {
    await AppSession.ready;
    bindEvents();
    await loadHistory();
  } catch (error) {
    console.error("Sensor history initialization failed", error);
  }
});

function bindEvents() {
  const form = document.getElementById("filter-form");
  const filterBySelect = document.getElementById("f-filter-by");
  const queryInput = document.getElementById("f-query");
  const rowsPerPageSelect = document.getElementById("rows-per-page");
  const resetButton = document.getElementById("reset-btn");

  updateQueryField(filterBySelect.value, queryInput);

  filterBySelect.addEventListener("change", () => {
    queryInput.value = "";
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();
    updateQueryField(filterBySelect.value, queryInput);
  });

  queryInput.addEventListener("input", () => {
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();

    state.filterBy = filterBySelect.value;
    state.query = queryInput.value.trim();
    state.page = 0;
    await loadHistory(queryInput);
  });

  resetButton.addEventListener("click", async () => {
    form.reset();
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();
    Object.assign(state, { page: 0, filterBy: "", query: "" });
    updateQueryField(filterBySelect.value, queryInput);
    await loadHistory(queryInput);
  });

  rowsPerPageSelect.addEventListener("change", async () => {
    state.pageSize = Number(rowsPerPageSelect.value);
    state.page = 0;
    await loadHistory(queryInput);
  });
}

function updateQueryField(filterBy, queryInput) {
  const queryLabel = document.getElementById("f-query-label");
  const timeIcon = document.getElementById("query-time-icon");
  const isTimeFilter = filterBy === "time";

  queryLabel.textContent = isTimeFilter ? "Time" : "Value";
  queryInput.placeholder = isTimeFilter ? "YYYY-MM-DD HH:mm:ss" : "e.g. 30";
  queryInput.inputMode = isTimeFilter ? "text" : "decimal";
  queryInput.classList.toggle("has-time-icon", isTimeFilter);
  timeIcon.classList.toggle("is-hidden", !isTimeFilter);
}

async function loadHistory(queryInput = document.getElementById("f-query")) {
  const params = new URLSearchParams({
    page: String(state.page),
    size: String(state.pageSize),
    sort: "createdAt,desc",
  });

  if (state.filterBy === "time") {
    if (state.query) params.set("time", state.query);
  } else {
    if (state.filterBy) params.set("sensorName", state.filterBy);
    if (state.query) params.set("value", state.query);
  }

  try {
    const result = await AppApi.get(`/api/sensor-data/history?${params.toString()}`);
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();
    renderRows(result.content);
    HistoryUtils.renderSummary(result, state);
    HistoryUtils.renderPagination({
      totalPages: result.totalPages,
      currentPage: state.page,
      windowSize: PAGE_WINDOW_SIZE,
      onPageChange(page) {
        state.page = page;
        loadHistory(queryInput);
      },
    });
    HistoryUtils.resetTableViewport(result.content.length);
  } catch (error) {
    if (error.status === 400) {
      HistoryUtils.setFieldInvalid(queryInput);
      HistoryUtils.showFilterError();
      queryInput.focus();
      return;
    }
    HistoryUtils.showFilterError();
  }
}

function renderRows(rows) {
  const tableBody = document.getElementById("table-body");

  if (rows.length === 0) {
    tableBody.innerHTML = '<tr class="empty-row"><td colspan="4">No matching records found.</td></tr>';
    return;
  }

  tableBody.innerHTML = rows
    .map(
      (row) => `
        <tr>
          <td>${row.id}</td>
          <td>${escapeHtml(row.sensorName)}</td>
          <td>${formatSensorValue(row.sensorName, row.value)}</td>
          <td>${HistoryUtils.formatDateTime(row.createdAt)}</td>
        </tr>
      `,
    )
    .join("");
}

function formatSensorValue(sensorName, value) {
  const number = Number(value);
  if (sensorName === "Temperature") return `${number.toFixed(1)} °C`;
  if (sensorName === "Humidity") return `${number.toFixed(1)} %`;
  return `${number.toFixed(0)} lux`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
