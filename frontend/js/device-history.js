// Server-side history queries; original form behavior and appearance are retained.

const DEFAULT_PAGE_SIZE = 10;
const PAGE_WINDOW_SIZE = 5;

const state = {
  page: 0,
  pageSize: DEFAULT_PAGE_SIZE,
  deviceName: "",
  action: "",
  status: "",
  timeText: "",
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
  const timeInput = document.getElementById("f-date");
  const rowsPerPageSelect = document.getElementById("rows-per-page");
  const resetButton = document.getElementById("reset-btn");

  timeInput.addEventListener("input", () => {
    HistoryUtils.clearFieldInvalid(timeInput);
    HistoryUtils.clearFilterError();
  });

  form.addEventListener("submit", (event) => {
    event.preventDefault();

    if (!validateTime(timeInput)) {
      HistoryUtils.showFilterError();
      timeInput.focus();
      return;
    }

    HistoryUtils.clearFilterError();
    state.deviceName = document.getElementById("f-device").value;
    state.action = document.getElementById("f-action").value;
    state.status = document.getElementById("f-status").value;
    state.timeText = timeInput.value.trim();
    state.page = 0;
    filterAndRender();
  });

  resetButton.addEventListener("click", () => {
    form.reset();
    HistoryUtils.clearFieldInvalid(timeInput);
    HistoryUtils.clearFilterError();
    Object.assign(state, {
      page: 0,
      deviceName: "",
      action: "",
      status: "",
      timeText: "",
    });
    filterAndRender();
  });

  rowsPerPageSelect.addEventListener("change", () => {
    state.pageSize = Number(rowsPerPageSelect.value);
    state.page = 0;
    filterAndRender();
  });
}

function validateTime(input) {
  HistoryUtils.clearFieldInvalid(input);
  if (!input.value.trim()) return true;

  try {
    HistoryUtils.parseTimePrefix(input.value);
    return true;
  } catch {
    HistoryUtils.setFieldInvalid(input);
    return false;
  }
}

// -----------------------------------------------------------------------------
// Filter and pagination
// -----------------------------------------------------------------------------

let querySequence = 0;
async function filterAndRender() {
  const sequence = ++querySequence;
  const query = {
    page: state.page, size: state.pageSize,
    deviceId: {"Light 1": 1, "Light 2": 2}[state.deviceName],
    action: state.action, status: state.status, time: state.timeText,
  };
  try {
    const result = await API.get("/api/devices/action-history", query);
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
    tableBody.innerHTML = '<tr class="empty-row"><td colspan="6">No matching records found.</td></tr>';
    return;
  }

  tableBody.innerHTML = rows
    .map(
      (row) => `
        <tr>
          <td>${row.id}</td>
          <td>${HistoryUtils.escapeHtml(row.deviceName)}</td>
          <td>${row.action}</td>
          <td>${statusBadge(row.status)}</td>
          <td>${HistoryUtils.escapeHtml(row.username)}</td>
          <td>${HistoryUtils.formatDateTime(row.createdAt)}</td>
        </tr>
      `,
    )
    .join("");
}

function statusBadge(status) {
  return `<span class="status-pill ${status.toLowerCase()}"><span class="dot"></span>${status}</span>`;
}
