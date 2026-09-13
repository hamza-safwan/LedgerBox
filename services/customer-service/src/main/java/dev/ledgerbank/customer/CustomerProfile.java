package dev.ledgerbank.customer;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="customer_profiles")
class CustomerProfile {
    @Id UUID id;
    @Column(name="user_subject", nullable=false, unique=true) UUID userSubject;
    @Column(name="legal_name_ciphertext", nullable=false) String legalName;
    @Column(name="date_of_birth_ciphertext", nullable=false) String dateOfBirth;
    @Column(name="address_ciphertext", nullable=false) String address;
    @Column(name="key_version", nullable=false) int keyVersion = 1;
    @Column(name="kyc_status", nullable=false) String kycStatus;
    @Column(name="kyc_reason") String kycReason;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
    @Version long version;
    protected CustomerProfile() {}
    CustomerProfile(UUID subject, String legalName, String dob, String address) {
        id=UUID.randomUUID(); userSubject=subject; this.legalName=legalName; dateOfBirth=dob; this.address=address;
        kycStatus="DRAFT"; createdAt=updatedAt=Instant.now();
    }
}

