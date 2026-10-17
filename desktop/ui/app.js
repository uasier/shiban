const PUBLIC_SERVER = "https://shiban.uasier.com:40400";

function normalizeServer(value) {
  const raw = String(value || "").trim().replace(/\/$/, "");
  if (!raw) return PUBLIC_SERVER;
  try {
    const url = new URL(raw);
    const host = url.hostname;
    const local = host === "localhost"
      || host === "127.0.0.1"
      || host === "10.0.2.2"
      || host.startsWith("192.168.")
      || host.startsWith("10.")
      || ["8787", "18787"].includes(url.port)
      || host === "154.36.168.192"
      || host === "shiban.uasier.com";
    return local ? PUBLIC_SERVER : raw;
  } catch {
    return PUBLIC_SERVER;
  }
}

const state = {
  server: normalizeServer(localStorage.getItem("shiban.server")),
  token: localStorage.getItem("shiban.token") || "",
  username: localStorage.getItem("shiban.user") || "",
  userId: localStorage.getItem("shiban.userId") || "",
  displayName: localStorage.getItem("shiban.display") || "",
  ai: {
    baseUrl: localStorage.getItem("shiban.aiUrl") || "https://api.x.ai/v1",
    model: localStorage.getItem("shiban.aiModel") || "grok-4.7",
    apiKey: localStorage.getItem("shiban.aiKey") || "",
  },
  todos: [],
  filter: "open",
  query: "",
  view: "list",
  draftText: "",
  draftImages: [],
  parsed: [],
  editingId: null,
  undo: null,
  clipboardOffer: null,
  capture: "",
  expandedId: null,
  busy: false,
  status: "",
  health: "",
  healthOk: null,
  update: "",
};

const app = document.querySelector("#app");

function saveSession() {
  localStorage.setItem("shiban.server", state.server.replace(/\/$/, ""));
  localStorage.setItem("shiban.token", state.token);
  localStorage.setItem("shiban.user", state.username);
  localStorage.setItem("shiban.userId", state.userId);
  localStorage.setItem("shiban.display", state.displayName);
  localStorage.setItem("shiban.aiUrl", state.ai.baseUrl || "");
  localStorage.setItem("shiban.aiModel", state.ai.model || "");
  localStorage.setItem("shiban.aiKey", state.ai.apiKey || "");
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (options.body && !(options.body instanceof FormData)) headers["Content-Type"] = "application/json";
  const response = await fetch(`${state.server.replace(/\/$/, "")}${path}`, { ...options, headers });
  const data = await response.json().catch(() => ({}));
  if (response.status === 401 && state.token && !path.startsWith("/api/auth/")) {
    state.token = "";
    state.todos = [];
    saveSession();
  }
  if (!response.ok) throw new Error(data.error || "请求失败");
  return data;
}

function today(millis) {
  if (!millis) return false;
  const date = new Date(millis);
  const now = new Date();
  return date.toDateString() === now.toDateString();
}

function formatTime(millis) {
  if (!millis) return "";
  const date = new Date(millis);
  const now = new Date();
  const time = date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hourCycle: "h23" });
  if (date.toDateString() === now.toDateString()) return `今天 ${time}`;
  const tomorrow = new Date(now);
  tomorrow.setDate(now.getDate() + 1);
  if (date.toDateString() === tomorrow.toDateString()) return `明天 ${time}`;
  return `${date.getMonth() + 1}月${date.getDate()}日 ${time}`;
}

function label(kind, value) {
  if (kind === "importance") return { high: "高", low: "低" }[value] || "中";
  return { must: "必须", optional: "可选" }[value] || "应该";
}

function visibleTodos() {
  return state.todos.filter((todo) => {
    const hay = [todo.content, todo.location, todo.note, todo.timeText].join(" ");
    if (state.query && !hay.toLowerCase().includes(state.query.toLowerCase())) return false;
    const done = Boolean(todo.completedAt);
    if (state.filter === "done") return done;
    if (done) return false;
    if (state.filter === "today") return today(todo.timeMillis);
    if (state.filter === "must") return todo.necessity === "must";
    if (state.filter === "overdue") return todo.timeMillis && todo.timeMillis < Date.now();
    return true;
  }).sort((a, b) => {
    const done = Number(Boolean(a.completedAt)) - Number(Boolean(b.completedAt));
    if (done) return done;
    const time = (a.timeMillis || Number.MAX_SAFE_INTEGER) - (b.timeMillis || Number.MAX_SAFE_INTEGER);
    if (time) return time;
    const rank = { must: 0, should: 1, optional: 2 };
    return (rank[a.necessity] ?? 1) - (rank[b.necessity] ?? 1);
  });
}

async function loadTodos() {
  const data = await api("/api/todos");
  state.todos = data.todos || [];
  render();
  offerClipboard();
}

window.addEventListener("focus", () => {
  if (state.token && state.view === "list") offerClipboard();
});

const DESKTOP_VERSION = "1.4.9";
const GITHUB_LATEST = "https://api.github.com/repos/uasier/shiban/releases/latest";

function compareVersion(left, right) {
  const parts = (value) => String(value || "").replace(/^v/, "").split("-").at(0).split(".").map((item) => Number(item) || 0);
  const a = parts(left);
  const b = parts(right);
  const count = Math.max(a.length, b.length);
  for (let index = 0; index < count; index += 1) {
    const diff = (a[index] || 0) - (b[index] || 0);
    if (diff) return diff;
  }
  return 0;
}

async function checkDesktopUpdate() {
  try {
    const response = await fetch(GITHUB_LATEST, {
      headers: { Accept: "application/vnd.github+json", "User-Agent": "shiban" },
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const json = await response.json();
    const version = String(json.tag_name || "").replace(/^v/, "");
    const mac = /Mac/i.test(navigator.platform) || /Mac OS/i.test(navigator.userAgent);
    const asset = (json.assets || []).find((item) => mac ? /macos-arm64\.dmg$/i.test(item.name) || /aarch64\.dmg$/i.test(item.name) : /\.(exe|msi)$/i.test(item.name));
    if (compareVersion(version, DESKTOP_VERSION) <= 0) {
      state.update = "";
    } else {
      state.update = asset ? `有新版本 ${version}` : `有新版本 ${version}，安装包还没准备好`;
    }
  } catch {
    state.update = "检查更新失败";
  }
  render();
}

function healthClass() {
  if (state.healthOk === true) return "ok";
  if (state.healthOk === false) return "bad";
  return "";
}

function currentServerInput() {
  return document.querySelector("[name=server]")?.value || state.server;
}

async function probeServer(url) {
  const target = String(url || currentServerInput() || "").trim().replace(/\/$/, "");
  if (!/^https?:\/\//i.test(target)) {
    state.healthOk = false;
    state.health = "服务端工作异常：地址不正确";
    render();
    return;
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(`${target}/api/health`, {
      signal: controller.signal,
      headers: { Accept: "application/json" },
    });
    const json = await response.json().catch(() => null);
    if (!response.ok || !json || json.ok !== true) {
      state.healthOk = false;
      state.health = response.ok ? "服务端工作异常" : `服务端工作异常：HTTP ${response.status}`;
    } else {
      state.healthOk = true;
      state.health = "";
    }
  } catch (error) {
    state.healthOk = false;
    state.health = error?.name === "AbortError" ? "服务端工作异常：连接超时" : "服务端工作异常：无法连接";
  } finally {
    clearTimeout(timer);
    render();
  }
}

function render() {
  const focusId = document.activeElement?.id;
  const caret = document.activeElement?.selectionStart;
  app.innerHTML = state.token ? workspace() : loginView();
  bind();
  if (focusId === "search" || focusId === "capture-text") {
    const field = document.querySelector(`#${focusId}`);
    if (field) {
      field.focus();
      if (typeof caret === "number" && field.setSelectionRange) field.setSelectionRange(caret, caret);
    }
  }
}

function loginView() {
  return `
    <main class="auth">
      <form class="panel card" id="login-form">
        <div class="brand">识办</div>
        <label class="field"><span>服务地址</span><input name="server" value="${escapeAttr(state.server)}" /></label>
        ${state.health ? `<p class="health ${healthClass()}">${escapeHtml(state.health)}</p>` : ""}
        <label class="field"><span>显示名</span><input name="displayName" value="${escapeAttr(state.displayName)}" placeholder="注册时填写" /></label>
        <label class="field"><span>用户名</span><input name="username" value="${escapeAttr(state.username)}" placeholder="2 到 24 个字符" /></label>
        <label class="field"><span>密码</span><input name="password" type="password" placeholder="至少 6 位" /></label>
        <div class="row">
          <button class="btn" type="submit">登录</button>
          <button class="ghost" type="button" id="register">注册并登录</button>
        </div>
        <p class="status">${escapeHtml(state.status)}</p>
      </form>
    </main>`;
}

function workspace() {
  const items = visibleTodos();
  const open = state.todos.filter((todo) => !todo.completedAt);
  return `
    <div class="shell">
      <aside class="side">
        <div>
          <div class="brand">识办</div>
          <p class="quiet">${escapeHtml(state.displayName || state.username)} · 未完成 ${open.length}</p>
        </div>
        <nav class="filters">
          ${chip("open", "待办", open.length)}${chip("today", "今天", open.filter((todo) => today(todo.timeMillis)).length)}${chip("must", "必须", open.filter((todo) => todo.necessity === "must").length)}${chip("overdue", "已过期", open.filter((todo) => todo.timeMillis && todo.timeMillis < Date.now()).length)}${chip("done", "已完成", state.todos.length - open.length)}
        </nav>
        <div class="side-foot">
          ${state.health && state.healthOk === false ? `<p class="health bad">${escapeHtml(state.health)}</p>` : ""}
          <button class="text-btn" id="more" type="button">更多</button>
          <div class="menu" id="menu" hidden>
            <button type="button" data-menu="new">详细新建</button>
            <button type="button" data-menu="image">导入图片</button>
            <button type="button" data-menu="account">账号与密码</button>
            <button type="button" id="probe" type="button">探活</button>
            <button type="button" id="check-update" type="button">检查更新</button>
            <button type="button" data-menu="logout">退出登录</button>
          </div>
        </div>
        <input id="file" type="file" accept="image/*" hidden />
      </aside>
      <section class="main">
        ${state.view === "review" ? reviewView() : state.view === "account" ? accountView() : `
          <header class="page-head">
            <div>
              <h1>${{ open: "待办", today: "今天", must: "必须", overdue: "已过期", done: "已完成" }[state.filter]}</h1>
              <p class="quiet">${escapeHtml(state.displayName || state.username)} · 未完成 ${open.length}</p>
            </div>
          </header>
          <form class="top" id="capture">
            <input class="search capture" id="capture-text" placeholder="写一句或粘贴，回车记下" value="${escapeAttr(state.capture)}" />
            <button class="btn" type="submit">记下</button>
            <input class="search" id="search" placeholder="搜索" value="${escapeAttr(state.query)}" />
          </form>
          ${state.clipboardOffer ? `<div class="card undo"><span>剪贴板：${escapeHtml(state.clipboardOffer.text.slice(0, 80))}</span><span class="row"><button class="btn" id="clip-parse">记下</button><button class="text-btn" id="clip-ignore">忽略</button></span></div>` : ""}
          ${state.undo ? `<div class="card undo"><span>已删除「${escapeHtml(state.undo.content)}」</span><button class="ghost" id="undo">撤销</button></div>` : ""}
          <div class="list">
            ${groupedList(items, `<div class="empty"><h2>这里还是空的</h2></div>`)}
            <p class="status">${escapeHtml(state.status)}</p>
          </div>`}
      </section>
    </div>`;
}

function groupedList(items, emptyHtml) {
  if (!items.length) return emptyHtml;
  return `<div class="group">${items.map(todoCard).join("")}</div>`;
}

function chip(id, text, count) {
  return `<button class="nav ${state.filter === id ? "active" : ""} ${id === "overdue" && count ? "warn" : ""}" data-filter="${id}"><span>${text}</span><em>${count}</em></button>`;
}

function dueHint(millis) {
  if (!millis) return "";
  const delta = millis - Date.now();
  const abs = Math.abs(delta);
  if (abs < 60000) return delta < 0 ? "刚刚到点" : "即将到点";
  if (abs >= 24 * 3600000) return "";
  const amount = abs < 3600000 ? `${Math.floor(abs / 60000)} 分钟` : `${Math.floor(abs / 3600000)} 小时`;
  return delta < 0 ? `已过期 ${amount}` : `还有 ${amount}`;
}

function todoCard(todo) {
  const late = !todo.completedAt && todo.timeMillis && todo.timeMillis < Date.now();
  const meta = [dueHint(todo.timeMillis), formatTime(todo.timeMillis) || todo.timeText, todo.location].filter(Boolean).join(" · ");
  const urls = Array.isArray(todo.imageUrls) && todo.imageUrls.length ? todo.imageUrls : todo.imageUrl ? [todo.imageUrl] : [];
  const image = urls.length
    ? `<div class="thumbs">${urls.map((url) => `<img class="thumb" alt="" data-src="${escapeAttr(state.server.replace(/\/$/, "") + url)}" />`).join("")}</div>`
    : "";
  return `
    <article class="todo ${todo.completedAt ? "done" : ""} ${late ? "late" : ""} ${todo.necessity === "must" ? "must" : ""} ${state.expandedId === todo.id ? "open" : ""}" data-open="${todo.id}">
      <span class="mark"></span>
      <input type="checkbox" data-done="${todo.id}" ${todo.completedAt ? "checked" : ""} />
      <div>
        <h3>${escapeHtml(todo.content)}</h3>
        ${meta ? `<div class="meta ${late ? "late" : ""}">${escapeHtml(meta)}</div>` : ""}
        <div class="tags">
          <span class="tag">重要性 ${label("importance", todo.importance)}</span>
          <span class="tag ${todo.necessity === "must" ? "must" : ""}">${label("necessity", todo.necessity)}</span>
        </div>
        ${state.expandedId === todo.id ? `<div class="more">${todo.note ? `<p class="quiet">${escapeHtml(todo.note)}</p>` : ""}${todo.sourceText && todo.sourceText !== todo.content ? `<p class="quiet">原文：${escapeHtml(todo.sourceText)}</p>` : ""}<button class="ghost slim" data-edit="${todo.id}" type="button">编辑</button></div>` : ""}
      </div>
      <div>
        ${image}
        <button class="x" data-delete="${todo.id}" title="删除">×</button>
      </div>
    </article>`;
}

function accountView() {
  return `
    <div class="review">
      <div class="row"><button class="ghost" id="back">返回</button></div>
      <div class="card item-edit">
        <strong>${escapeHtml(state.displayName || state.username)}</strong>
        <p class="quiet">${escapeHtml(state.username)}</p>
        <label class="field"><span>显示名</span><input id="profile-name" value="${escapeAttr(state.displayName)}" /></label>
        <label class="field"><span>当前密码</span><input id="old-password" type="password" /></label>
        <label class="field"><span>新密码</span><input id="new-password" type="password" placeholder="留空则不修改" /></label>
        <label class="field"><span>模型接口</span><input id="ai-url" value="${escapeAttr(state.ai.baseUrl)}" /></label>
        <label class="field"><span>模型名</span><input id="ai-model" value="${escapeAttr(state.ai.model)}" /></label>
        <label class="field"><span>API Key</span><input id="ai-key" type="password" value="${escapeAttr(state.ai.apiKey)}" placeholder="xai-..." /></label>
        <button class="btn" id="save-account">保存到账号</button>
        <p class="status">${escapeHtml(state.status)}</p>
      </div>
    </div>`;
}

function reviewView() {
  const cards = state.parsed.map((item, index) => `
    <div class="card item-edit" data-index="${index}">
      <strong>事项 ${index + 1}</strong>
      <label class="field"><span>内容</span><textarea data-field="content" rows="2">${escapeHtml(item.content || "")}</textarea></label>
      <label class="field"><span>时间原文</span><input data-field="timeText" value="${escapeAttr(item.timeText || "")}" /></label>
      <label class="field"><span>地点</span><input data-field="location" value="${escapeAttr(item.location || "")}" /></label>
      ${seg("importance", ["high", "medium", "low"], ["高", "中", "低"], item.importance || "medium")}
      ${seg("necessity", ["must", "should", "optional"], ["必须", "应该", "可选"], item.necessity || "should")}
      <label class="field"><span>备注</span><input data-field="note" value="${escapeAttr(item.note || "")}" /></label>
    </div>`).join("");
  return `
    <div class="review">
      <div class="row">
        <button class="ghost" id="back">返回</button>
        <button class="btn" id="save-parsed">${state.editingId ? "保存修改" : "保存"}</button>
      </div>
      <p class="status">${escapeHtml(state.status || "请核对后再保存。模型可能看错。")}</p>
      ${cards || `<div class="card">没有识别出待办。</div>`}
    </div>`;
}

function seg(field, values, labels, current) {
  return `<div class="seg">${values.map((value, index) =>
    `<button type="button" class="${current === value ? "on" : ""}" data-seg="${field}" data-value="${value}">${labels[index]}</button>`
  ).join("")}</div>`;
}

function bind() {
  document.querySelectorAll("[data-src]").forEach(async (image) => {
    try {
      const response = await fetch(image.dataset.src, { headers: { Authorization: `Bearer ${state.token}` } });
      if (!response.ok) return;
      image.src = URL.createObjectURL(await response.blob());
    } catch {}
  });
  document.querySelectorAll("#probe").forEach((button) => {
    button.addEventListener("click", () => probeServer(currentServerInput()));
  });
  document.querySelectorAll("#check-update").forEach((button) => {
    button.addEventListener("click", () => checkDesktopUpdate());
  });
  document.querySelector("#login-form")?.addEventListener("submit", (event) => auth(event, false));
  document.querySelector("#register")?.addEventListener("click", (event) => auth(event, true));
  document.querySelector("#logout")?.addEventListener("click", () => {
    state.token = "";
    state.userId = "";
    state.todos = [];
    state.ai.apiKey = "";
    saveSession();
    state.view = "list";
    render();
  });
  document.querySelector("#more")?.addEventListener("click", (event) => {
    event.stopPropagation();
    document.querySelector("#menu")?.toggleAttribute("hidden");
  });
  document.querySelector("#menu")?.addEventListener("click", (event) => event.stopPropagation());
  document.addEventListener("click", () => document.querySelector("#menu")?.setAttribute("hidden", ""), { once: true });
  document.querySelector("#save-account")?.addEventListener("click", saveAccount);
  document.querySelectorAll("[data-filter]").forEach((button) => {
    button.addEventListener("click", () => {
      state.filter = button.dataset.filter;
      state.view = "list";
      render();
    });
  });
  document.querySelector("#search")?.addEventListener("input", (event) => {
    state.query = event.target.value;
    const list = document.querySelector(".list");
    if (!list) return;
    const items = visibleTodos();
    list.innerHTML = `${groupedList(items, `<div class="empty"><h2>没有匹配的待办</h2></div>`)}<p class="status">${escapeHtml(state.status)}</p>`;
    bindList();
  });
  document.querySelector("#capture-text")?.addEventListener("input", (event) => {
    state.capture = event.target.value;
  });
  document.querySelector("#capture")?.addEventListener("submit", (event) => {
    event.preventDefault();
    parseText(document.querySelector("#capture-text").value);
  });
  document.querySelector("#undo")?.addEventListener("click", undoDelete);
  document.querySelector("#clip-parse")?.addEventListener("click", () => useClipboard("parse"));
  document.querySelector("#clip-ignore")?.addEventListener("click", () => useClipboard("ignore"));
  bindList();
  document.querySelector("[data-menu=\"new\"]")?.addEventListener("click", () => {
    state.editingId = null;
    state.parsed = [{ content: "", importance: "medium", necessity: "should" }];
    state.view = "review";
    state.status = "";
    render();
  });
  document.querySelector("[data-menu=\"image\"]")?.addEventListener("click", () => document.querySelector("#file")?.click());
  document.querySelector("[data-menu=\"account\"]")?.addEventListener("click", () => {
    state.view = "account";
    state.status = "";
    render();
  });
  document.querySelector("[data-menu=\"logout\"]")?.addEventListener("click", () => {
    state.token = "";
    state.userId = "";
    state.todos = [];
    state.ai.apiKey = "";
    saveSession();
    state.view = "list";
    render();
  });
  document.querySelector("#file")?.addEventListener("change", (event) => parseFiles(event.target.files));
  document.querySelector(".main")?.addEventListener("dragover", (event) => event.preventDefault());
  document.querySelector(".main")?.addEventListener("drop", (event) => {
    event.preventDefault();
    parseFiles(event.dataTransfer.files);
  });
  document.querySelector("#back")?.addEventListener("click", () => {
    state.view = "list";
    state.editingId = null;
    render();
  });
  document.querySelector("#save-parsed")?.addEventListener("click", saveParsed);
  document.querySelectorAll("[data-field]").forEach((input) => {
    input.addEventListener("input", () => {
      const card = input.closest("[data-index]");
      state.parsed[Number(card.dataset.index)][input.dataset.field] = input.value;
    });
  });
  document.querySelectorAll("[data-seg]").forEach((button) => {
    button.addEventListener("click", () => {
      const card = button.closest("[data-index]");
      state.parsed[Number(card.dataset.index)][button.dataset.seg] = button.dataset.value;
      render();
    });
  });
}

async function auth(event, register) {
  event.preventDefault();
  const form = document.querySelector("#login-form");
  state.server = form.server.value.trim();
  state.username = form.username.value.trim();
  state.displayName = form.displayName.value.trim();
  saveSession();
  state.status = register ? "正在注册…" : "正在登录…";
  render();
  try {
    const data = await api(register ? "/api/auth/register" : "/api/auth/login", {
      method: "POST",
      body: JSON.stringify({
        username: state.username,
        password: form.password.value,
        displayName: state.displayName,
      }),
    });
    const previous = state.userId;
    state.token = data.token;
    state.username = data.user.username;
    state.userId = data.user.id;
    state.displayName = data.user.displayName;
    if (data.user.ai) state.ai = data.user.ai;
    if (previous && previous !== state.userId) state.todos = [];
    saveSession();
    await loadTodos();
  } catch (error) {
    state.status = error.message;
    render();
  }
}

function bindList() {
  document.querySelectorAll("[data-done]").forEach((box) => {
    box.addEventListener("click", (event) => event.stopPropagation());
    box.addEventListener("change", () => toggle(box.dataset.done, box.checked));
  });
  document.querySelectorAll("[data-delete]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      remove(button.dataset.delete);
    });
  });
  document.querySelectorAll("[data-edit]").forEach((button) => {
    button.addEventListener("click", (event) => {
      event.stopPropagation();
      openTodo(button.dataset.edit);
    });
  });
  document.querySelectorAll("[data-open]").forEach((card) => {
    card.addEventListener("click", () => {
      state.expandedId = state.expandedId === card.dataset.open ? null : card.dataset.open;
      refreshList();
    });
  });
}

function refreshList() {
  const list = document.querySelector(".list");
  if (!list || state.view !== "list") {
    render();
    return;
  }
  const items = visibleTodos();
  list.innerHTML = `${groupedList(items, `<div class="empty"><h2>没有匹配的待办</h2><p class="quiet">在上面写一句，回车就会记下。</p></div>`)}<p class="status">${escapeHtml(state.status)}</p>`;
  bindList();
}

function onKey(event) {
  const typing = document.activeElement && ["INPUT", "TEXTAREA"].includes(document.activeElement.tagName);
  if (event.key === "Escape") {
    if (state.view !== "list") {
      state.view = "list";
      state.editingId = null;
      render();
      return;
    }
    if (state.expandedId) {
      state.expandedId = null;
      refreshList();
    }
    return;
  }
  if (typing || event.metaKey || event.ctrlKey || event.altKey) return;
  if (event.key === "n" || event.key === "N") {
    event.preventDefault();
    document.querySelector("#capture-text")?.focus();
  } else if (event.key === "/") {
    event.preventDefault();
    document.querySelector("#search")?.focus();
  }
}

document.addEventListener("keydown", onKey);

function ensureAi() {
  if (state.ai?.apiKey) return true;
  state.view = "account";
  state.status = "先保存这个账号的模型密钥，再识别。";
  render();
  return false;
}

function worthClipboard(text) {
  const value = text.trim();
  if (value.length >= 4) return true;
  return value.length >= 2 && /[^\u0000-\u007f]/.test(value);
}

async function offerClipboard() {
  if (!state.token || state.view !== "list" || state.clipboardOffer) return;
  let text = "";
  try {
    if (window.__TAURI__?.core?.invoke) text = await window.__TAURI__.core.invoke("read_clipboard");
    else if (navigator.clipboard?.readText) text = await navigator.clipboard.readText();
  } catch {
    return;
  }
  text = String(text || "").trim();
  if (!worthClipboard(text) || text.length > 4000) return;
  const sig = `${text.length}:${text.slice(0, 120)}`;
  if (localStorage.getItem("shiban.clip") === sig) return;
  if (state.todos.some((todo) => todo.content === text || todo.sourceText === text)) {
    localStorage.setItem("shiban.clip", sig);
    return;
  }
  state.clipboardOffer = { text, sig };
  render();
}

function useClipboard(action) {
  const offer = state.clipboardOffer;
  if (!offer) return;
  localStorage.setItem("shiban.clip", offer.sig);
  state.clipboardOffer = null;
  if (action === "parse") parseText(offer.text);
  else if (action === "save") saveText(offer.text);
  else render();
}

async function ingestQuiet(payload) {
  if (state.busy) return;
  state.busy = true;
  try {
    const data = await api("/api/ingest", { method: "POST", body: JSON.stringify(payload) });
    state.view = "list";
    state.editingId = null;
    state.parsed = [];
    state.draftImages = [];
    state.status = `已记入 ${data.count || 0} 条`;
    await loadTodos();
  } catch (error) {
    state.status = error.message;
    render();
  } finally {
    state.busy = false;
  }
}

async function parseText(text) {
  const value = text.trim();
  if (!value) {
    document.querySelector("#capture-text")?.focus();
    return;
  }
  state.capture = "";
  await ingestQuiet({ text: value });
}

async function saveText(text) {
  const value = text.trim();
  if (!value) return;
  state.status = "正在保存…";
  try {
    await api("/api/todos", {
      method: "POST",
      body: JSON.stringify({ content: value, importance: "medium", necessity: "should" }),
    });
    document.querySelector("#capture-text").value = "";
    state.status = "已保存";
    await loadTodos();
  } catch (error) {
    state.status = error.message;
    render();
  }
}

function openTodo(id) {
  const todo = state.todos.find((item) => item.id === id);
  if (!todo) return;
  state.editingId = id;
  state.parsed = [{
    content: todo.content,
    timeText: todo.timeText || "",
    time: todo.timeMillis ? new Date(todo.timeMillis).toISOString().slice(0, 19) : "",
    location: todo.location || "",
    importance: todo.importance,
    necessity: todo.necessity,
    note: todo.note || "",
  }];
  state.view = "review";
  state.status = "";
  render();
}

async function undoDelete() {
  const todo = state.undo;
  if (!todo) return;
  state.undo = null;
  await api("/api/todos", {
    method: "POST",
    body: JSON.stringify({
      content: todo.content,
      timeMillis: todo.timeMillis,
      timeText: todo.timeText,
      location: todo.location,
      importance: todo.importance,
      necessity: todo.necessity,
      note: todo.note,
      completedAt: todo.completedAt,
    }),
  });
  state.status = "已恢复";
  await loadTodos();
}

async function toggle(id, done) {
  const todo = state.todos.find((item) => item.id === id);
  if (!todo || state.busy) return;
  todo.completedAt = done ? Date.now() : null;
  render();
  try {
    await api(`/api/todos/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ completedAt: todo.completedAt }),
    });
  } catch (error) {
    state.status = error.message;
    await loadTodos();
  }
}

async function remove(id) {
  const todo = state.todos.find((item) => item.id === id);
  if (!todo || state.busy) return;
  state.busy = true;
  try {
    await api(`/api/todos/${id}`, { method: "DELETE" });
    state.undo = todo;
    state.status = "已删除，可撤销";
    await loadTodos();
  } catch (error) {
    state.status = error.message;
    render();
  } finally {
    state.busy = false;
  }
}

async function parseFiles(files) {
  const images = [];
  for (const file of files || []) {
    if (!file.type.startsWith("image/")) continue;
    images.push(await fileToBase64(file));
  }
  if (!images.length) return;
  await ingestQuiet({ images });
}

async function saveAccount() {
  const displayName = document.querySelector("#profile-name").value.trim();
  const oldPassword = document.querySelector("#old-password").value;
  const newPassword = document.querySelector("#new-password").value;
  try {
    const profile = await api("/api/me", {
      method: "PATCH",
      body: JSON.stringify({
        displayName,
        ai: {
          baseUrl: document.querySelector("#ai-url").value.trim(),
          model: document.querySelector("#ai-model").value.trim(),
          apiKey: document.querySelector("#ai-key").value.trim(),
        },
      }),
    });
    state.displayName = profile.displayName;
    if (profile.ai) state.ai = profile.ai;
    if (newPassword) {
      await api("/api/auth/password", {
        method: "POST",
        body: JSON.stringify({ oldPassword, newPassword }),
      });
    }
    saveSession();
    state.status = "账号已更新";
  } catch (error) {
    state.status = error.message;
  }
  render();
}

function todoBody(item, images) {
  const list = (images || []).filter(Boolean).slice(0, 4);
  return {
    content: item.content.trim(),
    timeMillis: item.time ? Date.parse(item.time) || null : null,
    timeText: item.timeText || item.time || null,
    location: item.location || null,
    importance: item.importance || "medium",
    necessity: item.necessity || "should",
    note: item.note || null,
    imageBase64: list[0] || null,
    imagesBase64: list,
  };
}

function imagesFor(images, count, index) {
  if (!images.length) return [];
  if (count <= 1) return images.slice(0, 4);
  if (index === 0 && images.length > count) return [images[0], ...images.slice(count)].slice(0, 4);
  return images[index] ? [images[index]] : [];
}

async function saveParsed() {
  const items = state.parsed.filter((item) => item.content && item.content.trim());
  if (!items.length) {
    state.status = "先写上要做的事";
    render();
    return;
  }
  if (state.busy) return;
  state.busy = true;
  try {
    if (state.editingId) {
      await api(`/api/todos/${state.editingId}`, { method: "PATCH", body: JSON.stringify(todoBody(items[0])) });
      state.status = "已保存修改";
    } else {
      for (const [index, item] of items.entries()) {
        await api("/api/todos", { method: "POST", body: JSON.stringify(todoBody(item, imagesFor(state.draftImages, items.length, index))) });
      }
      state.status = `已保存 ${items.length} 条`;
    }
    state.view = "list";
    state.editingId = null;
    state.draftImages = [];
    await loadTodos();
  } catch (error) {
    state.status = error.message;
    render();
  } finally {
    state.busy = false;
  }
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.onerror = reject;
    reader.readAsDataURL(file);
  });
}

function escapeHtml(value) {
  return String(value || "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[char]));
}

function escapeAttr(value) {
  return escapeHtml(value);
}

localStorage.setItem("shiban.server", state.server);
render();
if (state.token) loadTodos().catch((error) => {
  state.status = error.message;
  if (String(error.message).includes("登录")) state.token = "";
  render();
});
