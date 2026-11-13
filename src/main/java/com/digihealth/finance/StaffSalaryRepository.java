package com.digihealth.finance;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StaffSalaryRepository extends JpaRepository<StaffSalary, Long> {

    List<StaffSalary> findByActiveTrue();
}
