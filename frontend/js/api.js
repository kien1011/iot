// Same-origin HTTP JSON; credentials and CSRF are managed centrally.
const API = (() => {
  async function decode(response) {
    if (response.status === 204) return null;
    const text = await response.text();
    if (!text) return null;
    try { return JSON.parse(text); } catch { return {message: text}; }
  }
  async function request(path, options = {}) {
    const method = options.method || "GET";
    const headers = {Accept: "application/json", ...options.headers};
    if (method === "POST") {
      // Fresh masked token: works before login, after token rotation and for logout.
      const csrf = await request("/api/auth/csrf");
      headers[csrf.headerName] = csrf.token;
      if (options.body !== undefined) headers["Content-Type"] = "application/json";
    }
    const response = await fetch(path, {...options, method, headers, credentials: "same-origin"});
    const body = await decode(response);
    if (!response.ok) {
      const error = new Error(response.status === 401 && path === "/api/auth/login"
        ? "Incorrect username or password." : body?.message || `Request failed (${response.status}).`);
      error.status = response.status;
      if (response.status === 401 && path !== "/api/auth/login") location.replace("/html/login.html");
      throw error; // Never translate authentication/DB errors into empty data or success.
    }
    return body;
  }
  function get(path, query = {}) {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") params.set(key, value);
    });
    return request(path + (params.size ? `?${params}` : ""));
  }
  function post(path, body) {
    return request(path, {method: "POST", ...(body === undefined ? {} : {body: JSON.stringify(body)})});
  }
  function report(error) { if (error.status !== 401) console.error(error); }
  return {get, post, report};
})();
