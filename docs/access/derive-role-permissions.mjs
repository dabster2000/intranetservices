#!/usr/bin/env node
/**
 * Phase 4 task 4.6 — derive the role_permission seed from live gate values.
 *
 * Inputs:
 *  - FE:  src/access/access-manifest.json  (Phase 3, per-handler gates + roles)
 *  - FE:  the role-gated route files (backendFetch / raw fetch call sites)
 *  - BE:  intranetservices resource classes (@Path + @RolesAllowed + verb)
 *
 * Output: a JSON report (pairs, widenings, unmapped, excluded) for owner review.
 */
import fs from 'node:fs';
import path from 'node:path';

const FE = '/Users/hansernstlassen/Development/Trustworks Intranet Parent/trustworks-intranet-v2';
const BE = '/Users/hansernstlassen/Development/Trustworks Intranet Parent/worktrees/be-phase-4-permission-catalogue';
const OUT = process.argv[2] || '/private/tmp/claude-501/-Users-hansernstlassen-Development-Trustworks-Intranet-Parent/555ea30a-f4cd-4f4b-9649-e09401edca54/scratchpad/role-permission-derivation.json';

// ---------- 1. Backend endpoint table ----------
const VERBS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];

function* walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) yield* walk(p);
    else if (e.name.endsWith('.java')) yield p;
  }
}

function stripComments(src) {
  // Remove block comments and line comments so commented-out annotations don't count
  // (the knowledge-graph trap from findings).
  return src.replace(/\/\*[\s\S]*?\*\//g, m => m.replace(/[^\n]/g, ' '))
            .replace(/\/\/[^\n]*/g, '');
}

function parseRoles(annot) {
  const m = annot.match(/@RolesAllowed\s*\(\s*(\{[^}]*\}|"[^"]*")\s*\)/);
  if (!m) return null;
  return [...m[1].matchAll(/"([^"]*)"/g)].map(x => x[1]);
}

const endpoints = [];
for (const file of walk(path.join(BE, 'src/main/java'))) {
  const src = stripComments(fs.readFileSync(file, 'utf8'));
  if (!/@Path\s*\(/.test(src)) continue;

  // class-level annotations: the annotation block before "public class"
  const classDeclIdx = src.search(/\b(public\s+)?(abstract\s+)?class\s+\w+/);
  if (classDeclIdx < 0) continue;
  const head = src.slice(0, classDeclIdx);
  const classPathM = head.match(/@Path\s*\(\s*"([^"]*)"\s*\)/);
  const classPath = classPathM ? classPathM[1] : '';
  const classRoles = parseRoles(head);
  const classPermitAll = /@PermitAll\b/.test(head);

  // method-level: find verb annotations and their annotation blocks.
  // Boundaries must ignore '{'/'}'/';' inside parentheses and strings — e.g. the
  // '{' in @Path("/{id}") or @RolesAllowed({"x"}) is NOT a method-body brace.
  const body = src.slice(classDeclIdx);
  const depth = new Int32Array(body.length); // paren depth per char, strings masked
  {
    let d = 0, inStr = null;
    for (let i = 0; i < body.length; i++) {
      const ch = body[i];
      if (inStr) {
        depth[i] = -1; // inside string: never a boundary
        if (ch === '\\') { i++; if (i < body.length) depth[i] = -1; continue; }
        if (ch === inStr) inStr = null;
        continue;
      }
      if (ch === '"' || ch === "'") { inStr = ch; depth[i] = -1; continue; }
      if (ch === '(') { depth[i] = d; d++; continue; }
      if (ch === ')') { d = Math.max(0, d - 1); depth[i] = d; continue; }
      depth[i] = d;
    }
  }
  const verbRe = /@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b/g;
  let m;
  while ((m = verbRe.exec(body)) !== null) {
    const verb = m[1];
    let blockStart = 0;
    for (let i = m.index - 1; i >= 0; i--) {
      if ((body[i] === '}' || body[i] === ';') && depth[i] === 0) { blockStart = i + 1; break; }
    }
    let fwd = Math.min(m.index + 2000, body.length);
    for (let i = m.index; i < body.length; i++) {
      if ((body[i] === '{' || body[i] === ';') && depth[i] === 0) { fwd = i; break; }
    }
    const block = body.slice(blockStart, fwd);

    const mPathM = block.match(/@Path\s*\(\s*"([^"]*)"\s*\)/);
    const mPath = mPathM ? mPathM[1] : '';
    const mRoles = parseRoles(block);
    const permitAll = /@PermitAll\b/.test(block);

    let full = ('/' + [classPath, mPath].map(s => s.replace(/^\/|\/$/g, '')).filter(Boolean).join('/'))
      .replace(/\/+/g, '/');
    // normalize path params {name: regex} and {name}
    full = full.replace(/\{[^}/]*\}/g, '{}');

    const roles = permitAll ? [] : (mRoles ?? (classPermitAll ? [] : classRoles));
    endpoints.push({ verb, path: full, roles: roles ?? null, file: path.relative(BE, file), permitAll });
  }
}

// ---------- 2. Frontend role-gated handlers and their backend calls ----------
const manifest = JSON.parse(fs.readFileSync(path.join(FE, 'src/access/access-manifest.json'), 'utf8'));
const roleGated = [];
for (const r of manifest.routes) {
  for (const [method, info] of Object.entries(r.methods || {})) {
    if (info.roles && info.roles.length) roleGated.push({ path: r.path, file: r.file, method, roles: info.roles, gate: info.gate });
  }
}

function methodFromOpts(opts) {
  if (!opts) return 'GET';
  const mm = opts.match(/method\s*:\s*['"`](\w+)['"`]/);
  return mm ? mm[1].toUpperCase() : 'GET';
}

function resolvePathVariable(src, name) {
  // const backendPath = `/invoices/${x}/book` + (maybe query tail)
  const re = new RegExp('(?:const|let|var)\\s+' + name + '\\s*[:=][\\s\\S]{0,200}?(`[^`]*`|\'[^\']*\'|"[^"]*")');
  const m = re.exec(src);
  return m ? m[1].slice(1, -1) : null;
}

function extractCalls(src) {
  const calls = [];
  // backendFetch<T>(`/path/${x}` or '/path' or pathVariable, { method: 'POST', ... }?)
  const re = /backendFetch(?:<[^>]*>)?\s*\(\s*(`[^`]*`|'[^']*'|"[^"]*"|[A-Za-z_$][\w$]*)\s*(?:,\s*(\{[\s\S]*?\}))?\s*\)/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    let arg = m[1], raw;
    if (/^[`'"]/.test(arg)) {
      raw = arg.slice(1, -1);
    } else {
      raw = resolvePathVariable(src, arg);
      if (!raw) { calls.push({ raw: '<unresolved:' + arg + '>', method: methodFromOpts(m[2]), at: m.index }); continue; }
    }
    calls.push({ raw, method: methodFromOpts(m[2]), at: m.index });
  }
  // raw fetch against the backend base URL (F-11 bypass sites)
  const re2 = /fetch\s*\(\s*`\$\{[^}]*(?:BACKEND_URL|backendUrl|baseUrl|BASE_URL)[^}]*\}(\/[^`]*)`\s*(?:,\s*(\{[\s\S]*?\}))?\s*\)/g;
  while ((m = re2.exec(src)) !== null) {
    calls.push({ raw: m[1], method: methodFromOpts(m[2]), at: m.index });
  }
  return calls;
}

// Local-import following for helper/factory modules (createXGetRoute patterns).
const moduleCallCache = new Map();
function resolveImportPath(fromFile, spec) {
  let base;
  if (spec.startsWith('@/')) base = path.join(FE, 'src', spec.slice(2));
  else if (spec.startsWith('.')) base = path.resolve(path.dirname(fromFile), spec);
  else return null;
  for (const cand of [base + '.ts', base + '.tsx', path.join(base, 'index.ts')]) {
    if (fs.existsSync(cand)) return cand;
  }
  return null;
}

function callsFromImports(file, depth) {
  if (depth <= 0) return [];
  const src = fs.readFileSync(file, 'utf8');
  const out = [];
  const re = /import\s[\s\S]*?from\s+['"]([^'"]+)['"]/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const target = resolveImportPath(file, m[1]);
    if (!target || target.endsWith(path.join('lib', 'api', 'client.ts'))) continue;
    if (moduleCallCache.has(target)) { out.push(...moduleCallCache.get(target)); continue; }
    moduleCallCache.set(target, []); // cycle guard
    const tsrc = fs.readFileSync(target, 'utf8');
    let found = extractCalls(tsrc).filter(c => !c.raw.startsWith('<unresolved'));
    if (found.length === 0) found = callsFromImports(target, depth - 1);
    const tagged = found.map(c => ({ ...c, via: path.relative(FE, target) }));
    moduleCallCache.set(target, tagged);
    out.push(...tagged);
  }
  return out;
}

function normalizeFePath(raw, moduleSrc) {
  let p = raw;
  // substitute `${IDENT}` where IDENT is a module-level string const (e.g. BACKEND_BASE)
  p = p.replace(/\$\{\s*([A-Za-z_$][\w$]*)\s*\}/g, (full, ident) => {
    if (moduleSrc) {
      const lit = resolvePathVariable(moduleSrc, ident);
      if (lit && !lit.includes('${')) return lit;
    }
    return full;
  });
  p = p.replace(/\$\{[^}]*\}/g, '{}');
  // an unterminated `${` means the template held a nested template (a query-string
  // ternary); everything from there on is query, not path
  const unterminated = p.indexOf('${');
  if (unterminated >= 0) p = p.slice(0, unterminated);
  p = p.split('?')[0];
  // a trailing `{}` glued to a literal or another `{}` (e.g. `jobs${qs}`) is a
  // query-string variable, not a path segment — drop it
  while (/([^/]|\{\})\{\}$/.test(p) && !/\/\{\}$/.test(p)) p = p.slice(0, -2);
  p = p.replace(/\/+/g, '/');
  if (p.length > 1) p = p.replace(/\/$/, '');
  if (!p.startsWith('/')) return null; // not a backend-relative path
  return p;
}

// slice the file into exported-handler regions so calls attribute to the right method
function handlerRegions(src) {
  const regions = [];
  const re = /export\s+(?:async\s+)?(?:function|const)\s+(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b/g;
  let m;
  const marks = [];
  while ((m = re.exec(src)) !== null) marks.push({ name: m[1], start: m.index });
  for (let i = 0; i < marks.length; i++) {
    regions.push({ name: marks[i].name, start: marks[i].start, end: i + 1 < marks.length ? marks[i + 1].start : src.length });
  }
  return regions;
}

function matchEndpoint(verb, fePath) {
  const feSegs = fePath.split('/').filter(Boolean);
  const candidates = endpoints.filter(e => e.verb === verb);
  let best = [], bestScore = -1;
  for (const e of candidates) {
    const beSegs = e.path.split('/').filter(Boolean);
    if (beSegs.length !== feSegs.length) continue;
    let ok = true, score = 0;
    for (let i = 0; i < beSegs.length; i++) {
      const a = beSegs[i], b = feSegs[i];
      if (a === b) { score += 2; continue; }
      if (a === '{}' || b === '{}') { score += 0; continue; }
      ok = false; break;
    }
    if (!ok) continue;
    if (score > bestScore) { best = [e]; bestScore = score; }
    else if (score === bestScore) best.push(e);
  }
  if (best.length === 0) return null;
  // ties are fine when every tied endpoint requires the same scopes
  const sets = new Set(best.map(e => JSON.stringify([...(e.roles || [])].sort())));
  if (sets.size > 1) return { ambiguous: best };
  return best[0];
}

// scope preference for multi-scope @RolesAllowed: match action to verb
function pickScopes(endpoint, verb) {
  const roles = endpoint.roles || [];
  if (roles.length <= 1) return roles;
  const readish = roles.filter(s => /:(read|view)$/.test(s));
  const writish = roles.filter(s => !/:(read|view)$/.test(s));
  if (verb === 'GET' || verb === 'HEAD') return readish.length ? readish : roles;
  return writish.length ? writish : roles;
}

// ---------- 3. Join ----------
// Hand-verified mappings where the call site defeats static extraction.
// /api/admin/document-migration/jobs/[job] posts to one of five sibling job
// endpoints (crawl|match|copy|categorize|verify) — all @RolesAllowed({"documents:write"}).
const MANUAL_MAPPINGS = new Map([
  ['POST /api/admin/document-migration/jobs/[job]', [{ method: 'POST', path: '/admin/document-migration/copy' }]],
]);
const pairs = new Map();           // scope -> Map(role -> Set(contributing fe route))
const unmapped = [];
const noBackendCall = [];
const multiScope = [];

for (const h of roleGated) {
  const feFile = path.join(FE, h.file);
  if (!fs.existsSync(feFile)) { unmapped.push({ ...h, reason: 'route file missing' }); continue; }
  const src = fs.readFileSync(feFile, 'utf8');
  const regions = handlerRegions(src);
  const region = regions.find(r => r.name === h.method);
  const all = extractCalls(src);
  // calls inside this handler region, plus calls outside any region (shared helpers)
  const inRegion = all.filter(c => region && c.at >= region.start && c.at < region.end);
  const shared = all.filter(c => !regions.some(r => c.at >= r.start && c.at < r.end));
  let calls = [...inRegion, ...shared].filter(c => !c.raw.startsWith('<unresolved'));
  if (calls.length === 0) calls = callsFromImports(feFile, 2); // factory/helper-module routes
  if (calls.length === 0) { noBackendCall.push({ route: h.path, method: h.method, roles: h.roles }); continue; }

  const manual = MANUAL_MAPPINGS.get(`${h.method} ${h.path}`);
  if (manual) calls = manual.map(mm => ({ raw: mm.path, method: mm.method, at: -1 }));

  for (const c of calls) {
    const callerSrc = c.via ? fs.readFileSync(path.join(FE, c.via), 'utf8') : src;
    const p = normalizeFePath(c.raw, callerSrc);
    if (!p) { unmapped.push({ route: h.path, method: h.method, call: c.raw, reason: 'not a backend-relative path' }); continue; }
    const ep = matchEndpoint(c.method, p);
    if (!ep) { unmapped.push({ route: h.path, method: h.method, call: `${c.method} ${p}`, reason: 'no backend endpoint matched' }); continue; }
    if (ep.ambiguous) { unmapped.push({ route: h.path, method: h.method, call: `${c.method} ${p}`, reason: 'ambiguous: tied endpoints with different scopes', candidates: ep.ambiguous.map(e => `${e.path} ${JSON.stringify(e.roles)}`) }); continue; }
    if (ep.permitAll || !ep.roles || ep.roles.length === 0) continue; // public endpoint: no permission implied
    const scopes = pickScopes(ep, c.method);
    if ((ep.roles || []).length > 1) multiScope.push({ route: h.path, call: `${c.method} ${p}`, endpoint: ep.path, all: ep.roles, picked: scopes });
    for (const scope of scopes) {
      if (!pairs.has(scope)) pairs.set(scope, new Map());
      const roleMap = pairs.get(scope);
      for (const role of h.roles) {
        if (!roleMap.has(role)) roleMap.set(role, new Set());
        roleMap.get(role).add(`${h.method} ${h.path}`);
      }
    }
  }
}

// ---------- 4. Report ----------
const PROD_ROLES = new Set(['SYSTEM','APPLICATION','USER','EXTERNAL','ADMIN','PARTNER','TECHPARTNER','TEAMLEAD','SALES','ACCOUNTING','MARKETING','EDITOR','HR','DPO','COMMUNICATIONS','TEMP','DEVOPS']);

const perPermission = {};
const excludedPhantom = {};
for (const [scope, roleMap] of [...pairs.entries()].sort()) {
  const roles = [...roleMap.keys()].sort();
  const present = roles.filter(r => PROD_ROLES.has(r));
  const phantom = roles.filter(r => !PROD_ROLES.has(r));
  if (phantom.length) excludedPhantom[scope] = phantom;
  // contributing distinct role-sets for widening analysis
  const routeSets = new Map();
  for (const [role, routes] of roleMap) for (const rt of routes) {
    if (!routeSets.has(rt)) routeSets.set(rt, []);
    routeSets.get(rt).push(role);
  }
  const distinctSets = [...new Set([...routeSets.values()].map(s => JSON.stringify(s.sort())))].map(s => JSON.parse(s));
  perPermission[scope] = {
    seededRoles: present,
    excludedPhantomRoles: phantom,
    distinctContributingSets: distinctSets,
    widened: distinctSets.length > 1,
    contributingRoutes: Object.fromEntries([...routeSets.entries()].map(([rt, rs]) => [rt, rs.sort()])),
  };
}

const report = {
  generatedFrom: { feManifest: 'src/access/access-manifest.json', beWorktree: 'authz/phase-4-permission-catalogue' },
  totals: {
    backendEndpoints: endpoints.length,
    roleGatedHandlers: roleGated.length,
    permissionsSeeded: Object.keys(perPermission).length,
    pairsSeeded: Object.values(perPermission).reduce((n, p) => n + p.seededRoles.length, 0),
    widenedPermissions: Object.values(perPermission).filter(p => p.widened).length,
    unmappedCalls: unmapped.length,
    handlersWithNoBackendCall: noBackendCall.length,
  },
  perPermission,
  excludedPhantom,
  multiScopeEndpoints: multiScope,
  unmapped,
  noBackendCall,
};
fs.writeFileSync(OUT, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report.totals, null, 2));
console.log('report → ' + OUT);
