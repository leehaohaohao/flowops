// 通用请求封装
async function api(url, options = {}) {
    const token = localStorage.getItem('token');
    const headers = {'Authorization': token};
    if (options.body && !(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(url, {...options, headers});
    return res.json();
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
