package com.digihealth.finance;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetLineRepository extends JpaRepository<BudgetLine, Long> {

    List<BudgetLine> findByMonthOrderByIdAsc(String month);

    List<BudgetLine> findByMonthIn(Collection<String> months);

    void deleteByMonth(String month);
}
