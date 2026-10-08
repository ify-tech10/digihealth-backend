package com.digihealth.carerequest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CareRequestDtos {

    private CareRequestDtos() {
    }

    public static final String PHONE_RULE = "^[+0-9 ()-]{7,20}$";

    /** Body of POST /care-requests (public Book Care page, and CCS phone requests). */
    public record CreateCareRequest(
            @NotBlank(message = "Full name is required") @Size(max = 150, message = "Name is too long") String fullName,
            @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 255) String email,
            @NotBlank(message = "Phone number is required") @Pattern(regexp = PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
            @Size(max = 300, message = "Address is too long") String address,
            @Size(max = 60) String locationArea,
            @NotBlank(message = "Please select a service") @Size(max = 100) String serviceNeeded,
            @Size(max = 2000, message = "Please keep the description under 2,000 characters") String description,
            @NotBlank(message = "Please select a preferred time") @Size(max = 40) String preferredContactTime) {
    }

    /** accountCreated=false when the email already had an account (the page then says "sign in"). */
    public record CreatedCareRequest(Long id, boolean accountCreated) {
    }
}
