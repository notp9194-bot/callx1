package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "payment_accounts",
        indices = {@Index(value = {"ownerUid"})}
)
public class PaymentAccountEntity {
    @PrimaryKey
    @NonNull public String id = "";
    @NonNull public String ownerUid = "";
    public String bankName;
    public String maskedAccount;
    public String upiId;
    public boolean isDefault;
    @NonNull public String status = "ACTIVE";
    public long createdAt;
}