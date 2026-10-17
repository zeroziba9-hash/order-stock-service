"use strict";
(() => {
  const params=new URLSearchParams(location.search);
  const id=params.get("checkout")||params.get("orderId")||history.state?.checkoutId;
  const key=params.get("paymentKey"),raw=params.get("amount"),amount=raw&&/^\d+$/.test(raw)?Number(raw):NaN;
  const failed=params.get("failed")==="1";
  // Payment identifiers stay in memory only. Keep just the opaque checkout ID for read-only reloads.
  history.replaceState(typeof id==="string"&&/^srp_[a-f0-9]{32}$/.test(id)?{checkoutId:id}:null,"",location.pathname);
  const $=selector=>document.querySelector(selector);let busy=false;
  async function check(confirm=false,reconcile=false) {
    if(busy)return;busy=true;$("#payment-retry").disabled=true;$("#payment-error").textContent="";
    try {
      if(typeof id!=="string"||!/^srp_[a-f0-9]{32}$/.test(id))throw new Error("결제 주소를 확인할 수 없어요. 내 주문에서 다시 확인하세요.");
      let result;
      if(reconcile)await StockroomToss.api(`/payments/toss/${id}/reconcile`,{method:"POST",body:{}});
      if(confirm) {
        if(!/^[A-Za-z0-9_.=-]{1,200}$/.test(key||"")||!Number.isSafeInteger(amount)||amount<1)throw new Error("결제 인증 정보를 확인할 수 없어요. 내 주문에서 다시 시작하세요.");
        result=await StockroomToss.api("/payments/toss/confirm",{method:"POST",body:{orderId:id,paymentKey:key,amount}});
      } else result=await StockroomToss.api(`/payments/toss/${id}`);
      $("#payment-title").textContent=StockroomToss.label(result);
      $("#payment-status").textContent=result.pending?"결과가 확정될 때까지 주문 취소와 자동 만료가 보류돼요. 응답이 늦어져도 새 결제를 만들지 말고 결과를 다시 확인하세요.":result.status==="READY"?"결제 인증 또는 승인이 완료되지 않았어요. 내 주문에서 다시 시작할 수 있어요.":`주문 #${result.orderId} · ${new Intl.NumberFormat("ko-KR").format(result.amount)}원`;
      $("#payment-retry").hidden=!result.pending&&result.status!=="PAID";
      document.title=`${$("#payment-title").textContent} — STOCKROOM`;
    } catch(error) {$("#payment-title").textContent="결제 결과를 다시 확인해 주세요.";$("#payment-error").textContent=error.message;$("#payment-retry").hidden=false;}
    finally{busy=false;$("#payment-retry").disabled=false;}
  }
  $("#payment-retry").addEventListener("click",()=>check(false,true));
  check(Boolean(key)&&!failed);
})();
