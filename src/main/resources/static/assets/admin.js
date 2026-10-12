(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const state = {user:null, csrf:null, products:[], selected:null, next:null, saving:false, blocked:false, pending:null, historyEpoch:0};
  const number = value => Number(value).toLocaleString('ko-KR');
  const storageKey = () => `stockroom-inventory:${state.user.userId}`;
  function message(text, error=false) { $('#admin-status').textContent=text; $('#admin-status').classList.toggle('error',error); }
  async function api(path, options={}) {
    const headers = {'Accept':'application/json'};
    if (options.body) { headers['Content-Type']='application/json'; headers[state.csrf.headerName]=state.csrf.token; }
    if (options.key) headers['Idempotency-Key']=options.key;
    const response = await fetch(path,{method:options.body?'POST':'GET',headers,credentials:'same-origin',body:options.body?JSON.stringify(options.body):undefined});
    const body = await response.json().catch(()=>({}));
    if (!response.ok) {
      const error = new Error(body.message || '요청을 처리하지 못했어요.'); error.status=response.status;
      if (response.status===401 || response.status===403) {
        state.blocked=true; syncForm();
        error.message='로그인 또는 관리자 권한을 확인해야 해요. 컬렉션에서 다시 로그인하세요.';
      }
      throw error;
    }
    return body;
  }
  function selected() { return state.products.find(product=>product.id===state.selected); }
  function syncForm() {
    const locked = state.saving || state.blocked || Boolean(state.pending);
    $('#stock-delta').readOnly=locked; $('#adjustment-reason').readOnly=locked;
    $('#adjust-submit').disabled=state.saving || state.blocked || !selected();
    $('#adjust-submit').textContent=state.saving?'결과를 확인하고 있어요…':state.pending?'같은 요청 다시 확인 →':'조정 내용 확인 →';
    $('#pending-help').hidden=!state.pending;
    document.querySelectorAll('.admin-product').forEach(button=>{button.disabled=locked;});
  }
  function renderProducts() {
    const list=$('#admin-products'); list.replaceChildren();
    for (const product of state.products) {
      const button=document.createElement('button'); button.type='button'; button.className='admin-product';
      button.dataset.productId=product.id; button.setAttribute('aria-pressed',String(product.id===state.selected));
      const name=document.createElement('span'); const title=document.createElement('strong'); title.textContent=product.name;
      const caption=document.createElement('small'); caption.textContent=`PRODUCT #${product.id} · ${number(product.price)}원`;
      name.append(title,caption);
      const count=document.createElement('span'); count.className='admin-stock'; count.textContent=number(product.stock);
      const unit=document.createElement('small'); unit.textContent='개'; count.append(unit); button.append(name,count);
      button.addEventListener('click',()=>{ if (state.pending || state.saving) return; state.selected=product.id; renderProducts(); loadHistory().catch(error=>message(error.message,true)); });
      list.append(button);
    }
    $('#selected-product').textContent=selected()?`${selected().name} · 현재 ${number(selected().stock)}개`:'선택할 상품이 없어요.';
    preview(); syncForm();
  }
  function preview() {
    const product=selected(); const delta=Number($('#stock-delta').value);
    $('#stock-preview').textContent=product && $('#stock-delta').value && Number.isInteger(delta)
      ? `예상 재고 ${number(product.stock)} → ${number(product.stock+delta)}개 · 실제 반영 시 다시 확인해요.`
      : '증가는 양수, 감소는 음수로 입력하세요.';
  }
  async function loadProducts() {
    state.products=await api('/products');
    if (!state.selected && state.products.length) state.selected=state.products[0].id;
    renderProducts();
  }
  async function loadHistory(append=false) {
    if (!state.selected) return;
    const epoch=++state.historyEpoch;
    const cursor=append && state.next?`&beforeId=${state.next}`:'';
    $('#history-more').disabled=true;
    try {
      const data=await api(`/admin/inventory-adjustments?productId=${state.selected}${cursor}`);
      if (epoch!==state.historyEpoch) return;
      const table=$('#adjustment-history'); if (!append) table.replaceChildren();
      for (const entry of data.adjustments) {
        const row=document.createElement('tr'); row.dataset.adjustmentId=entry.adjustmentId;
        const values=[new Date(entry.createdAt).toLocaleString('ko-KR'),entry.productName,`${entry.delta>0?'+':''}${number(entry.delta)}`,
          `${number(entry.beforeStock)} → ${number(entry.afterStock)}`,entry.reason,`#${entry.adminId}`];
        values.forEach((value,index)=>{const cell=document.createElement('td'); cell.textContent=value; if(index===2) cell.className=entry.delta>0?'stock-positive':'stock-negative'; row.append(cell);});
        table.append(row);
      }
      state.next=data.nextBeforeId; $('#history-more').hidden=!state.next;
      $('#history-empty').hidden=Boolean(table.children.length); $('#history-empty').textContent='이 상품의 수동 재고 조정 이력이 없어요.';
      $('#history-filter').textContent=selected()?.name || '';
    } finally { if(epoch===state.historyEpoch) $('#history-more').disabled=false; }
  }
  function confirmAdjustment(intent) {
    const dialog=$('#inventory-confirm');
    $('#inventory-confirm-message').textContent=`${selected().name}의 재고를 ${intent.delta>0?'+':''}${number(intent.delta)}개 조정합니다. 사유: ${intent.reason}`;
    return new Promise(resolve=>{dialog.addEventListener('close',()=>resolve(dialog.returnValue==='yes'),{once:true}); dialog.returnValue='no'; dialog.showModal();});
  }
  function savePending(intent) { sessionStorage.setItem(storageKey(),JSON.stringify(intent)); state.pending=intent; }
  function clearPending() { sessionStorage.removeItem(storageKey()); state.pending=null; }
  $('#stock-delta').addEventListener('input',preview);
  $('#adjustment-form').addEventListener('submit',async event=>{
    event.preventDefault(); if(state.saving || state.blocked || !selected()) return;
    const intent=state.pending || {productId:state.selected,delta:Number($('#stock-delta').value),reason:$('#adjustment-reason').value.trim(),key:crypto.randomUUID()};
    if(!Number.isInteger(intent.delta) || intent.delta===0 || !intent.reason) return message('0이 아닌 정수 수량과 변경 사유를 입력하세요.',true);
    state.saving=true; syncForm();
    let completed=false;
    try {
      if(!state.pending && !await confirmAdjustment(intent)) return;
      // Persist before POST. If storage is unavailable, no adjustment is sent.
      if(!state.pending) savePending(intent);
      state.csrf=await api('/auth/csrf');
      const result=await api(`/admin/products/${intent.productId}/inventory-adjustments`,{body:{delta:intent.delta,reason:intent.reason},key:intent.key});
      clearPending(); $('#adjustment-form').reset(); completed=true;
      message(`재고 조정 #${result.adjustmentId}이 반영됐어요. ${number(result.beforeStock)} → ${number(result.afterStock)}개`);
    } catch(error) {
      if(error.status>=400 && error.status<500 && !state.blocked) clearPending();
      message(state.pending && !state.blocked?'응답을 확인하지 못했어요. 같은 요청으로 결과를 다시 확인하세요.':error.message,true);
    } finally {state.saving=false; syncForm();}
    if(completed) {
      const refresh=await Promise.allSettled([loadProducts(),loadHistory()]);
      if(refresh.some(result=>result.status==='rejected')) message('재고 조정은 완료됐어요. 목록을 불러오지 못했으니 새로고침하세요.',true);
    }
  });
  $('#admin-refresh').addEventListener('click',async()=>{try {await loadProducts(); await loadHistory();} catch(error){message(error.message,true);}});
  $('#history-more').addEventListener('click',()=>loadHistory(true).catch(error=>message(error.message,true)));
  (async()=>{
    try {
      state.user=await api('/auth/me');
      if(state.user.role!=='ADMIN') throw new Error('관리자 계정으로 로그인하세요.');
      $('#admin-account').textContent=state.user.email;
      const stored=sessionStorage.getItem(storageKey());
      if(stored) {
        const pending=JSON.parse(stored);
        if(!Number.isSafeInteger(pending.productId) || !Number.isInteger(pending.delta) || pending.delta===0
          || typeof pending.reason!=='string' || !pending.reason.trim() || pending.reason.length>200
          || typeof pending.key!=='string' || !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(pending.key)) throw new Error('저장된 조정 요청을 확인할 수 없어요.');
        state.pending=pending; state.selected=pending.productId;
        $('#stock-delta').value=pending.delta; $('#adjustment-reason').value=pending.reason;
      }
      await loadProducts(); await loadHistory(); $('#admin-workspace').hidden=false;
      message(state.pending?'이전 조정 요청이 남아 있어요. 같은 요청으로 결과를 확인하세요.':'현재 재고를 확인하고 변경 사유와 함께 조정하세요.'); syncForm();
    } catch(error) {message(error.message,true);}
  })();
})();
