package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.PaymentAccountEntity;

import java.util.List;

@Dao
public interface PaymentAccountDao {
    @Query("SELECT * FROM payment_accounts WHERE ownerUid = :ownerUid ORDER BY isDefault DESC, createdAt DESC")
    LiveData<List<PaymentAccountEntity>> observeForOwner(String ownerUid);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PaymentAccountEntity account);

    @Query("UPDATE payment_accounts SET isDefault = 0 WHERE ownerUid = :ownerUid")
    void clearDefault(String ownerUid);

    @Query("UPDATE payment_accounts SET isDefault = :isDefault WHERE id = :id")
    void setDefault(String id, boolean isDefault);
}