package com.digihealth.labportal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogueItemRepository extends JpaRepository<CatalogueItem, Long> {

    List<CatalogueItem> findByKindOrderByNameAsc(String kind);

    Optional<CatalogueItem> findByIdAndKind(Long id, String kind);
}
