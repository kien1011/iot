// Shared session gate and original user-menu interactions.
const App = {
  ready: API.get("/api/auth/me").then((user) => {
    document.querySelectorAll(".user-name").forEach((node) => { node.textContent = user.fullName || user.username; });
    document.querySelectorAll(".user-avatar").forEach((node) => { node.textContent = (user.fullName || user.username)[0].toUpperCase(); });
    return user;
  }),
};
// Also observe errors on the static Profile page; API has already handled session loss.
App.ready.catch(API.report);

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
      await API.post("/api/auth/logout");
      window.location.replace("login.html");
    } catch (error) { API.report(error); logoutButton.disabled = false; }
  });
}

document.addEventListener("DOMContentLoaded", initUserMenu);
