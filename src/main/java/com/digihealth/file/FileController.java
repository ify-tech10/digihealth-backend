package com.digihealth.file;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.common.CurrentUser;
import com.digihealth.file.FileService.FileView;

/*
 * Admin-only test endpoints for uploads (not used by the frontend).
 * Portal uploads (lab results, CVs, invoices...) go through their own
 * endpoints, which call FileService.
 */
@RestController
@RequestMapping("/files")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class FileController {

    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    /** POST /files  (multipart: file, publicImage=true|false) */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileView upload(@RequestParam("file") MultipartFile file,
                           @RequestParam(name = "publicImage", defaultValue = "false") boolean publicImage) {
        StoredFile saved = files.upload(file,
            publicImage ? FileService.Kind.PUBLIC_IMAGE : FileService.Kind.DOCUMENT, CurrentUser.id());
        return files.view(saved);
    }

    /** GET /files/{id} -> a fresh link (private links expire after an hour) */
    @GetMapping("/{id}")
    public FileView get(@PathVariable("id") Long id) {
        return files.view(files.get(id));
    }
}
