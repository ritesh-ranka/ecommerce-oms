package com.ecommerce.oms.inventory.repository;

import com.ecommerce.oms.inventory.domain.StockLedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockLedgerEntryRepository extends JpaRepository<StockLedgerEntry, Long> {

    Page<StockLedgerEntry> findByInventoryItemIdOrderByIdDesc(Long inventoryItemId, Pageable pageable);

    List<StockLedgerEntry> findByReferenceOrderByIdAsc(String reference);
}
