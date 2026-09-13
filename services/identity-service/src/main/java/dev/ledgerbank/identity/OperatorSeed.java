package dev.ledgerbank.identity;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OperatorSeed implements CommandLineRunner {
    private final UserAccountRepository users; private final PasswordEncoder encoder;
    private final String email; private final String password;
    OperatorSeed(UserAccountRepository users, PasswordEncoder encoder,
                 @Value("${ledgerbank.operator-email}") String email,
                 @Value("${ledgerbank.operator-password}") String password) {
        this.users = users; this.encoder = encoder; this.email = email.toLowerCase(Locale.ROOT); this.password = password;
    }
    @Override @Transactional public void run(String... args) {
        if (users.findByEmail(email).isEmpty()) users.save(new UserAccount(UUID.randomUUID(), email, encoder.encode(password), "OPERATOR", true));
    }
}
