package com.digihealth.file;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.common.ApiException;

/*
 * Every upload in the app goes through here.
 *  - DOCUMENT: medical records, CVs, licences, invoices, lab results.
 *    Stored privately; opened through a link that expires after an hour.
 *  - PUBLIC_IMAGE: photos and logos that may be shown to anyone.
 * Checks size (10 MB), extension and the file's first bytes, so a renamed
 * .exe can't pass as a .pdf.
 */
@Service
public class FileService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final long LINK_SECONDS = 60 * 60;

    public enum Kind { DOCUMENT, PUBLIC_IMAGE }

    private static final Set<String> DOCUMENT_TYPES = Set.of("pdf", "jpg", "jpeg", "png", "webp", "doc", "docx", "xlsx", "csv");
    private static final Set<String> IMAGE_TYPES = Set.of("jpg", "jpeg", "png", "webp");

    /* First bytes of each format. csv is plain text and is checked separately. */
    private static final Map<String, byte[][]> SIGNATURES = Map.of(
        "pdf", new byte[][] {{'%', 'P', 'D', 'F'}},
        "png", new byte[][] {{(byte) 0x89, 'P', 'N', 'G'}},
        "jpg", new byte[][] {{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
        "jpeg", new byte[][] {{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
        "webp", new byte[][] {{'R', 'I', 'F', 'F'}},
        "docx", new byte[][] {{'P', 'K', 3, 4}},
        "xlsx", new byte[][] {{'P', 'K', 3, 4}},
        "doc", new byte[][] {{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}});

    private final CloudinaryClient cloudinary;
    private final StoredFileRepository files;

    public FileService(CloudinaryClient cloudinary, StoredFileRepository files) {
        this.cloudinary = cloudinary;
        this.files = files;
    }

    /** What the frontend receives for a file: { id, name, url, contentType, size } */
    public record FileView(Long id, String name, String url, String contentType, long size) {
    }

    @Transactional
    public StoredFile upload(MultipartFile file, Kind kind, Long ownerId) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("Files can be up to 10 MB.");
        }
        String name = cleanName(file.getOriginalFilename());
        String ext = extensionOf(name);
        Set<String> allowed = kind == Kind.PUBLIC_IMAGE ? IMAGE_TYPES : DOCUMENT_TYPES;
        if (!allowed.contains(ext)) {
            throw ApiException.badRequest(kind == Kind.PUBLIC_IMAGE
                ? "Upload a JPG, PNG or WebP image."
                : "Upload a PDF, image, Word, Excel or CSV file.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The file couldn't be read. Please try again.");
        }
        if (!contentMatches(ext, bytes)) {
            throw ApiException.badRequest("This file's contents don't match its type (." + ext + ").");
        }

        boolean isPrivate = kind == Kind.DOCUMENT;
        String resourceType = isPrivate ? "raw" : "image";
        String deliveryType = isPrivate ? "private" : "upload";
        String publicId = isPrivate
            ? "digihealth/documents/" + UUID.randomUUID() + "." + ext
            : "digihealth/images/" + UUID.randomUUID();

        CloudinaryClient.Uploaded up = cloudinary.upload(bytes, name, publicId, resourceType, deliveryType);
        return files.save(new StoredFile(ownerId, name, file.getContentType(), bytes.length,
            up.publicId(), resourceType, deliveryType, isPrivate ? null : up.secureUrl()));
    }

    /** Public files: permanent URL. Private files: a fresh link valid for one hour. */
    public String urlFor(StoredFile f) {
        return f.isPrivate()
            ? cloudinary.privateDownloadUrl(f.getPublicId(), f.getResourceType(), LINK_SECONDS)
            : f.getSecureUrl();
    }

    public FileView view(StoredFile f) {
        return new FileView(f.getId(), f.getOriginalName(), urlFor(f), f.getContentType(), f.getSizeBytes());
    }

    @Transactional(readOnly = true)
    public StoredFile get(Long id) {
        return files.findById(id).orElseThrow(() -> ApiException.notFound("File not found."));
    }

    private static boolean contentMatches(String ext, byte[] bytes) {
        if ("csv".equals(ext)) {
            int n = Math.min(bytes.length, 1024);
            for (int i = 0; i < n; i++) {
                if (bytes[i] == 0) {
                    return false;   // binary data, not text
                }
            }
            return true;
        }
        for (byte[] sig : SIGNATURES.getOrDefault(ext, new byte[0][])) {
            if (bytes.length >= sig.length && Arrays.equals(Arrays.copyOf(bytes, sig.length), sig)) {
                return true;
            }
        }
        return false;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /* Keep the name for display only; strip any path and odd characters. */
    private static String cleanName(String original) {
        String name = original == null ? "file" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ ()-]", "_").trim();
        if (name.isEmpty()) {
            name = "file";
        }
        return name.length() > 200 ? name.substring(name.length() - 200) : name;
    }
}
