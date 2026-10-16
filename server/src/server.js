import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
loadEnv(path.join(root, ".env"));

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || "0.0.0.0";
const JWT_SECRET = process.env.JWT_SECRET || "shiban-dev-secret-change-me";
const GROK_API_KEY = (process.env.GROK_API_KEY || "").trim();
const GROK_BASE_URL = (process.env.GROK_BASE_URL || "https://api.x.ai/v1").replace(/\/$/, "");
const GROK_MODEL = process.env.GROK_MODEL || "grok-4.7";
const DATA_DIR = path.resolve(root, process.env.DATA_DIR || "./data");
const IMAGE_DIR = path.join(DATA_DIR, "images");
const MAX_BODY = 24 * 1024 * 1024;

fs.mkdirSync(IMAGE_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, "shiban.db"));
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS todos (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    content TEXT NOT NULL,
    time_millis INTEGER,
    time_text TEXT,
    location TEXT,
    importance TEXT NOT NULL,
    necessity TEXT NOT NULL,
    note TEXT,
    source_text TEXT,
    image_name TEXT,
    created_at INTEGER NOT NULL,
    completed_at INTEGER,
    updated_at INTEGER NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_todos_user ON todos(user_id, updated_at);
`);
migrateUsers();
migrateTodos();

const server = http.createServer(async (req, res) => {
  try {
    setCors(res);
    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }
    const url = new URL(req.url || "/", `http://${req.headers.host || "localhost"}`);
    const user = readUser(req);
    await route(req, res, url, user);
  } catch (error) {
    send(res, error.status || 500, { error: error.message || "服务器错误" });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`识办服务已启动 http://${HOST}:${PORT}`);
  console.log(GROK_API_KEY ? `识别模型 ${GROK_MODEL}` : "尚未配置 GROK_API_KEY，识别接口会拒绝请求");
});

async function route(req, res, url, user) {
  const { pathname } = url;
  if (req.method === "GET" && pathname === "/api/health") {
    return send(res, 200, { ok: true, modelConfigured: Boolean(GROK_API_KEY), model: GROK_MODEL });
  }
  if (req.method === "POST" && pathname === "/api/auth/register") return register(req, res);
  if (req.method === "POST" && pathname === "/api/auth/login") return login(req, res);
  if (!user) return send(res, 401, { error: "请先登录" });
  if (req.method === "GET" && pathname === "/api/me") return send(res, 200, publicUser(user));
  if (req.method === "PATCH" && pathname === "/api/me") return updateProfile(req, res, user);
  if (req.method === "POST" && pathname === "/api/auth/password") return changePassword(req, res, user);
  if (req.method === "GET" && pathname === "/api/todos") return listTodos(res, user);
  if (req.method === "POST" && pathname === "/api/todos") return createTodo(req, res, user);
  if (req.method === "POST" && pathname === "/api/parse") return parseShare(req, res, user);
  if (req.method === "POST" && pathname === "/api/ingest") return ingestShare(req, res, user);
  const imageAt = pathname.match(/^\/api\/todos\/([^/]+)\/image\/(\d+)$/);
  if (imageAt && req.method === "GET") return readImage(res, user, imageAt[1], Number(imageAt[2]));
  const image = pathname.match(/^\/api\/todos\/([^/]+)\/image$/);
  if (image && req.method === "GET") return readImage(res, user, image[1], 0);
  const one = pathname.match(/^\/api\/todos\/([^/]+)$/);
  if (one && req.method === "PATCH") return patchTodo(req, res, user, one[1]);
  if (one && req.method === "DELETE") return deleteTodo(res, user, one[1]);
  return send(res, 404, { error: "接口不存在" });
}

function register(req, res) {
  return readJson(req).then((body) => {
    guardAttempts(req, "register");
    const username = validateUsername(body.username);
    const password = validatePassword(body.password);
    const key = usernameKey(username);
    if (db.prepare("SELECT id FROM users WHERE username_key = ?").get(key)) {
      throw httpError(409, "这个用户名已经有人注册了");
    }
    const id = crypto.randomUUID();
    const displayName = validateDisplayName(body.displayName, username);
    db.prepare(
      "INSERT INTO users (id, username, username_key, display_name, password_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)"
    ).run(id, username, key, displayName, hashPassword(password), Date.now());
    const row = db.prepare("SELECT * FROM users WHERE id = ?").get(id);
    send(res, 201, { token: signToken(row), user: publicUser(row) });
  });
}

function login(req, res) {
  return readJson(req).then((body) => {
    const key = usernameKey(body.username || "");
    const attemptKey = `${clientIp(req)}:${key}`;
    if (isLocked(attemptKey)) throw httpError(429, "尝试太多次，请 10 分钟后再试");
    const row = db.prepare("SELECT * FROM users WHERE username_key = ?").get(key);
    if (!row || !verifyPassword(String(body.password || ""), row.password_hash)) {
      noteFailure(attemptKey);
      throw httpError(401, "用户名或密码不对");
    }
    clearFailures(attemptKey);
    send(res, 200, { token: signToken(row), user: publicUser(row) });
  });
}

function updateProfile(req, res, user) {
  return readJson(req).then((body) => {
    const current = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id);
    const displayName = body.displayName == null
      ? current.display_name || current.username
      : validateDisplayName(body.displayName, current.username);
    const ai = normalizeAiUpdate(body.ai, current);
    db.prepare(
      "UPDATE users SET display_name = ?, ai_base_url = ?, ai_model = ?, ai_api_key = ? WHERE id = ?"
    ).run(displayName, ai.baseUrl, ai.model, ai.apiKey, user.id);
    send(res, 200, publicUser(db.prepare("SELECT * FROM users WHERE id = ?").get(user.id)));
  });
}

function changePassword(req, res, user) {
  return readJson(req).then((body) => {
    const row = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id);
    if (!verifyPassword(String(body.oldPassword || ""), row.password_hash)) {
      throw httpError(401, "当前密码不对");
    }
    const next = validatePassword(body.newPassword);
    db.prepare("UPDATE users SET password_hash = ? WHERE id = ?").run(hashPassword(next), user.id);
    send(res, 200, { ok: true });
  });
}

function listTodos(res, user) {
  const rows = db.prepare("SELECT * FROM todos WHERE user_id = ? ORDER BY updated_at DESC").all(user.id);
  send(res, 200, { todos: rows.map((row) => toTodo(row)) });
}

function createTodo(req, res, user) {
  return readJson(req).then((body) => {
    const saved = insertTodo(user, body);
    if (!saved) throw httpError(400, "内容不能为空");
    send(res, 201, saved);
  });
}

function insertTodo(user, body) {
  const content = String(body.content || "").trim();
  if (!content) return null;
  const id = crypto.randomUUID();
  const now = Date.now();
  const images = saveImageList(user.id, id, imagePayload(body));
  db.prepare(`
    INSERT INTO todos (
      id, user_id, content, time_millis, time_text, location, importance, necessity,
      note, source_text, image_name, extra_images, created_at, completed_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    id,
    user.id,
    content,
    numberOrNull(body.timeMillis),
    textOrNull(body.timeText),
    textOrNull(body.location),
    normalizeImportance(body.importance),
    normalizeNecessity(body.necessity),
    textOrNull(body.note),
    textOrNull(body.sourceText),
    images.imageName,
    images.extra,
    numberOrNull(body.createdAt) || now,
    numberOrNull(body.completedAt),
    now
  );
  return toTodo(getOwned(user.id, id));
}

function patchTodo(req, res, user, id) {
  return readJson(req).then((body) => {
    const current = getOwned(user.id, id);
    const content = body.content == null ? current.content : String(body.content).trim();
    if (!content) throw httpError(400, "内容不能为空");
    let imageName = current.image_name;
    let extraImages = current.extra_images;
    const incoming = imagePayload(body);
    if (incoming.length) {
      const saved = saveImageList(user.id, id, incoming);
      const keep = new Set(imageNames({ image_name: saved.imageName, extra_images: saved.extra }));
      imageNames(current).forEach((name) => {
        if (!keep.has(name)) fs.rmSync(path.join(IMAGE_DIR, name), { force: true });
      });
      imageName = saved.imageName;
      extraImages = saved.extra;
    }
    db.prepare(`
      UPDATE todos SET
        content = ?, time_millis = ?, time_text = ?, location = ?, importance = ?, necessity = ?,
        note = ?, source_text = ?, image_name = ?, extra_images = ?, completed_at = ?, updated_at = ?
      WHERE id = ? AND user_id = ?
    `).run(
      content,
      body.timeMillis === undefined ? current.time_millis : numberOrNull(body.timeMillis),
      body.timeText === undefined ? current.time_text : textOrNull(body.timeText),
      body.location === undefined ? current.location : textOrNull(body.location),
      body.importance === undefined ? current.importance : normalizeImportance(body.importance),
      body.necessity === undefined ? current.necessity : normalizeNecessity(body.necessity),
      body.note === undefined ? current.note : textOrNull(body.note),
      body.sourceText === undefined ? current.source_text : textOrNull(body.sourceText),
      imageName,
      extraImages,
      body.completedAt === undefined ? current.completed_at : numberOrNull(body.completedAt),
      Date.now(),
      id,
      user.id
    );
    send(res, 200, toTodo(getOwned(user.id, id)));
  });
}

function deleteTodo(res, user, id) {
  const current = getOwned(user.id, id);
  db.prepare("DELETE FROM todos WHERE id = ? AND user_id = ?").run(id, user.id);
  imageNames(current).forEach((name) => fs.rmSync(path.join(IMAGE_DIR, name), { force: true }));
  send(res, 200, { ok: true });
}

function readImage(res, user, id, index = 0) {
  const current = getOwned(user.id, id);
  const name = imageNames(current)[index];
  if (!name) throw httpError(404, "没有图片");
  const file = path.join(IMAGE_DIR, name);
  if (!file.startsWith(IMAGE_DIR) || !fs.existsSync(file)) throw httpError(404, "图片不存在");
  res.writeHead(200, { "Content-Type": "image/jpeg", "Cache-Control": "private, max-age=3600" });
  fs.createReadStream(file).pipe(res);
}

async function ingestShare(req, res, user) {
  const row = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id);
  const ai = resolveAi(row);
  const body = await readJson(req);
  const text = textOrNull(body.text);
  const images = Array.isArray(body.images) ? body.images.filter((item) => typeof item === "string" && item.length > 0).slice(0, 4) : [];
  if (!text && images.length === 0) throw httpError(400, "没有文字或图片");
  let items = [];
  if (ai.apiKey) {
    try {
      items = extractItems(await callGrok(text, images, ai, 55000));
    } catch {
      items = [];
    }
  }
  if (!items.length) items = localItems(text, images.length > 0);
  if (!items.length) throw httpError(400, "没有可记的内容");
  const saved = [];
  let skipped = 0;
  for (const item of assignImages(items, images)) {
    if (item.content && !genericImageTitle(item.content) && recentOpen(user.id, item.content)) {
      skipped++;
      continue;
    }
    const rowSaved = insertTodo(user, {
      content: item.content,
      timeMillis: item.timeMillis || (item.time ? Date.parse(item.time) : null),
      timeText: item.timeText || item.time || null,
      location: item.location,
      importance: item.importance,
      necessity: item.necessity,
      note: item.note,
      sourceText: text,
      imagesBase64: item.imagesBase64,
    });
    if (rowSaved) saved.push(rowSaved);
  }
  send(res, 201, { count: saved.length, duplicate: saved.length === 0 && skipped > 0, todos: saved });
}

function genericImageTitle(content) {
  return content === "查看导入的图片" || content === "查看图片里的事项";
}

function recentOpen(userId, content) {
  const since = Date.now() - 10 * 60 * 1000;
  return Boolean(db.prepare(`
    SELECT id FROM todos
    WHERE user_id = ? AND content = ? AND completed_at IS NULL AND created_at >= ?
    LIMIT 1
  `).get(userId, content, since));
}

function localItems(text, hasImage) {
  const lines = splitTasks(text);
  if (!lines.length) {
    return hasImage ? [{ content: "查看导入的图片", importance: "medium", necessity: "should" }] : [];
  }
  return lines.map((line) => {
    const time = guessTime(line);
    const urgent = /紧急|马上|立刻|尽快|asap/i.test(line);
    const must = /必须|务必|一定要|一定得/.test(line);
    return {
      content: line.slice(0, 80),
      timeMillis: time?.millis || null,
      timeText: time?.text || null,
      importance: urgent ? "high" : "medium",
      necessity: must ? "must" : "should",
    };
  });
}

function splitTasks(text) {
  const raw = String(text || "").replace(/\r/g, "").trim();
  if (!raw) return [];
  let parts = raw.split(/\n+/).map((line) => line.trim()).filter(Boolean);
  if (parts.length === 1) {
    const sentences = parts[0].split(/[。；;]\s*/).map((line) => line.trim()).filter((line) => line.length >= 2);
    if (sentences.length > 1) parts = sentences;
  }
  const seen = new Set();
  const tasks = [];
  for (const part of parts) {
    const line = part.replace(/^\s*(?:[-*•·]|(?:\d+|[一二三四五六七八九十]+)[.、.)])\s*/, "").trim();
    if (line.length < 2 || seen.has(line)) continue;
    seen.add(line);
    tasks.push(line);
    if (tasks.length >= 12) break;
  }
  return tasks;
}

function guessTime(line) {
  const now = new Date();
  const text = String(line || "");
  if (/半小时后|半个小时后/.test(text)) return { millis: Date.now() + 30 * 60 * 1000, text: "半小时后" };
  const after = text.match(/(\d+)\s*(?:个)?\s*(小时|分钟)后/);
  if (after) {
    const amount = Number(after[1]);
    const unit = after[2] === "小时" ? 60 * 60 * 1000 : 60 * 1000;
    return { millis: Date.now() + amount * unit, text: after[0] };
  }
  const clock = text.match(/(?<!第)(\d{1,2})\s*[:：点时]\s*(\d{1,2}|半)?/);
  let hour = clock ? Number(clock[1]) : null;
  let minute = clock && clock[2] === "半" ? 30 : clock && clock[2] ? Number(clock[2]) : 0;
  if (hour != null && hour < 12 && /下午|晚上|傍晚|今晚|明晚/.test(text)) hour += 12;
  if (hour == null) {
    if (/今晚|明晚|晚上/.test(text)) hour = 20;
    else if (/今早|明早|上午|早上/.test(text)) hour = 9;
    else if (/中午/.test(text)) hour = 12;
    else if (/下午/.test(text)) hour = 15;
  }
  const absolute = text.match(/(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})/);
  if (absolute) {
    return stamp(Number(absolute[1]), Number(absolute[2]), Number(absolute[3]), hour ?? 9, minute, absolute[0]);
  }
  const monthDay = text.match(/(\d{1,2})月(\d{1,2})[日号]/);
  if (monthDay) {
    return stamp(now.getFullYear(), Number(monthDay[1]), Number(monthDay[2]), hour ?? 9, minute, monthDay[0]);
  }
  const week = text.match(/(下下|下|本|这)?(?:周|星期|礼拜)([一二三四五六日天])/);
  if (week) {
    const names = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 0, 天: 0 };
    const target = names[week[2]];
    const today = now.getDay();
    const fromMonday = (day) => (day === 0 ? 6 : day - 1);
    const prefix = week[1] || "";
    const weekOffset = prefix === "下下" ? 2 : prefix === "下" ? 1 : prefix === "本" || prefix === "这" ? 0 : fromMonday(target) < fromMonday(today) ? 1 : 0;
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - fromMonday(today) + fromMonday(target) + weekOffset * 7, hour ?? 9, hour == null ? 0 : minute, 0, 0);
    return { millis: date.getTime(), text: week[0] };
  }
  const offset = text.includes("大后天") ? 3 : text.includes("后天") ? 2 : /明天|明早|明晚/.test(text) ? 1 : /今天|今晚|今早/.test(text) ? 0 : null;
  if (offset == null && hour == null) return null;
  if (offset == null) {
    const todayAt = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, minute, 0, 0);
    const millis = todayAt.getTime() > Date.now() ? todayAt.getTime() : todayAt.getTime() + 24 * 60 * 60 * 1000;
    return { millis, text: clock ? clock[0] : null };
  }
  const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() + offset, hour ?? 9, hour == null ? 0 : minute, 0, 0);
  return { millis: date.getTime(), text: clock ? clock[0] : text.match(/今天|明天|后天|大后天|今晚|明早|明晚/)?.[0] || null };
}

function stamp(year, month, day, hour, minute, text) {
  const date = new Date(year, month - 1, day, hour, minute, 0, 0);
  if (Number.isNaN(date.getTime())) return null;
  return { millis: date.getTime(), text };
}

async function parseShare(req, res, user) {
  const row = db.prepare("SELECT * FROM users WHERE id = ?").get(user.id);
  const ai = resolveAi(row);
  if (!ai.apiKey) throw httpError(503, "这个账号还没配置模型密钥。请先在账号设置里保存。");
  const body = await readJson(req);
  const text = textOrNull(body.text);
  const images = Array.isArray(body.images) ? body.images.filter((item) => typeof item === "string" && item.length > 0).slice(0, 4) : [];
  if (!text && images.length === 0) throw httpError(400, "没有文字或图片");
  const raw = await callGrok(text, images, ai);
  send(res, 200, { raw, items: extractItems(raw) });
}

async function callGrok(text, images, ai, timeoutMs = 180000) {
  const now = new Date();
  const userContent = images.map((image) => ({
    type: "input_image",
    image_url: image.startsWith("data:") ? image : `data:image/jpeg;base64,${image}`,
    detail: "high",
  }));
  userContent.push({ type: "input_text", text: userPrompt(text, images.length) });
  const useResponses = ai.baseUrl.includes("api.x.ai");
  const endpoint = useResponses ? `${ai.baseUrl}/responses` : `${ai.baseUrl}/chat/completions`;
  const payload = useResponses
    ? {
        model: ai.model,
        store: false,
        reasoning: { effort: "low" },
        input: [
          { role: "system", content: systemPrompt(now) },
          { role: "user", content: userContent },
        ],
      }
    : {
        model: ai.model,
        temperature: 0.2,
        messages: [
          { role: "system", content: systemPrompt(now) },
          {
            role: "user",
            content: images.length
              ? [
                  ...images.map((image) => ({
                    type: "image_url",
                    image_url: { url: image.startsWith("data:") ? image : `data:image/jpeg;base64,${image}` },
                  })),
                  { type: "text", text: userPrompt(text, images.length) },
                ]
              : userPrompt(text, 0),
          },
        ],
      };
  let response = await postJson(endpoint, payload, ai.apiKey, timeoutMs);
  if (response.status === 400 && payload.reasoning) {
    delete payload.reasoning;
    response = await postJson(endpoint, payload, ai.apiKey, timeoutMs);
  }
  if (response.status < 200 || response.status >= 300) {
    throw httpError(502, `模型接口错误：${friendlyModelMessage(errorSnippet(response.body))}`);
  }
  const content = extractAssistantText(response.json);
  if (!content) throw httpError(502, "模型没有返回正文");
  return content;
}

function systemPrompt(now) {
  const iso = now.toISOString().slice(0, 19);
  const human = new Intl.DateTimeFormat("zh-CN", {
    timeZone: process.env.TZ || undefined,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    weekday: "short",
    hourCycle: "h23",
  }).format(now);
  return `你是「识办」的待办抽取代理。用户会分享文字或图片，例如聊天截图、海报、车票、菜单、备忘。
你的工作是抽出可执行的待办，不是总结闲聊，也不是描述图片。
当前时间：${human}（${iso}）。
字段：
- content：一句可执行事项。去掉寒暄。
- time：绝对本地时间，格式 yyyy-MM-dd'T'HH:mm:ss。没有明确时间就返回 null。禁止猜测。
- timeText：原文里的时间说法。没有则为 null。
- location：具体地点。没有则为 null。不要把微信、相册、App 名称当地点。
- importance：只能是 high、medium、low。
- necessity：must=不做会出事或对方明确要求；should=应该做但可以推迟；optional=可做可不做。
- note：不超过 40 字，说明判断依据。
规则：多件独立的事拆成多条。没有行动项时 items 为空数组。信息不足就留空。只返回 JSON，不要 Markdown。
JSON：{"items":[{"content":"","time":null,"timeText":null,"location":null,"importance":"medium","necessity":"should","note":""}]}`;
}

function userPrompt(text, imageCount) {
  const omitted = text ? `\n\n分享的文字：\n${String(text).slice(0, 8000)}` : imageCount ? "\n\n没有附带文字，请只根据图片判断。" : "";
  return `请从下面的分享中抽取待办。${imageCount ? `附带 ${imageCount} 张图片，请阅读图片里的文字和场景。` : ""}${omitted}`;
}

function extractItems(raw) {
  let text = String(raw || "").trim();
  if (text.startsWith("```")) text = text.replace(/^```(?:json)?/i, "").replace(/```$/, "").trim();
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return [];
  const json = JSON.parse(text.slice(start, end + 1));
  const list = Array.isArray(json.items) ? json.items : Array.isArray(json.todos) ? json.todos : [];
  return list
    .map((item) => ({
      content: textOrNull(item.content || item.title || item["内容"]),
      time: textOrNull(item.time || item["时间"]),
      timeText: textOrNull(item.timeText || item.time_text || item["时间原文"]),
      location: textOrNull(item.location || item["地点"]),
      importance: normalizeImportance(item.importance || item["重要性"]),
      necessity: normalizeNecessity(item.necessity || item["必要性"]),
      note: textOrNull(item.note || item["说明"] || item["备注"]),
    }))
    .filter((item) => item.content);
}

function extractAssistantText(json) {
  if (!json || typeof json !== "object") return "";
  if (typeof json.output_text === "string" && json.output_text.trim()) return json.output_text;
  const chunks = [];
  for (const item of json.output || []) collect(item.content, chunks);
  for (const choice of json.choices || []) collect(choice.message?.content, chunks);
  return chunks.join("\n").trim();
}

function collect(content, chunks) {
  if (typeof content === "string" && content.trim()) chunks.push(content);
  if (Array.isArray(content)) {
    for (const part of content) {
      if (part && typeof part.text === "string" && part.text.trim()) chunks.push(part.text);
    }
  }
}

function friendlyModelMessage(message) {
  const text = String(message || "");
  if (/insufficient account balance|insufficient[_ ]quota|quota exceeded|billing|余额不足|额度不足|账户余额/i.test(text)) {
    return "账户余额不足";
  }
  return text;
}

async function postJson(url, payload, apiKey, timeoutMs = 180000) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      Accept: "application/json",
    },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const body = await response.text();
  let json = null;
  try {
    json = JSON.parse(body);
  } catch {
    json = null;
  }
  return { status: response.status, body, json };
}

function errorSnippet(body) {
  try {
    const json = JSON.parse(body);
    const error = json.error;
    const message = typeof error === "string" ? error : error?.message || json.message || body;
    return String(message).replace(/\s+/g, " ").slice(0, 360);
  } catch {
    return String(body || "").replace(/\s+/g, " ").slice(0, 360);
  }
}

function getOwned(userId, id) {
  const row = db.prepare("SELECT * FROM todos WHERE id = ? AND user_id = ?").get(id, userId);
  if (!row) throw httpError(404, "待办不存在");
  return row;
}

function toTodo(row) {
  const urls = imageNames(row).map((_, index) =>
    index === 0 ? `/api/todos/${row.id}/image` : `/api/todos/${row.id}/image/${index}`
  );
  return {
    id: row.id,
    content: row.content,
    timeMillis: row.time_millis,
    timeText: row.time_text,
    location: row.location,
    importance: row.importance,
    necessity: row.necessity,
    note: row.note,
    sourceText: row.source_text,
    imageUrl: urls[0] || null,
    imageUrls: urls,
    createdAt: row.created_at,
    completedAt: row.completed_at,
    updatedAt: row.updated_at,
  };
}

function imagePayload(body) {
  if (Array.isArray(body.imagesBase64)) {
    return body.imagesBase64.filter((item) => typeof item === "string" && item.length > 0).slice(0, 4);
  }
  if (typeof body.imageBase64 === "string" && body.imageBase64.length > 0) return [body.imageBase64];
  return [];
}

function assignImages(items, images) {
  if (items.length <= 1) return items.map((item) => ({ ...item, imagesBase64: images }));
  return items.map((item, index) => {
    let chosen = [];
    if (index === 0 && images.length > items.length) chosen = [images[0], ...images.slice(items.length)];
    else if (index < images.length) chosen = [images[index]];
    return { ...item, imagesBase64: chosen };
  });
}

function imageNames(row) {
  const extras = String(row?.extra_images || "")
    .split("\n")
    .map((name) => name.trim())
    .filter((name) => name && !name.includes("/") && !name.includes("\\") && !name.includes(".."));
  const first = row?.image_name;
  if (first && !String(first).includes("/") && !String(first).includes("..")) return [first, ...extras];
  return extras;
}

function saveImageList(userId, todoId, base64s) {
  const list = (base64s || []).filter((item) => typeof item === "string" && item.length > 0).slice(0, 4);
  if (!list.length) return { imageName: null, extra: null };
  const names = list.map((raw, index) => {
    const name = index === 0 ? `${userId}-${todoId}.jpg` : `${userId}-${todoId}-${index + 1}.jpg`;
    fs.writeFileSync(path.join(IMAGE_DIR, name), decodeImage(raw));
    return name;
  });
  return { imageName: names[0], extra: names.slice(1).join("\n") || null };
}

function decodeImage(base64) {
  const raw = String(base64).replace(/^data:image\/[a-zA-Z0-9.+-]+;base64,/, "");
  const bytes = Buffer.from(raw, "base64");
  if (bytes.length < 32 || bytes.length > 8 * 1024 * 1024) throw httpError(400, "图片无效或超过 8MB");
  return bytes;
}

function readUser(req) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!token) return null;
  const payload = verifyToken(token);
  if (!payload) return null;
  const row = db.prepare("SELECT * FROM users WHERE id = ?").get(payload.sub);
  return row || null;
}

function signToken(user) {
  const header = b64url(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const payload = b64url(JSON.stringify({ sub: user.id, username: user.username, exp: Date.now() + 30 * 24 * 3600 * 1000 }));
  const signature = b64url(crypto.createHmac("sha256", JWT_SECRET).update(`${header}.${payload}`).digest());
  return `${header}.${payload}.${signature}`;
}

function verifyToken(token) {
  const [header, payload, signature] = token.split(".");
  if (!header || !payload || !signature) return null;
  const expected = b64url(crypto.createHmac("sha256", JWT_SECRET).update(`${header}.${payload}`).digest());
  const left = Buffer.from(signature);
  const right = Buffer.from(expected);
  if (left.length !== right.length || !crypto.timingSafeEqual(left, right)) return null;
  const json = JSON.parse(Buffer.from(payload, "base64url").toString());
  if (!json.exp || json.exp < Date.now()) return null;
  return json;
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString("base64url");
  const hash = crypto.scryptSync(password, salt, 32).toString("base64url");
  return `scrypt$${salt}$${hash}`;
}

function verifyPassword(password, stored) {
  const [kind, salt, hash] = String(stored).split("$");
  if (kind !== "scrypt" || !salt || !hash) return false;
  const actual = crypto.scryptSync(password, salt, 32);
  const expected = Buffer.from(hash, "base64url");
  return actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY) {
        reject(httpError(413, "请求太大"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => {
      if (chunks.length === 0) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        reject(httpError(400, "请求不是 JSON"));
      }
    });
    req.on("error", reject);
  });
}

function setCors(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
}

function send(res, status, body) {
  const raw = JSON.stringify(body);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(raw);
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function publicUser(row) {
  const ai = resolveAi(row);
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name || row.username,
    ai: {
      baseUrl: ai.baseUrl,
      model: ai.model,
      apiKey: ai.apiKey,
      configured: Boolean(ai.apiKey),
      source: ai.source,
    },
  };
}

function resolveAi(row) {
  const ownKey = decryptSecret(row?.ai_api_key);
  if (ownKey) {
    return {
      baseUrl: cleanUrl(row.ai_base_url) || GROK_BASE_URL,
      model: textOrNull(row.ai_model) || GROK_MODEL,
      apiKey: ownKey,
      source: "account",
    };
  }
  return {
    baseUrl: GROK_BASE_URL,
    model: GROK_MODEL,
    apiKey: GROK_API_KEY,
    source: GROK_API_KEY ? "server" : "none",
  };
}

function normalizeAiUpdate(ai, current) {
  const previous = resolveAi(current);
  if (!ai || typeof ai !== "object") {
    return {
      baseUrl: current.ai_base_url || "",
      model: current.ai_model || "",
      apiKey: current.ai_api_key || null,
    };
  }
  const baseUrl = ai.baseUrl == null ? current.ai_base_url || "" : cleanUrl(ai.baseUrl);
  const model = ai.model == null ? current.ai_model || "" : String(ai.model).trim();
  let storedKey = current.ai_api_key || null;
  if (ai.apiKey != null) {
    const next = String(ai.apiKey).trim();
    storedKey = next ? encryptSecret(next) : null;
  }
  if (baseUrl && !/^https?:\/\//i.test(baseUrl)) throw httpError(400, "模型接口地址需要以 http:// 或 https:// 开头");
  if (model.length > 80) throw httpError(400, "模型名太长");
  if (previous && ai.apiKey === "") storedKey = null;
  return { baseUrl, model, apiKey: storedKey };
}

function cleanUrl(value) {
  return String(value || "").trim().replace(/\/$/, "");
}

function encryptSecret(plain) {
  const key = crypto.createHash("sha256").update(JWT_SECRET).digest();
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(String(plain), "utf8"), cipher.final()]);
  return `v1.${iv.toString("base64url")}.${cipher.getAuthTag().toString("base64url")}.${encrypted.toString("base64url")}`;
}

function decryptSecret(stored) {
  if (!stored) return "";
  const [version, ivText, tagText, dataText] = String(stored).split(".");
  if (version !== "v1" || !ivText || !tagText || !dataText) return "";
  try {
    const key = crypto.createHash("sha256").update(JWT_SECRET).digest();
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, Buffer.from(ivText, "base64url"));
    decipher.setAuthTag(Buffer.from(tagText, "base64url"));
    return Buffer.concat([
      decipher.update(Buffer.from(dataText, "base64url")),
      decipher.final(),
    ]).toString("utf8");
  } catch {
    return "";
  }
}

function validateUsername(value) {
  const username = String(value || "").trim();
  if (username.length < 2 || username.length > 24) throw httpError(400, "用户名需要 2 到 24 个字符");
  if (!/^[\p{L}\p{N}_.-]+$/u.test(username)) throw httpError(400, "用户名只能包含字母、数字、中文、下划线、点或短横线");
  return username;
}

function validateDisplayName(value, fallback) {
  const name = String(value || "").trim() || fallback;
  if (name.length < 1 || name.length > 24) throw httpError(400, "显示名需要 1 到 24 个字符");
  return name;
}

function validatePassword(value) {
  const password = String(value || "");
  if (password.length < 6 || password.length > 72) throw httpError(400, "密码需要 6 到 72 位");
  return password;
}

function usernameKey(value) {
  return String(value || "").trim().toLocaleLowerCase();
}

function migrateTodos() {
  const columns = db.prepare("PRAGMA table_info(todos)").all().map((column) => column.name);
  if (!columns.includes("extra_images")) db.exec("ALTER TABLE todos ADD COLUMN extra_images TEXT");
}

function migrateUsers() {
  const columns = db.prepare("PRAGMA table_info(users)").all().map((column) => column.name);
  if (!columns.includes("display_name")) db.exec("ALTER TABLE users ADD COLUMN display_name TEXT");
  if (!columns.includes("username_key")) db.exec("ALTER TABLE users ADD COLUMN username_key TEXT");
  if (!columns.includes("ai_base_url")) db.exec("ALTER TABLE users ADD COLUMN ai_base_url TEXT");
  if (!columns.includes("ai_model")) db.exec("ALTER TABLE users ADD COLUMN ai_model TEXT");
  if (!columns.includes("ai_api_key")) db.exec("ALTER TABLE users ADD COLUMN ai_api_key TEXT");
  db.exec("UPDATE users SET username_key = lower(username) WHERE username_key IS NULL OR username_key = ''");
  db.exec("UPDATE users SET display_name = username WHERE display_name IS NULL OR display_name = ''");
  db.exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_key ON users(username_key)");
}

const attempts = new Map();
function attemptList(key) {
  const now = Date.now();
  return (attempts.get(key) || []).filter((time) => now - time < 10 * 60 * 1000);
}
function isLocked(key) {
  return attemptList(key).length >= 8;
}
function noteFailure(key) {
  const list = attemptList(key);
  list.push(Date.now());
  attempts.set(key, list);
}
function clearFailures(key) {
  attempts.delete(key);
}
function guardAttempts(req, action) {
  const key = `${action}:${clientIp(req)}`;
  if (isLocked(key)) throw httpError(429, "注册太频繁，请稍后再试");
  noteFailure(key);
}
function clientIp(req) {
  return req.socket.remoteAddress || "unknown";
}

function textOrNull(value) {
  if (value == null) return null;
  const text = String(value).trim();
  if (!text || text === "null" || text === "无" || text === "未知") return null;
  return text;
}

function numberOrNull(value) {
  if (value == null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function normalizeImportance(value) {
  const text = String(value || "").toLowerCase();
  if (text.includes("high") || text.includes("高") || text.includes("紧急")) return "high";
  if (text.includes("low") || text.includes("低")) return "low";
  return "medium";
}

function normalizeNecessity(value) {
  const text = String(value || "").toLowerCase();
  if (text.includes("不必") || text.includes("可选") || text.includes("optional")) return "optional";
  if (text.includes("must") || text.includes("必须") || text.includes("必要")) return "must";
  return "should";
}

function b64url(value) {
  const buffer = Buffer.isBuffer(value) ? value : Buffer.from(value);
  return buffer.toString("base64url");
}

function loadEnv(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
    const index = trimmed.indexOf("=");
    const key = trimmed.slice(0, index).trim();
    const value = trimmed.slice(index + 1).trim().replace(/^["']|["']$/g, "");
    if (!process.env[key]) process.env[key] = value;
  }
}
