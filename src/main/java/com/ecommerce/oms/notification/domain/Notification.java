package com.ecommerce.oms.notification.domain;

import com.ecommerce.oms.common.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A message sent, or attempted, to a customer.
 *
 * <p>Persisting notifications rather than only logging them is what makes the asynchronous pipeline
 * <em>observable</em>: a reviewer can place an order and then query {@code /notifications} to see that
 * the confirmation was produced after the response was sent. It also gives support a record of what a
 * customer was actually told, which is the question behind most "I never received anything" tickets.
 */
@Entity
@Getter
@Table(name = "notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseEntity {

    public enum Status {
        PENDING, SENT, FAILED
    }

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 30)
    private NotificationChannelType channel;

    @Column(name = "subject", nullable = false, length = 190)
    private String subject;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "sent_at")
    private Instant sentAt;

    public static Notification pending(Long userId, NotificationChannelType channel,
                                       String subject, String body) {
        Notification notification = new Notification();
        notification.userId = userId;
        notification.channel = channel;
        notification.subject = truncate(subject, 190);
        notification.body = truncate(body, 2000);
        notification.status = Status.PENDING;
        return notification;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
