"use strict";
// Shared identity client. Secrets and authorization decisions stay on the server.
window.TickItAuth = (() => {
  const base = window.TICKIT_API_BASE || "";
  let initialization = null;
  let clerk = null;
  let config = {};
  let user = null;
  let syncGeneration = 0;
  const listeners = new Set();
  const emit = () => listeners.forEach((fn) => fn(user));
  async function token() {
    return clerk?.session ? clerk.session.getToken() : null;
  }
  async function sync() {
    const generation = ++syncGeneration;
    const bearer = await token();
    if (generation !== syncGeneration) return;
    if (!bearer) {
      user = null;
      emit();
      return;
    }
    const response = await fetch(`${base}/api/me`, {
      headers: { Authorization: `Bearer ${bearer}` },
    });
    if (generation !== syncGeneration) return;
    if (!response.ok) {
      user = null;
      emit();
      throw new Error("Your account could not be loaded. Please try again.");
    }
    const profile = await response.json();
    if (generation !== syncGeneration) return;
    user = profile;
    emit();
  }
  function loadScript(url, publishableKey) {
    return new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = url;
      script.async = true;
      script.crossOrigin = "anonymous";
      if (publishableKey) script.dataset.clerkPublishableKey = publishableKey;
      script.onload = resolve;
      script.onerror = () =>
        reject(new Error("Sign-in is temporarily unavailable."));
      document.head.append(script);
    });
  }
  async function initialize() {
    const response = await fetch(`${base}/api/public/config`);
    if (!response.ok)
      throw new Error("Account settings are temporarily unavailable.");
    config = await response.json();
    if (config.authProvider !== "clerk") return;
    const host = atob(config.publishableKey.split("_")[2]).replace(/\$$/, "");
    if (!/^[a-z0-9.-]+$/i.test(host))
      throw new Error("Sign-in is temporarily unavailable.");
    await loadScript(`https://${host}/npm/@clerk/ui@1/dist/ui.browser.js`);
    await loadScript(
      `https://${host}/npm/@clerk/clerk-js@6/dist/clerk.browser.js`,
      config.publishableKey,
    );
    clerk = window.Clerk;
    await clerk.load({
      ui: { ClerkUI: window.__internal_ClerkUICtor },
      appearance: {
        variables: {
          colorPrimary: "#194f43",
          borderRadius: "4px",
          fontFamily: "Inter, system-ui, sans-serif",
        },
      },
    });
    await sync();
    clerk.addListener(() =>
      sync().catch((error) =>
        window.dispatchEvent(
          new CustomEvent("tickit:auth-error", { detail: error.message }),
        ),
      ),
    );
  }
  function init() {
    if (!initialization)
      initialization = initialize().catch((error) => {
        initialization = null;
        throw error;
      });
    return initialization;
  }
  async function open(kind) {
    try {
      await init();
    } catch (error) {
      window.dispatchEvent(
        new CustomEvent("tickit:auth-error", { detail: error.message }),
      );
      return;
    }
    if (!clerk) {
      window.dispatchEvent(
        new CustomEvent("tickit:auth-error", {
          detail: "Sign-in is temporarily unavailable. Please try again later.",
        }),
      );
      return;
    }
    const options = { forceRedirectUrl: location.href };
    if (kind === "sign-up") clerk.openSignUp(options);
    else clerk.openSignIn(options);
  }
  return {
    init,
    token,
    open,
    onChange(fn) {
      listeners.add(fn);
    },
    signOut: async () => {
      if (clerk) await clerk.signOut();
    },
    get user() {
      return user;
    },
    get config() {
      return config;
    },
  };
})();
