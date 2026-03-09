// ─── Sidebar ───
function renderSidebar(activePage) {
  const items = [
    { id: 'monitor', label: '모니터', icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
    { id: 'pipeline', label: '파이프라인', icon: 'M13 10V3L4 14h7v7l9-11h-7z' },
    { id: 'settings', label: '설정', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z' },
  ];

  const projectName = sessionStorage.getItem('af_project_name') || '—';
  const pipeCount = sessionStorage.getItem('af_pipe_active') || '';

  let html = `
  <aside class="w-56 bg-gray-900 text-gray-300 flex flex-col flex-shrink-0 h-screen sticky top-0">
    <div class="p-4 border-b border-gray-700">
      <div class="flex items-center gap-2">
        <div class="w-8 h-8 bg-sky-500 rounded-lg flex items-center justify-center text-white font-bold text-sm">AF</div>
        <div><div class="text-white font-semibold text-sm">AutoFix Agent</div><div class="text-xs text-gray-500">v1.0.0</div></div>
      </div>
    </div>
    <div class="p-3 border-b border-gray-700">
      <a href="select.html" class="flex items-center gap-1 text-xs text-gray-500 hover:text-white truncate no-underline transition" title="프로젝트 변경">
        <span class="truncate">${esc(projectName)}</span>
        <svg class="w-3 h-3 flex-shrink-0 opacity-50" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 9l4-4 4 4m0 6l-4 4-4-4"/></svg>
      </a>
    </div>
    <nav class="flex-1 py-3">`;

  items.forEach(p => {
    const active = p.id === activePage ? ' active' : '';
    const badge = (p.id === 'pipeline' && pipeCount && pipeCount !== '0')
      ? `<span class="ml-auto text-xs bg-red-500 text-white px-1.5 py-0.5 rounded-full">${pipeCount}</span>` : '';
    const href = p.id + '.html';
    html += `<a href="${href}" class="sidebar-item${active} block px-4 py-2.5 text-sm flex items-center gap-3 rounded-r-lg mr-2 no-underline text-gray-300 hover:text-white">
      <svg class="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="${p.icon}"/></svg>
      ${p.label}${badge}</a>`;
  });

  html += `</nav>
    <div class="p-3 border-t border-gray-700">
      <div class="text-xs text-gray-500 font-semibold mb-2">에이전트</div>
      <div class="space-y-1.5" id="sidebar-agents">
        <div class="flex items-center gap-2 text-xs tip-down" data-tip="이슈 감지 — WhaTap API를 통해 메트릭 폴링"><span class="w-2 h-2 bg-blue-500 rounded-full pulse"></span><span class="text-purple-400">Scout</span><span class="text-gray-600 ml-auto">폴링 중</span></div>
        <div class="flex items-center gap-2 text-xs tip-down" data-tip="근본 원인 분석 — 원인 식별"><span class="w-2 h-2 bg-gray-600 rounded-full"></span><span class="text-gray-500">Analyzer</span><span class="text-gray-600 ml-auto">대기</span></div>
        <div class="flex items-center gap-2 text-xs tip-down" data-tip="수정 생성 — 코드 수정 생성"><span class="w-2 h-2 bg-gray-600 rounded-full"></span><span class="text-gray-500">Fixer</span><span class="text-gray-600 ml-auto">대기</span></div>
        <div class="flex items-center gap-2 text-xs tip-down" data-tip="실행/배포 — CI/CD 배포"><span class="w-2 h-2 bg-gray-600 rounded-full"></span><span class="text-gray-500">Deployer</span><span class="text-gray-600 ml-auto">대기</span></div>
        <div class="flex items-center gap-2 text-xs tip-down" data-tip="결과 검증 — 메트릭 정상화 확인"><span class="w-2 h-2 bg-gray-600 rounded-full"></span><span class="text-gray-500">Verifier</span><span class="text-gray-600 ml-auto">대기</span></div>
      </div>
    </div>
    <div class="p-3 border-t border-gray-700">
      <button onclick="doLogout()" class="w-full flex items-center gap-2 px-3 py-2 text-xs text-gray-400 hover:text-white hover:bg-gray-800 rounded-lg transition">
        <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"/></svg>
        로그아웃
      </button>
    </div>
  </aside>`;

  document.getElementById('sidebar').innerHTML = html;
}

// ─── Agent sidebar update ───
function updateSidebarAgents(pipelines) {
  const el = document.getElementById('sidebar-agents');
  if (!el) return;
  const activeStages = new Set((pipelines || []).filter(p => p.status === 'IN_PROGRESS').map(p => p.currentStage));
  activeStages.add('SCOUT');
  const taskCount = {};
  (pipelines || []).filter(p => p.status === 'IN_PROGRESS').forEach(p => {
    taskCount[p.currentStage] = (taskCount[p.currentStage] || 0) + 1;
  });
  const stages = ['SCOUT','ANALYZER','FIXER','DEPLOYER','VERIFIER'];
  const names = ['Scout','Analyzer','Fixer','Deployer','Verifier'];
  const colors = ['text-purple-400','text-sky-400','text-gray-300','text-orange-400','text-green-400'];

  el.innerHTML = stages.map((s, i) => {
    const active = activeStages.has(s);
    const count = taskCount[s] || 0;
    const dotColor = active ? 'bg-blue-500 rounded-full pulse' : 'bg-gray-600 rounded-full';
    const nameColor = active ? colors[i] : 'text-gray-500';
    const status = s === 'SCOUT' ? '폴링 중' : (count > 0 ? count + ' 작업' : '대기');
    return `<div class="flex items-center gap-2 text-xs"><span class="w-2 h-2 ${dotColor}"></span><span class="${nameColor}">${names[i]}</span><span class="text-gray-600 ml-auto">${status}</span></div>`;
  }).join('');

  // Update pipe badge in sessionStorage
  const activeCount = (pipelines || []).filter(p => p.status === 'IN_PROGRESS').length;
  sessionStorage.setItem('af_pipe_active', String(activeCount));
}

// ─── Connection check → redirect to login if needed ───
async function requireConnection() {
  try {
    const r = await fetch('/api/auth/whatap/status');
    const d = await r.json();
    if (!d.connected) {
      location.href = 'index.html';
      return false;
    }
    return true;
  } catch(e) {
    location.href = 'index.html';
    return false;
  }
}

// ─── Helpers ───
function esc(s) { if (!s) return ''; const d = document.createElement('div'); d.textContent = String(s); return d.innerHTML; }
function fmtTime(t) { if (!t) return ''; const d = new Date(t); return d.toLocaleTimeString('ko-KR', {hour:'2-digit',minute:'2-digit',second:'2-digit'}); }
function fmtTimeShort(t) { return fmtTime(t); }

function showToast(msg) {
  let c = document.getElementById('toast-container');
  if (!c) { c = document.createElement('div'); c.id = 'toast-container'; c.className = 'fixed top-4 right-4 z-[60] space-y-2'; document.body.appendChild(c); }
  const t = document.createElement('div');
  t.className = 'toast bg-gray-900 text-white px-4 py-2 rounded-lg shadow-lg text-sm';
  t.textContent = msg;
  c.appendChild(t);
  setTimeout(() => t.remove(), 3000);
}

function showModal(id) { document.getElementById(id).classList.add('show'); }
function hideModal(id) { document.getElementById(id).classList.remove('show'); }

// ─── Pipeline helpers (shared) ───
function getSeverity(p) {
  if (p.status === 'COMPLETED') return {cls: 'badge-success', label: 'SUCCESS'};
  if (p.status === 'FAILED') return {cls: 'badge-critical', label: 'FAILED'};
  const t = p.issueType || '';
  if (t.includes('HIGH') || t.includes('SPIKE') || t.includes('EXHAUSTED') || t.includes('FULL'))
    return {cls: 'badge-critical', label: 'CRITICAL'};
  return {cls: 'badge-warning', label: 'WARNING'};
}

function getAgentLabel(p) {
  const stage = p.currentStage || 'SCOUT';
  const map = {
    SCOUT:{name:'Scout',cls:'agent-scout'},
    ANALYZER:{name:'Analyzer',cls:'agent-analyzer'},
    FIXER:{name:'Fixer',cls:'agent-fixer'},
    DEPLOYER:{name:'Deployer',cls:'agent-deployer'},
    VERIFIER:{name:'Verifier',cls:'agent-verifier'}
  };
  if (p.status === 'COMPLETED') return {name: fmtTime(p.createdAt), cls:'text-xs text-gray-400'};
  return map[stage] || map.SCOUT;
}

function getIssueDesc(p) {
  if (p.issue) return p.issue.metric + ': ' + p.issue.value + ' (임계값: ' + p.issue.threshold + ')';
  return p.issueType;
}

function renderMiniDots(p) {
  const stages = ['SCOUT','ANALYZER','FIXER','DEPLOYER','VERIFIER'];
  const cur = stages.indexOf(p.currentStage);
  const isSim = p.deploy && p.deploy.simulated;
  return stages.map((s, i) => {
    let cls = '';
    if (p.status === 'COMPLETED') cls = 'done';
    else if (p.status === 'FAILED' && i === cur) cls = 'failed';
    else if (i < cur) cls = 'done';
    else if (i === cur) {
      if (isSim && (s === 'DEPLOYER' || s === 'VERIFIER')) cls = 'sim';
      else cls = p.status === 'IN_PROGRESS' ? 'active' : 'done';
    }
    const line = i < 4 ? `<div class="line ${i < cur ? 'done' : ''}"></div>` : '';
    return `<div class="dot ${cls}"></div>${line}`;
  }).join('');
}

// ─── Logout ───
async function doLogout() {
  try {
    await fetch('/api/auth/logout', { method: 'POST' });
  } catch(e) { /* ignore */ }
  sessionStorage.clear();
  location.href = 'index.html';
}

// ─── Init: modal backdrop click ───
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.modal-overlay').forEach(m => {
    m.addEventListener('click', e => { if (e.target === m) m.classList.remove('show'); });
  });
});
