package com.digihealth.auth;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digihealth.config.AppProperties;
import com.digihealth.email.EmailService;
import com.digihealth.user.User;

/*
 * Creates single-use password links and emails them.
 * Use sendInvite(user) whenever an account is created for someone
 * (admin, RM, CNO, HMO, or the public Book Care page).
 */
@Service
public class PasswordLinks {

    private final PasswordTokenRepository tokens;
    private final EmailService email;
    private final AppProperties props;

    public PasswordLinks(PasswordTokenRepository tokens, EmailService email, AppProperties props) {
        this.tokens = tokens;
        this.email = email;
        this.props = props;
    }

    /** New account: "Set your password" link, valid 72 hours. */
    @Transactional
    public void sendInvite(User user) {
        send(user, TokenPurpose.SET);
    }

    @Transactional
    public void send(User user, TokenPurpose purpose) {
        Instant now = Instant.now();
        tokens.invalidateAllForUser(user.getId(), now);

        String raw = Tokens.newRaw();
        tokens.save(new PasswordToken(user, Tokens.hash(raw), purpose, now.plus(purpose.lifetime)));

        String link = stripSlash(props.frontendUrl()) + purpose.page + "?token=" + raw;
        email.sendPasswordLink(user.getEmail(), user.getFullName(), purpose == TokenPurpose.SET, link, purpose.label);
    }

    private static String stripSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
