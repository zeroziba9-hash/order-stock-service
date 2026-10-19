"use strict";

(() => {
  const $ = (selector) => document.querySelector(selector);
  const money = (value) => `${new Intl.NumberFormat("ko-KR").format(value)}원`;
  const date = (value) => new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
  const escape = (value) => String(value).replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[char]));
  const statuses = { CREATED: "결제 대기", PAID: "결제 완료", CANCELLED: "취소 완료", REFUNDED: "환불 완료", EXPIRED: "주문 만료" };
  const state = { user: null, products: [], orders: [], next: null, filter: "all", register: false, selected: null, config: {}, epoch: 0, ordersLoading: false };
  const pendingKeys = new Map();
  let tossEnabled=false,tossReady=true,tossStates=new Map();
  let authReady=false;
  let recovery = [], recoveryRequest = 0, recoveryBusy = false;
  document.addEventListener("error",event => {
    const photo = event.target;
    if (!(photo instanceof HTMLImageElement) || !photo.classList.contains("product-photo")) return;
    const visual = photo.closest(".product-image"); if (!visual) return;
    visual.setAttribute("role","img"); visual.setAttribute("aria-label",photo.alt+" 기본 일러스트");
    photo.remove(); const fallback = document.createElement("span"); fallback.className = "generic-art"; fallback.textContent = "✳"; visual.append(fallback);
    if (visual.closest("#product-art")) $("#product-image-caption").textContent = "이미지를 불러오지 못해 기본 일러스트를 표시해요.";
  },true);
  let toastTimer;
  let afterLogin;
  let ordersRequest = 0;
  let detailRequest = 0;
  const authCooldown = { login: 0, register: 0, password: 0 };
  let authCooldownTimer;
  let passwordCooldownTimer;
  let sessionsRequest = 0;
  let productsRequest = 0;
  let productPage = 0, productMore = false;
  let productQuery = "", productSort = "ID_ASC";
  let selectedProduct = null, productDetailRequest = 0;
  let orderFilters = {};
  let cartOwner = null, cart = [], cartPending = null, cartBusy = false, cartReady = false, cartRevision = 0, cartRequest = 0;

  class ApiError extends Error {
    constructor(message, status, code, retryAfter = 0) { super(message); this.status = status; this.code = code; this.retryAfter = retryAfter; }
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
      response = await fetch(path, { method, headers, credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(25000),
        body: form ? new URLSearchParams(form) : body === undefined ? undefined : JSON.stringify(body) });
    } catch {
      throw new ApiError(method === "GET" ? "서버에 연결할 수 없어요. 연결을 확인하고 다시 시도해 주세요."
        : path === "/auth/password" ? "변경 결과를 확인하지 못했어요. 로그아웃 후 새 비밀번호로 로그인을 확인해 주세요."
        : path === "/auth/sessions/logout-others" ? "종료 결과를 확인하지 못했어요. 접속 목록을 새로고침해 주세요."
        : "응답을 받지 못했어요. 같은 내용으로 다시 시도하면 중복 요청을 확인해요. 새 주문 전에는 내 주문도 확인해 주세요.", 0);
    }
    const data = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      const retryAfter = Number(response.headers.get("Retry-After"));
      throw new ApiError(data?.message || "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.", response.status, data?.code,
        Number.isFinite(retryAfter) && retryAfter > 0 ? Math.min(retryAfter, 3600) : 0);
    }
    if (response.status !== 204 && data === null) throw new ApiError("서버 응답을 확인하지 못했어요. 주문이라면 같은 요청으로 결과를 다시 확인하세요.",0);
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
      renderProfile();
      error.message = "로그인이 만료됐어요. 다시 로그인해 주세요.";
      if (target) notify(error.message);
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
    const owner = state.user?.userId || null;
    if (cartOwner !== owner) {
      tossStates=new Map();tossReady=true;
      cartOwner = owner; cart = []; cartPending = null; cartBusy = false; cartReady = false; cartRevision = 0; cartRequest++;
      if (owner) {
        cartPending = readPending("cart");
        loadCart();
      }
      recovery = []; recoveryBusy = false; recoveryRequest++; $("#recovery-status").textContent = ""; renderRecovery();
      if (owner) loadRecovery();
      renderCart();
    }
    $("#account").innerHTML = state.user
      ? `<span class="account-email" title="${escape(state.user.email)}">${escape(state.user.email)}</span><button class="button ghost small" id="logout-button">로그아웃</button>`
      : '<button class="button small" id="login-button">로그인 <span aria-hidden="true">↗</span></button>';
    $("#order-count").hidden = !state.user || !state.orders.length;
    $("#order-count").textContent = state.orders.length + (state.next ? "+" : "");
    $("#admin-nav").hidden = state.user?.role !== "ADMIN";
  }

  function productArt(product) {
    if (product.imageUrl) return `<img class="product-photo" src="${escape(product.imageUrl)}" alt="${escape(product.name)}" loading="lazy" decoding="async">`;
    if (product.name.includes("머그")) return '<div class="mug"><div class="mug-print">make<br>something<span>good.</span></div></div>';
    if (product.name.includes("스티커")) return '<div class="sticker-sheet"><div class="sheet-label">&lt;hello /&gt;</div><div class="sheet-label">✳</div><div class="sheet-label">just create.</div><div class="sheet-label">:)</div></div>';
    return '<span class="generic-art">✳</span>';
  }

  function renderProducts() {
    $("#product-count").textContent = String(state.products.length).padStart(2, "0") + (productMore ? "+" : "");
    $("#product-results").textContent = `${productQuery ? `“${productQuery}” 검색 · ` : ""}${state.filter === "available" ? "구매 가능한 상품 · " : ""}${state.products.length}개${productMore ? " 이상" : ""} 표시`;
    $("#reset-products").hidden = !productQuery && state.filter === "all" && productSort === "ID_ASC";
    const products = state.products.filter((product) => state.filter !== "available" || product.stock > 0);
    $("#products").innerHTML = products.length ? products.map((product) => {
      const type = product.name.includes("머그") ? "mugs" : product.name.includes("스티커") ? "stickers" : "generic";
      return `<article class="product-card"><a class="product-visual-link" href="#product/${product.id}" aria-label="${escape(product.name)} 상세 보기"><div class="product-image ${type} tone-${product.id % 4}" ${product.imageUrl ? '' : `role="img" aria-label="${escape(product.name)}을 표현한 일러스트"`}>${product.imageUrl ? '' : '<span class="product-badge">STOCKROOM EDITION</span>'}${productArt(product)}</div></a><div class="product-meta"><span class="product-category">${type === "mugs" ? "EVERYDAY OBJECTS" : type === "stickers" ? "LITTLE EXPRESSIONS" : "THE COLLECTION"}</span><span class="stock-tag ${product.stock === 0 ? "sold" : product.stock <= 5 ? "low" : ""}"><span class="live-dot"></span>${product.stock === 0 ? "품절" : `${escape(product.stock)}개 남음`}</span></div><h3><a href="#product/${product.id}">${escape(product.name)}</a></h3><div class="product-bottom"><p class="price">${escape(money(product.price))}</p><button class="buy-button" data-buy="${escape(product.id)}" ${product.stock === 0 ? "disabled" : ""}>${product.stock === 0 ? "품절" : '주문하기 <span aria-hidden="true">↗</span>'}</button></div><button class="button primary full" data-add-cart="${escape(product.id)}" ${product.stock === 0 ? "disabled" : ""}>장바구니 담기</button></article>`;
    }).join("") : productQuery || state.filter === "available" ? '<div class="empty"><strong>조건에 맞는 상품이 없어요.</strong><p>검색어나 구매 가능 필터를 바꿔보세요.</p><button class="button" data-reset-products>전체 상품 보기</button></div>' : '<p class="empty">지금은 등록된 상품이 없어요.<br>다음 컬렉션을 기다려 주세요.</p>';
    renderCart();
  }

  async function loadProducts(append = false) {
    if (typeof append !== "boolean") append = false;
    const requestId = ++productsRequest;
    const button = $("#refresh-products"); button.disabled = true;
    $("#products-more").disabled = true;
    const page = append ? productPage + 1 : 0;
    if (!append) { state.products = []; productMore = false; $("#products-more").hidden = true; $("#products").textContent = "상품을 불러오고 있어요."; }
    try {
      const result = await api(`/products/search?${new URLSearchParams({ q: productQuery, sort: productSort, available: String(state.filter === "available"), page })}`);
      if (requestId !== productsRequest) return;
      state.products = append ? [...state.products, ...result.products.filter(item => !state.products.some(old => old.id === item.id))] : result.products;
      productPage = page; productMore = result.hasMore; renderProducts(); $("#products-more").hidden = !productMore;
    }
    catch (error) {
      if (requestId !== productsRequest) return;
      if (!state.products.length) $("#products").innerHTML = '<p class="empty">상품을 불러오지 못했어요.<br>재고 새로고침을 눌러 다시 시도해 주세요.</p>';
      handleError(error);
    } finally { if (requestId === productsRequest) { button.disabled = false; $("#products-more").disabled = false; } }
  }

  function renderOrders() {
    const list = $("#orders");
    if (!state.user) list.innerHTML = '<div class="empty"><strong>나만의 컬렉션을 확인하세요.</strong>로그인하면 다른 기기에서 주문한 내역도 볼 수 있어요.<br><button class="button primary" data-login>로그인하기 →</button></div>';
    else if (!state.orders.length && Object.keys(orderFilters).length) list.innerHTML = '<div class="empty"><strong>조건에 맞는 주문이 없어요.</strong><p>날짜나 주문 상태를 바꿔보세요.</p><button class="button" data-reset-orders>전체 주문 보기</button></div>';
    else if (!state.orders.length) list.innerHTML = '<div class="empty"><strong>아직 담아 둔 취향이 없네요.</strong>첫 번째 컬렉션을 둘러보고 마음에 드는 상품을 골라보세요.<br><a class="button primary" href="#shop">상품 둘러보기 ↗</a></div>';
    else list.innerHTML = state.orders.map((order) => `<article class="order-card"><div class="order-top"><div><span class="order-number">ORDER #${escape(order.orderId)}</span><time class="order-date" datetime="${escape(order.createdAt)}">${escape(date(order.createdAt))}</time></div>${badge(order)}</div><div class="order-items">${order.items.map((item) => `${escape(item.productName)} <small>· ${escape(item.quantity)}개</small>`).join("<br>")}</div><div class="order-bottom"><strong class="price">${escape(money(order.totalAmount))}</strong><div class="order-actions"><button class="button ghost small" data-detail="${escape(order.orderId)}">상세·이력 보기 ↗</button>${order.status === "CREATED" ? `<button class="button small" data-cancel="${escape(order.orderId)}" ${!tossReady || tossStates.get(order.orderId)?.pending ? "disabled" : ""}>주문 취소</button>` : ""}</div></div></article>`).join("");
    $("#load-more").hidden = !state.user || !state.next;
    renderAccount();
  }

  async function loadOrders(append = false) {
    if (!state.user || (append && state.ordersLoading)) { if (!state.user) renderOrders(); return; }
    const epoch = state.epoch; const requestId = ++ordersRequest; state.ordersLoading = true;
    $("#refresh-orders").disabled = true; $("#load-more").disabled = true;
    try {
      const parameters = new URLSearchParams(orderFilters);
      if (append && state.next) parameters.set("beforeId",state.next);
      const page = await api(`/orders${parameters.size ? `?${parameters}` : ""}`);
      if (epoch !== state.epoch || requestId !== ordersRequest) return;
      tossReady=false;renderOrders();
      const ids=page.orders.map(order=>order.orderId);
      const gateway=ids.length?await api(`/payments/toss/states?ids=${ids.join(",")}`):[];
      if (epoch !== state.epoch || requestId !== ordersRequest) return;
      if(!append)tossStates=new Map();for(const payment of gateway)tossStates.set(payment.orderId,payment);tossReady=true;
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

  function route(event) {
    const target = { "#orders": "#orders-title", "#cart": "#cart-title", "#account": "#account-title", "#collection": "#collection-title" }[location.hash];
    for (const id of ["#purchase-dialog","#detail-dialog"]) if ($(id).open && !$(id).dataset.busy) $(id).close();
    const orders = location.hash === "#orders";
    const account = location.hash === "#account";
    const basket = location.hash === "#cart";
    const product = location.hash.startsWith("#product/");
    $("#shop-view").hidden = orders || account || basket || product; $("#orders-view").hidden = !orders; $("#account-view").hidden = !account; $("#cart-view").hidden = !basket; $("#product-view").hidden = !product;
    for (const [id, active] of [["#shop-nav", !orders && !account && !basket], ["#orders-nav", orders], ["#account-nav", account], ["#cart-nav", basket]]) {
      if (active) $(id).setAttribute("aria-current", "page"); else $(id).removeAttribute("aria-current");
    }
    $("#change-password-form").reset();
    if (orders) { loadOrders(); if (state.user) loadRecovery(); }
    if (account) { renderProfile(); loadSessions(); }
    if (basket) { renderCart(); if (state.user) loadCart(); }
    if (product) loadProductDetail(); else { productDetailRequest++; selectedProduct = null; }
    document.title = `${orders ? "내 주문" : account ? "내 계정" : basket ? "장바구니" : product ? "상품 상세" : "작은 취향을 담는 곳"} — STOCKROOM`;
    if (event && !document.querySelector("dialog[open]")) {
      const heading = target ? $(target) : !product ? $("#shop-view h1") : null;
      if (heading) {
        heading.tabIndex = -1;
        heading.focus({ preventScroll: true });
        // A fragment target can be hidden when navigating back from another view.
        if (location.hash === "#collection") $("#collection").scrollIntoView({ block: "start" });
        else window.scrollTo(0, 0);
      }
    }
  }

  async function loadProductDetail() {
    const routeHash = location.hash, request = ++productDetailRequest;
    const raw = routeHash.slice("#product/".length), id = Number(raw);
    selectedProduct = null; $("#product-content").hidden = true; $("#product-error").hidden = true; $("#product-loading").hidden = false;
    try {
      if (!/^[1-9]\d*$/.test(raw) || !Number.isSafeInteger(id)) throw new Error("상품 주소를 확인해 주세요.");
      const product = await api(`/products/${id}`);
      if (request !== productDetailRequest || location.hash !== routeHash) return;
      selectedProduct = product;
      $("#product-title").textContent = product.name; $("#product-price").textContent = money(product.price);
      $("#product-stock").textContent = product.stock > 0 ? `현재 ${product.stock}개 남아 있어요` : "현재 품절이에요";
      const type = product.name.includes("머그") ? "mugs" : product.name.includes("스티커") ? "stickers" : "generic";
      $("#product-art").innerHTML = `<div class="product-image ${type} tone-${product.id % 4}" ${product.imageUrl ? '' : `role="img" aria-label="${escape(product.name)}을 표현한 일러스트"`}>${productArt(product)}</div>`;
      $("#product-image-caption").textContent = product.imageUrl ? "등록된 상품 이미지예요." : "상품을 표현한 일러스트예요.";
      for (const field of ["description","specification"]) {
        $("#product-"+field).textContent = product[field]; $("#product-"+field+"-section").hidden = !product[field];
      }
      $("#product-quantity").value = 1; $("#product-quantity").max = Math.max(1,Math.min(product.stock,2147483647));
      $("#product-quantity").disabled = product.stock < 1; $("#product-action-status").textContent = "";
      $("#product-content").hidden = false; updateProductSubtotal(); syncProductActions();
      // A detail hash has no native anchor: do not inherit scroll from a lower catalog row.
      document.title = `${product.name} — STOCKROOM`;
      if (!document.querySelector("dialog[open]")) { $("#product-title").focus({ preventScroll: true }); window.scrollTo(0,0); }
    } catch (error) {
      if (request !== productDetailRequest || location.hash !== routeHash) return;
      $("#product-error-message").textContent = error.message; $("#product-error").hidden = false; if (!document.querySelector("dialog[open]")) $("#product-error").focus();
    } finally { if (request === productDetailRequest && location.hash === routeHash) $("#product-loading").hidden = true; }
  }
  function updateProductSubtotal() {
    const quantity = Number($("#product-quantity").value);
    $("#product-subtotal").textContent = selectedProduct && Number.isInteger(quantity) && quantity > 0 ? money(BigInt(selectedProduct.price)*BigInt(quantity)) : "—";
  }
  function syncProductActions() {
    $("#product-add").disabled = !authReady || !selectedProduct || selectedProduct.stock < 1 || Boolean(state.user && (cartBusy || cartPending || !cartReady));
    $("#product-buy").disabled = !authReady || !selectedProduct || selectedProduct.stock < 1;
  }
  $("#product-retry").addEventListener("click",loadProductDetail);
  $("#product-quantity").addEventListener("input",updateProductSubtotal);
  $("#product-purchase").addEventListener("submit",async event => {
    event.preventDefault(); if (!selectedProduct || !$("#product-quantity").checkValidity()) return;
    const id = selectedProduct.id, quantity = Number($("#product-quantity").value);
    await addToCart(id,quantity);
  });
  $("#product-buy").addEventListener("click",() => {
    if (selectedProduct && $("#product-quantity").reportValidity()) purchase(selectedProduct.id,Number($("#product-quantity").value),selectedProduct);
  });

  function renderRecovery() {
    const panel = $("#checkout-recovery"); panel.hidden = !state.user || (!recovery.length && !$("#recovery-status").textContent);
    $("#recovery-items").replaceChildren();
    for (const intent of recovery) {
      const card = document.createElement("article"); card.className = "recovery-card";
      const heading = document.createElement("h3"); heading.textContent = intent.orderId ? `완료된 주문 #${intent.orderId}` : "확인할 주문 요청";
      const summary = document.createElement("p"); summary.textContent = intent.items.map(item => `${item.name} ${item.quantity}개`).join(" · ");
      const status = document.createElement("p"); status.className = "field-help";
      status.textContent = intent.orderId ? "이미 완료된 주문이에요. 주문 내역에서 결과를 확인하세요." : `총 ${money(intent.items.reduce((sum,item) => sum + BigInt(item.price)*BigInt(item.quantity),0n))} · 확인 기한 ${date(intent.expiresAt)}`;
      const actions = document.createElement("div"); actions.className = "recovery-actions";
      const resume = document.createElement("button"); resume.type = "button"; resume.className = "button small"; resume.disabled = recoveryBusy || cartBusy;
      resume.textContent = intent.orderId ? "주문 보기" : "같은 요청으로 확인";
      resume.addEventListener("click",() => intent.orderId ? detail(intent.orderId) : resumeCheckout(intent));
      const close = document.createElement("button"); close.type = "button"; close.className = "button ghost small"; close.disabled = recoveryBusy || cartBusy;
      close.textContent = intent.orderId ? "확인 완료" : "이 요청 종료"; close.addEventListener("click",() => closeCheckout(intent));
      actions.append(resume,close); card.append(heading,summary,status,actions); $("#recovery-items").append(card);
    }
  }
  async function loadRecovery() {
    if (!state.user) return;
    const epoch = state.epoch, request = ++recoveryRequest;
    try { const rows = await api("/orders/cart/recovery"); if (epoch !== state.epoch || request !== recoveryRequest) return; recovery = rows; $("#recovery-status").textContent = ""; renderRecovery(); }
    catch(error) { if (epoch === state.epoch && request === recoveryRequest) { handleError(error,"#recovery-status"); renderRecovery(); } }
  }
  async function acknowledgeCheckout(quoteId) {
    if (!quoteId) return;
    try { await api(`/orders/cart/quote/${quoteId}/acknowledge`, { method: "POST" }); }
    catch { /* A known order remains visible in server recovery until acknowledged. */ }
    await loadRecovery();
  }
  async function closeCheckout(intent) {
    if (!state.user || recoveryBusy || cartBusy) return;
    const epoch = state.epoch; recoveryBusy = true; renderRecovery();
    try {
      if (!intent.orderId && !await confirmAction("이 주문 요청을 종료할까요? 이미 완료된 주문은 취소되지 않아요. 처리 중인 요청의 결과는 내 주문에서 확인하세요.")) return;
      if (epoch !== state.epoch) return;
      await api(`/orders/cart/quote/${intent.quoteId}/acknowledge`, { method: "POST" });
      if (epoch !== state.epoch) return;
      for (const kind of ["cart","direct"]) if (readPending(kind)?.body?.quoteId === intent.quoteId) forgetPending(kind);
      await Promise.all([loadRecovery(),loadOrders()]); renderCart();
    } catch(error) { if (epoch === state.epoch) handleError(error,"#recovery-status"); }
    finally { if (epoch === state.epoch) { recoveryBusy = false; renderRecovery(); } }
  }
  async function resumeCheckout(intent) {
    if (!state.user || recoveryBusy || cartBusy) return;
    const epoch = state.epoch; recoveryBusy = true; cartBusy = true; renderRecovery(); renderCart();
    try {
      if (!await confirmAction(`저장된 주문을 같은 요청 키로 확인할까요?\n\n${intent.items.map(item => `${item.name} · ${money(item.price)} × ${item.quantity}개`).join("\n")}\n\n이미 완료된 요청은 기존 주문을 보여줘요. 미완료 요청은 확인한 조건이 유효할 때 주문해요.`) || epoch !== state.epoch) return;
      const order = await api("/orders/cart/checkout",{method:"POST",body:{quoteId:intent.quoteId},key:intent.key});
      if (epoch !== state.epoch) return;
      if (intent.kind === "cart" && intent.cartRevision !== null) {
        try { const cleared = await api("/cart",{method:"PUT",body:{revision:intent.cartRevision,items:[]}}); if (epoch !== state.epoch) return; cart = cleared.items; cartRevision = cleared.revision; }
        catch { if (epoch !== state.epoch) return; cartReady = false; notify("주문은 완료됐어요. 장바구니는 새로고침해 확인하세요."); }
      }
      await acknowledgeCheckout(intent.quoteId);
      if (epoch !== state.epoch) return;
      for (const kind of ["cart","direct"]) if (readPending(kind)?.body?.quoteId === intent.quoteId) forgetPending(kind);
      await Promise.all([loadProducts(),loadOrders()]); location.hash = "orders"; notify(`주문 #${order.orderId}의 결과를 확인했어요.`);
    } catch(error) {
      if (epoch !== state.epoch) return;
      await loadRecovery();
      if (epoch !== state.epoch) return;
      handleError(error,"#recovery-status"); renderRecovery();
    } finally { if (epoch === state.epoch) { recoveryBusy = false; cartBusy = false; renderRecovery(); renderCart(); } }
  }
  function pendingSlot(kind) { return `stockroom-${kind}-pending:${cartOwner}`; }
  function readPending(kind) {
    try {
      const pending = JSON.parse(sessionStorage.getItem(pendingSlot(kind)) || "null");
      if (pending && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(pending.key)
        && (typeof pending.body?.quoteId === "string" || Array.isArray(pending.body?.items))) return pending;
    } catch { /* Checkout must save a pending request before sending it. */ }
    return null;
  }
  async function loadCart() {
    if (!state.user || cartBusy) return;
    const epoch = state.epoch, request = ++cartRequest;
    cartReady = false; renderCart();
    try {
      let result = await api("/cart");
      if (epoch !== state.epoch || request !== cartRequest) return;
      // Import the previous tab-local basket once, only into an untouched empty server basket.
      let legacy;
      try { legacy = JSON.parse(sessionStorage.getItem(`stockroom-cart:${cartOwner}`) || "[]"); } catch { legacy = []; }
      if (!result.revision && !result.items.length && Array.isArray(legacy) && legacy.length && legacy.length <= 20
          && legacy.every(item => Number.isSafeInteger(item.id) && item.id > 0 && Number.isInteger(item.quantity) && item.quantity > 0)) {
        result = await api("/cart", { method: "PUT", body: { revision: 0, items: legacy.map(item => ({ productId: item.id, quantity: item.quantity })) } });
        if (epoch !== state.epoch || request !== cartRequest) return;
      }
      cart = result.items; cartRevision = result.revision; cartReady = true;
      try { sessionStorage.removeItem(`stockroom-cart:${cartOwner}`); } catch { /* Server basket is authoritative. */ }
    } catch (error) { if (epoch === state.epoch && request === cartRequest) handleError(error,"#cart-error"); }
    finally { if (epoch === state.epoch && request === cartRequest) renderCart(); }
  }
  async function saveCart(items) {
    if (!state.user || !cartReady || cartBusy || cartPending) return;
    const epoch = state.epoch; cartBusy = true; cartRequest++; renderCart(); $("#cart-error").textContent = "";
    try {
      const result = await api("/cart", { method: "PUT", body: { revision: cartRevision, items: items.map(item => ({ productId: item.id, quantity: item.quantity })) } });
      if (epoch !== state.epoch) return;
      cart = result.items; cartRevision = result.revision; return true;
    } catch (error) {
      if (epoch !== state.epoch) return;
      handleError(error,"#cart-error");
      // Reload after a conflict or ambiguous save; never silently overwrite another device.
      cartReady = false;
    } finally { if (epoch === state.epoch) { cartBusy = false; renderCart(); if (!cartReady) await loadCart(); } }
  }
  function renderCart() {
    $("#cart-count").textContent = cart.length; $("#cart-count").hidden = !cart.length;
    $("#cart-pending").hidden = !cartPending;
    const frozen = cartPending || cartBusy || !cartReady;
    $("#cart-items").innerHTML = !state.user ? '<p class="empty">로그인하고 장바구니를 이용하세요.<br><button class="button" data-login>로그인하기 →</button></p>' : !cartReady ? '<p class="empty">장바구니를 불러오는 중이에요. 연결을 확인한 뒤 새로고침해 주세요.</p>' : !cart.length ? '<p class="empty">장바구니가 비어 있어요. 컬렉션에서 상품을 담아보세요.</p>' : cart.map(item => `<article class="cart-row"><div><strong>${escape(item.name)}</strong><p>${escape(money(item.price))} / 개${!item.selling ? " · 판매 중지" : item.stock < item.quantity ? " · 재고 부족" : ""}</p></div><div><label for="cart-quantity-${item.id}">수량</label><input id="cart-quantity-${item.id}" data-cart-quantity="${item.id}" type="number" min="1" max="2147483647" step="1" value="${item.quantity}" ${frozen ? "disabled" : ""}><button class="button small" data-cart-remove="${item.id}" ${frozen ? "disabled" : ""}>빼기</button></div></article>`).join("");
    let total = 0n; for (const item of cart) total += BigInt(item.price) * BigInt(item.quantity);
    $("#cart-total").textContent = new Intl.NumberFormat("ko-KR").format(total) + "원";
    $("#cart-checkout").disabled = !state.user || (!cart.length && !cartPending) || cartBusy || (!cartReady && !cartPending);
    $("#cart-checkout").textContent = cartPending ? "같은 주문 결과 다시 확인 →" : "가격 확인하고 함께 주문 →";
    $("#refresh-cart").disabled = !state.user || cartBusy; syncProductActions(); renderRecovery();
    document.querySelectorAll("[data-add-cart]").forEach(button => { button.disabled = !authReady || Boolean(state.user && frozen) || state.products.find(item => item.id === Number(button.dataset.addCart))?.stock === 0; });
    document.querySelectorAll("[data-buy]").forEach(button => { button.disabled = !authReady || state.products.find(item => item.id === Number(button.dataset.buy))?.stock === 0; });
  }
  async function addToCart(id,quantity = 1) {
    if (!authReady) return;
    if (!state.user) return showAuth(() => addToCart(id,quantity));
    const epoch = state.epoch;
    if (!cartReady && !cartBusy && !cartPending) await loadCart();
    if (epoch !== state.epoch) return;
    if (cartPending || cartBusy || !cartReady) return notify("장바구니를 불러오거나 이전 주문의 결과를 먼저 확인하세요.");
    const product = selectedProduct?.id === id ? selectedProduct : state.products.find(item => item.id === id); if (!product || product.stock < 1) return;
    const items = cart.map(item => ({ ...item })), existing = items.find(item => item.id === id);
    if (existing) existing.quantity = Math.min(existing.quantity + quantity,2147483647);
    else { if (items.length >= 20) return notify("상품은 최대 20종까지 담을 수 있어요."); items.push({ id, quantity }); }
    if (await saveCart(items) && epoch === state.epoch) { notify("장바구니에 담았어요."); if (selectedProduct?.id === id) $("#product-action-status").textContent = `${quantity}개를 장바구니에 담았어요.`; }
  }
  $("#refresh-recovery").addEventListener("click",loadRecovery);
  $("#refresh-cart").addEventListener("click",loadCart);
  $("#cart-items").addEventListener("change",event => {
    const input=event.target.closest("[data-cart-quantity]"); if (!input || cartPending || cartBusy) return;
    const value=Number(input.value);
    if (!Number.isInteger(value) || value<1 || value>2147483647) return renderCart();
    saveCart(cart.map(item => ({ ...item, quantity: item.id === Number(input.dataset.cartQuantity) ? value : item.quantity })));
  });
  async function prepareCheckout(kind,items) {
    const epoch = state.epoch, existing = readPending(kind);
    if (existing) return existing;
    const quote = await api("/orders/cart/quote", { method: "POST", body: { items: items.map(item => ({ productId: item.id, quantity: item.quantity })) } });
    if (epoch !== state.epoch) return null;
    if (!quote.canOrder) throw new Error(quote.items.filter(item => !item.selling || item.stock < item.quantity)
      .map(item => `${item.name}: ${!item.selling ? "판매 중지" : `재고 ${item.stock}개로 부족해요`}`).join(" / "));
    const changed = quote.items.some(item => { const before = items.find(old => old.id === item.productId); return before.price !== undefined && (before.price !== item.price || before.name !== item.name); });
    const message = `${changed ? "상품 정보가 바뀌었어요. 아래 최신 가격을 확인하세요.\n\n" : "주문 내용을 확인하세요.\n\n"}${quote.items.map(item => `${item.name} · ${money(item.price)} × ${item.quantity}개`).join("\n")}\n\n총 ${money(BigInt(quote.totalAmount))}\n10분 안에 확인한 조건으로 주문해요. 재고는 주문 완료 시 확보돼요.`;
    if (!await confirmAction(message) || epoch !== state.epoch) return null;
    const body = { quoteId: quote.quoteId }, request = requestKey(`checkout-${kind}`,body);
    const pending = { body, key: request.key, items: quote.items.map(item => ({ id: item.productId, name: item.name, price: item.price, quantity: item.quantity })), cartRevision: kind === "cart" ? cartRevision : null };
    try { sessionStorage.setItem(pendingSlot(kind),JSON.stringify(pending)); }
    catch { throw new Error("요청 정보를 저장할 수 없어 주문을 보내지 않았어요. 브라우저 저장소를 확인하세요."); }
    const saved = await api(`/orders/cart/quote/${quote.quoteId}/intent`, { method: "PUT", body: { key: pending.key, kind, cartRevision: pending.cartRevision } });
    if (epoch !== state.epoch) return null;
    pending.key = saved.key; pending.cartRevision = saved.cartRevision;
    sessionStorage.setItem(pendingSlot(kind),JSON.stringify(pending));
    return pending;
  }
  function forgetPending(kind) {
    const pending = readPending(kind);
    if (pending) requestKey(`checkout-${kind}`,pending.body).done();
    sessionStorage.removeItem(pendingSlot(kind));
    if (kind === "cart") cartPending = null;
  }
  function definitiveRejection(error) {
    // Authentication/CSRF/rate-limit/lock failures do not prove that an earlier ambiguous attempt failed.
    return error.status >= 400 && error.status < 500 && ![401,403,429].includes(error.status) && error.code !== "ORDER_CONFLICT";
  }
  $("#cart-checkout").addEventListener("click",async () => {
    if (!state.user || cartBusy) return;
    const epoch=state.epoch; cartBusy=true; cartRequest++; renderCart(); $("#cart-error").textContent="";
    try {
      cartPending ||= await prepareCheckout("cart",cart);
      if (!cartPending || epoch !== state.epoch) return;
      // Pending V17 requests retain their original endpoint, body and key across an upgrade.
      const order=await api(cartPending.body.quoteId ? "/orders/cart/checkout" : "/orders/cart",{method:"POST",body:cartPending.body,key:cartPending.key});
      if(epoch!==state.epoch)return;
      const revision=cartPending.cartRevision ?? cartRevision;
      try {
        const cleared=await api("/cart",{method:"PUT",body:{revision,items:[]}});
        if(epoch!==state.epoch)return;
        cart=cleared.items;cartRevision=cleared.revision;
      } catch {
        if(epoch!==state.epoch)return;
        notify("주문은 완료됐어요. 다른 기기의 변경이나 연결 상태 때문에 장바구니를 비우지 못했어요. 새로고침해 확인하세요.");
        cartReady=false;
      }
      await acknowledgeCheckout(cartPending.body.quoteId);
      if (epoch !== state.epoch) return;
      forgetPending("cart");
      notify(`주문 #${order.orderId}이 완료됐어요.${!cartReady ? " 장바구니는 새로고침해 확인하세요." : ""}`);
      await Promise.all([loadProducts(),loadOrders()]); location.hash="orders";
    } catch(error) {
      if(epoch!==state.epoch)return;
      if(definitiveRejection(error) && cartPending)forgetPending("cart");
      handleError(error,"#cart-error");
    } finally {if(epoch===state.epoch){cartBusy=false;renderCart();if(!cartReady)loadCart();}}
  });
  $("#product-search").addEventListener("submit",event=>{event.preventDefault();productQuery=$("#product-query").value.trim();productSort=$("#product-sort").value;loadProducts();});
  $("#products-more").addEventListener("click",()=>loadProducts(true));
  $("#order-search").addEventListener("submit",event=>{
    event.preventDefault();orderFilters={};
    for(const field of ["status","from","to"]){const value=$("#order-"+field).value;if(value)orderFilters[field]=value;}
    state.next=null;state.orders=[];loadOrders();
  });

  function renderProfile() {
    $("#account-gate").hidden = Boolean(state.user); $("#account-content").hidden = !state.user;
    $("#profile-email").textContent = state.user?.email || "";
    $("#login-sessions").replaceChildren(); $("#sessions-error").textContent = "";
    $("#change-password-error").textContent = ""; $("#change-password-form").reset();
    $("#logout-others").disabled = true;
  }

  async function loadSessions() {
    if (!state.user) return;
    const epoch = state.epoch; const requestId = ++sessionsRequest;
    $("#refresh-sessions").disabled = true; $("#logout-others").disabled = true;
    $("#sessions-error").textContent = ""; $("#login-sessions").textContent = "접속을 확인하고 있어요.";
    try {
      const result = await api("/auth/sessions");
      if (epoch !== state.epoch || requestId !== sessionsRequest) return;
      $("#login-sessions").innerHTML = result.sessions.map((session) => `<article class="session-row"><strong>${session.current ? "현재 접속" : "다른 접속"}</strong><p>접속 시작 · <time datetime="${escape(session.createdAt)}">${escape(date(session.createdAt))}</time><br>최근 활동 · <time datetime="${escape(session.lastAccessedAt)}">${escape(date(session.lastAccessedAt))}</time></p></article>`).join("") || '<p class="field-help">표시할 접속이 없어요. 새로고침해 주세요.</p>';
      if (result.truncated) $("#login-sessions").insertAdjacentHTML("beforeend", '<p class="field-help">최근 접속 100개까지 표시해요. 다른 기기 로그아웃은 표시되지 않은 접속에도 적용돼요.</p>');
      $("#logout-others").disabled = !result.truncated && !result.sessions.some((session) => !session.current);
    } catch (error) {
      if (epoch !== state.epoch || requestId !== sessionsRequest) return;
      $("#login-sessions").replaceChildren(); handleError(error, "#sessions-error");
    } finally { if (requestId === sessionsRequest) $("#refresh-sessions").disabled = false; }
  }

  function renderPasswordCooldown() {
    clearTimeout(passwordCooldownTimer);
    const seconds = Math.max(0, Math.ceil((authCooldown.password - performance.now()) / 1000));
    $("#change-password-submit").disabled = seconds > 0 || Boolean($("#change-password-form").dataset.busy);
    $("#change-password-submit").textContent = seconds > 0 ? `${seconds}초 후 다시 시도` : "비밀번호 변경";
    if (seconds > 0) passwordCooldownTimer = setTimeout(renderPasswordCooldown, 250);
  }

  $("#refresh-sessions").addEventListener("click", loadSessions);
  $("#logout-others").addEventListener("click", async () => {
    const epoch = state.epoch;
    let actionError;
    const button = $("#logout-others"); button.disabled = true;
    try {
      if (!await confirmAction("다른 기기의 접속을 모두 종료할까요? 이 브라우저는 로그인 상태를 유지해요.")) return;
      if (epoch !== state.epoch || !state.user) return;
      await api("/auth/sessions/logout-others", { method: "POST" }); if (epoch === state.epoch) notify("다른 기기의 접속을 종료했어요.");
    } catch (error) { actionError = error; }
    finally {
      await loadSessions();
      if (actionError && epoch === state.epoch) handleError(actionError, "#sessions-error");
    }
  });

  $("#change-password-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const form = event.currentTarget;
    if (form.dataset.busy || authCooldown.password > performance.now()) return;
    $("#change-password-error").textContent = "";
    if ($("#new-password").value !== $("#confirm-password").value) { $("#change-password-error").textContent = "새 비밀번호 확인이 일치하지 않아요."; return; }
    if (new TextEncoder().encode($("#new-password").value).length > 72 || new TextEncoder().encode($("#current-password").value).length > 72) {
      $("#change-password-error").textContent = "비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요."; return;
    }
    const epoch = state.epoch; form.dataset.busy = "true";
    const controls = [...form.querySelectorAll("button, input")]; controls.forEach((control) => { control.disabled = true; });
    try {
      if (!await confirmAction("비밀번호를 변경하면 모든 접속이 종료돼요. 변경할까요?")) return;
      if (epoch !== state.epoch || !state.user) return;
      await api("/auth/password", { method: "POST", body: { currentPassword: $("#current-password").value, newPassword: $("#new-password").value } });
      if (epoch !== state.epoch) return;
      state.user = null; state.epoch++; state.orders = []; state.next = null;
      renderAccount(); renderOrders(); renderProfile(); notify("비밀번호를 변경했어요. 새 비밀번호로 다시 로그인해 주세요.");
    } catch (error) {
      if (epoch !== state.epoch) return;
      if (error.status === 429) authCooldown.password = performance.now() + (error.retryAfter || 1) * 1000;
      handleError(error, "#change-password-error");
    } finally { form.reset(); delete form.dataset.busy; controls.forEach((control) => { control.disabled = false; }); renderPasswordCooldown(); }
  });

  function showAuth(callback) {
    afterLogin = callback; state.register = false; renderAuth(); $("#auth-form").reset();
    StockroomFocus.open($("#auth-dialog"), "#email");
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
    renderAuthCooldown();
  }

  function renderAuthCooldown() {
    clearTimeout(authCooldownTimer);
    const seconds = Math.max(0, Math.ceil((authCooldown[state.register ? "register" : "login"] - performance.now()) / 1000));
    const button = $("#auth-submit");
    button.disabled = seconds > 0 || Boolean($("#auth-dialog").dataset.busy);
    button.textContent = seconds > 0 ? `${seconds}초 후 다시 시도` : state.register ? "회원가입 →" : "로그인 →";
    if (seconds > 0) authCooldownTimer = setTimeout(renderAuthCooldown, 250);
  }

  function purchase(productId,initialQuantity = 1,source = null) {
    if (!authReady) return;
    if (!state.user) return showAuth(() => purchase(productId,initialQuantity,source));
    const waiting = readPending("direct"), saved = waiting?.items?.[0];
    const product = saved ? { ...saved, stock: saved.quantity } : source || state.products.find((item) => item.id === productId);
    if (!product || product.stock < 1) return notify("품절된 상품이에요. 재고를 새로고침해 주세요.");
    state.selected = product; $("#purchase-title").textContent = product.name;
    $("#purchase-stock").textContent = `현재 ${product.stock}개 남아 있어요. · ${money(product.price)}`;
    $("#quantity").value = saved?.quantity || initialQuantity; $("#quantity").max = Math.min(product.stock, 2147483647);
    for (const selector of ["#quantity","#quantity-minus","#quantity-plus"]) $(selector).disabled = Boolean(waiting);
    $("#purchase-error").textContent = readPending("direct") ? "이전 주문 결과를 먼저 확인해요. 같은 요청으로 다시 확인하면 중복 주문되지 않아요." : "";
    updateTotal(); StockroomFocus.open($("#purchase-dialog"), "#quantity");
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
    if (!$("#detail-dialog").open) StockroomFocus.open($("#detail-dialog"));
    try {
      const [order, payments, refunds, toss] = await Promise.all([api(`/orders/${orderId}`), api(`/orders/${orderId}/payments`), api(`/orders/${orderId}/refunds`), api(`/orders/${orderId}/toss`)]);
      if (epoch !== state.epoch || requestId !== detailRequest || !$("#detail-dialog").open) return;
      const mockPay = !tossEnabled && ["NONE","FAILED","CANCELED"].includes(toss.status) && state.config.mockPayments && order.status === "CREATED" && Date.now() < Date.parse(order.expiresAt);
      const mockRefund = ["NONE","FAILED"].includes(toss.status) && state.config.mockRefunds && order.status === "PAID";
      $("#order-detail").innerHTML = `<div class="detail-summary">${badge(order)}<time datetime="${escape(order.createdAt)}">${escape(date(order.createdAt))}</time></div>${order.items.map((item) => `<div class="detail-item"><span>${escape(item.productName)}<br>${escape(money(item.unitPrice))} × ${escape(item.quantity)}</span><strong>${escape(money(item.unitPrice * item.quantity))}</strong></div>`).join("")}<div class="total-row"><span>총 주문 금액</span><strong>${escape(money(order.totalAmount))}</strong></div>${deadlineNotice(order)}${order.cancelledAt ? `<p class="field-help">${order.status === "REFUNDED" ? "환불 완료" : order.status === "EXPIRED" ? "주문 만료" : "취소 완료"} · ${escape(date(order.cancelledAt))}</p>` : ""}${toss.status!=="NONE"?`<section class="gateway-status" aria-label="카드 테스트 결제 상태"><p role="status"><strong>${escape(StockroomToss.label(toss))}</strong></p>${toss.pending?'<p class="field-help">결과를 확인할 때까지 취소와 자동 만료가 보류돼요. 새 결제를 시작하지 말고 결과를 다시 확인하세요.</p>':""}${toss.reviewRequired?'<p class="field-help">결제 정보 확인이 필요해요. 자동 재승인 없이 결제사의 결과를 조회해요.</p>':""}</section>`:""}${tossEnabled?`<p class="demo-note">실제 금액이 청구되지 않는 카드 테스트 결제예요.</p><div class="detail-actions">${order.status==="CREATED"&&!toss.pending&&Date.now()<Date.parse(order.expiresAt)?`<button class="button primary small" data-toss-pay="${orderId}">테스트 카드 결제</button>`:""}${toss.status==="PAID"&&!toss.reviewRequired?`<button class="button primary small" data-toss-refund="${escape(toss.checkoutId)}" data-order="${orderId}">테스트 전액 환불</button>`:""}${toss.pending||toss.status==="PAID"?`<button class="button small" data-toss-check="${escape(toss.checkoutId)}" data-order="${orderId}">결제 결과 다시 확인</button>`:""}</div>`:""}${history("결제 이력", payments)}${history("환불 이력", refunds)}${mockPay || mockRefund ? '<p class="demo-note">로컬 체험용 모의 결제·환불이에요. 실제 금액은 청구되지 않아요.</p>' : ""}<div class="detail-actions">${order.status === "CREATED" && !toss.pending ? `<button class="button small" data-cancel="${orderId}">주문 취소</button>` : ""}${mockPay ? `<button class="button primary small" data-mock="payments" data-id="${orderId}">모의 결제</button>` : ""}${mockRefund ? `<button class="button primary small" data-mock="refunds" data-id="${orderId}">모의 전액 환불</button>` : ""}</div>`;
    } catch (error) { if (epoch === state.epoch && requestId === detailRequest) handleError(error, "#detail-error"); }
  }

  function confirmAction(message) {
    const dialog = $("#confirm-dialog");
    $("#confirm-message").textContent = message; dialog.returnValue = "no";
    return new Promise((resolve) => {
      dialog.addEventListener("close", () => resolve(dialog.returnValue === "yes"), { once: true });
      StockroomFocus.open(dialog);
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

  async function tossAction(button) {
    if(button.dataset.tossRefund && !await confirmAction("카드 테스트 결제를 전액 환불할까요? 결과가 확정되면 재고가 복구돼요."))return;
    const epoch=state.epoch;button.disabled=true;$("#detail-error").textContent="";
    try {
      if(button.dataset.tossPay)await StockroomToss.pay(Number(button.dataset.tossPay));
      else await StockroomToss.api(`/payments/toss/${button.dataset.tossRefund||button.dataset.tossCheck}/${button.dataset.tossRefund?"refund":"reconcile"}`,{method:"POST",body:{}});
      if(epoch!==state.epoch)return;
      await Promise.all([loadProducts(),loadOrders()]);
      if($("#detail-dialog").open)await detail(Number(button.dataset.order||button.dataset.tossPay));
    }catch(error){if(epoch===state.epoch)handleError(error,"#detail-error");}
    finally{button.disabled=false;}
  }

  document.addEventListener("click", (event) => {
    const button = event.target.closest("button"); if (!button || button.disabled) return;
    if (button.matches("[data-close]")) button.closest("dialog").close();
    else if (button.id === "login-button" || button.matches("[data-login]")) showAuth();
    else if (button.matches("[data-reset-products]")) {
      productQuery = ""; productSort = "ID_ASC"; state.filter = "all"; $("#product-query").value = ""; $("#product-sort").value = "ID_ASC";
      document.querySelectorAll("[data-filter]").forEach(filter => { const active = filter.dataset.filter === "all"; filter.classList.toggle("active",active); filter.setAttribute("aria-pressed",String(active)); });
      loadProducts();
    }
    else if (button.matches("[data-reset-orders]")) { orderFilters = {}; $("#order-search").reset(); loadOrders(); }
    else if (button.matches("[data-buy]")) purchase(Number(button.dataset.buy));
    else if (button.matches("[data-add-cart]")) addToCart(Number(button.dataset.addCart));
    else if (button.matches("[data-cart-remove]") && !cartPending && !cartBusy) {saveCart(cart.filter(item=>item.id!==Number(button.dataset.cartRemove)));}
    else if (button.matches("[data-detail]")) detail(Number(button.dataset.detail));
    else if(button.matches("[data-toss-pay], [data-toss-refund], [data-toss-check]")) tossAction(button);
    else if (button.matches("[data-cancel]")) changeOrder(button, "cancel", Number(button.dataset.cancel));
    else if (button.matches("[data-mock]")) changeOrder(button, button.dataset.mock, Number(button.dataset.id));
    else if (button.matches("[data-filter]")) {
      state.filter = button.dataset.filter;
      document.querySelectorAll("[data-filter]").forEach((filter) => { filter.classList.toggle("active", filter === button); filter.setAttribute("aria-pressed", String(filter === button)); });
      loadProducts();
    }
  });

  $("#auth-switch").addEventListener("click", () => { state.register = !state.register; renderAuth(); });
  $("#auth-form").addEventListener("submit", async (event) => {
    event.preventDefault(); const dialog = $("#auth-dialog");
    const endpoint = state.register ? "register" : "login";
    if (dialog.dataset.busy || authCooldown[endpoint] > performance.now()) return;
    const controls = [...dialog.querySelectorAll("button, input")]; controls.forEach((control) => { control.disabled = true; }); dialog.dataset.busy = "true";
    const account = { email: $("#email").value.trim(), password: $("#password").value };
    $("#auth-error").textContent = "";
    try {
      if (state.register) {
        if (new TextEncoder().encode(account.password).length > 72) throw new Error("비밀번호는 UTF-8 기준 72바이트 이하로 입력해 주세요.");
        await api("/auth/register", { method: "POST", body: account });
        state.register = false; renderAuth(); notify("회원가입이 완료됐어요. 입력한 정보로 로그인해 주세요."); return;
      }
      state.user = await api("/auth/login", { method: "POST", form: account }); authReady=true;state.epoch++;
      dialog.close(); $("#auth-form").reset(); renderAccount(); renderProfile(); await loadOrders(); if (location.hash === "#account") await loadSessions(); notify("스톡룸에 오신 걸 환영해요.");
      const callback = afterLogin; afterLogin = null; callback?.();
    } catch (error) {
      if (error.status === 429 && error.code === "AUTH_RATE_LIMITED") {
        authCooldown[endpoint] = performance.now() + (error.retryAfter || 1) * 1000;
      }
      handleError(error, "#auth-error");
    }
    finally { controls.forEach((control) => { control.disabled = false; }); delete dialog.dataset.busy; renderAuthCooldown(); }
  });

  $("#purchase-form").addEventListener("submit", async (event) => {
    event.preventDefault(); if (!state.user) { $("#purchase-dialog").close(); showAuth(); return; }
    const dialog = $("#purchase-dialog"); const controls = [...dialog.querySelectorAll("button, input")];
    const body = { productId: state.selected.id, quantity: Number($("#quantity").value) };
    const epoch = state.epoch;
    controls.forEach((control) => { control.disabled = true; }); dialog.dataset.busy = "true"; $("#purchase-error").textContent = "";
    try {
      const pending = await prepareCheckout("direct",[{ id: body.productId, quantity: body.quantity, price: state.selected.price, name: state.selected.name }]);
      if (!pending || epoch !== state.epoch) return;
      const order = await api("/orders/cart/checkout", { method: "POST", body: pending.body, key: pending.key });
      if (epoch !== state.epoch) return;
      await acknowledgeCheckout(pending.body.quoteId);
      if (epoch !== state.epoch) return;
      forgetPending("direct"); dialog.close();
      notify(`주문 #${order.orderId}이 완료됐어요. 내 주문에서 확인하세요.`);
      await Promise.all([loadProducts(), loadOrders()]); location.hash = "orders";
    } catch (error) {
      if (epoch !== state.epoch) return;
      if (definitiveRejection(error)) forgetPending("direct");
      handleError(error, "#purchase-error"); if (error.code === "INSUFFICIENT_STOCK") await loadProducts();
    }
    finally {
      controls.forEach((control) => { control.disabled = false; }); delete dialog.dataset.busy;
      if (readPending("direct")) for (const selector of ["#quantity","#quantity-minus","#quantity-plus"]) $(selector).disabled = true;
    }
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
    try { await api("/auth/logout", { method: "POST" }); state.user = null; state.epoch++; state.orders = []; state.next = null; renderAccount(); renderOrders(); renderProfile(); notify("로그아웃했어요."); }
    catch (error) { handleError(error); button.disabled = false; }
  });
  window.addEventListener("hashchange", route);

  async function init() {
    const epoch = state.epoch;
    renderOrders(); route();
    await Promise.all([(async()=>{try{tossEnabled=(await api("/payments/toss/config")).enabled;}catch{/* Unconfigured payment UI stays hidden. */}})(), (async () => { try { $("#forgot-password").hidden = !(await api("/auth/password-reset/config")).enabled; } catch { /* Unconfigured recovery remains hidden. */ } })(), loadProducts(), (async () => { try { state.config = await api("/storefront/config"); } catch { /* Mock actions remain hidden if configuration is unavailable. */ } })(),
      (async () => { try { const user = await api("/auth/me"); if (epoch !== state.epoch) return; state.user = user; state.epoch++; renderAccount(); renderProfile(); if (location.hash === "#orders") await loadOrders(); if (location.hash === "#account") await loadSessions(); } catch (error) { if (epoch === state.epoch && error.status !== 401) notify(error.message); } finally { authReady=true;renderCart(); } })()]);
  }
  init();
})();
