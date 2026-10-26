package com.digihealth.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.AppTime;
import com.digihealth.common.CurrentUser;
import com.digihealth.file.FileService;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;
import com.digihealth.user.User;
import com.digihealth.user.UserRepository;

/** Raising and listing your own requests for funds (CNO team now, Finance Manager in Phase 12). Admin approves. */
@Service
public class FinancialRequestService {

    private static final long MAX_ATTACHMENT = 5L * 1024 * 1024;

    private final FinancialRequestRepository requests;
    private final UserRepository users;
    private final FileService files;
    private final NotificationService notifications;
    private final ActivityService activity;

    public FinancialRequestService(FinancialRequestRepository requests, UserRepository users, FileService files,
                                   NotificationService notifications, ActivityService activity) {
        this.requests = requests;
        this.users = users;
        this.files = files;
        this.notifications = notifications;
        this.activity = activity;
    }

    /** Multipart form: title, category, amount, neededBy?, description, attachment? */
    public record Form(@NotBlank(message = "Give the request a title") @Size(max = 200) String title,
                       @NotBlank(message = "Choose a category") @Pattern(regexp = "^[A-Z_]{2,40}$", message = "Choose a category") String category,
                       @NotNull(message = "Enter the amount") @DecimalMin(value = "1", message = "Enter the amount") BigDecimal amount,
                       String neededBy,
                       @NotBlank(message = "Explain what the money is for") @Size(max = 4000) String description,
                       MultipartFile attachment) {
    }

    public record RequestView(Long id, String title, String category, BigDecimal amount, LocalDate neededBy,
                              String description, String attachmentUrl, String status, String reviewNote,
                              Instant reviewedAt, Instant createdAt) {
    }

    @Transactional(readOnly = true)
    public List<RequestView> mine() {
        List<FinancialRequest> list = requests.findByRequestedByIdOrderByCreatedAtDesc(CurrentUser.id());
        Map<Long, String> urls = files.urlsFor(list.stream().map(FinancialRequest::getAttachmentFileId).toList());
        return list.stream().map(r -> new RequestView(r.getId(), r.getTitle(), r.getCategory(), r.getAmount(),
            r.getNeededBy(), r.getDescription(), urls.get(r.getAttachmentFileId()), r.getStatus(), r.getReviewNote(),
            r.getReviewedAt(), r.getCreatedAt())).toList();
    }

    @Transactional
    public RequestView create(Form form) {
        User me = users.findById(CurrentUser.id()).orElseThrow();
        LocalDate neededBy = AppTime.parseDate(form.neededBy(), "neededBy");
        if (neededBy != null && neededBy.isBefore(AppTime.today())) {
            throw ApiException.badRequest("'Needed by' can't be in the past.");
        }
        FinancialRequest r = new FinancialRequest(form.title().trim(), form.category(), form.amount());
        r.setNeededBy(neededBy);
        r.setDescription(form.description().trim());
        r.setRequestedById(me.getId());
        r.setRequestedByName(me.getFullName());
        r.setRequestedByRole(me.getRole().name());
        MultipartFile file = form.attachment();
        if (file != null && !file.isEmpty()) {
            if (file.getSize() > MAX_ATTACHMENT) {
                throw ApiException.badRequest("Attachments can be up to 5 MB.");
            }
            r.setAttachmentFileId(files.upload(file, FileService.Kind.DOCUMENT, me.getId()).getId());
        }
        FinancialRequest saved = requests.save(r);

        notifications.notifyRoles(Role.ADMINS, "Financial request to review",
            me.getFullName() + " requested ₦" + String.format("%,.0f", saved.getAmount()) + " for " + saved.getTitle() + ".",
            "/admin/financial-requests");
        activity.record("FINANCIAL_REQUEST_CREATED", "Requested ₦" + String.format("%,.0f", saved.getAmount())
            + " for " + saved.getTitle(), "FINANCIAL_REQUEST", saved.getId());
        return mine().stream().filter(x -> x.id().equals(saved.getId())).findFirst().orElseThrow();
    }
}
