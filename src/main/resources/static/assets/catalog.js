(() => {
  'use strict';
  const $=s=>document.querySelector(s);
  const state={user:null,products:[],selected:null,revision:null,busy:false,blocked:false,pending:null};
  const scope=()=>`stockroom-catalog-create:${state.user.userId}`;
  const status=text=>{$('#catalog-status').textContent=text;};
  async function api(path,method='GET',body,key) {
    const headers={Accept:'application/json'};
    if(body) {const csrf=await api('/auth/csrf'); headers[csrf.headerName]=csrf.token; headers['Content-Type']='application/json';}
    if(key) headers['Idempotency-Key']=key;
    const response=await fetch(path,{method,headers,credentials:'same-origin',body:body?JSON.stringify(body):undefined,cache:'no-store'});
    const data=await response.json().catch(()=>({}));
    if(!response.ok) {
      if(response.status===401 || response.status===403) {state.blocked=true; sync();}
      const error=new Error(state.blocked?'관리자 권한 또는 로그인 상태를 확인하세요.':data.message||'처리하지 못했어요.'); error.status=response.status; throw error;
    }
    return data;
  }
  function sync() {
    for(const id of ['create-name','create-price']) $("#"+id).readOnly=state.busy||state.blocked||Boolean(state.pending);
    $('#create-submit').disabled=state.busy||state.blocked;
    $('#create-submit').textContent=state.pending?'같은 등록 요청 다시 확인 →':'상품 등록 →';
    $('#create-pending').hidden=!state.pending;
    $('#edit-submit').disabled=state.busy||state.blocked||!state.selected;
    $('#catalog-refresh').disabled=state.busy;
    for(const id of ['edit-name','edit-price','edit-selling']) $('#'+id).disabled=state.busy||state.blocked||!state.selected;
    document.querySelectorAll('#catalog-products button').forEach(button=>button.disabled=state.busy);
  }
  function select(item) {
    state.selected=item.id; state.revision=item.revision;
    $('#edit-name').value=item.name; $('#edit-price').value=item.price; $('#edit-selling').value=String(item.selling);
    $('#edit-caption').textContent=`상품 #${item.id} · 재고 ${item.stock}개 · 수정 버전 ${item.revision}`;
    document.querySelectorAll('#catalog-products button').forEach(button=>button.setAttribute('aria-pressed',String(Number(button.dataset.id)===item.id)));
    sync();
  }
  async function load() {
    state.products=await api('/admin/products'); const list=$('#catalog-products'); list.replaceChildren();
    for(const item of state.products) {
      const button=document.createElement('button'); button.className='admin-product'; button.type='button'; button.dataset.id=item.id;
      const name=document.createElement('strong'); name.textContent=item.name;
      const info=document.createElement('small'); info.textContent=`${item.price.toLocaleString('ko-KR')}원 · ${item.selling?'판매 중':'판매 중지'}`;
      button.append(name,info); button.addEventListener('click',()=>select(item)); list.append(button);
    }
    const item=state.products.find(p=>p.id===state.selected)||state.products[0]; if(item) select(item); sync();
  }
  function confirm(text) {
    $('#catalog-confirm-message').textContent=text; const dialog=$('#catalog-confirm'); dialog.returnValue='no';
    return new Promise(resolve=>{dialog.addEventListener('close',()=>resolve(dialog.returnValue==='yes'),{once:true}); dialog.showModal();});
  }
  $('#create-product').addEventListener('submit',async event=>{
    event.preventDefault(); if(state.busy||state.blocked) return;
    const intent=state.pending||{name:$('#create-name').value.trim(),price:Number($('#create-price').value),key:crypto.randomUUID()};
    if(!intent.name||!Number.isSafeInteger(intent.price)||intent.price<0) return status('상품명과 정수 가격을 확인하세요.');
    state.busy=true; sync(); let done=false;
    try {
      if(!state.pending && !await confirm(`${intent.name} · ${intent.price.toLocaleString('ko-KR')}원으로 등록할까요?`)) return;
      sessionStorage.setItem(scope(),JSON.stringify(intent)); state.pending=intent;
      const result=await api('/admin/products','POST',{name:intent.name,price:intent.price},intent.key);
      sessionStorage.removeItem(scope()); state.pending=null; state.selected=result.productId; $('#create-product').reset(); done=true;
      status(`상품 #${result.productId} 등록을 확인했어요. 재고 관리에서 입고하세요.`);
    } catch(error) {
      if(error.status>=400 && error.status<500 && !state.blocked) {sessionStorage.removeItem(scope()); state.pending=null;}
      status(state.pending&&!state.blocked?'응답을 확인하지 못했어요. 같은 등록 요청으로 결과를 확인하세요.':error.message);
    } finally {state.busy=false; sync();}
    if(done) {try {await load();} catch {status('상품 등록은 완료됐어요. 목록을 새로고침하세요.');}}
  });
  $('#edit-product').addEventListener('submit',async event=>{
    event.preventDefault(); if(state.busy||state.blocked||!state.selected) return;
    const body={name:$('#edit-name').value.trim(),price:Number($('#edit-price').value),selling:$('#edit-selling').value==='true',revision:state.revision};
    state.busy=true; sync();
    try {
      if(!await confirm(`${body.name} · ${body.price.toLocaleString('ko-KR')}원 · ${body.selling?'판매 중':'판매 중지'}로 저장할까요?`)) return;
      await api(`/admin/products/${state.selected}`,'PUT',body); status('상품 변경이 저장됐어요.');
      try {await load();} catch {status('상품 변경은 완료됐어요. 목록을 새로고침하세요.');}
    } catch(error) {status(`${error.message} 응답이 불확실하거나 수정 충돌이면 새로고침으로 현재 내용을 확인하세요.`);}
    finally {state.busy=false; sync();}
  });
  $('#catalog-refresh').addEventListener('click',()=>load().catch(error=>status(error.message)));
  (async()=>{try {
    state.user=await api('/auth/me'); if(state.user.role!=='ADMIN') throw new Error('관리자 계정으로 로그인하세요.');
    const stored=sessionStorage.getItem(scope()); if(stored) {
      const p=JSON.parse(stored); if(typeof p.name!=='string'||!p.name.trim()||p.name.length>100||!Number.isSafeInteger(p.price)||p.price<0||p.price>1000000000000||typeof p.key!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(p.key)) throw new Error('저장된 등록 요청을 확인할 수 없어요.');
      state.pending=p; $('#create-name').value=p.name; $('#create-price').value=p.price;
    }
    await load(); $('#catalog-workspace').hidden=false; status(state.pending?'이전 등록 요청을 같은 키로 확인하세요.':'상품을 등록하거나 선택해 판매 정보를 수정하세요.'); sync();
  } catch(error) {status(error.message);} })();
})();
