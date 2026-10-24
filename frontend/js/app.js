window.appReady = import("./api.js").then(() => Api.request("/api/auth/me"));
// Keep the rejection observable to page initializers without an unhandled rejection.
window.appReady.catch(error => console.error("Authentication check failed", error));

// Shared behavior for authenticated pages.

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
    logoutButton.disabled = true;
    try {
      await window.appReady;
      await Api.request("/api/auth/logout", { method: "POST" });
      Api.clearAuth();
      window.location.replace("login.html");
    } catch (error) {
      console.error("Logout failed", error);
      logoutButton.disabled = false;
    }
  });
}

document.addEventListener("DOMContentLoaded", initUserMenu);
