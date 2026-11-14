/* FocusQuest cloud trainer dashboard. All user data is inserted with textContent, never innerHTML. */
(function () {
  "use strict";

  var root = document.getElementById("root");
  var me = null, meta = null, source = null, live = null, classInfo = null;
  var drawerMode = null, selectedId = null;
  var TZ = -new Date().getTimezoneOffset();

  // ---------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------
  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    if (attrs) Object.keys(attrs).forEach(function (k) {
      var v = attrs[k];
      if (v == null || v === false) return;
      if (k === "text") e.textContent = v;
      else if (k === "class") e.className = v;
      else if (k === "style") e.setAttribute("style", v);
      else if (k.indexOf("on") === 0) e.addEventListener(k.slice(2), v);
      else e.setAttribute(k, v === true ? "" : v);
    });
    (children || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  }
  function $(id) { return document.getElementById(id); }
  function fmt(sec) { sec = Math.max(0, sec | 0); return Math.floor(sec / 60) + ":" + String(sec % 60).padStart(2, "0"); }
  function pctColor(p) { return p >= 70 ? "var(--focus)" : p >= 40 ? "var(--warm)" : "var(--danger)"; }
  function toast(msg) { var t = $("toast"); t.textContent = msg; t.style.display = "block"; clearTimeout(toast.h); toast.h = setTimeout(function () { t.style.display = "none"; }, 3000); }
  function store(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) { return null; } }
  function plural(n, w) { return n + " " + w + (n === 1 ? "" : "s"); }

  function api(method, path, body) {
    var opts = { method: method, credentials: "same-origin", headers: { "X-FQ": "1" } };
    if (body !== undefined) { opts.headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(body); }
    return fetch(path, opts).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (r.status === 401 && path.indexOf("/api/login") !== 0 && path !== "/api/me/password") { me = null; go("#/login"); throw new Error("auth"); }
        if (!r.ok) throw new Error(j.error || ("Error " + r.status));
        return j;
      });
    });
  }
  function fail(e) { if (e && e.message !== "auth") toast(e.message); }
  function download(url) {
    toast("Preparing report…");
    var a = el("a", { href: url + (url.indexOf("?") >= 0 ? "&" : "?") + "tz=" + TZ, download: "" });
    document.body.appendChild(a); a.click(); a.remove();
  }

  function applyTheme(t) { if (t) document.documentElement.setAttribute("data-theme", t); else document.documentElement.removeAttribute("data-theme"); }
  applyTheme(store("fq.theme"));
  function toggleTheme() {
    var cur = document.documentElement.getAttribute("data-theme") || (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
    var next = cur === "light" ? "dark" : "light"; applyTheme(next); store("fq.theme", next);
  }

  // ---------------------------------------------------------------------------------
  // routing
  // ---------------------------------------------------------------------------------
  function go(hash) { if (location.hash !== hash) location.hash = hash; else route(); }
  window.addEventListener("hashchange", route);

  function route() {
    stopLive(); closeDrawer(); document.body.classList.remove("projector");
    var h = location.hash || "#/";
    api("GET", "/api/status").then(function (st) {
      me = st.user;
      if (st.setupNeeded) return viewSetup();
      if (!me) return viewLogin();
      var m;
      if ((m = h.match(/^#\/class\/([\w-]+)/))) return viewClass(m[1]);
      if (h === "#/teachers") return viewTeachers();
      if (h === "#/account") return viewAccount();
      return viewClasses();
    }).catch(fail);
  }

  function topbar(title, sub, extra) {
    return el("header", { class: "topbar" }, [
      el("div", { class: "grow" }, [
        el("div", { class: "brand", text: "FocusQuest · " + (me ? me.orgName : "Trainer") }),
        el("h1", { text: title }),
        sub ? el("div", { class: "muted small" }, [sub]) : null,
      ]),
      extra || null,
    ]);
  }

  function nav() {
    return el("div", { class: "row controls" }, [
      el("button", { text: "Classes", onclick: function () { go("#/"); } }),
      me && me.role === "admin" ? el("button", { text: "Teachers", onclick: function () { go("#/teachers"); } }) : null,
      el("button", { text: "Account", onclick: function () { go("#/account"); } }),
      el("button", { class: "ghost", text: "◐", title: "Light/dark theme", "aria-label": "Toggle theme", onclick: toggleTheme }),
      el("button", { class: "ghost", text: "Sign out", onclick: function () { api("POST", "/api/logout").finally(function () { go("#/login"); }); } }),
    ]);
  }

  // ---------------------------------------------------------------------------------
  // auth views
  // ---------------------------------------------------------------------------------
  function authForm(title, intro, fields, submitText, onSubmit) {
    var err = el("div", { class: "err" });
    var inputs = {};
    var form = el("form", null, [el("div", { class: "brand", text: "FocusQuest" }), el("h2", { style: "margin-top:6px", text: title }),
      intro ? el("div", { class: "muted small", text: intro }) : null]);
    fields.forEach(function (f) {
      var id = "f_" + f.name;
      form.appendChild(el("label", { for: id, text: f.label }));
      inputs[f.name] = el("input", { id: id, name: f.name, type: f.type || "text", autocomplete: f.autocomplete || "off", required: true, minlength: f.minlength || null });
      form.appendChild(inputs[f.name]);
    });
    form.appendChild(err);
    var btn = el("button", { class: "primary", type: "submit", text: submitText });
    form.appendChild(btn);
    form.addEventListener("submit", function (e) {
      e.preventDefault(); err.textContent = ""; btn.disabled = true;
      var vals = {}; Object.keys(inputs).forEach(function (k) { vals[k] = inputs[k].value; });
      onSubmit(vals).catch(function (x) { if (x.message !== "auth") err.textContent = x.message; }).finally(function () { btn.disabled = false; });
    });
    root.textContent = "";
    root.appendChild(el("div", { class: "auth" }, [form]));
    var first = form.querySelector("input"); if (first) first.focus();
  }

  function viewSetup() {
    authForm("Set up your school", "Create the first account. You'll be the school admin and can add other teachers.", [
      { name: "orgName", label: "School name" }, { name: "name", label: "Your name", autocomplete: "name" },
      { name: "email", label: "Email", type: "email", autocomplete: "email" },
      { name: "password", label: "Password (10+ characters)", type: "password", autocomplete: "new-password", minlength: 10 },
    ], "Create school", function (v) { return api("POST", "/api/setup", v).then(function () { go("#/"); }); });
  }

  function viewLogin() {
    authForm("Teacher sign in", null, [
      { name: "email", label: "Email", type: "email", autocomplete: "username" },
      { name: "password", label: "Password", type: "password", autocomplete: "current-password" },
    ], "Sign in", function (v) {
      return api("POST", "/api/login", v).then(function () { go(location.hash.indexOf("#/class/") === 0 ? location.hash : "#/"); });
    });
  }

  // ---------------------------------------------------------------------------------
  // classes list
  // ---------------------------------------------------------------------------------
  function viewClasses() {
    root.textContent = "";
    var wrap = el("div", { class: "wrap" }, [topbar("Classes", "Hi " + me.name + ". Pick a class to watch it live.", nav())]);
    var name = el("input", { placeholder: "New class name, e.g. Grade 4B Focus Club", "aria-label": "New class name", maxlength: 60, class: "grow" });
    wrap.appendChild(el("form", { class: "panel row", onsubmit: function (e) {
      e.preventDefault();
      if (!name.value.trim()) return;
      api("POST", "/api/classes", { name: name.value }).then(function (r) { go("#/class/" + r.id); }).catch(fail);
    } }, [name, el("button", { class: "primary", type: "submit", text: "Create class" })]));
    var list = el("div", { class: "classes" }, [el("div", { class: "muted", text: "Loading…" })]);
    wrap.appendChild(list);
    root.appendChild(wrap);
    api("GET", "/api/classes").then(function (cs) {
      list.textContent = "";
      if (!cs.length) { list.appendChild(el("div", { class: "empty", text: "No classes yet. Create one above, then give students its join code." })); return; }
      cs.forEach(function (c) {
        list.appendChild(el("button", { class: "class-card" + (c.archived ? " archived" : ""), type: "button", onclick: function () { go("#/class/" + c.id); } }, [
          el("div", { class: "name", text: c.name }),
          el("div", { class: "muted small", text: plural(c.studentCount, "student") + (c.teacherName ? " · " + c.teacherName : "") + (c.archived ? " · archived" : "") }),
          el("div", { class: "muted small", style: "margin-top:10px", text: "Join code" }),
          el("div", { class: "jc", text: c.joinCode }),
        ]));
      });
    }).catch(fail);
  }

  // ---------------------------------------------------------------------------------
  // live class
  // ---------------------------------------------------------------------------------
  function stopLive() { if (source) { source.close(); source = null; } live = null; classInfo = null; }

  function viewClass(id) {
    root.textContent = "";
    var header = el("header", { class: "topbar" }, [
      el("div", { class: "grow" }, [
        el("div", { class: "brand" }, [el("a", { href: "#/", text: "← Classes", class: "controls" })]),
        el("h1", { id: "className", text: "Class" }),
        el("div", { class: "muted small" }, [el("span", { id: "connectedText", text: "…" }), " · ",
          el("span", { class: "live" }, [el("span", { class: "dot", id: "liveDot" }), el("span", { id: "liveText", text: "Connecting…" })])]),
      ]),
      el("div", { class: "code" }, [
        el("div", { class: "label", text: "Students join with" }),
        el("div", { class: "value", id: "joinCode", text: "––––––" }),
        el("div", { class: "server", text: "Server: " + location.origin }),
      ]),
      el("div", { class: "row controls" }, [
        el("button", { text: "🎉 Cheer all", onclick: function () { api("POST", "/api/classes/" + id + "/cheer", { text: "Great work, everyone! 🌟" }).then(function () { toast("Cheer sent to the class"); }).catch(fail); } }),
        el("button", { text: "📄 Reports", onclick: function () { openDrawer("reports"); loadReports(id, +(store("fq.days") || 30)); } }),
        el("button", { text: "Projector", title: "Large, positive view for a shared screen", onclick: function () { document.body.classList.add("projector"); closeDrawer(); } }),
        el("button", { text: "⚙ Class", onclick: function () { openDrawer("settings"); renderSettings(id); } }),
        el("button", { class: "ghost", text: "◐", "aria-label": "Toggle theme", onclick: toggleTheme }),
      ]),
      el("div", { class: "projector-only" }, [el("button", { text: "Exit projector", onclick: function () { document.body.classList.remove("projector"); } })]),
    ]);
    var stats = el("div", { class: "stats" }, [
      stat("statConnected", "connected"), stat("statPlaying", "playing now"), stat("statFocused", "on goal right now"),
      stat("statAvg", "avg time on goal", "scores"), stat("statAlerts", "need attention", "controls"),
    ]);
    var search = el("input", { id: "search", class: "grow", placeholder: "Search students", "aria-label": "Search students", oninput: renderGrid });
    var sort = el("select", { id: "sort", "aria-label": "Sort students", onchange: function () { store("fq.sort", sort.value); renderGrid(); } }, [
      el("option", { value: "attention", text: "Needs attention first" }), el("option", { value: "name", text: "Name" }), el("option", { value: "focus", text: "Time on goal" }),
    ]);
    if (store("fq.sort")) sort.value = store("fq.sort");
    root.appendChild(el("div", { class: "wrap" }, [
      header, stats, el("section", { class: "panel controls", id: "groupPanel" }),
      el("div", { class: "row controls", style: "margin-bottom:10px" }, [search, sort]),
      el("div", { class: "grid", id: "grid" }),
      el("div", { class: "empty", id: "empty", hidden: true }, [
        "No students yet.", el("br"),
        el("span", { class: "small", text: "In the FocusQuest app: Settings → Join a class → Online class, enter " + location.origin + " and the join code above." }),
      ]),
    ]));
    if (!meta) api("GET", "/api/meta").then(function (m) { meta = m; renderGroup(id); }).catch(fail);
    connect(id);
  }

  function stat(id, label, cls) {
    return el("div", { class: "stat" + (cls ? " " + cls : "") }, [el("b", { id: id, text: "0" }), el("span", { class: "muted small", text: label })]);
  }

  function connect(id) {
    if (source) source.close();
    source = new EventSource("/api/classes/" + encodeURIComponent(id) + "/events");
    source.onmessage = function (e) {
      try { live = JSON.parse(e.data); } catch (x) { return; }
      if (!live) return;
      setLive(true); renderLive(id);
    };
    source.onerror = function () { setLive(false); api("GET", "/api/status").catch(function () {}); };
  }
  function setLive(on) { var d = $("liveDot"), t = $("liveText"); if (d) d.className = "dot" + (on ? " on" : ""); if (t) t.textContent = on ? "Live" : "Reconnecting…"; }

  function renderLive(id) {
    if (!$("grid")) return;
    $("className").textContent = live.className;
    document.title = live.className + " · FocusQuest";
    $("joinCode").textContent = live.code;
    var st = live.students, connected = st.filter(function (s) { return s.connected; });
    var playing = connected.filter(function (s) { return s.session && !s.session.paused; });
    var focused = playing.filter(function (s) { return s.session.focused; });
    var inSession = connected.filter(function (s) { return s.session && s.session.elapsed > 0; });
    $("connectedText").textContent = connected.length + " of " + plural(st.length, "student") + " connected";
    $("statConnected").textContent = connected.length;
    $("statPlaying").textContent = playing.length;
    $("statFocused").textContent = focused.length;
    $("statAlerts").textContent = connected.filter(function (s) { return s.alerts.length; }).length;
    $("statAvg").textContent = inSession.length ? Math.round(inSession.reduce(function (a, s) { return a + s.session.focusPct; }, 0) / inSession.length) + "%" : "–";
    renderGroup(id); renderGrid();
    if (drawerMode === "student") renderStudent(id);
  }

  function renderGroup(id) {
    var p = $("groupPanel"); if (!p || !live || !meta) return;
    var g = live.group, connected = live.students.filter(function (s) { return s.connected; }).length;
    var key = JSON.stringify([g, connected]);
    if (p.dataset.key === key) return;
    p.dataset.key = key; p.textContent = "";
    if (!g) {
      var game = el("select", { "aria-label": "Game" }), dur = el("select", { "aria-label": "Duration" }), groups = {};
      meta.games.forEach(function (x) { var label = x.skill + " · " + x.style; (groups[label] = groups[label] || []).push(x); });
      Object.keys(groups).forEach(function (label) {
        var og = el("optgroup", { label: label });
        groups[label].forEach(function (x) { og.appendChild(el("option", { value: x.id, text: x.title })); });
        game.appendChild(og);
      });
      if (store("fq.game")) game.value = store("fq.game");
      meta.durations.forEach(function (d) { dur.appendChild(el("option", { value: d, text: (d / 60) + " min" })); });
      dur.value = store("fq.dur") || "300";
      var btn = el("button", { class: "primary", disabled: !connected, text: connected ? "Start for " + plural(connected, "student") : "No students connected", onclick: function () {
        store("fq.game", game.value); store("fq.dur", dur.value); btn.disabled = true;
        api("POST", "/api/classes/" + id + "/group/start", { game: game.value, duration: +dur.value })
          .then(function () { toast("Group session started"); }).catch(function (e) { fail(e); btn.disabled = false; });
      } });
      p.appendChild(el("div", { class: "row" }, [
        el("div", { class: "grow" }, [el("b", { text: "Group session" }), el("div", { class: "muted small", text: "Start the same game on every connected student's phone." })]),
        game, dur, btn,
      ]));
    } else {
      var left = Math.max(0, g.plannedSec - g.elapsedSec);
      var row = el("div", { class: "row" }, [el("div", { class: "grow" }, [
        el("b", { text: g.gameTitle + " · group session" }),
        el("div", { class: "muted small", text: (g.ended ? "Ended" : "About " + fmt(left) + " left") + " · " + g.results + "/" + g.total + " results in" }),
      ])]);
      if (!g.ended && !g.allResultsIn) row.appendChild(el("button", { text: "End for everyone", onclick: function () { api("POST", "/api/classes/" + id + "/group/end").catch(fail); } }));
      row.appendChild(el("button", { class: g.allResultsIn || g.ended ? "primary" : "", text: "Save & download report", onclick: function () {
        api("POST", "/api/classes/" + id + "/group/close").then(function (r) {
          download("/api/classes/" + id + "/reports/group.pdf?gid=" + encodeURIComponent(r.groupId)); toast("Session saved");
        }).catch(fail);
      } }));
      p.appendChild(row);
    }
  }

  function sortStudents(list) {
    var mode = ($("sort") || {}).value, q = (($("search") || {}).value || "").trim().toLowerCase();
    if (q) list = list.filter(function (s) { return s.name.toLowerCase().indexOf(q) >= 0; });
    return list.slice().sort(function (a, b) {
      if (mode === "name") return a.name.localeCompare(b.name);
      if (mode === "focus") return ((b.session && b.session.focusPct) || -1) - ((a.session && a.session.focusPct) || -1);
      var aa = a.connected && a.alerts.length ? 1 : 0, bb = b.connected && b.alerts.length ? 1 : 0;
      if (aa !== bb) return bb - aa;
      if (a.connected !== b.connected) return a.connected ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
  }

  function spark(values, threshold, color) {
    var ns = "http://www.w3.org/2000/svg", svg = document.createElementNS(ns, "svg");
    svg.setAttribute("class", "spark"); svg.setAttribute("viewBox", "0 0 100 38"); svg.setAttribute("preserveAspectRatio", "none");
    svg.setAttribute("aria-hidden", "true");
    if (threshold != null) {
      var t = document.createElementNS(ns, "line"), y = 38 - 38 * threshold / 100;
      [["x1", 0], ["x2", 100], ["y1", y], ["y2", y], ["stroke", "var(--warm)"], ["stroke-width", ".8"], ["stroke-dasharray", "3 2"], ["vector-effect", "non-scaling-stroke"]]
        .forEach(function (a) { t.setAttribute(a[0], a[1]); });
      svg.appendChild(t);
    }
    if (values.length > 1) {
      var pts = values.map(function (v, i) { return (i * 100 / (values.length - 1)).toFixed(2) + "," + (38 - 38 * Math.max(0, Math.min(100, v)) / 100).toFixed(2); });
      var pl = document.createElementNS(ns, "polyline");
      [["points", pts.join(" ")], ["fill", "none"], ["stroke", color], ["stroke-width", "2"], ["vector-effect", "non-scaling-stroke"], ["stroke-linejoin", "round"]]
        .forEach(function (a) { pl.setAttribute(a[0], a[1]); });
      svg.appendChild(pl);
    }
    return svg;
  }

  function bar(label, value, color, on) {
    return el("div", { class: "bar" }, [
      el("span", { class: "lbl", text: label }),
      el("div", { class: "track" }, [el("div", { class: "fill", style: "width:" + (on ? Math.max(0, Math.min(100, value)) : 0) + "%;background:" + color })]),
      el("span", { class: "num", text: on ? String(value) : "–" }),
    ]);
  }

  function renderGrid() {
    var grid = $("grid"); if (!grid || !live) return;
    $("empty").hidden = live.students.length > 0;
    var frag = document.createDocumentFragment();
    sortStudents(live.students).forEach(function (s) {
      var ses = s.session, calm = ses && ses.metric === "CALM", alert = s.connected && s.alerts.length > 0;
      var status = !s.connected ? (s.joined ? "Offline" : "Not connected yet") : s.source === "Simulator" ? "Simulator" : s.source === "None" ? "No headset" : "Signal " + s.signal + "%";
      var card = el("button", { class: "card" + (alert ? " alert" : "") + (s.connected ? "" : " offline"), type: "button", "aria-label": s.name, onclick: function () { openStudent(s.id); } }, [
        el("div", { class: "who" }, [
          el("div", { class: "avatar", style: "background:" + s.color, text: (s.name || "?").charAt(0).toUpperCase() }),
          el("div", { style: "min-width:0;flex:1" }, [el("div", { class: "name", text: s.name }), el("div", { class: "muted small status", text: status })]),
          el("span", { class: "dot" + (s.connected ? " on" : "") }),
        ]),
      ]);
      if (ses && s.connected) {
        card.appendChild(el("div", { class: "muted small", style: "margin-top:8px", text: ses.gameTitle + " · " + fmt(ses.duration - ses.elapsed) + " left" }));
        card.appendChild(el("div", { class: "row scores", style: "gap:8px;align-items:baseline" }, [
          el("span", { class: "big", style: "color:" + pctColor(ses.focusPct), text: ses.focusPct + "%" }),
          el("span", { class: "chip" + (ses.focused && !ses.paused ? " ok" : ""), text: ses.paused ? "paused" : ses.focused ? (calm ? "calm ✓" : "focused ✓") : "drifting" }),
        ]));
        card.appendChild(el("div", { class: "projector-only star", text: ses.focused && !ses.paused ? (ses.streak >= 10 ? "🌟 " + ses.streak + "s" : "⭐") : "🌱" }));
      } else if (s.connected) {
        card.appendChild(el("div", { class: "muted small", style: "margin:8px 0 2px", text: "Not in a game" }));
      }
      card.appendChild(el("div", { class: "bars" }, [bar("Attention", s.attention, "var(--focus)", s.connected), bar("Calm", s.meditation, "var(--calm)", s.connected)]));
      var sp = spark(calm ? s.calmHistory : s.attentionHistory, ses ? ses.threshold : null, calm ? "var(--calm)" : "var(--focus)");
      sp.classList.add("bars"); card.appendChild(sp);
      if (alert) card.appendChild(el("div", { class: "alerts", text: "⚠ " + s.alerts.join(" · ") }));
      frag.appendChild(card);
    });
    grid.textContent = ""; grid.appendChild(frag);
  }

  // ---------------------------------------------------------------------------------
  // drawer: student / reports / class settings
  // ---------------------------------------------------------------------------------
  function openDrawer(mode) { drawerMode = mode; $("drawer").classList.add("open"); $("backdrop").classList.add("open"); }
  function closeDrawer() { drawerMode = null; selectedId = null; $("drawer").classList.remove("open"); $("backdrop").classList.remove("open"); $("drawer").textContent = ""; }
  $("backdrop").addEventListener("click", closeDrawer);
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") { closeDrawer(); document.body.classList.remove("projector"); } });
  function drawerHeader(title, sub) {
    return el("div", { class: "row" }, [el("div", { class: "grow" }, [el("h2", { style: "margin:0", text: title }), sub ? el("div", { class: "muted small", text: sub }) : null]),
      el("button", { class: "ghost", text: "✕", "aria-label": "Close", onclick: closeDrawer })]);
  }

  function classId() { var m = location.hash.match(/^#\/class\/([\w-]+)/); return m && m[1]; }

  function openStudent(id) { if (document.body.classList.contains("projector")) return; selectedId = id; openDrawer("student"); renderStudent(classId()); }
  function renderStudent(cid) {
    var s = live && live.students.find(function (x) { return x.id === selectedId; }), d = $("drawer");
    if (!s) { closeDrawer(); return; }
    d.textContent = "";
    d.appendChild(drawerHeader(s.name, "Age " + s.age + " · " + (s.connected ? "connected" : "offline")));
    var ses = s.session, p = el("div", { class: "panel", style: "margin-top:14px" });
    if (ses) {
      p.appendChild(el("b", { text: ses.gameTitle }));
      if (ses.progress) p.appendChild(el("div", { class: "muted small", text: ses.progress }));
      p.appendChild(el("div", { class: "stats", style: "margin:10px 0 0" }, [
        mini(ses.focusPct + "%", "on goal", pctColor(ses.focusPct)), mini(ses.streak + "s", "current streak"),
        mini(String(ses.threshold), (ses.metric === "CALM" ? "calm" : "focus") + " goal"), mini(fmt(ses.duration - ses.elapsed), "left"),
      ]));
    } else p.appendChild(el("div", { class: "muted", text: s.connected ? "Not in a game right now." : "Offline." }));
    p.appendChild(bar("Attention", s.attention, "var(--focus)", s.connected));
    p.appendChild(bar("Calm", s.meditation, "var(--calm)", s.connected));
    p.appendChild(el("div", { class: "muted small", style: "margin-top:10px", text: "Attention, last 90 seconds" }));
    p.appendChild(spark(s.attentionHistory, ses ? ses.threshold : null, "var(--focus)"));
    p.appendChild(el("div", { class: "muted small", style: "margin-top:6px", text: "Calm, last 90 seconds" }));
    p.appendChild(spark(s.calmHistory, null, "var(--calm)"));
    if (s.alerts.length && s.connected) p.appendChild(el("div", { class: "alerts", text: "⚠ " + s.alerts.join(" · ") }));
    d.appendChild(p);
    if (s.connected) {
      d.appendChild(el("h3", { text: "Send encouragement" }));
      d.appendChild(el("div", { class: "row" }, ["Great focus! 🌟", "Keep going! 💪", "Nice and calm 🌙", "You've got this! 🚀"].map(function (c) {
        return el("button", { text: c, onclick: function () { api("POST", "/api/classes/" + cid + "/cheer", { studentId: s.id, text: c }).then(function () { toast("Sent to " + s.name); }).catch(fail); } });
      })));
    }
    d.appendChild(el("h3", { text: "Report" }));
    d.appendChild(el("div", { class: "row" }, [7, 30, 90, 0].map(function (n) {
      return el("button", { text: n ? n + " days" : "All time", onclick: function () { download("/api/classes/" + cid + "/reports/student.pdf?sid=" + encodeURIComponent(s.id) + "&days=" + n); } });
    })));
  }
  function mini(value, label, color) {
    return el("div", { class: "stat" }, [el("b", { style: color ? "color:" + color : null, text: value }), el("span", { class: "muted small", text: label })]);
  }

  function loadReports(cid, days) {
    store("fq.days", String(days));
    var d = $("drawer"); d.textContent = "";
    var range = el("select", { "aria-label": "Date range", onchange: function () { loadReports(cid, +range.value); } });
    [[7, "Last 7 days"], [30, "Last 30 days"], [90, "Last 90 days"], [0, "All time"]].forEach(function (r) { range.appendChild(el("option", { value: r[0], text: r[1] })); });
    range.value = String(days);
    d.appendChild(drawerHeader("Reports"));
    d.appendChild(el("div", { class: "row", style: "margin:12px 0" }, [
      range,
      el("button", { class: "primary", text: "Class summary PDF", onclick: function () { download("/api/classes/" + cid + "/reports/class.pdf?days=" + days); } }),
      el("button", { text: "CSV export", onclick: function () { download("/api/classes/" + cid + "/reports/class.csv?days=" + days); } }),
    ]));
    var body = el("div", { class: "muted", text: "Loading…" }); d.appendChild(body);
    api("GET", "/api/classes/" + cid + "/reports/summary?days=" + days + "&tz=" + TZ).then(function (r) {
      body.textContent = ""; body.className = "";
      if (!r.students.length) { body.appendChild(el("div", { class: "muted", text: "No student data yet." })); return; }
      var table = el("table");
      table.appendChild(el("tr", null, ["Student", "Sessions", "Min", "On goal", "Change", "Status", ""].map(function (h) { return el("th", { text: h }); })));
      r.students.forEach(function (s) {
        var change = s.change == null ? "–" : (s.change > 0 ? "+" : "") + s.change;
        var bad = s.flag === "Needs support" || s.flag === "Declining";
        table.appendChild(el("tr", null, [
          el("td", { text: s.name }), el("td", { text: String(s.sessions) }), el("td", { text: String(s.minutes) }),
          el("td", { text: s.focusPct == null ? "–" : s.focusPct + "%" }), el("td", { text: change }),
          el("td", { text: s.flag || "OK", style: bad ? "color:var(--danger)" : s.flag === "Improving" ? "color:var(--focus)" : null }),
          el("td", null, [el("a", { href: "#", text: "PDF", onclick: function (e) { e.preventDefault(); download("/api/classes/" + cid + "/reports/student.pdf?sid=" + encodeURIComponent(s.id) + "&days=" + days); } })]),
        ]));
      });
      body.appendChild(table);
      if (r.groups.length) {
        body.appendChild(el("h3", { text: "Live session reports" }));
        var gt = el("table");
        r.groups.forEach(function (g) {
          gt.appendChild(el("tr", null, [el("td", { text: g.gameTitle }), el("td", { text: new Date(g.startedAt).toLocaleString() }),
            el("td", { text: g.results + "/" + g.total + " results" }),
            el("td", null, [el("a", { href: "#", text: "PDF", onclick: function (e) { e.preventDefault(); download("/api/classes/" + cid + "/reports/group.pdf?gid=" + encodeURIComponent(g.id)); } })])]));
        });
        body.appendChild(gt);
      }
    }).catch(function (e) { body.textContent = e.message; });
  }

  function renderSettings(cid) {
    var d = $("drawer"); d.textContent = "";
    d.appendChild(drawerHeader("Class settings"));
    var name = el("input", { value: live ? live.className : "", maxlength: 60, "aria-label": "Class name", class: "grow" });
    d.appendChild(el("h3", { text: "Name" }));
    d.appendChild(el("div", { class: "row" }, [name, el("button", { text: "Save", onclick: function () {
      api("PATCH", "/api/classes/" + cid, { name: name.value }).then(function () { toast("Saved"); }).catch(fail);
    } })]));
    d.appendChild(el("h3", { text: "Join code" }));
    d.appendChild(el("div", { class: "muted small", text: "Make a new code if the old one was shared too widely. Students already in the class stay connected." }));
    d.appendChild(el("div", { class: "row", style: "margin-top:8px" }, [el("button", { text: "Make a new join code", onclick: function () {
      api("PATCH", "/api/classes/" + cid, { newCode: true }).then(function (r) { toast("New code: " + r.joinCode); }).catch(fail);
    } })]));
    d.appendChild(el("h3", { text: "Students" }));
    var list = el("div");
    (live ? live.students : []).forEach(function (s) {
      list.appendChild(el("div", { class: "row", style: "padding:6px 0;border-bottom:1px solid var(--surface-2)" }, [
        el("span", { class: "grow", text: s.name + " (age " + s.age + ")" }),
        el("button", { class: "danger", text: "Remove", onclick: function () {
          if (!confirm("Remove " + s.name + " and all of their session data from this class? This can't be undone.")) return;
          api("DELETE", "/api/classes/" + cid + "/students/" + s.id).then(function () { toast(s.name + " removed"); renderSettings(cid); }).catch(fail);
        } }),
      ]));
    });
    if (!list.childNodes.length) list.appendChild(el("div", { class: "muted small", text: "No students yet." }));
    d.appendChild(list);
    d.appendChild(el("h3", { text: "Archive or delete" }));
    d.appendChild(el("div", { class: "row" }, [
      el("button", { text: "Archive class", title: "Stops new joins and connections; keeps all data", onclick: function () {
        api("PATCH", "/api/classes/" + cid, { archived: true }).then(function () { toast("Class archived"); go("#/"); }).catch(fail);
      } }),
      el("button", { class: "danger", text: "Delete class", onclick: function () {
        if (!confirm("Delete this class and ALL of its students' data? This can't be undone.")) return;
        api("DELETE", "/api/classes/" + cid).then(function () { toast("Class deleted"); go("#/"); }).catch(fail);
      } }),
    ]));
  }

  // ---------------------------------------------------------------------------------
  // teachers (admin) & account
  // ---------------------------------------------------------------------------------
  function viewTeachers() {
    root.textContent = "";
    var wrap = el("div", { class: "wrap" }, [topbar("Teachers", "Add teachers to your school. Each gets a one-time password to sign in.", nav())]);
    var name = el("input", { placeholder: "Name", "aria-label": "Teacher name", maxlength: 80 });
    var email = el("input", { placeholder: "Email", type: "email", "aria-label": "Teacher email", maxlength: 200, class: "grow" });
    var role = el("select", { "aria-label": "Role" }, [el("option", { value: "teacher", text: "Teacher" }), el("option", { value: "admin", text: "School admin" })]);
    var result = el("div");
    wrap.appendChild(el("form", { class: "panel", onsubmit: function (e) {
      e.preventDefault();
      api("POST", "/api/users", { name: name.value, email: email.value, role: role.value }).then(function (r) {
        result.textContent = "";
        result.appendChild(el("div", { class: "panel", style: "margin-top:12px;background:var(--surface-2)" }, [
          el("b", { text: "Account created for " + email.value }),
          el("div", { class: "muted small", text: "Give them this one-time password. It won't be shown again; they should change it under Account." }),
          el("div", { class: "secret", style: "margin-top:8px", text: r.tempPassword }),
        ]));
        name.value = ""; email.value = ""; load();
      }).catch(fail);
    } }, [el("div", { class: "row" }, [name, email, role, el("button", { class: "primary", type: "submit", text: "Add teacher" })]), result]));
    var table = el("table");
    wrap.appendChild(el("div", { class: "panel" }, [table]));
    root.appendChild(wrap);
    function load() {
      api("GET", "/api/users").then(function (us) {
        table.textContent = "";
        table.appendChild(el("tr", null, ["Name", "Email", "Role", ""].map(function (h) { return el("th", { text: h }); })));
        us.forEach(function (u) {
          table.appendChild(el("tr", null, [el("td", { text: u.name }), el("td", { text: u.email }), el("td", { text: u.role === "admin" ? "School admin" : "Teacher" }),
            el("td", null, [u.id === me.id ? el("span", { class: "muted small", text: "you" }) : el("button", { class: "danger", text: "Remove", onclick: function () {
              if (!confirm("Remove " + u.name + "'s account? Their classes stay with the school.")) return;
              api("DELETE", "/api/users/" + u.id).then(load).catch(fail);
            } })])]));
        });
      }).catch(fail);
    }
    load();
  }

  function viewAccount() {
    root.textContent = "";
    var cur = el("input", { type: "password", autocomplete: "current-password", id: "pw_cur" });
    var next = el("input", { type: "password", autocomplete: "new-password", minlength: 10, id: "pw_new" });
    var err = el("div", { class: "err" });
    root.appendChild(el("div", { class: "wrap" }, [
      topbar("Account", me.name + " · " + me.email, nav()),
      el("form", { class: "panel", style: "max-width:460px", onsubmit: function (e) {
        e.preventDefault(); err.textContent = "";
        api("POST", "/api/me/password", { current: cur.value, password: next.value })
          .then(function () { cur.value = ""; next.value = ""; toast("Password changed. Other browsers were signed out."); })
          .catch(function (x) { if (x.message !== "auth") err.textContent = x.message; });
      } }, [
        el("h2", { text: "Change password" }),
        el("label", { for: "pw_cur", text: "Current password" }), cur,
        el("label", { for: "pw_new", text: "New password (10+ characters)" }), next,
        err, el("button", { class: "primary", type: "submit", style: "margin-top:12px", text: "Change password" }),
      ]),
    ]));
  }

  route();
})();
