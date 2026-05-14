package com.example.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user participating in the checkout flow.
 */
@Entity
@Table(name = "checkout_users")
public class User {

    @Id
    @Column(name = "user_id")
    private Long id;

    @Column(name = "email")
    private String email;

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }
}
