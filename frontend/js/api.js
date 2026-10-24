// Shared same-origin JSON API using the bearer token returned by login.
window.Api = (() => {
  const AUTH_KEY = "iot.auth";
  function getToken() {
    try {
      const auth = JSON.parse(window.sessionStorage.getItem(AUTH_KEY));
      return typeof auth?.token === "string" && auth.token ? auth.token : null;
    } catch {
      clearAuth();
      return null;
    }
  }
  function setAuth(auth) {
    if (typeof auth?.token !== "string" || !auth.token) throw new Error("Login response is missing a token.");
    const { id, username, fullName, token } = auth;
    window.sessionStorage.setItem(AUTH_KEY, JSON.stringify({ id, username, fullName, token }));
  }
  function clearAuth() { window.sessionStorage.removeItem(AUTH_KEY); }

  async function request(path, options = {}) {
    const { redirectOn401 = true, ...fetchOptions } = options;
    const method = fetchOptions.method || "GET";
    const headers = new Headers(fetchOptions.headers || {});
    const token = getToken();
    if (token && path !== "/api/auth/login") headers.set("Authorization", `Bearer ${token}`);
    if (fetchOptions.body !== undefined) headers.set("Content-Type", "application/json");
    const response = await fetch(path, { ...fetchOptions, method, headers, credentials: "omit", cache: "no-store" });
    const text = await response.text();
    const body = text ? JSON.parse(text) : null;
    if (!response.ok) {
      if (response.status === 401 && redirectOn401 && getToken() === token) {
        clearAuth();
        window.location.replace("/html/login.html");
      }
      const error = new Error(body?.message || `HTTP ${response.status}`);
      error.status = response.status;
      throw error;
    }
    return body;
  }
  function query(values) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(values)) {
      if (value !== null && value !== undefined && value !== "") params.set(key, value);
    }
    return params.toString();
  }
  // API DATETIME fields already use Asia/Ho_Chi_Minh. Do not reinterpret as browser-local time.
  function formatDateTime(value) { return value ? value.replace("T", " ").slice(0, 19) : "--"; }
  function shortTime(value) { return value ? value.slice(11, 19) : "--"; }
  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  }
  return { request, getToken, setAuth, clearAuth, query, formatDateTime, shortTime, escapeHtml };
})();
