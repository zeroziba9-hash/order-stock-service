"use strict";

(() => {
  const $ = (selector) => document.querySelector(selector);
  const money = (value) => `${new Intl.NumberFormat("ko-KR").format(value)}원`;
  const date = (value) => new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
  const statuses = { CREATED: "결제 대기", PAID: "결제 완료", CANCELLED: "취소 완료", REFUNDED: "환불 완료", EXPIRED: "주문 만료" };
  const state = { user: null, products: [], orders: [], next: null, filter: "all", register: false, selected: null, config: {}, epoch: 0, ordersLoading: false };
  const pendingKeys = new Map();
  let toastTimer;
  let afterLogin;
  let ordersRequest = 0;
  let detailRequest = 0;

  class ApiError extends Error {
    constructor(message, status, code) { super(message); this.status = status; this.code = code; }
  }

  async function api(path, { method = "GET", body, form, key } = {}) {
    const headers = { Accept: "application/json" };
    if (method !== "GET") {
      const csrf = await api("/auth/csrf");
      headers[csrf.headerName] = csrf.token;
    }
    if (key) headers["Idempotency-Key"] = key;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (form) headers["Content-Type"] = "application/x-www-form-urlencoded";
    let response;
    try {
      response = await fetch(path, { method, headers, credentials: "same-origin", cache: "no-store",
        body: form ? new URLSearchParams(form) : body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      throw new ApiError(method === "GET" ? "서버에 연결할 수 없어요. 연결을 확인하고 다시 시도해 주세요."
        : "응답을 받지 못했어요. 같은 내용으로 다시 시도하면 중복 요청을 확인해요. 새 주문 전에는 내 주문도 확인해 주세요.", 0);
    }
    const data = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) throw new ApiError(data?.message || "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.", response.status, data?.code);
    return data;
  }

  function notify(message) {
    clearTimeout(toastTimer); $("#toast").textContent = message; $("#toast").hidden = false;
    toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 6000);
  }

  function handleError(error, target) {
    if (error.status === 401 && state.user) {
      state.user = null; state.epoch++; state.orders = []; state.next = null;
      renderAccount(); renderOrders();
      error.message = "로그인이 만료됐어요. 다시 로그인해 주세요.";
    }
    if (target) $(target).textContent = error.message; else notify(error.message);
  }

  function requestKey(kind, body) {
    const scope = `stockroom-request:${state.user.userId}:${kind}:${JSON.stringify(body)}`;
    let key = pendingKeys.get(scope);
    try { key ||= sessionStorage.getItem(scope); } catch { /* Memory fallback when browser storage is unavailable. */ }
    if (!key) {
      key = [...crypto.getRandomValues(new Uint8Array(24))].map((value) => value.toString(16).padStart(2, "0")).join("");
      pendingKeys.set(scope, key);
      try { sessionStorage.setItem(scope, key); } catch { /* Keep the key in memory. */ }
    }
    return { key, done() { pendingKeys.delete(scope); try { sessionStorage.removeItem(scope); } catch { /* No storage to remove. */ } } };
  }

  function badge(order) { return `<span class="status ${escape(order.status)}">${escape(statuses[order.status] || order.status)}</span>`; }

  function renderAccount() {
    $("#account").innerHTML = state.user
      ? `<span class="account-email" title="${escape(state.user.email)}">${escape(state.user.email)}</span><button class="button ghost small" id="logout-button">로그아웃</button>`
      : '<button class="button small" id="login-button">로그인 <span aria-hidden="true">↗</span></button>';
    $("#order-count").hidden = !state.user || !state.orders.length;
    $("#order-count").textContent = state.orders.length + (state.next ? "+" : "");
    $("#admin-nav").hidden = state.user?.role !== "ADMIN";
  }

  function productArt(product) {
    if (product.name.includes("머그")) return '<div class="mug"><div class="mug-print">make<br>something<span>good.</span></div></div>';
    if (product.name.includes("스티커")) return '<div class="sticker-sheet"><div class="sheet-label">&lt;hello /&gt;</div><div class="sheet-label">✳</div><div class="sheet-label">just create.</div><div class="sheet-label">:)</div></div>';
    return '<span class="generic-art">✳</span>';
  }

  function renderProducts() {
    $("#product-count").textContent = String(state.products.length).padStart(2, "0");
    const products = state.products.filter((product) => state.filter !== "available" || product.stock > 0);
    $("#products").innerHTML = products.length ? products.map((product) => {
      const type = product.name.includes("머그") ? "mugs" : product.name.includes("스티커") ? "stickers" : "generic";
      return `<article class="product-card"><div class="product-image ${type}" role="img" aria-label="${escape(product.name)}을 표현한 일러스트"><span class="product-badge">STOCKROOM EDITION</span>${productArt(product)}</div><div class="product-meta"><span class="product-category">${type === "mugs" ? "EVERYDAY OBJECTS" : type === "stickers" ? "LITTLE EXPRESSIONS" : "THE COLLECTION"}</span><span class="stock-tag ${product.stock === 0 ? "sold" : product.stock <= 5 ? "low" : ""}"><span class="live-dot"></span>${product.stock === 0 ? "품절" : `${escape(product.stock)}개 남음`}</span></div><h3>${escape(product.name)}</h3><div class="product-bottom"><p class="price">${escape(money(product.price))}</p><button class="buy-button" data-buy="${escape(product.id)}" ${product.stock === 0 ? "disabled" : ""}>${product.stock === 0 ? "다음 기회를 기다려요" : '주문하기 <span aria-hidden="true">↗</span>'}</button></div></article>`;
    }).join("") : '<p class="empty">지금 구매할 수 있는 상품이 없어요.<br>다음 컬렉션을 기다려 주세요.</p>';
  }

  async function loadProducts() {
    const button = $("#refresh-products"); button.disabled = true;
    try { state.products = await api("/products"); renderProducts(); }
    catch (error) {
      if (!state.products.length) $("#products").innerHTML = '<p class="empty">상품을 불러오지 못했어요.<br>재고 새로고침을 눌러 다시 시도해 주세요.</p>';
      handleError(error);
    } finally { button.disabled = false; }
  }

  function renderOrders() {
    const list = $("#orders");
    if (!state.user) list.innerHTML = '<div class="empty"><strong>나만의 컬렉션을 확인하세요.</strong>로그인하면 다른 기기에서 주문한 내역도 볼 수 있어요.<br><button class="button primary" data-login>로그인하기 →</button></div>';
    else if (!state.orders.length) list.innerHTML = '<div class="empty"><strong>아직 담아 둔 취향이 없네요.</strong>첫 번째 컬렉션을 둘러보고 마음에 드는 상품을 골라보세요.<br><a class="button primary" href="#shop">상품 둘러보기 ↗</a></div>';
    else list.innerHTML = state.orders.map((order) => `<article class="order-card"><div class="order-top"><div><span class="order-number">ORDER #${escape(order.orderId)}</span><time class="order-date" datetime="${escape(order.createdAt)}">${escape(date(order.createdAt))}</time></div>${badge(order)}</div><div class="order-items">${order.items.map((item) => `${escape(item.productName)} <small>· ${escape(item.quantity)}개</small>`).join("<br>")}</div><div class="order-bottom"><strong class="price">${escape(money(order.totalAmount))}</strong><div class="order-actions"><button class="button ghost small" data-detail="${escape(order.orderId)}">상세·이력 보기 ↗</button>${order.status === "CREATED" ? `<button class="button small" data-cancel="${escape(order.orderId)}">주문 취소</button>` : ""}</div></div></article>`).join("");
    $("#load-more").hidden = !state.user || !state.next;
    renderAccount();
  }

  async function loadOrders(append = false) {
    if (!state.user || (append && state.ordersLoading)) { if (!state.user) renderOrders(); return; }
    const epoch = state.epoch; const requestId = ++ordersRequest; state.ordersLoading = true;
    $("#refresh-orders").disabled = true; $("#load-more").disabled = true;
    try {
      const page = await api(`/orders${append && state.next ? `?beforeId=${encodeURIComponent(state.next)}` : ""}`);
      if (epoch !== state.epoch || requestId !== ordersRequest) return;
      state.orders = append ? [...state.orders, ...page.orders] : page.orders;
      state.next = page.nextBeforeId; renderOrders();
    } catch (error) {
      if (epoch !== state.epoch || requestId !== ordersRequest) return;
      if (!state.orders.length) $("#orders").innerHTML = '<p class="empty">주문 내역을 불러오지 못했어요. 새로고침으로 다시 시도해 주세요.</p>';
      handleError(error);
    } finally {
      if (requestId === ordersRequest) {
        state.ordersLoading = false; $("#refresh-orders").disabled = false; $("#load-more").disabled = false;
      }
    }
  }

  function route() {
    const orders = location.hash === "#orders";
    $("#shop-view").hidden = orders; $("#orders-view").hidden = !orders;
    $("#shop-nav").toggleAttribute("aria-current", !orders); $("#orders-nav").toggleAttribute("aria-current", orders);
    if (orders) $("#orders-nav").setAttribute("aria-current", "page"); else $("#shop-nav").setAttribute("aria-current", "page");
    if (orders) loadOrders();
  }

  function showAuth(callback) {
    afterLogin = callback; state.register = false; renderAuth(); $("#auth-form").reset();
    $("#auth-dialog").showModal();
  }

  function renderAuth() {
    $("#auth-title").textContent = state.register ? "새로운 취향의 시작." : "다시 만나 반가워요.";
    $("#auth-description").textContent = state.register ? "이메일로 간단하게 나만의 계정을 만드세요." : "로그인하고 나만의 컬렉션을 만나보세요.";
    $("#auth-submit").textContent = state.register ? "회원가입 →" : "로그인 →";
    $("#auth-switch-label").textContent = state.register ? "이미 계정이 있나요?" : "아직 계정이 없나요?";
    $("#auth-switch").textContent = state.register ? "로그인" : "회원가입";
    $("#password").autocomplete = state.register ? "new-password" : "current-password";
    $("#password").minLength = state.register ? 8 : 1;
    $("#password-help").hidden = !state.register; $("#auth-error").textContent = "";
  }

  function purchase(productId) {
    if (!state.user) return showAuth(() => purchase(productId));
    const product = state.products.find((item) => item.id === productId);
    if (!product || product.stock < 1) return notify("품절된 상품이에요. 재고를 새로고침해 주세요.");
    state.selected = product; $("#purchase-title").textContent = product.name;
    $("#purchase-stock").textContent = `현재 ${product.stock}개 남아 있어요. · ${money(product.price)}`;
    $("#quantity").value = 1; $("#quantity").max = Math.min(product.stock, 2147483647);
    $("#purchase-error").textContent = ""; updateTotal(); $("#purchase-dialog").showModal();
  }

  function updateTotal() {
    const quantity = Number($("#quantity").value);
    $("#purchase-total").textContent = Number.isInteger(quantity) && quantity > 0 ? money(state.selected.price * quantity) : "—";
  }

  function history(title, rows) {
    return `<h3 class="history-heading">${title}</h3>${rows.length ? rows.map((row) => `<div class="history-row"><div>${row.status === "SUCCEEDED" ? "완료" : "실패"}<br><time datetime="${escape(row.createdAt)}">${escape(date(row.createdAt))}</time></div><strong>${escape(money(row.amount))}</strong></div>`).join("") : '<p class="history-empty">아직 이력이 없어요.</p>'}`;
  }

  function deadlineNotice(order) {
    if (order.status !== "CREATED" || !order.expiresAt) return "";
    return `<p class="field-help">결제 기한 · ${escape(date(order.expiresAt))}<br>${Date.now() >= Date.parse(order.expiresAt) ? "기한이 지나 결제할 수 없어요. 자동 만료 처리 후 재고가 반환돼요." : "기한 내 결제하지 않으면 주문이 자동 만료되고 재고가 반환돼요."}</p>`;
  }

  async function detail(orderId) {
    const epoch = state.epoch; const requestId = ++detailRequest;
    $("#detail-title").textContent = `주문 #${orderId}`; $("#detail-error").textContent = "";
    $("#order-detail").innerHTML = '<p class="history-empty">주문을 불러오고 있어요.</p>';
    if (!$("#detail-dialog").open) $("#detail-dialog").showModal();
    try {
      const [order, payments, refunds] = await Promise.all([api(`/orders/${orderId}`), api(`/orders/${orderId}/payments`), api(`/orders/${orderId}/refunds`)]);
      if (epoch !== state.epoch || requestId !== detailRequest || !$("#detail-dialog").open) return;
      const mockPay = state.config.mockPayments && order.status === "CREATED" && Date.now() < Date.parse(order.expiresAt);
      const mockRefund = state.config.mockRefunds && order.status === "PAID";
      $("#order-detail").innerHTML = `<div class="detail-summary">${badge(order)}<time datetime="${escape(order.createdAt)}">${escape(date(order.createdAt))}</time></div>${order.items.map((item) => `<div class="detail-item"><span>${escape(item.productName)}<br>${escape(money(item.unitPrice))} × ${escape(item.quantity)}</span><strong>${escape(money(item.unitPrice * item.quantity))}</strong></div>`).join("")}<div class="total-row"><span>총 주문 금액</span><strong>${escape(money(order.totalAmount))}</strong></div>${deadlineNotice(order)}${order.cancelledAt ? `<p class="field-help">${order.status === "REFUNDED" ? "환불 완료" : order.status === "EXPIRED" ? "주문 만료" : "취소 완료"} · ${escape(date(order.cancelledAt))}</p>` : ""}${history("결제 이력", payments)}${history("환불 이력", refunds)}${mockPay || mockRefund ? '<p class="demo-note">로컬 체험용 모의 결제·환불이에요. 실제 금액은 청구되지 않아요.</p>' : ""}<div class="detail-actions">${order.status === "CREATED" ? `<button class="button small" data-cancel="${orderId}">주문 취소</button>` : ""}${mockPay ? `<button class="button primary small" data-mock="payments" data-id="${orderId}">모의 결제</button>` : ""}${mockRefund ? `<button class="button primary small" data-mock="refunds" data-id="${orderId}">모의 전액 환불</button>` : ""}</div>`;
    } catch (error) { if (epoch === state.epoch && requestId === detailRequest) handleError(error, "#detail-error"); }
  }

  function confirmAction(message) {
    const dialog = $("#confirm-dialog");
    $("#confirm-message").textContent = message; dialog.returnValue = "no";
    return new Promise((resolve) => {
      dialog.addEventListener("close", () => resolve(dialog.returnValue === "yes"), { once: true });
      dialog.showModal();
    });
  }

  async function changeOrder(button, operation, orderId) {
    const message = operation === "cancel" ? "주문을 취소할까요? 확보한 재고가 반환돼요." : operation === "payments" ? "모의 결제를 완료할까요? 실제 돈은 결제되지 않아요." : "모의 전액 환불을 진행할까요? 재고가 반환돼요.";
    if (!await confirmAction(message)) return;
    if (!state.user) return showAuth();
    const controls = [...document.querySelectorAll("[data-cancel], [data-mock], #logout-button")];
    controls.forEach((control) => { control.disabled = true; });
    const body = operation === "cancel" ? {} : { result: "SUCCESS" };
    const request = operation === "cancel" ? null : requestKey(`${orderId}/${operation}`, body);
    try {
      await api(`/orders/${orderId}/${operation}`, { method: "POST", body, key: request?.key });
      request?.done(); notify(operation === "cancel" ? "주문을 취소하고 재고를 반환했어요." : operation === "payments" ? "모의 결제를 완료했어요." : "모의 환불을 완료하고 재고를 반환했어요.");
      await Promise.all([loadProducts(), loadOrders()]);
      if ($("#detail-dialog").open) await detail(orderId);
    } catch (error) {
      if (error.code === "ORDER_EXPIRED") {
        request?.done();
        await Promise.all([loadProducts(), loadOrders()]);
        if ($("#detail-dialog").open) await detail(orderId);
      }
      handleError(error, $("#detail-dialog").open ? "#detail-error" : null);
    }
    finally { controls.forEach((control) => { control.disabled = false; }); }
  }

  document.addEventListener("click", (event) => {
    const button = event.target.closest("button"); if (!button || button.disabled) return;
    if (button.matches("[data-close]")) button.closest("dialog").close();
    else if (button.id === "login-button" || button.matches("[data-login]")) showAuth();
    else if (button.matches("[data-buy]")) purchase(Number(button.dataset.buy));
    else if (button.matches("[data-detail]")) detail(Number(button.dataset.detail));
    else if (button.matches("[data-cancel]")) changeOrder(button, "cancel", Number(button.dataset.cancel));
    else if (button.matches("[data-mock]")) changeOrder(button, button.dataset.mock, Number(button.dataset.id));
    else if (button.matches("[data-filter]")) {
      state.filter = button.dataset.filter;
      document.querySelectorAll("[data-filter]").forEach((filter) => { filter.classList.toggle("active", filter === button); filter.setAttribute("aria-pressed", String(filter === button)); });
      renderProducts();
    }
  });

  $("#auth-switch").addEventListener("click", () => { state.register = !state.register; renderAuth(); });
  $("#auth-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const dialog = $("#auth-dialog");
    const controls = [...dialog.querySelectorAll("button, input")]; controls.forEach((control) => { control.disabled = true; }); dialog.dataset.busy = "true";
    const account = { email: $("#email").value.trim(), password: $("#password").value };
    $("#auth-error").textContent = "";
    try {
      if (state.register) {
        if (new TextEncoder().encode(account.password).length > 72) throw new Error("비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요.");
        await api("/auth/register", { method: "POST", body: account });
        state.register = false; renderAuth(); notify("회원가입이 완료됐어요. 입력한 정보로 로그인해 주세요."); return;
      }
      state.user = await api("/auth/login", { method: "POST", form: account }); state.epoch++;
      dialog.close(); $("#auth-form").reset(); renderAccount(); await loadOrders(); notify("스톡룸에 오신 걸 환영해요.");
      const callback = afterLogin; afterLogin = null; callback?.();
    } catch (error) { handleError(error, "#auth-error"); }
    finally { controls.forEach((control) => { control.disabled = false; }); delete dialog.dataset.busy; }
  });

  $("#purchase-form").addEventListener("submit", async (event) => {
    event.preventDefault(); if (!state.user) { $("#purchase-dialog").close(); showAuth(); return; }
    const dialog = $("#purchase-dialog"); const controls = [...dialog.querySelectorAll("button, input")];
    const body = { productId: state.selected.id, quantity: Number($("#quantity").value) };
    const request = requestKey("orders", body);
    controls.forEach((control) => { control.disabled = true; }); dialog.dataset.busy = "true"; $("#purchase-error").textContent = "";
    try {
      const order = await api("/orders", { method: "POST", body, key: request.key }); request.done(); dialog.close();
      notify(`주문 #${order.orderId}이 완료됐어요. 내 주문에서 확인하세요.`);
      await Promise.all([loadProducts(), loadOrders()]); location.hash = "orders";
    } catch (error) { handleError(error, "#purchase-error"); if (error.code === "INSUFFICIENT_STOCK") await loadProducts(); }
    finally { controls.forEach((control) => { control.disabled = false; }); delete dialog.dataset.busy; }
  });

  document.querySelectorAll("dialog").forEach((dialog) => dialog.addEventListener("cancel", (event) => { if (dialog.dataset.busy) event.preventDefault(); }));
  $("#quantity").addEventListener("input", updateTotal);
  $("#quantity-minus").addEventListener("click", () => { $("#quantity").stepDown(); updateTotal(); });
  $("#quantity-plus").addEventListener("click", () => { $("#quantity").stepUp(); updateTotal(); });
  $("#refresh-products").addEventListener("click", loadProducts);
  $("#refresh-orders").addEventListener("click", () => loadOrders());
  $("#load-more").addEventListener("click", () => loadOrders(true));
  $("#account").addEventListener("click", async (event) => {
    const button = event.target.closest("#logout-button"); if (!button) return;
    button.disabled = true;
    try { await api("/auth/logout", { method: "POST" }); state.user = null; state.epoch++; state.orders = []; state.next = null; renderAccount(); renderOrders(); notify("로그아웃했어요."); }
    catch (error) { handleError(error); button.disabled = false; }
  });
  window.addEventListener("hashchange", route);

  async function init() {
    const epoch = state.epoch;
    renderOrders(); route();
    await Promise.all([loadProducts(), (async () => { try { state.config = await api("/storefront/config"); } catch { /* Mock actions remain hidden if configuration is unavailable. */ } })(),
      (async () => { try { const user = await api("/auth/me"); if (epoch !== state.epoch) return; state.user = user; state.epoch++; renderAccount(); if (location.hash === "#orders") await loadOrders(); } catch (error) { if (epoch === state.epoch && error.status !== 401) notify(error.message); } })()]);
  }
  init();
})();
