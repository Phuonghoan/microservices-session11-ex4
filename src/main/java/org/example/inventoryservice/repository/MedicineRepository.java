package org.example.inventoryservice.repository;

import org.example.inventoryservice.entity.Medicine;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
        UPDATE Medicine m
        SET m.stock = m.stock - :quantity
        WHERE m.id = :medicineId
          AND m.stock >= :quantity
    """)
    int decreaseStock(
            @Param("medicineId") Long medicineId,
            @Param("quantity") Integer quantity
    );
}
