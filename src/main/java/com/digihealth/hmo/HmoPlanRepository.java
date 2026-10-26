package com.digihealth.hmo;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HmoPlanRepository extends JpaRepository<HmoPlan, Long> {

    List<HmoPlan> findAllByOrderByActiveDescNameAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
