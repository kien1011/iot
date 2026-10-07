// Device History: frontend only presents controls/results; backend validates, filters and paginates.

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

document.addEventListener("DOMContentLoaded", async () => {
  try {
    await AppSession.ready;
    bindEvents();
    await loadHistory();
  } catch (error) {
    console.error("Device history initialization failed", error);
  }
});

function bindEvents() {
  const form = document.getElementById("filter-form");
  const timeInput = document.getElementById("f-date");
  const rowsPerPageSelect = document.getElementById("rows-per-page");
  const resetButton = document.getElementById("reset-btn");

  timeInput.addEventListener("input", () => {
    HistoryUtils.clearFieldInvalid(timeInput);
    HistoryUtils.clearFilterError();
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    HistoryUtils.clearFieldInvalid(timeInput);
    HistoryUtils.clearFilterError();

    state.deviceName = document.getElementById("f-device").value;
    state.action = document.getElementById("f-action").value;
    state.status = document.getElementById("f-status").value;
    state.timeText = timeInput.value.trim();
    state.page = 0;
    await loadHistory(timeInput);
  });

  resetButton.addEventListener("click", async () => {
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
    await loadHistory(timeInput);
  });

  rowsPerPageSelect.addEventListener("change", async () => {
    state.pageSize = Number(rowsPerPageSelect.value);
    state.page = 0;
    await loadHistory(timeInput);
  });
}

async function loadHistory(timeInput = document.getElementById("f-date")) {
  const params = new URLSearchParams({
    page: String(state.page),
    size: String(state.pageSize),
    sort: "createdAt,desc",
  });

  if (state.deviceName) params.set("deviceName", state.deviceName);
  if (state.action) params.set("action", state.action);
  if (state.status) params.set("status", state.status);
  if (state.timeText) params.set("time", state.timeText);

  try {
    const result = await AppApi.get(`/api/devices/action-history?${params.toString()}`);
    HistoryUtils.clearFieldInvalid(timeInput);
    HistoryUtils.clearFilterError();
    renderRows(result.content);
    HistoryUtils.renderSummary(result, state);
    HistoryUtils.renderPagination({
      totalPages: result.totalPages,
      currentPage: state.page,
      windowSize: PAGE_WINDOW_SIZE,
      onPageChange(page) {
        state.page = page;
        loadHistory(timeInput);
      },
    });
    HistoryUtils.resetTableViewport(result.content.length);
  } catch (error) {
    if (error.status === 400) {
      HistoryUtils.setFieldInvalid(timeInput);
      HistoryUtils.showFilterError();
      timeInput.focus();
      return;
    }
    HistoryUtils.showFilterError();
  }
}

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
          <td>${escapeHtml(row.deviceName)}</td>
          <td>${row.action}</td>
          <td>${statusBadge(row.status)}</td>
          <td>${escapeHtml(row.username)}</td>
          <td>${HistoryUtils.formatDateTime(row.createdAt)}</td>
        </tr>
      `,
    )
    .join("");
}

function statusBadge(status) {
  return `<span class="status-pill ${status.toLowerCase()}"><span class="dot"></span>${status}</span>`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}
