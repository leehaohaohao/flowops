// 判断是否为 Sa-Token 认证失败（错误码 11011~11016）
function isAuthError(data) {
    return data && typeof data.code === 'number' && data.code >= 11011 && data.code <= 11016;
}

// 通用请求封装
async function api(url, options = {}) {
    const token = localStorage.getItem('token');
    const headers = {'Authorization': token};
    if (options.body && !(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(url, {...options, headers});
    const data = await res.json();
    if (res.status === 401 || res.status === 403 || isAuthError(data)) {
        localStorage.removeItem('token');
        location.href = '/login';
        throw new Error('token 已失效，请重新登录');
    }
    return data;
}

// 登出
async function logout() {
    await api('/auth/logout', {method: 'POST'});
    localStorage.removeItem('token');
    location.href = '/login';
}

// Toast 提示
function showToast(msg, type = 'info') {
    const container = document.getElementById('toastContainer');
    if (!container) return alert(msg); // fallback

    const bgMap = {success: 'bg-success', danger: 'bg-danger', warning: 'bg-warning text-dark', info: 'bg-primary'};
    const iconMap = {success: '✓', danger: '✗', warning: '⚠', info: 'ℹ'};
    const bg = bgMap[type] || bgMap.info;
    const icon = iconMap[type] || iconMap.info;

    const el = document.createElement('div');
    el.className = `toast align-items-center border-0 show ${bg}`;
    el.setAttribute('role', 'alert');
    el.innerHTML = `
        <div class="d-flex">
            <div class="toast-body">${icon} ${msg}</div>
            <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>
        </div>`;
    container.appendChild(el);

    setTimeout(() => { el.classList.remove('show'); setTimeout(() => el.remove(), 300); }, 3000);
}
