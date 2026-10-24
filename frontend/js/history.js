// Shared helpers for Sensor Data and Device History.

const HistoryUtils = (() => {
  const INVALID_TIME_ERROR = "INVALID_TIME";
  const FILTER_ERROR_MESSAGE = "Invalid input. Please check your entries and try again.";

  function formatDateTime(value) { return Api.formatDateTime(value); }

  // Accepted formats:
  // YYYY | YYYY-MM | YYYY-MM-DD | YYYY-MM-DD HH | YYYY-MM-DD HH:mm | YYYY-MM-DD HH:mm:ss
  function parseTimePrefix(input) {
    const value = String(input).trim();
    const match = value.match(
      /^(\d{4})(?:-(\d{2})(?:-(\d{2})(?: (\d{2})(?::(\d{2})(?::(\d{2}))?)?)?)?)?$/,
    );

    if (!match) throw new Error(INVALID_TIME_ERROR);

    const year = Number(match[1]);
    const month = match[2] === undefined ? null : Number(match[2]);
    const day = match[3] === undefined ? null : Number(match[3]);
    const hour = match[4] === undefined ? null : Number(match[4]);
    const minute = match[5] === undefined ? null : Number(match[5]);
    const second = match[6] === undefined ? null : Number(match[6]);

    if (month !== null && (month < 1 || month > 12)) {
      throw new Error(INVALID_TIME_ERROR);
    }

    if (day !== null) {
      const isLeapYear = year % 400 === 0 || (year % 4 === 0 && year % 100 !== 0);
      const daysInMonth = [31, isLeapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

      if (day < 1 || day > daysInMonth[month - 1]) {
        throw new Error(INVALID_TIME_ERROR);
      }
    }

    if (hour !== null && (hour < 0 || hour > 23)) throw new Error(INVALID_TIME_ERROR);
    if (minute !== null && (minute < 0 || minute > 59)) throw new Error(INVALID_TIME_ERROR);
    if (second !== null && (second < 0 || second > 59)) throw new Error(INVALID_TIME_ERROR);

    return value.replace(" ", "T");
  }

  function setFieldInvalid(input) {
    input.classList.add("is-invalid");
    input.setAttribute("aria-invalid", "true");
  }

  function clearFieldInvalid(input) {
    input.classList.remove("is-invalid");
    input.removeAttribute("aria-invalid");
  }

  function showFilterError() {
    const error = document.getElementById("filter-error");
    error.textContent = FILTER_ERROR_MESSAGE;
    error.classList.add("show");
  }

  function clearFilterError() {
    const error = document.getElementById("filter-error");
    error.textContent = "";
    error.classList.remove("show");
  }

  function renderSummary(result, state) {
    const summary = document.getElementById("summary-text");

    if (result.totalElements === 0) {
      summary.textContent = "Showing 0 records";
      return;
    }

    const from = state.page * state.pageSize + 1;
    const to = state.page * state.pageSize + result.content.length;
    summary.textContent = `Showing ${from}\u2013${to} of ${result.totalElements} records`;
  }

  function getPageWindow(currentPage, totalPages, windowSize) {
    const halfWindow = Math.floor(windowSize / 2);
    let start = Math.max(0, currentPage - halfWindow);
    let end = Math.min(totalPages - 1, start + windowSize - 1);
    start = Math.max(0, end - windowSize + 1);

    const pages = [];
    for (let page = start; page <= end; page += 1) pages.push(page);
    return pages;
  }

  function renderPagination({ totalPages, currentPage, windowSize, onPageChange }) {
    const container = document.getElementById("pagination");
    container.innerHTML = "";

    if (totalPages <= 1) return;

    function addButton(label, targetPage, disabled, active = false) {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = label;
      button.disabled = disabled;
      button.classList.toggle("active", active);

      const ariaLabel =
        label === "\u2039"
          ? "Previous page"
          : label === "\u203A"
            ? "Next page"
            : `Page ${targetPage + 1}`;

      button.setAttribute("aria-label", ariaLabel);
      if (active) button.setAttribute("aria-current", "page");

      button.addEventListener("click", () => onPageChange(targetPage));
      container.appendChild(button);
    }

    addButton("\u2039", currentPage - 1, currentPage === 0);

    getPageWindow(currentPage, totalPages, windowSize).forEach((page) => {
      addButton(String(page + 1), page, false, page === currentPage);
    });

    addButton("\u203A", currentPage + 1, currentPage === totalPages - 1);
  }

  function resetTableViewport(rowCount) {
    const tableWrap = document.querySelector(".table-wrap");
    if (!tableWrap) return;

    tableWrap.classList.toggle("is-scrollable", rowCount > 10);
    tableWrap.scrollTop = 0;
  }

  function createLoader(state, endpoint, params, renderRows) {
    let sequence = 0;
    return async function load() {
      const current = ++sequence;
      try {
        await window.appReady;
        const result = await Api.request(`${endpoint}?${Api.query({ ...params(), page: state.page, size: state.pageSize })}`);
        if (current !== sequence) return;
        renderRows(result.content);
        renderSummary(result, state);
        renderPagination({ totalPages: result.totalPages, currentPage: state.page, windowSize: 5,
          onPageChange(page) { state.page = page; load(); } });
        resetTableViewport(result.content.length);
      } catch (error) {
        if (current !== sequence) return;
        if (error.status === 400) showFilterError();
        else console.error("History request failed", error);
      }
    };
  }

  return {
    createLoader,
    formatDateTime,
    parseTimePrefix,
    setFieldInvalid,
    clearFieldInvalid,
    showFilterError,
    clearFilterError,
    renderSummary,
    renderPagination,
    resetTableViewport,
  };
})();
