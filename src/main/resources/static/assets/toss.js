"use strict";
(() => {
  async function api(path, {method="GET",body,key}={}) {
    const headers={Accept:"application/json"};
    if (method!=="GET") {
      const csrf=await api("/auth/csrf");headers[csrf.headerName]=csrf.token;
      headers["Content-Type"]="application/json";
    }
    if(key) headers["Idempotency-Key"]=key;
    let response;
    try { response=await fetch(path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),credentials:"same-origin",cache:"no-store",signal:AbortSignal.timeout(25000)}); }
    catch { throw new Error("응답을 확인하지 못했어요. 내 주문에서 결제 결과를 먼저 확인하세요."); }
    const data=await response.json().catch(()=>null);
    if(!response.ok) {
      const error=new Error(response.status===401?"다시 로그인한 뒤 내 주문에서 결제 결과를 확인하세요.":data?.message||"결제 정보를 확인하지 못했어요.");error.status=response.status;throw error;
    }
    if(data===null)throw new Error("결제 응답을 확인하지 못했어요. 내 주문에서 다시 확인하세요.");
    return data;
  }
  let sdk;
  function load() {
    if(typeof window.TossPayments==="function")return Promise.resolve();
    if(!sdk)sdk=new Promise((resolve,reject)=>{
      const script=document.createElement("script");script.src="https://js.tosspayments.com/v2/standard";script.referrerPolicy="no-referrer";
      const fail=()=>{clearTimeout(timer);script.remove();sdk=null;reject(new Error("결제창을 불러오지 못했어요. 연결을 확인한 뒤 다시 시도하세요."));};
      const timer=setTimeout(fail,12000);script.onerror=fail;script.onload=()=>{clearTimeout(timer);if(typeof window.TossPayments==="function")resolve();else fail();};document.head.append(script);
    });
    return sdk;
  }
  async function pay(orderId) {
    const key=[...crypto.getRandomValues(new Uint8Array(24))].map(v=>v.toString(16).padStart(2,"0")).join("");
    const checkout=await api(`/orders/${orderId}/toss/checkout`,{method:"POST",body:{},key});
    if(!/^test_ck_/.test(checkout.clientKey) || new URL(checkout.successUrl).origin!==location.origin || new URL(checkout.failUrl).origin!==location.origin)
      throw new Error("서비스 주소를 확인한 뒤 다시 결제를 시작하세요.");
    await load();
    try {
      await window.TossPayments(checkout.clientKey).payment({customerKey:checkout.customerKey}).requestPayment({
        method:"CARD",amount:{currency:"KRW",value:checkout.payment.amount},orderId:checkout.payment.checkoutId,
        orderName:checkout.orderName,successUrl:checkout.successUrl,failUrl:checkout.failUrl,
      });
    } catch { throw new Error("결제창이 닫혔거나 인증을 마치지 못했어요. 내 주문에서 결과를 확인한 뒤 다시 시도하세요."); }
  }
  const labels={NONE:"",READY:"카드 테스트 결제 대기",CONFIRMING:"결제 승인 확인 중",UNKNOWN:"결제 결과 확인 중",PAID:"카드 테스트 결제 완료",CANCELING:"전액 환불 확인 중",CANCELED:"전액 환불 완료",FAILED:"카드 테스트 결제 미승인"};
  window.StockroomToss={api,pay,label:payment=>payment.reviewRequired?"결제 정보 확인 필요":labels[payment.status]||"결제 결과 확인 중"};
})();
