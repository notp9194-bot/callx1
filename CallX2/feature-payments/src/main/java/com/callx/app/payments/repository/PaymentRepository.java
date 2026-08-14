package com.callx.app.payments.repository;

import androidx.lifecycle.LiveData;

import com.callx.app.db.entity.PaymentAccountEntity;
import com.callx.app.db.entity.PaymentTransactionEntity;
import com.callx.app.payments.model.PaymentDraft;

import java.util.List;

public interface PaymentRepository {
    LiveData<List<PaymentTransactionEntity>> observeTransactions();
    LiveData<List<PaymentAccountEntity>> observeAccounts();
    void createTransaction(PaymentDraft draft, Callback<PaymentTransactionEntity> callback);
    void linkAccount(String bankName, String accountLast4, String upiId, Callback<PaymentAccountEntity> callback);
    void setPinConfigured(boolean configured, Callback<Boolean> callback);
    PaymentTransactionEntity getTransaction(String id);

    interface Callback<T> {
        void onResult(T value, String error);
    }
}