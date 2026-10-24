// MySQL-backed history; original demo interactions and table rendering.

const DEFAULT_PAGE_SIZE = 10;

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
  filterAndRender();
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

const filterAndRender = HistoryUtils.createLoader(state, "/api/devices/action-history", () => {
    return { deviceId: ({ "Light 1": 1, "Light 2": 2 })[state.deviceName],
      action: state.action, status: state.status, time: state.timeText };
}, renderRows);

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
          <td>${Api.escapeHtml(row.deviceName)}</td>
          <td>${row.action}</td>
          <td>${statusBadge(row.status)}</td>
          <td>${Api.escapeHtml(row.username)}</td>
          <td>${HistoryUtils.formatDateTime(row.createdAt)}</td>
        </tr>
      `,
    )
    .join("");
}

function statusBadge(status) {
  return `<span class="status-pill ${status.toLowerCase()}"><span class="dot"></span>${status}</span>`;
}
