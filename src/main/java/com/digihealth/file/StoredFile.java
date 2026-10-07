package com.digihealth.file;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.digihealth.common.BaseEntity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stored_files")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StoredFile extends BaseEntity {

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "public_id", nullable = false, unique = true)
    private String publicId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "delivery_type", nullable = false)
    private String deliveryType;

    @Column(name = "secure_url")
    private String secureUrl;

    StoredFile(Long ownerId, String originalName, String contentType, long sizeBytes,
               String publicId, String resourceType, String deliveryType, String secureUrl) {
        this.ownerId = ownerId;
        this.originalName = originalName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.publicId = publicId;
        this.resourceType = resourceType;
        this.deliveryType = deliveryType;
        this.secureUrl = secureUrl;
    }

    public boolean isPrivate() {
        return "private".equals(deliveryType);
    }
}
