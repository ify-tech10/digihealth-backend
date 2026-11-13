package com.digihealth.labportal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.activity.ActivityService;
import com.digihealth.common.ApiException;
import com.digihealth.common.CurrentUser;
import com.digihealth.notification.NotificationService;
import com.digihealth.user.Role;

/** DiGi's own test / drug price list. Shared by all lab scientists (tests) or all pharmacists (drugs). */
@Service
public class CatalogueService {

    private final CatalogueItemRepository items;
    private final StockMovementRepository movements;
    private final NotificationService notifications;
    private final ActivityService activity;

    public CatalogueService(CatalogueItemRepository items, StockMovementRepository movements,
                            NotificationService notifications, ActivityService activity) {
        this.items = items;
        this.movements = movements;
        this.notifications = notifications;
        this.activity = activity;
    }

    /**
     * pharmacy: { name, code, unitPrice, strength, form, reorderLevel, stock (new items only) }
     * lab:      { name, code, unitPrice, sampleType, turnaroundHours }
     */
    public record ItemBody(
        @NotBlank(message = "Enter the name") @Size(max = 150) String name,
        @Size(max = 30) String code,
        @NotNull(message = "Enter a price in naira") @DecimalMin(value = "1", message = "Enter a price in naira") BigDecimal unitPrice,
        @Size(max = 60) String strength,
        @Size(max = 60) String form,
        @Size(max = 30) String sampleType,
        @Min(value = 1, message = "Hours until results") @Max(2160) Integer turnaroundHours,
        @Min(value = 0, message = "Whole number") @Max(1000000) Integer reorderLevel,
        @Min(value = 0, message = "Opening stock must be a whole number") @Max(1000000) Integer stock,
        Boolean active) {
    }

    public record StockBody(@NotNull(message = "Enter the change") Integer change,
                            @NotBlank(message = "Give a reason") @Size(max = 300) String reason) {
    }

    public record ItemView(Long id, String kind, String name, String code, BigDecimal unitPrice, BigDecimal price,
                           String strength, String form, String sampleType, Integer turnaroundHours, Integer stock,
                           int reorderLevel, boolean lowStock, boolean active, Instant updatedAt) {
        static ItemView of(CatalogueItem i) {
            boolean low = i.getStock() != null && i.getStock() <= i.getReorderLevel();
            return new ItemView(i.getId(), i.getKind(), i.getName(), i.getCode(), i.getUnitPrice(), i.getUnitPrice(),
                i.getStrength(), i.getForm(), i.getSampleType(), i.getTurnaroundHours(), i.getStock(),
                i.getReorderLevel(), low, i.isActive(), i.getUpdatedAt());
        }
    }

    @Transactional(readOnly = true)
    public List<ItemView> list() {
        return items.findByKindOrderByNameAsc(LabPortalService.myKind()).stream().map(ItemView::of).toList();
    }

    @Transactional
    public ItemView create(ItemBody b) {
        String kind = LabPortalService.myKind();
        unique(kind, null, b);
        CatalogueItem item = new CatalogueItem(kind, b.name().trim());
        apply(item, b);
        if (LabPortalService.PHARMACY.equals(kind)) {
            item.setStock(b.stock() == null ? 0 : b.stock());
        }
        CatalogueItem saved = items.save(item);
        if (saved.getStock() != null && saved.getStock() > 0) {
            movements.save(new StockMovement(saved.getId(), saved.getStock(), saved.getStock(), "Opening stock",
                null, CurrentUser.id()));
        }
        activity.record("CATALOGUE_ITEM_ADDED", "Added " + display(saved) + " to the catalogue", "CATALOGUE_ITEM", saved.getId());
        return ItemView.of(saved);
    }

    /** Stock is not changed here: use the stock endpoint so every change has a reason. */
    @Transactional
    public ItemView update(Long id, ItemBody b) {
        CatalogueItem item = find(id);
        /* check before changing the entity, so the query doesn't flush a duplicate first */
        unique(item.getKind(), item.getId(), b);
        item.setName(b.name().trim());
        apply(item, b);
        activity.record("CATALOGUE_ITEM_UPDATED", "Updated " + display(item), "CATALOGUE_ITEM", item.getId());
        return ItemView.of(item);
    }

    @Transactional
    public ItemView adjustStock(Long id, StockBody b) {
        CatalogueItem item = find(id);
        if (item.getStock() == null) {
            throw ApiException.badRequest("Tests don't have stock.");
        }
        int change = b.change();
        if (change == 0) {
            throw ApiException.badRequest("Enter a whole number greater than 0.");
        }
        if (Math.abs(change) > 1_000_000) {
            throw ApiException.badRequest("That change is too large.");
        }
        int after = item.getStock() + change;
        if (after < 0) {
            throw ApiException.conflict("Only " + item.getStock() + " in stock.");
        }
        int before = item.getStock();
        item.setStock(after);
        movements.save(new StockMovement(item.getId(), change, after, b.reason().trim(), null, CurrentUser.id()));

        if (before > item.getReorderLevel() && after <= item.getReorderLevel()) {
            notifications.notifyRoles(List.of(Role.PHARMACIST), "Low stock", display(item) + " is down to " + after + ".",
                "/pharmacy/catalogue");
        }
        activity.record("STOCK_ADJUSTED", (change > 0 ? "Added " : "Removed ") + Math.abs(change) + " × " + display(item)
            + " (" + b.reason().trim() + ")", "CATALOGUE_ITEM", item.getId());
        return ItemView.of(item);
    }

    /* ── helpers ── */

    private CatalogueItem find(Long id) {
        return items.findByIdAndKind(id, LabPortalService.myKind())
            .orElseThrow(() -> ApiException.notFound("Item not found."));
    }

    private static void apply(CatalogueItem i, ItemBody b) {
        String code = LabPortalService.blankToNull(b.code());
        i.setCode(code == null ? null : code.toUpperCase(Locale.ROOT));
        i.setUnitPrice(b.unitPrice().setScale(2, RoundingMode.HALF_UP));
        if (LabPortalService.PHARMACY.equals(i.getKind())) {
            i.setStrength(LabPortalService.blankToNull(b.strength()));
            i.setForm(LabPortalService.blankToNull(b.form()));
            i.setReorderLevel(b.reorderLevel() == null ? 10 : b.reorderLevel());
        } else {
            i.setSampleType(LabPortalService.blankToNull(b.sampleType()));
            i.setTurnaroundHours(b.turnaroundHours());
        }
        if (b.active() != null) {
            i.setActive(b.active());
        }
    }

    private void unique(String kind, Long id, ItemBody b) {
        String name = b.name().trim();
        String strength = LabPortalService.PHARMACY.equals(kind) ? Objects.toString(LabPortalService.blankToNull(b.strength()), "") : "";
        for (CatalogueItem other : items.findByKindOrderByNameAsc(kind)) {
            if (!Objects.equals(other.getId(), id) && other.getName().equalsIgnoreCase(name)
                && Objects.toString(other.getStrength(), "").equalsIgnoreCase(strength)) {
                throw ApiException.conflict((strength.isEmpty() ? name : name + " " + strength) + " is already in the catalogue.");
            }
        }
    }

    private static String display(CatalogueItem i) {
        return i.getStrength() == null ? i.getName() : i.getName() + " " + i.getStrength();
    }
}
