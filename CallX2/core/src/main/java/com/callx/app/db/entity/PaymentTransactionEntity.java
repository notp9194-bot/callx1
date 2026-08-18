package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Offline-first payment ledger. Amounts are stored in paise to avoid floating
 * point rounding when a real gateway is connected later.
 */
@Entity(
        tableName = "payment_transactions",
        indices = {@Index(value = {"ownerUid", "createdAt"})}
)
public class PaymentTransactionEntity {
    @PrimaryKey
    @NonNull public String id = "";
    @NonNull public String ownerUid = "";
    public String counterpartyUid;
    public String counterpartyName;
    public String counterpartyUpi;
    public long amountPaise;
    @NonNull public String currency = "INR";
    @NonNull public String type = "SEND";
    public String note;
    @NonNull public String status = "PENDING";
    public String referenceId;
    public String chatId;
    @NonNull public String direction = "OUTGOING";
    public long createdAt;
    public long updatedAt;
}