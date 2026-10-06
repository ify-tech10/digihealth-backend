package com.digihealth.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.config.AppProperties;

/*
 * Creates the first super admin on startup, so someone can sign in and create
 * every other account. Runs only when BOOTSTRAP_ADMIN_EMAIL is set and no admin
 * exists yet, so it is safe to leave on.
 *
 * The account has no password. From Phase 3 they get a set-password email.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final AppProperties props;

    public AdminBootstrap(UserRepository users, AppProperties props) {
        this.users = users;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.BootstrapAdmin admin = props.bootstrapAdmin();
        String email = admin == null ? null : User.normalizeEmail(admin.email());
        if (email == null || email.isBlank()) {
            return;
        }
        if (users.existsByRoleIn(Role.ADMINS)) {
            return;
        }
        if (users.existsByEmail(email)) {
            log.warn("BOOTSTRAP_ADMIN_EMAIL already belongs to a non-admin account; no super admin created.");
            return;
        }
        String name = admin.name() == null || admin.name().isBlank() ? "DiGi Health Admin" : admin.name().trim();
        users.save(new User(email, name, Role.SUPER_ADMIN));
        log.info("Created the first super admin account for {}", email);
    }
}
