package com.example.checkout;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the checkout flow.
 */
@RestController
@RequestMapping("/checkout")
public class CheckoutController {

    private final UserRepository users;

    public CheckoutController(UserRepository users) {
        this.users = users;
    }

    /**
     * Look up a user by id and return their checkout state.
     */
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return users.findById(id).orElseThrow();
    }
}
