package com.digihealth.email;

import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import com.digihealth.config.AppProperties;

/*
 * Sends email through Resend (SMTP). Runs in the background (@Async) so a slow
 * mail server never slows down a request, and failures are logged, not thrown.
 * With MAIL_ENABLED=false the email is printed to the console instead
 * (for local testing only - never in production, since links are secrets).
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties props;

    public EmailService(JavaMailSender mailSender, AppProperties props) {
        this.mailSender = mailSender;
        this.props = props;
    }

    /** "Set your password" (new account) or "Reset your password". */
    @Async
    public void sendPasswordLink(String to, String name, boolean newAccount, String link, String validFor) {
        String subject = newAccount ? "Set your DiGi Health password" : "Reset your DiGi Health password";
        String heading = newAccount ? "Welcome to DiGi Health" : "Reset your password";
        String intro = newAccount
            ? "Your DiGi Health account is ready. Choose a password to finish setting it up."
            : "We received a request to reset your DiGi Health password.";
        String button = newAccount ? "Set my password" : "Reset my password";
        String outro = newAccount
            ? "This link works once and expires in " + validFor + "."
            : "This link works once and expires in " + validFor + ". If you didn't ask for this, you can ignore this email; your password won't change.";

        String greeting = "Hello" + (name == null || name.isBlank() ? "," : " " + name.trim() + ",");

        String text = greeting + "\n\n" + intro + "\n\n" + button + ": " + link + "\n\n" + outro + "\n\nDiGi Health";

        String html = """
            <div style="font-family:Arial,Helvetica,sans-serif;max-width:520px;margin:0 auto;padding:24px;color:#1a2550">
              <h2 style="margin:0 0 16px">%s</h2>
              <p>%s</p>
              <p>%s</p>
              <p style="margin:28px 0">
                <a href="%s" style="background:#16a34a;color:#ffffff;padding:12px 22px;border-radius:8px;text-decoration:none;font-weight:bold">%s</a>
              </p>
              <p style="font-size:13px;color:#555">%s</p>
              <p style="font-size:12px;color:#888">If the button doesn't work, copy this link into your browser:<br>%s</p>
              <p style="font-size:12px;color:#888">DiGi Health &middot; Healthcare at home</p>
            </div>
            """.formatted(
                esc(heading), esc(greeting), esc(intro), esc(link), esc(button), esc(outro), esc(link));

        send(to, subject, text, html);
    }

    private void send(String to, String subject, String text, String html) {
        AppProperties.Mail mail = props.mail();
        if (mail == null || !mail.enabled()) {
            log.info("MAIL_ENABLED=false - email not sent.\nTo: {}\nSubject: {}\n{}", to, subject, text);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mail.from(), mail.fromName() == null ? "DiGi Health" : mail.fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
            log.info("Email '{}' sent to {}", subject, to);
        } catch (Exception ex) {
            log.error("Could not send email '{}' to {}: {}", subject, to, ex.getMessage());
        }
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }
}
