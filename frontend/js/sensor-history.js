// Server-side history queries; original form behavior and appearance are retained.

const DEFAULT_PAGE_SIZE = 10;
const PAGE_WINDOW_SIZE = 5;

const state = {
  page: 0,
  pageSize: DEFAULT_PAGE_SIZE,
  filterBy: "",
  query: "",
};

document.addEventListener("DOMContentLoaded", () => {
  bindEvents();
  App.ready.then(filterAndRender).catch(API.report);
});

// -----------------------------------------------------------------------------
// Filter form
// -----------------------------------------------------------------------------

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

  form.addEventListener("submit", (event) => {
    event.preventDefault();

    if (!validateQuery(filterBySelect.value, queryInput)) {
      HistoryUtils.showFilterError();
      queryInput.focus();
      return;
    }

    HistoryUtils.clearFilterError();
    state.filterBy = filterBySelect.value;
    state.query = queryInput.value.trim();
    state.page = 0;
    filterAndRender();
  });

  resetButton.addEventListener("click", () => {
    form.reset();
    HistoryUtils.clearFieldInvalid(queryInput);
    HistoryUtils.clearFilterError();
    Object.assign(state, { page: 0, filterBy: "", query: "" });
    updateQueryField(filterBySelect.value, queryInput);
    filterAndRender();
  });

  rowsPerPageSelect.addEventListener("change", () => {
    state.pageSize = Number(rowsPerPageSelect.value);
    state.page = 0;
    filterAndRender();
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

function validateQuery(filterBy, input) {
  HistoryUtils.clearFieldInvalid(input);
  if (!input.value.trim()) return true;

  try {
    if (filterBy === "time") HistoryUtils.parseTimePrefix(input.value);
    else parseExactNumber(input.value);
    return true;
  } catch {
    HistoryUtils.setFieldInvalid(input);
    return false;
  }
}

function parseExactNumber(input) {
  const value = String(input).trim();

  if (!/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)$/.test(value)) {
    throw new Error("INVALID_NUMBER");
  }

  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error("INVALID_NUMBER");
  return number;
}

// -----------------------------------------------------------------------------
// Filter and pagination
// -----------------------------------------------------------------------------

let querySequence = 0;
async function filterAndRender() {
  const sequence = ++querySequence;
  const sensorIds = {Temperature: 1, Humidity: 2, Light: 3};
  const query = {
    page: state.page, size: state.pageSize,
    sensorId: sensorIds[state.filterBy],
    value: state.filterBy === "time" ? "" : state.query,
    time: state.filterBy === "time" ? state.query : "",
  };
  try {
    const result = await API.get("/api/sensor-data/history", query);
    if (sequence !== querySequence) return;
    renderRows(result.content);
    HistoryUtils.renderSummary(result, {page: result.page, pageSize: result.size});
    HistoryUtils.renderPagination({
      totalPages: result.totalPages, currentPage: result.page, windowSize: PAGE_WINDOW_SIZE,
      onPageChange(page) { state.page = page; filterAndRender(); },
    });
    HistoryUtils.resetTableViewport(result.content.length);
  } catch (error) {
    if (sequence !== querySequence) return;
    if (error.status === 400) HistoryUtils.showFilterError();
    else API.report(error);
  }
}

// -----------------------------------------------------------------------------
// Table rendering
// -----------------------------------------------------------------------------

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
          <td>${HistoryUtils.escapeHtml(row.sensorName)}</td>
          <td>${formatSensorValue(row.sensorName, row.value)}</td>
          <td>${HistoryUtils.formatDateTime(row.createdAt)}</td>
        </tr>
      `,
    )
    .join("");
}

function formatSensorValue(sensorName, value) {
  if (sensorName === "Temperature") return `${value.toFixed(1)} °C`;
  if (sensorName === "Humidity") return `${value.toFixed(1)} %`;
  return `${value.toFixed(0)} lux`;
}
