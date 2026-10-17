package com.zeroziba.orderstock.payment.toss;

import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class TossPaymentService {
    private final TossTransactions transactions;
    private final TossApiClient api;
    public TossPaymentService(TossTransactions transactions,TossApiClient api) { this.transactions=transactions;this.api=api; }
    public TossView confirm(long user,String id,String key,long amount) {
        return execute(transactions.confirm(user,id,key,amount),user,id);
    }
    public TossView cancel(long user,String id) { return execute(transactions.cancel(user,id),user,id); }
    public TossView reconcile(long user,String id) { return execute(transactions.claim(id,user),user,id); }
    void recover(String id) { execute(transactions.claim(id,null),null,id); }
    private TossView execute(TossTransactions.Claim claim,Long user,String id) {
        if (claim==null) return user==null?null:transactions.viewById(user,id);
        var row=claim.row();
        TossApiClient.Result result;
        if (claim.action().equals("CONFIRM")) result=api.confirm(row);
        else if (claim.action().equals("CANCEL")) result=api.cancel(row);
        else {
            result=api.lookup(row);
            LocalDateTime started=row.status().equals("CANCELING")?row.refundStartedAt():row.confirmStartedAt();
            // Never reuse a mutation key indefinitely, or write to a PG from a restored snapshot.
            if (result.outcome()==TossApiClient.Outcome.UNKNOWN && row.pending() && !row.outboundDisabled()
                    && started!=null && transactions.now().isBefore(started.plusHours(24))) {
                result=row.status().equals("CANCELING")?api.cancel(row):api.confirm(row);
            }
        }
        if (!claim.action().equals("LOOKUP") && (result.outcome()==TossApiClient.Outcome.UNKNOWN || result.outcome()==TossApiClient.Outcome.REJECTED)) {
            var verified=api.lookup(row);
            if (verified.outcome()!=TossApiClient.Outcome.UNKNOWN) result=verified;
        }
        return transactions.apply(claim,result);
    }
}
