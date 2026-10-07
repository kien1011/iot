const PASSWORD_ICONS = {
  visible:
    '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>',
  hidden:
    '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c6.5 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68"/><path d="M6.61 6.61C3.35 8.36 2 12 2 12s3.5 7 10 7a9.5 9.5 0 0 0 5-1.5"/><path d="M9.88 9.88a3 3 0 1 0 4.24 4.24"/><line x1="2" x2="22" y1="2" y2="22"/></svg>',
};

document.addEventListener("DOMContentLoaded", () => {
  const form = document.getElementById("login-form");
  const errorBox = document.getElementById("login-error");
  const submitButton = document.getElementById("login-btn");
  const usernameInput = document.getElementById("username");
  const passwordInput = document.getElementById("password");
  const passwordToggle = document.getElementById("toggle-password");

  bindPasswordToggle(passwordToggle, passwordInput);

  function clearLoginError() {
    errorBox.textContent = "";
    errorBox.classList.remove("show");
    usernameInput.removeAttribute("aria-invalid");
    passwordInput.removeAttribute("aria-invalid");
  }

  function setSubmitting(isSubmitting) {
    form.setAttribute("aria-busy", String(isSubmitting));
    submitButton.disabled = isSubmitting;
    usernameInput.disabled = isSubmitting;
    passwordInput.disabled = isSubmitting;
    passwordToggle.disabled = isSubmitting;
    submitButton.textContent = isSubmitting ? "Logging in..." : "Login";
  }

  usernameInput.addEventListener("input", clearLoginError);
  passwordInput.addEventListener("input", clearLoginError);

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitButton.disabled) return;

    const username = usernameInput.value.trim();
    const password = passwordInput.value;

    clearLoginError();

    // Theo báo cáo: frontend kiểm tra trường bắt buộc chưa nhập.
    if (!username || !password) {
      showLoginError(errorBox, "Please enter both username and password.");
      setInvalid(usernameInput, !username);
      setInvalid(passwordInput, !password);
      (!username ? usernameInput : passwordInput).focus();
      return;
    }

    setSubmitting(true);

    try {
      await login(username, password);
      window.location.replace("dashboard.html");
    } catch (error) {
      showLoginError(errorBox, error.message);
      setSubmitting(false);
      setInvalid(usernameInput, true);
      setInvalid(passwordInput, true);
      passwordInput.focus();
    }
  });
});

async function login(username, password) {
  let response;
  try {
    response = await fetch("/api/auth/login", {
      method: "POST",
      credentials: "same-origin",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new Error("Cannot connect to the server. Please try again.");
  }

  if (response.ok) return;

  let message = "Incorrect username or password.";
  try {
    const body = await response.json();
    if (body?.message) message = body.message;
  } catch {
    // Keep the same visible fallback message.
  }
  throw new Error(message);
}

function showLoginError(errorBox, message) {
  errorBox.textContent = message;
  errorBox.classList.add("show");
}

function setInvalid(input, isInvalid) {
  if (isInvalid) input.setAttribute("aria-invalid", "true");
  else input.removeAttribute("aria-invalid");
}

function bindPasswordToggle(button, input) {
  button.addEventListener("click", () => {
    const isCurrentlyVisible = input.type === "text";
    const willShowPassword = !isCurrentlyVisible;

    input.type = willShowPassword ? "text" : "password";
    button.innerHTML = willShowPassword ? PASSWORD_ICONS.hidden : PASSWORD_ICONS.visible;
    button.setAttribute("aria-label", willShowPassword ? "Hide password" : "Show password");
    button.setAttribute("aria-pressed", String(willShowPassword));
  });
}
