package com.callx.app.payments.repository;

import android.content.Context;

public final class PaymentRepositoryProvider {
    private static volatile PaymentRepository instance;

    private PaymentRepositoryProvider() {}

    public static PaymentRepository get(Context context) {
        if (instance == null) {
            synchronized (PaymentRepositoryProvider.class) {
                if (instance == null) instance = new PaymentRepositoryImpl(context);
            }
        }
        return instance;
    }
}