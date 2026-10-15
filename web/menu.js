// Shared slide-out menu: pages, who's running, manage users, full screen,
// the Android app and debug. Inside the Android app it also offers
// start/stop overlay and the server address. Needs a <button id="menuBtn">.
(function () {
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  };
  const link = (href, text, cls) => {
    const a = el("a", cls, text);
    a.href = href;
    if (location.pathname === href) a.classList.add("current");
    return a;
  };

  const scrim = el("div", "scrim");
  const drawer = el("nav", "drawer");
  drawer.setAttribute("aria-label", "Menu");
  drawer.append(el("div", "head", "Treadmill"), link("/", "Live"), link("/runs", "Runs"));

  drawer.append(el("div", "section", "Who's running"));
  const users = el("div");
  drawer.append(users, link("/users", "Manage users"));

  drawer.append(el("div", "section", "More"));
  const fs = el("button", "item", "Full screen");
  fs.hidden = !document.documentElement.requestFullscreen;
  fs.onclick = () => {
    close();
    if (document.fullscreenElement) document.exitFullscreen();
    else document.documentElement.requestFullscreen().catch(() => {});
  };
  const apk = link("/echelon-overlay.apk", "Android app (APK)");
  apk.hidden = true;
  apk.setAttribute("download", "");

  // Extra items when the page runs inside the Android app (EchelonApp bridge).
  const app = window.EchelonApp;
  const overlay = el("button", "item");
  const server = el("button", "item", "Server address");
  const update = el("button", "item", "Check for app update");
  overlay.hidden = server.hidden = !app;
  update.hidden = !(app && app.installUpdate);
  update.onclick = async () => {
    close();
    let latest;
    try {
      const r = await fetch("/echelon-overlay.json", { cache: "no-store" });
      if (!r.ok) throw new Error("no update info on the server");
      latest = await r.json();
    } catch (e) {
      alert("Couldn't check for updates: " + e.message);
      return;
    }
    const mine = app.appVersion();
    if (latest.versionCode > mine) {
      if (confirm("Update the app to version " + latest.versionName + "? (You have 1.0." + mine + ")")) {
        app.installUpdate(new URL("/echelon-overlay.apk", location.href).href);
      }
    } else {
      alert("The app is up to date (version 1.0." + mine + ").");
    }
  };
  // Optional shortcut on the page itself (dashboard header).
  const headerBtn = document.getElementById("overlayBtn");
  const motion = el("button", "item");
  motion.hidden = !(app && app.setMotionEnabled);
  const refreshApp = () => {
    if (!app) return;
    const on = app.overlayRunning();
    overlay.textContent = on ? "Stop overlay" : "Start overlay";
    if (headerBtn) {
      headerBtn.hidden = false;
      headerBtn.classList.toggle("on", on);
      headerBtn.textContent = on ? "Overlay on" : "Overlay off";
    }
    if (app.motionEnabled) motion.textContent = "Wake on motion: " + (app.motionEnabled() ? "on" : "off");
  };
  const toggleOverlay = () => {
    app.toggleOverlay();
    close();
    setTimeout(refreshApp, 800);
  };
  overlay.onclick = toggleOverlay;
  if (headerBtn && app) headerBtn.onclick = toggleOverlay;
  motion.onclick = () => {
    app.setMotionEnabled(!app.motionEnabled());
    setTimeout(refreshApp, 800);
  };
  server.onclick = () => {
    const url = prompt("Dashboard server address", app.serverUrl());
    if (url) app.setServerUrl(url);
  };
  if (app) fs.hidden = true;  // the app is already full screen
  refreshApp();
  const timeoutItem = el("button", "item");
  timeoutItem.onclick = async () => {
    const v = prompt("Let the screen turn off after how many minutes with the treadmill stopped?",
      String(screenTimeoutMin));
    if (v == null) return;
    const r = await fetch("/api/settings", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ screen_timeout_min: Number(v) }),
    });
    if (!r.ok) { alert(await r.text()); return; }
    applySettings(await r.json());
    lastActive = Date.now();
    updateAwake();
  };
  drawer.append(overlay, motion, fs, timeoutItem, apk, update, server, link("/debug", "Debug", "muted-link"));

  document.body.append(scrim, drawer);
  const open = () => {
    refreshApp();
    document.body.classList.add("menu-open");
  };
  const close = () => document.body.classList.remove("menu-open");
  scrim.onclick = close;
  document.addEventListener("keydown", (e) => e.key === "Escape" && close());
  const btn = document.getElementById("menuBtn");
  if (btn) btn.onclick = open;

  async function choose(id) {
    await fetch("/api/users/active", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ id }),
    });
    await load();
    document.dispatchEvent(new CustomEvent("runnerchange"));
    close();
  }

  async function load() {
    let data;
    try { data = await (await fetch("/api/users")).json(); } catch (e) { return; }
    users.textContent = "";
    if (!data.history) {
      users.append(el("div", "item sub", "Needs run history (--db)"));
      return;
    }
    const opts = [{ id: null, name: "Nobody" }, ...data.users];
    for (const u of opts) {
      const b = el("button", "item" + (u.id === data.active_id ? " selected" : ""));
      b.append(el("span", "radio"), el("span", null, u.name));
      b.onclick = () => choose(u.id);
      users.append(b);
    }
    if (!data.users.length) users.append(el("div", "item sub", "No users yet. Add one below."));
  }

  // --- Keep the screen on while the treadmill is in use ---------------
  // Awake while the belt moves and for `screen_timeout_min` after it stops
  // (or after the page opens); then the device's own screen timeout applies.
  let screenTimeoutMin = 15, lastActive = Date.now(), wakeLock = null, awake = null;

  function applySettings(s) {
    screenTimeoutMin = s.screen_timeout_min || 15;
    timeoutItem.textContent = "Screen timeout: " + screenTimeoutMin + " min";
  }
  applySettings({});

  async function setAwake(on) {
    if (on === awake && (!on || app || wakeLock)) return;
    awake = on;
    if (app && app.setKeepScreenOn) { app.setKeepScreenOn(on); return; }
    try {
      if (on && !wakeLock && "wakeLock" in navigator && document.visibilityState === "visible") {
        wakeLock = await navigator.wakeLock.request("screen");
        wakeLock.addEventListener("release", () => { wakeLock = null; });
      } else if (!on && wakeLock) {
        await wakeLock.release();
        wakeLock = null;
      }
    } catch (e) { /* wake lock not supported or not allowed */ }
  }

  function updateAwake() {
    setAwake(Date.now() - lastActive < screenTimeoutMin * 60000);
  }

  async function pollActivity() {
    try {
      const st = await (await fetch("/api/state")).json();
      if (st.speed > 0) lastActive = Date.now();
    } catch (e) { /* server unreachable: just let the timeout run */ }
    updateAwake();
  }

  fetch("/api/settings").then((r) => r.json()).then(applySettings).catch(() => {});
  document.addEventListener("visibilitychange", () => { awake = null; updateAwake(); });
  pollActivity();
  setInterval(pollActivity, 10000);

  if (!app) {
    fetch("/echelon-overlay.apk", { method: "HEAD" }).then((r) => { apk.hidden = !r.ok; }).catch(() => {});
  }
  load();
  // Called by the app when the camera sees movement: counts as activity.
  const poke = () => { lastActive = Date.now(); updateAwake(); };
  window.TreadmillMenu = { open, close, reload: load, refreshApp, poke };
})();
