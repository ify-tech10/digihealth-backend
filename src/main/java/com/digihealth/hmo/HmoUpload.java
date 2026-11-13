package com.digihealth.hmo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One bulk employee upload and its outcome. */
@Entity
@Table(name = "hmo_uploads")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HmoUpload extends BaseEntity {

    @Column(name = "organisation_id", nullable = false)
    private Long organisationId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(nullable = false)
    private int created;

    @Column(nullable = false)
    private int updated;

    @Column(nullable = false)
    private int skipped;

    @Column
    private String errors;

    @Column(nullable = false)
    private String status = "COMPLETED";

    @Column(name = "uploaded_by_id")
    private Long uploadedById;

    @Column(name = "uploaded_by_name")
    private String uploadedByName;

    public HmoUpload(Long organisationId, String fileName) {
        this.organisationId = organisationId;
        this.fileName = fileName;
    }
}
