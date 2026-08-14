package com.callx.app.payments.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.PaymentAccountEntity;
import com.callx.app.db.entity.PaymentPinEntity;
import com.callx.app.db.entity.PaymentTransactionEntity;
import com.callx.app.payments.api.MockPaymentService;
import com.callx.app.payments.api.PaymentService;
import com.callx.app.payments.model.PaymentDraft;
import com.google.firebase.auth.FirebaseAuth;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository owns persistence and gateway orchestration. Replace only the
 * PaymentService supplied here when the real API is available.
 */
public final class PaymentRepositoryImpl implements PaymentRepository {
    private final AppDatabase db;
    private final PaymentService service;
    private final String ownerUid;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public PaymentRepositoryImpl(Context context) {
        db = AppDatabase.getInstance(context.getApplicationContext());
        service = new MockPaymentService();
        String firebaseUid = FirebaseAuth.getInstance().getCurrentUser() == null
                ? null : FirebaseAuth.getInstance().getCurrentUser().getUid();
        ownerUid = firebaseUid == null ? "local-demo-user" : firebaseUid;
    }

    @Override
    public LiveData<List<PaymentTransactionEntity>> observeTransactions() {
        return db.paymentTransactionDao().observeForOwner(ownerUid);
    }

    @Override
    public LiveData<List<PaymentAccountEntity>> observeAccounts() {
        return db.paymentAccountDao().observeForOwner(ownerUid);
    }

    @Override
    public void createTransaction(PaymentDraft draft, Callback<PaymentTransactionEntity> callback) {
        service.execute(draft, response -> {
            PaymentTransactionEntity entity = new PaymentTransactionEntity();
            entity.id = UUID.randomUUID().toString();
            entity.ownerUid = ownerUid;
            entity.counterpartyUid = draft.counterpartyUid;
            entity.counterpartyName = draft.counterpartyName;
            entity.counterpartyUpi = draft.counterpartyUpi;
            entity.amountPaise = draft.amountPaise;
            entity.type = draft.type;
            entity.note = draft.note;
            entity.status = response.success ? response.status : "FAILED";
            entity.referenceId = response.referenceId;
            entity.chatId = draft.chatId;
            entity.direction = "REQUEST".equals(draft.type) ? "REQUESTED" : "OUTGOING";
            entity.createdAt = System.currentTimeMillis();
            entity.updatedAt = entity.createdAt;
            io.execute(() -> {
                db.paymentTransactionDao().insert(entity);
                main.post(() -> callback.onResult(entity, response.success ? null : response.message));
            });
        });
    }

    @Override
    public void linkAccount(String bankName, String accountLast4, String upiId,
                            Callback<PaymentAccountEntity> callback) {
        io.execute(() -> {
            db.paymentAccountDao().clearDefault(ownerUid);
            PaymentAccountEntity account = new PaymentAccountEntity();
            account.id = UUID.randomUUID().toString();
            account.ownerUid = ownerUid;
            account.bankName = bankName;
            account.maskedAccount = accountLast4 == null || accountLast4.isEmpty()
                    ? null : "•••• " + accountLast4;
            account.upiId = upiId;
            account.isDefault = true;
            account.status = "DEMO_LINKED";
            account.createdAt = System.currentTimeMillis();
            db.paymentAccountDao().insert(account);
            main.post(() -> callback.onResult(account,
                    "Demo account saved. Bank verification will be added with the API."));
        });
    }

    @Override
    public void setPinConfigured(boolean configured, Callback<Boolean> callback) {
        io.execute(() -> {
            PaymentPinEntity pin = new PaymentPinEntity();
            pin.ownerUid = ownerUid;
            pin.configured = configured;
            pin.updatedAt = System.currentTimeMillis();
            db.paymentPinDao().save(pin);
            main.post(() -> callback.onResult(configured, null));
        });
    }

    @Override
    public PaymentTransactionEntity getTransaction(String id) {
        return db.paymentTransactionDao().getById(id);
    }
}