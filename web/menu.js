// Shared slide-out menu: pages, who's running, manage users, full screen,
// the Android overlay app and debug. Needs a <button id="menuBtn"> on the page.
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
  const apk = link("/echelon-overlay.apk", "Android overlay app (APK)");
  apk.hidden = true;
  apk.setAttribute("download", "");
  drawer.append(fs, apk, link("/debug", "Debug", "muted-link"));

  document.body.append(scrim, drawer);
  const open = () => document.body.classList.add("menu-open");
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

  fetch("/echelon-overlay.apk", { method: "HEAD" }).then((r) => { apk.hidden = !r.ok; }).catch(() => {});
  load();
  window.TreadmillMenu = { open, close, reload: load };
})();
