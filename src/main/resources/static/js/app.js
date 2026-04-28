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