package com.digihealth.finance;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollLineRepository extends JpaRepository<PayrollLine, Long> {

    List<PayrollLine> findByMonthOrderByStaffNameAsc(String month);

    List<PayrollLine> findByMonthIn(Collection<String> months);

    boolean existsByMonth(String month);

    List<PayrollLine> findByStatus(String status);
}
