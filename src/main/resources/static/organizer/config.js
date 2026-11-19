// Public API origin only. No credentials are included in this file.
window.TICKIT_API_BASE = /^(localhost|127\.0\.0\.1)$/.test(location.hostname)
  ? "http://localhost:8081"
  : location.hostname.endsWith(".azurewebsites.net")
    ? location.origin
    : "https://tickit-mpy2.onrender.com";
