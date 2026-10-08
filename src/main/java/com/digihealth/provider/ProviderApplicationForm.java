package com.digihealth.provider;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.web.multipart.MultipartFile;

import com.digihealth.carerequest.CareRequestDtos;

/**
 * Multipart body of POST /auth/provider/apply (public "Apply as a provider" form).
 * Field names match the FormData keys sent by Apply.jsx.
 */
public record ProviderApplicationForm(
        @NotBlank(message = "Full name is required") @Size(max = 150) String fullName,
        @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") @Size(max = 255) String email,
        @NotBlank(message = "Phone is required") @Pattern(regexp = CareRequestDtos.PHONE_RULE, message = "Enter a valid phone number") String phoneNumber,
        @NotBlank(message = "Please select your role") @Size(max = 80) String serviceProviderType,
        @NotBlank(message = "Address is required") @Size(max = 300) String address,
        @Size(max = 30) String gender,
        @NotBlank(message = "Please select experience range") @Size(max = 40) String yearsOfExperience,
        @Size(max = 60) String qualification,
        @Size(max = 60) String employmentStatus,
        @Size(max = 1000) String specialisations,
        @Size(max = 4000, message = "Please keep the summary under 4,000 characters") String professionalSummary,
        @NotNull(message = "CV / Resume is required") MultipartFile cv,
        MultipartFile certificate,
        MultipartFile governmentId,
        MultipartFile license,
        @Size(max = 150) String ref1FullName,
        @Size(max = 80) String ref1Relationship,
        @Size(max = 30) String ref1Phone,
        @Size(max = 255) String ref1Email,
        @Size(max = 150) String ref2FullName,
        @Size(max = 80) String ref2Relationship,
        @Size(max = 30) String ref2Phone,
        @Size(max = 255) String ref2Email,
        @NotBlank(message = "Please select availability") @Size(max = 30) String availabilityType,
        @Size(max = 200) String preferredHours,
        @Size(max = 60) String locationArea,
        @Size(max = 40) String startDate,
        @Size(max = 2000) String additionalInfo) {
}
