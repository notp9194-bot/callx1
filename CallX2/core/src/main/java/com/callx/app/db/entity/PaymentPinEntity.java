package com.callx.app.db.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Stores only whether a PIN is configured. The actual PIN must never be kept
 * in this local placeholder database; gateway-backed verification will replace
 * this record later.
 */
@Entity(tableName = "payment_pins")
public class PaymentPinEntity {
    @PrimaryKey
    @NonNull public String ownerUid = "";
    public boolean configured;
    public long updatedAt;
}