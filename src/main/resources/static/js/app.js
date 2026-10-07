// Shared REST/session helpers and the account menu for authenticated pages.
// Visual identity remains fixed exactly as in the frontend demo.

const AppApi = (() => {
  async function request(path, options = {}) {
    const response = await fetch(path, {
      credentials: "same-origin",
      ...options,
      headers: {
        Accept: "application/json",
        ...(options.body ? { "Content-Type": "application/json" } : {}),
        ...(options.headers || {}),
      },
    });

    if (response.status === 401) {
      window.location.replace("/html/login.html");
      throw new Error("Authentication required.");
    }

    if (!response.ok) {
      let message = `Request failed (${response.status}).`;
      try {
        const error = await response.json();
        if (error && error.message) message = error.message;
      } catch {
        // Keep the generic message when the response is not JSON.
      }
      const requestError = new Error(message);
      requestError.status = response.status;
      throw requestError;
    }

    if (response.status === 204) return null;
    return response.json();
  }

  return {
    get(path) {
      return request(path);
    },
    post(path, body) {
      return request(path, {
        method: "POST",
        body: JSON.stringify(body),
      });
    },
  };
})();

const AppSession = (() => {
  let readyResolve;
  let readyReject;
  const ready = new Promise((resolve, reject) => {
    readyResolve = resolve;
    readyReject = reject;
  });
  ready.catch(() => {});

  async function initialize() {
    try {
      // This call is only a session guard. The visible account/profile content
      // intentionally stays fixed as in the supplied frontend demo.
      await AppApi.get("/api/auth/me");
      initUserMenu();
      readyResolve();
    } catch (error) {
      readyReject(error);
    }
  }

  function initUserMenu() {
    const userChip = document.getElementById("user-chip");
    const userMenu = document.getElementById("user-menu");
    const logoutButton = document.getElementById("logout-btn");
    if (!userChip || !userMenu || !logoutButton) return;

    const wrapper = userChip.parentElement;

    function setOpen(isOpen) {
      userMenu.classList.toggle("open", isOpen);
      userChip.setAttribute("aria-expanded", String(isOpen));
    }

    userChip.addEventListener("click", () => {
      setOpen(!userMenu.classList.contains("open"));
    });

    userChip.addEventListener("keydown", (event) => {
      if (event.key !== "ArrowDown") return;
      event.preventDefault();
      setOpen(true);
      logoutButton.focus();
    });

    wrapper.addEventListener("keydown", (event) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      userChip.focus();
    });

    wrapper.addEventListener("focusout", (event) => {
      if (!wrapper.contains(event.relatedTarget)) setOpen(false);
    });

    document.addEventListener("click", (event) => {
      if (!wrapper.contains(event.target)) setOpen(false);
    });

    logoutButton.addEventListener("click", async () => {
      try {
        await AppApi.post("/api/auth/logout", {});
      } finally {
        window.location.replace("/html/login.html");
      }
    });
  }

  document.addEventListener("DOMContentLoaded", initialize);
  return { ready };
})();

window.AppApi = AppApi;
window.AppSession = AppSession;
