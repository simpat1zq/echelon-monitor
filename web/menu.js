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
  overlay.hidden = server.hidden = !app;
  const refreshApp = () => {
    if (!app) return;
    overlay.textContent = app.overlayRunning() ? "Stop overlay" : "Start overlay";
  };
  overlay.onclick = () => {
    app.toggleOverlay();
    close();
    setTimeout(refreshApp, 800);
  };
  server.onclick = () => {
    const url = prompt("Dashboard server address", app.serverUrl());
    if (url) app.setServerUrl(url);
  };
  if (app) fs.hidden = true;  // the app is already full screen
  refreshApp();
  drawer.append(overlay, fs, apk, server, link("/debug", "Debug", "muted-link"));

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

  if (!app) {
    fetch("/echelon-overlay.apk", { method: "HEAD" }).then((r) => { apk.hidden = !r.ok; }).catch(() => {});
  }
  load();
  window.TreadmillMenu = { open, close, reload: load, refreshApp };
})();
