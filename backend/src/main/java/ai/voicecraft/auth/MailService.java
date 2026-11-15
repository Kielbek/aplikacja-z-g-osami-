package ai.voicecraft.auth;

import ai.voicecraft.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Wysyłka e-maili przez SMTP (MAIL_HOST…). Bez konfiguracji SMTP wiadomość trafia do logów
 * serwera — wyłącznie na potrzeby środowiska deweloperskiego.
 */
@Service
public class MailService {
    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private final ObjectProvider<JavaMailSender> sender;
    private final AppProperties props;

    public MailService(ObjectProvider<JavaMailSender> sender, AppProperties props) {
        this.sender = sender;
        this.props = props;
    }

    public boolean configured() {
        return sender.getIfAvailable() != null;
    }

    /** Błąd wysyłki jest logowany, ale nie przerywa operacji (np. resetu hasła). */
    public void send(String to, String subject, String body) {
        JavaMailSender mail = sender.getIfAvailable();
        if (mail == null) {
            log.warn("[DEV] SMTP nie jest skonfigurowany — e-mail do {} nie został wysłany.\nTemat: {}\n{}", to, subject, body);
            return;
        }
        var msg = new SimpleMailMessage();
        msg.setFrom(props.mailFrom());
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        try {
            mail.send(msg);
        } catch (org.springframework.mail.MailException e) {
            log.error("Nie udało się wysłać e-maila do {}: {}", to, e.getMessage());
        }
    }
}
