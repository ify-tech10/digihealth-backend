package com.digihealth.cno;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/*
 * CNO portal > Orders: send referrals / drug orders / test requests to partner facilities or
 * DiGi's own lab scientists and pharmacists, and track them. Clinical leadership only (/cno/**).
 * Added in Phase 13 to connect the facility and lab portals; not in the original 189.
 */
@RestController
@RequestMapping("/cno")
public class CnoOrdersController {

    private final CnoOrdersService orders;

    public CnoOrdersController(CnoOrdersService orders) {
        this.orders = orders;
    }

    @GetMapping("/patients")
    public List<Map<String, Object>> patients(@RequestParam(name = "q", required = false) String q) {
        return orders.patients(q);
    }

    @GetMapping("/facilities")
    public List<Map<String, Object>> facilities(@RequestParam(name = "kind") String kind) {
        return orders.facilities(kind);
    }

    @GetMapping("/facilities/{id}/tests")
    public List<Map<String, Object>> facilityTests(@PathVariable("id") Long id) {
        return orders.facilityTests(id);
    }

    @GetMapping("/catalogue")
    public List<Map<String, Object>> catalogue(@RequestParam(name = "kind") String kind) {
        return orders.catalogue(kind);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> list() {
        return orders.orders();
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> send(@Valid @RequestBody CnoOrdersService.OrderBody body) {
        return orders.send(body);
    }
}
