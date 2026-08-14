package com.callx.app.db.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.PaymentPinEntity;

@Dao
public interface PaymentPinDao {
    @Query("SELECT * FROM payment_pins WHERE ownerUid = :ownerUid LIMIT 1")
    PaymentPinEntity get(String ownerUid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void save(PaymentPinEntity pin);
}