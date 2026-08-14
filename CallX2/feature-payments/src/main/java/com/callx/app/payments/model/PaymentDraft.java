package com.callx.app.payments.model;

/**
 * Transport-neutral input shared by send-money and request-money flows.
 */
public final class PaymentDraft {
    public final String type;
    public final String counterpartyUid;
    public final String counterpartyName;
    public final String counterpartyUpi;
    public final long amountPaise;
    public final String note;
    public final String chatId;

    public PaymentDraft(String type, String counterpartyUid, String counterpartyName,
                        String counterpartyUpi, long amountPaise, String note, String chatId) {
        this.type = type;
        this.counterpartyUid = counterpartyUid;
        this.counterpartyName = counterpartyName;
        this.counterpartyUpi = counterpartyUpi;
        this.amountPaise = amountPaise;
        this.note = note;
        this.chatId = chatId;
    }
}