// Shared display helpers for Sensor Data and Device History.

const HistoryUtils = (() => {
  const FILTER_ERROR_MESSAGE = "Invalid input. Please check your entries and try again.";

  function formatDateTime(value) {
    const date = new Date(value);
    const pad = (number) => String(number).padStart(2, "0");

    if (Number.isNaN(date.getTime())) return "--";

    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
      `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
    );
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

  return {
    formatDateTime,
    setFieldInvalid,
    clearFieldInvalid,
    showFilterError,
    clearFilterError,
    renderSummary,
    renderPagination,
    resetTableViewport,
  };
})();
