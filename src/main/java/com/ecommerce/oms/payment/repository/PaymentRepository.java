package com.ecommerce.oms.payment.repository;

import com.ecommerce.oms.payment.domain.Payment;
import com.ecommerce.oms.payment.domain.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /** Operator-facing: the UNCONFIRMED rows that need checking against the provider. */
    Page<Payment> findByStatusOrderByIdDesc(PaymentStatus status, Pageable pageable);

    long countByStatus(PaymentStatus status);
}
