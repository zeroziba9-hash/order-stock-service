(() => {
  'use strict';const $=selector=>document.querySelector(selector);
  let token=location.hash.startsWith('#token=')?location.hash.slice(7):null;
  const fromLink=token!==null||Boolean(history.state?.resetLinkOpened);
  if(token!==null)history.replaceState({resetLinkOpened:true},'',location.pathname+location.search);
  // Opening another email link in this tab can be a same-document navigation.
  window.addEventListener('hashchange',()=>{if(location.hash.startsWith('#token='))location.reload();});
  let enabled=false,busy=false,cooldown=0,timer;
  async function api(path,body){
    const headers={Accept:'application/json'};if(body){const csrf=await api('/auth/csrf');headers[csrf.headerName]=csrf.token;headers['Content-Type']='application/json';}
    let response;try{response=await fetch(path,{method:body?'POST':'GET',body:body?JSON.stringify(body):undefined,headers,credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(25000)});}
    catch{throw new Error(body?'응답을 확인하지 못했어요. 메일 요청은 수신함을 먼저 확인하세요. 변경 요청은 새 비밀번호로 로그인을 확인하세요.':'서버에 연결할 수 없어요. 새로고침해 다시 확인하세요.');}
    const data=response.status===204?null:await response.json().catch(()=>null);
    if(!response.ok){if(response.status===429){cooldown=Date.now()+Math.min(3600,Number(response.headers.get('Retry-After'))||60)*1000;sync();}throw new Error(data?.message||'요청을 처리하지 못했어요. 잠시 후 다시 확인하세요.');}
    if(response.status!==204&&data===null)throw new Error('서버 응답을 확인하지 못했어요. 수신함 또는 새 비밀번호 로그인을 확인하세요.');return data;
  }
  function sync(){
    clearTimeout(timer);const seconds=Math.max(0,Math.ceil((cooldown-Date.now())/1000));
    $('#reset-request-submit').disabled=busy||!enabled||seconds>0;$('#reset-confirm-submit').disabled=busy||!token||seconds>0;
    $('#reset-request-submit').textContent=seconds?`${seconds}초 후 다시 요청`:'재설정 메일 요청';$('#reset-confirm-submit').textContent=seconds?`${seconds}초 후 다시 시도`:'새 비밀번호로 변경';
    for(const input of document.querySelectorAll('input'))input.disabled=busy;
    if(seconds)timer=setTimeout(sync,250);
  }
  if(fromLink){
    $('#reset-request').hidden=true;$('#reset-confirm').hidden=false;$('#reset-title').textContent='새 비밀번호를 정하세요.';$('#reset-description').textContent='메일 링크는 15분 동안 한 번만 사용할 수 있어요.';
    if(!token||!/^[A-Za-z0-9_-]{43}$/.test(token)){token=null;$('#reset-error').textContent='메일의 재설정 링크를 다시 열어주세요. 링크 정보는 브라우저에 저장하지 않아요.';}
  }
  $('#reset-request').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!enabled||cooldown>Date.now())return;busy=true;sync();$('#reset-error').textContent='';
    try{const result=await api('/auth/password-reset/request',{email:$('#reset-email').value.trim()});$('#reset-status').textContent=result.message;cooldown=Date.now()+60000;}
    catch(error){$('#reset-error').textContent=error.message;}finally{busy=false;sync();}
  });
  $('#reset-confirm').addEventListener('submit',async event=>{
    event.preventDefault();if(busy||!token||cooldown>Date.now())return;
    const password=$('#reset-password').value;$('#reset-error').textContent='';
    if(password!==$('#reset-password-again').value){$('#reset-error').textContent='새 비밀번호와 확인 값이 일치하지 않아요.';return;}
    if(new TextEncoder().encode(password).length>72){$('#reset-error').textContent='비밀번호는 UTF-8 기준 72바이트 이하로 입력하세요.';return;}
    busy=true;sync();try{await api('/auth/password-reset/confirm',{token,newPassword:password});token=null;$('#reset-confirm').reset();$('#reset-confirm').hidden=true;$('#reset-title').textContent='비밀번호를 변경했어요.';$('#reset-status').textContent='기존 접속이 모두 종료됐어요. 새 비밀번호로 다시 로그인하세요.';history.replaceState(null,'',location.pathname);}
    catch(error){$('#reset-error').textContent=error.message;}finally{busy=false;sync();}
  });
  (async()=>{try{enabled=(await api('/auth/password-reset/config')).enabled;if(!enabled&&!fromLink)$('#reset-error').textContent='현재 이메일 복구가 설정되지 않았어요.';}catch(error){if(!fromLink)$('#reset-error').textContent=error.message;}sync();})();sync();
})();
