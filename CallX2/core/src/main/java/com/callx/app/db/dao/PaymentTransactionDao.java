package com.callx.app.db.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.callx.app.db.entity.PaymentTransactionEntity;

import java.util.List;

@Dao
public interface PaymentTransactionDao {
    @Query("SELECT * FROM payment_transactions WHERE ownerUid = :ownerUid ORDER BY createdAt DESC")
    LiveData<List<PaymentTransactionEntity>> observeForOwner(String ownerUid);

    @Query("SELECT * FROM payment_transactions WHERE ownerUid = :ownerUid ORDER BY createdAt DESC")
    List<PaymentTransactionEntity> getForOwnerSync(String ownerUid);

    @Query("SELECT * FROM payment_transactions WHERE id = :id LIMIT 1")
    PaymentTransactionEntity getById(String id);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(PaymentTransactionEntity transaction);
}