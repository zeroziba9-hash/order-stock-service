(() => {
  'use strict';
  const $=s=>document.querySelector(s);
  const state={user:null,products:[],selected:null,revision:null,busy:false,blocked:false,pending:null};
  let historyRequest=0, historyLoading=false, historyNext=null;
  let listPage=0,listRequest=0,listFilters={};
  let contentRequest=0,contentRevision=null,contentNext=null,contentLoading=false;
  const historyDates=()=>{const values=new URLSearchParams();for(const key of ['from','to'])if($('#catalog-'+key).value)values.set(key,$('#catalog-'+key).value);return values.size?'&'+values:'';};
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
    for (const id of ['content-description','content-specification','content-save']) $('#'+id).disabled=state.busy||state.blocked||!state.selected||contentRevision===null;
    $('#content-more').disabled=state.busy||state.blocked||contentLoading;
    for(const id of ['edit-name','edit-price','edit-selling']) $('#'+id).disabled=state.busy||state.blocked||!state.selected;
    document.querySelectorAll('#catalog-products button').forEach(button=>button.disabled=state.busy);
    $('#catalog-history-refresh').disabled=state.busy||state.blocked||!state.selected;
    $('#catalog-history-more').disabled=historyLoading||state.busy||state.blocked;
  }
  function select(item) {
    state.selected=item.id; state.revision=item.revision;
    $('#edit-name').value=item.name; $('#edit-price').value=item.price; $('#edit-selling').value=String(item.selling);
    $('#edit-caption').textContent=`상품 #${item.id} · 재고 ${item.stock}개 · 수정 버전 ${item.revision}`;
    document.querySelectorAll('#catalog-products button').forEach(button=>button.setAttribute('aria-pressed',String(Number(button.dataset.id)===item.id)));
    sync();
    loadHistory(); loadContent();
  }
  async function loadContent(append=false) {
    if(!state.selected||state.blocked||(append&&!contentNext))return;
    const product=state.selected,request=++contentRequest;contentLoading=true;
    if(!append){contentRevision=null;contentNext=null;$('#content-description').value='';$('#content-specification').value='';$('#content-history').replaceChildren();}
    $('#content-more').hidden=true;$('#content-status').textContent='상품 설명을 불러오고 있어요.';sync();
    try{
      const base=`/admin/products/${product}/content`;
      const [content,history]=await Promise.all([append?Promise.resolve(null):api(base),api(base+'/changes'+(append?`?beforeId=${contentNext}`:''))]);
      if(request!==contentRequest||product!==state.selected)return;
      if(content){contentRevision=content.revision;$('#content-description').value=content.description;$('#content-specification').value=content.specification;}
      for(const change of history.changes){
        const card=document.createElement('article');card.className='catalog-change';
        const heading=document.createElement('h4');heading.textContent=`설명 수정 ${change.before.revision} → ${change.after.revision} · 관리자 #${change.actorId}`;
        const time=document.createElement('time');time.dateTime=change.createdAt;time.textContent=new Intl.DateTimeFormat('ko-KR',{dateStyle:'medium',timeStyle:'short'}).format(new Date(change.createdAt));
        card.append(heading,time);
        for(const [field,label] of [['description','설명'],['specification','규격']])if(change.before[field]!==change.after[field]){
          const line=document.createElement('p');line.textContent=`${label}: ${change.before[field]||'(비어 있음)'} → ${change.after[field]||'(비어 있음)'}`;card.append(line);
        }
        $('#content-history').append(card);
      }
      contentNext=history.nextBeforeId;$('#content-more').hidden=!contentNext;
      $('#content-status').textContent=`설명 수정 버전 ${contentRevision} · 상품 상세에 표시돼요.`;
      return true;
    }catch(error){if(request===contentRequest&&product===state.selected){$('#content-status').textContent=`${error.message} 목록 새로고침으로 다시 확인하세요.`;$('#content-more').hidden=!contentNext;}return false;}
    finally{if(request===contentRequest){contentLoading=false;sync();}}
  }
  $('#edit-content').addEventListener('submit',async event=>{
    event.preventDefault();if(state.busy||state.blocked||!state.selected||contentRevision===null)return;
    const product=state.selected,body={description:$('#content-description').value,specification:$('#content-specification').value,revision:contentRevision};
    state.busy=true;sync();
    try{
      if(!await confirm('상품 설명과 규격을 저장할까요? 상품 상세 화면에 반영돼요.'))return;
      await api(`/admin/products/${product}/content`,'PUT',body);const loaded=await loadContent();$('#content-status').textContent=loaded?'설명과 규격을 저장했어요.':'저장은 완료됐어요. 목록을 새로고침해 현재 설명과 이력을 확인하세요.';
    }catch(error){$('#content-status').textContent=`${error.message} 응답이 불확실하거나 수정 충돌이면 목록을 새로고침해 현재 내용을 확인하세요.`;}
    finally{state.busy=false;sync();}
  });
  $('#content-more').addEventListener('click',()=>loadContent(true));
  async function loadHistory(append=false) {
    if(!state.selected||state.blocked||(append&&(historyLoading||!historyNext))) return;
    const product=state.selected, request=++historyRequest, cursor=append?historyNext:null;
    historyLoading=true;
    if(!append) {historyNext=null; $('#catalog-history').replaceChildren();}
    $('#catalog-history-more').hidden=true;
    $('#catalog-history-status').textContent='변경 이력을 불러오고 있어요.'; sync();
    try {
      const suffix=historyDates();
      const result=await api(`/admin/products/${product}/changes${cursor?`?beforeId=${cursor}${suffix}`:suffix?'?'+suffix.slice(1):''}`);
      if(request!==historyRequest||product!==state.selected) return;
      for(const change of result.changes) {
        const card=document.createElement('article'); card.className='catalog-change'; card.dataset.changeId=change.changeId;
        const heading=document.createElement('h3'); heading.textContent=`수정 ${change.before.revision} → ${change.after.revision} · 관리자 #${change.actorId}`;
        const time=document.createElement('time'); time.dateTime=change.createdAt;
        time.textContent=new Intl.DateTimeFormat('ko-KR',{dateStyle:'medium',timeStyle:'medium'}).format(new Date(change.createdAt));
        card.append(heading,time);
        for(const [field,label,format] of [['name','상품명',String],['price','가격',v=>`${v.toLocaleString('ko-KR')}원`],['selling','판매 상태',v=>v?'판매 중':'판매 중지']]) {
          if(change.before[field]===change.after[field]) continue;
          const line=document.createElement('p'); line.textContent=`${label}: ${format(change.before[field])} → ${format(change.after[field])}`;
          card.append(line);
        }
        $('#catalog-history').append(card);
      }
      historyNext=result.nextBeforeId;
      $('#catalog-history-status').textContent=$('#catalog-history').children.length?'최근 변경부터 표시해요.':'기록된 변경이 없어요. 이력 기능 적용 이후의 수정부터 남아요.';
      $('#catalog-history-more').hidden=!historyNext;
    } catch(error) {
      if(request!==historyRequest||product!==state.selected) return;
      $('#catalog-history-status').textContent=`변경 이력을 불러오지 못했어요. ${error.message} 이력 새로고침으로 다시 확인하세요.`;
      $('#catalog-history-more').hidden=!historyNext;
    } finally {if(request===historyRequest) {historyLoading=false; sync();}}
  }
  async function load(append=false) {
    const request=++listRequest,page=append?listPage+1:0;
    const result=await api('/admin/products/search?'+new URLSearchParams({...listFilters,page}));if(request!==listRequest)return;
    state.products=append?[...state.products,...result.products]:result.products;listPage=page;
    $('#catalog-products-more').hidden=!result.hasMore;
    const list=$('#catalog-products'); list.replaceChildren();
    for(const item of state.products) {
      const button=document.createElement('button'); button.className='admin-product'; button.type='button'; button.dataset.id=item.id;
      const name=document.createElement('strong'); name.textContent=item.name;
      const info=document.createElement('small'); info.textContent=`${item.price.toLocaleString('ko-KR')}원 · ${item.selling?'판매 중':'판매 중지'}`;
      button.append(name,info); button.addEventListener('click',()=>select(item)); list.append(button);
    }
    const item=state.products.find(p=>p.id===state.selected)||state.products[0]; if(item) select(item);
    else{state.selected=null;state.revision=null;contentRequest++;contentRevision=null;$('#content-description').value='';$('#content-specification').value='';$('#content-history').replaceChildren();$('#content-status').textContent='상품을 선택하세요.';$('#catalog-history').replaceChildren();$('#catalog-history-status').textContent='조회 조건에 맞는 상품이 없어요.';$('#catalog-history-more').hidden=true;$('#edit-caption').textContent='상품을 선택하세요.';} sync();
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
  $('#catalog-product-search').addEventListener('submit',event=>{
    event.preventDefault();if(state.busy||state.blocked)return;
    listFilters={q:$('#catalog-query').value.trim()};if($('#catalog-selling').value!=='')listFilters.selling=$('#catalog-selling').value;
    historyRequest++;contentRequest++;contentRevision=null;historyNext=null;state.selected=null;load().catch(error=>status(error.message));
  });
  $('#catalog-products-more').addEventListener('click',()=>load(true).catch(error=>status(error.message)));
  $('#catalog-dates').addEventListener('submit',event=>{event.preventDefault();loadHistory();});
  $('#catalog-history-refresh').addEventListener('click',()=>loadHistory());
  $('#catalog-history-more').addEventListener('click',()=>loadHistory(true));
  (async()=>{try {
    state.user=await api('/auth/me'); if(state.user.role!=='ADMIN') throw new Error('관리자 계정으로 로그인하세요.');
    const stored=sessionStorage.getItem(scope()); if(stored) {
      const p=JSON.parse(stored); if(typeof p.name!=='string'||!p.name.trim()||p.name.length>100||!Number.isSafeInteger(p.price)||p.price<0||p.price>1000000000000||typeof p.key!=='string'||!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(p.key)) throw new Error('저장된 등록 요청을 확인할 수 없어요.');
      state.pending=p; $('#create-name').value=p.name; $('#create-price').value=p.price;
    }
    await load(); $('#catalog-workspace').hidden=false; status(state.pending?'이전 등록 요청을 같은 키로 확인하세요.':'상품을 등록하거나 선택해 판매 정보를 수정하세요.'); sync();
  } catch(error) {status(error.message);} })();
})();
